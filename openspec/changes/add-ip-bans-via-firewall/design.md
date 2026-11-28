## Context

The code this design builds on (checked on `main`, 2026-10-28):

- `backend/.../punishment/` stores player punishments keyed by the hashed owner (SHA-512 of the
  player UUID). An IP is not an owner, so an IP ban cannot be a punishment row without leaking into
  owner-keyed profiles, active slots and the public ban list.
- `backend/.../evasion/` correlates logins by a keyed HMAC of the IP (`blackhole.evasion.ip-salt`).
  That token cannot be turned back into an address, so the firewall cannot use it.
- `velocity/.../listener/PlayerLoginListener.java` reads `player.getRemoteAddress()`. That is the TCP
  peer of the proxy's player listener. Behind an edge proxy (TCPShield, Cloudflare-style) it is the
  edge address, not the player's address.
- `backend/.../imports/VanillaIpBanEntry.java` and `imports/service/VanillaImportService.java` count
  `banned-ips.json` entries and skip them (`ipsSkipped`).
- `application.yml` already configures the public ban list clients: named clients in
  `blackhole.ban-list.clients`, one secret file per client in `blackhole.ban-list.secrets-dir`,
  read once at start-up (`add-public-ban-list`, decision 2). The feed clients follow the same shape.
- Micrometer and the Prometheus registry are already dependencies of `backend` (`build.gradle.kts`).
  `@Scheduled` sweepers are the established pattern (for example `IpCorrelationRetentionSweeper`).
- NATS JetStream carries domain events (`blackhole.events.<type>`). Velocity subscribes via jnats
  (`switch-event-bus-to-nats`).
- Roles: Entra `ADMIN`, `MODERATOR`, `VIEWER`, plus machine roles `PROXY`, `DISCORD`, `METRICS`
  (`security/BlackholeRolesFinder.java`).
- Flyway: the head on `main` is V27 or lower depending on in-flight changes. The migration number is
  assigned at implementation time (next free number on main; main is at V27 today).
- `openspec/config.yaml` principles: GDPR-by-design (amended by `amend-data-protection-principle` for
  clear IP addresses with a legitimate-interest basis), layered backend, infra owns rate limiting,
  migrations append-only.
- Related proposals the feed reads from: `add-vpn-proxy-detection` (the VPN, hosting and proxy lists,
  the per-class policy `ALLOW`/`FLAG`/`DENY`, and `blackhole.intel.hosting-asns`) and `add-join-guard`
  (area bans for an ASN or a country). Both are proposals, not code on `main`.

OPNsense, not verified in this document: a URL Table alias (type "URL Table (IPs)") downloads a text
file from a URL and loads its entries into the firewall's pf table. A cron job, "Update and reload
firewall aliases", sets how often that happens. Four things about this are unknown and are checked in
the spike (tasks group 0), which decides only the comments default and the operator note; the build does not wait for it: whether `#` comment lines are ignored,
whether credentials in the URL work, the pf table entry limit against the expected feed size, and
whether a one-minute cron refresh holds. The spike also checks what the table does when the feed
returns an error or an empty body.

## Goals / Non-Goals

**Goals**

- A staff IP ban that holds at the firewall when the firewall sees the player's real address, and
  always holds at the proxy.
- The backend database is the only truth. A lifted ban leaves the feed at the next build and the
  firewall's next refresh. A feed outage leaves the firewall with its last good table, never with an
  empty one.
- Blackhole holds no firewall credentials and the backend never writes to the firewall.
- Imported vanilla IP bans keep their history.

**Non-Goals**

- Making the firewall the only enforcement point. It is an optimisation and a network-edge
  protection, not the authority.
- Choosing a real-IP forwarding technology for the edge. That is a deployment decision.
- Alias-based rules for ports or protocols. The feed holds addresses and networks only.
- Country bans in the feed. The firewall's own GeoIP aliases cover countries.

## Decisions

### 1. Storage: a dedicated `ip_bans` table, not a punishment row

Migration `V<next>__ip_bans.sql` (number assigned at implementation time) creates `ip_bans`:

- `id UUID` (primary key), `network VARCHAR(50)` (canonical text, for example `203.0.113.0/24` or
  `2001:db8::/48`; a single address is stored with its full prefix `/32` or `/128`), `family`
  (`4` or `6`), `prefix_length SMALLINT`, `reason VARCHAR(200)`, `template_key VARCHAR(64) NULL`,
  `source` (`PANEL`, `COMMAND`, `PUNISHMENT`, `IMPORT`), `punishment_id UUID NULL`,
  `case_id UUID NULL`, `actor_oid UUID NULL` (NULL for the system identity of imports),
  `created_at BIGINT`, `expires_at BIGINT NULL`, `lifted_at BIGINT NULL`, `lifted_by_oid UUID NULL`,
  `lift_reason VARCHAR(200) NULL`, `import_key VARCHAR(64) NULL UNIQUE` (for idempotent imports).
- A partial unique index on `network` where `lifted_at IS NULL`, so two active bans for the same
  range cannot exist.

Rejected: a row in `punishments` with an IP owner. That would put addresses into owner-keyed
profiles, active slots and the owner-hash rules of `punishment-management`, and it would break the
rule that the public ban list carries no identifier.

Rejected: storing only the keyed hash of the address. The firewall and the proxy compare addresses
and ranges, which needs the clear value.

### 2. The firewall pulls two feeds; Blackhole never pushes

The firewall fetches two plain-text feeds from the backend on its own schedule. Each is a URL Table
(IPs) alias on the firewall. The backend renders the feeds from the database and holds no state
about the firewall.

- `GET /firewall/feed/{token}/ips`: the active single-address IP bans, one bare address per line
  (`203.0.113.7`, `2001:db8::7`).
- `GET /firewall/feed/{token}/networks`: one CIDR per line, IPv4 and IPv6, made of:
  - the active CIDR IP bans;
  - the VPN, hosting and proxy ranges of the `add-vpn-proxy-detection` lists, for every class whose
    policy is `DENY`. The classes considered are `blackhole.firewall.feed.vpn-classes`, default
    `VPN,HOSTING,PROXY`. A class with `ALLOW` or `FLAG` contributes nothing;
  - the networks of the configured hosting ASNs (`blackhole.intel.hosting-asns`) only while the HOSTING policy is `DENY`, the same rule as the list classes, enumerated from the offline GeoIP ASN database;
  - the networks of active ASN area bans (`add-join-guard`), enumerated from the same database.
- Country area bans are NOT included.
- Format: `text/plain; charset=utf-8`, LF line endings, ASCII only, no trailing spaces. Entries are
  sorted (IPv4 before IPv6, then by address) so the same data always produces the same bytes. A
  network appears once even when two sources name it.
- Protected networks (decision 5) never appear. The renderer checks each line before it is written
  and counts a refusal in `blackhole.ipban.feed.refused`.
- A feed that is empty because no ban matches is a valid feed. It is served as an empty body with
  status 200; it is never produced by an error (decision 4).

Comments. An optional comment line `# ` may precede an entry. It carries the ban id, the kind or
category and the expiry, for example `# id=<uuid> kind=PANEL expires=2026-11-05T12:00:00Z` or
`# kind=ASN_AREA expires=never`. It never carries a player name, an owner hash or reason text. The
query `?comments=false` produces the same entries without any comment line. The comments switch exists from the start. The spike decides the default: comments are on if the firewall ignores `#` lines, and off if it does not. The operator note states which default applies and tells operators to use the query where comments are off.

Rejected: Blackhole pushes to the OPNsense API (alias add, delete, reconfigure and read-back). This
was the earlier design of this change and it is replaced. The reasons:

- It needs an OPNsense API key with write rights on aliases, stored in Blackhole, and it needs the
  backend to reach the firewall's management API. That is a privileged endpoint on the path from the
  application to the firewall.
- The request body of `alias_util` is not in the official reference. Every line of push code would
  depend on a spike result that could change with an OPNsense release.
- Push needs a reconciler: a diff, a reconfigure, a read-back, backoff, drift repair and in-sync
  metrics. All of that exists only to compensate for the push. The pull has no such state.
- A full-list overwrite through the alias controller would replace configuration that the operator
  shares with other rules. A scoped diff cannot be made safe against manual edits.
- With push, an outage or an API change leaves the alias stale in either direction. With pull, the
  firewall keeps its last good table, and the feed never fails open.

Accepted costs of pull: the lag is up to the firewall's cron interval (one minute in the design);
the feed is a secret-gated HTTPS endpoint that the firewall must reach; an alias on a URL Table
replaces its content on each refresh, so entries added by hand on the firewall are not kept (the
spike verifies this, and the operator note says so).

### 3. Feed clients, tokens and the access rules

**Tokens.** One random token per configured firewall client, at least 32 bytes from `SecureRandom`,
encoded as base64url without padding. The token is in the path because URL-table aliases most likely
cannot send auth headers (the spike verifies whether basic auth in the URL works, which is the
fallback).

- Only the SHA-256 hash of the token is stored, as hex, in one secret file per client. A token has
  enough entropy that a fast hash is sufficient, and no password hashing is needed. The comparison is
  constant time (`MessageDigest.isEqual`) and runs against every configured hash without an early
  exit.
- Configuration mirrors the public ban list clients: `blackhole.firewall.feed.clients`
  (`BLACKHOLE_FIREWALL_FEED_CLIENTS`, comma-separated names matching `[a-z0-9-]{1,32}`, default
  empty). Each name has a file `<blackhole.firewall.feed.secrets-dir>/<name>`
  (`BLACKHOLE_FIREWALL_FEED_SECRETS_DIR`) holding the hash. Clients are read once at start-up.
- Rotation: add a second file `<name>.next` with the new hash. Both tokens are accepted until the
  old file is removed. A restart applies the change.
- With no clients configured the feed routes answer 404, which is the state of every deployment that
  has not opted in.
- The token is never logged, never returned and never placed in an event.

**Unknown tokens.** An unknown token, an unknown path and a source address outside the allow-list all
return 404 with no body. The three cases look the same, so the response is no oracle.

**Source allow-list.** `blackhole.firewall.feed.allowed-sources` takes IP addresses and CIDR ranges
only, with no host names, as for the ban list. It is matched against the connection's peer address.
Forwarded headers are not used, because a client controls them.

**HTTPS only.** The feed is reachable only over TLS. The ingress rejects plain HTTP on the feed path
(stated in the Helm chart and the operator note). The backend does not redirect plain HTTP, because a
redirect would send the token in clear text first.

**Logging.** Request logs, metrics and traces name only the route template
`/firewall/feed/{token}/{feed}`. The raw path is never written to a log, a metric tag or a trace.
Decision 9 notes the test that enforces this.

**Trust model.** The feed is gated by a per-client secret, the same pattern as the public ban list routes (`/public/bans`) already on `main`. It does not add `@Secured` or an auth layer.

### 4. Polling every minute: ETag, 304 and memory

- Each response carries a strong `ETag` that is the SHA-256 of the exact body, quoted. The comment mode
  has its own ETag because the bodies differ.
- `Cache-Control: no-cache`, so the firewall revalidates on every fetch. If `If-None-Match` matches the
  current ETag, the answer is `304 Not Modified` with no body.
- The feed is built in memory. A build reads the database only when one of these changed: `ip_bans`,
  the `add-vpn-proxy-detection` lists or policies, the `add-join-guard` area bans, or the ASN database.
  Changes arrive as the events `ipban.changed`, `ipban.lifted` and the list and area-ban events, and
  every replica subscribes to them (not a durable subscription; the feed is rebuilt from the database
  after a restart).
- A background rebuild runs every `blackhole.firewall.feed.rebuild-interval` (default `PT60S`), so a
  lost event is corrected within one minute. The rebuild runs on a scheduler, never on a request
  thread.
- A request never reads the database. The first request after start-up waits for the first build
  (single-flight), and that build happens once.
- A failed build keeps the last good feed. An error never produces an empty feed, because an empty
  feed tells the firewall to remove every ban. If no good feed exists yet, the route answers 503 with
  no body and the firewall keeps its own table.

### 5. Expiry, lifecycle and validation

- Expiry is a read-time rule (`expires_at > now`), not a state change. The next feed build omits an
  expired entry, and the firewall drops it at its next refresh. No sweeper writes to the table for
  expiry. Lifted and expired rows are kept for history and purged by a retention sweeper after
  `blackhole.ipban.retention` (default `P365D` after `lifted_at` or `expires_at`), with the sweep
  reading the injected `Clock`.
- The option "also ban last IP" is a request flag on a network ban. It is accepted only for network
  bans; a mute or a kick cannot carry it (400). The backend asks the `add-player-intelligence` port
  for the player's last address. If none is on record, the player punishment is applied and the
  response carries a warning `NO_IP_ON_RECORD`. Otherwise an `ip_bans` row is written with
  `source = PUNISHMENT`, `punishment_id`, the punishment's `case_id` and the punishment's expiry
  (`expires_at` null for a permanent punishment).
- Lifting the player's punishment does not lift the linked IP ban. The IP ban is lifted only through
  its own route, with a mandatory `lift_reason`.
- The panel shows how many other accounts seen on that address are active, so that staff can see
  the cost of a range ban (shared addresses, carrier-grade NAT). The count comes from sightings and
  is shown to staff only.
- Input: a single address or a CIDR, IPv4 or IPv6. The value is parsed and normalised; the host bits
  of a CIDR must be zero or are cleared with a warning. Reason 1 to 200 characters.
- Width limits: IPv4 prefixes shorter than `/16` and IPv6 prefixes shorter than `/32` are refused
  with 422 `IPBAN_RANGE_TOO_WIDE`. Staff who need wider ranges use the firewall directly.
- Protected networks: `blackhole.ipban.protected-networks` (default: loopback, link-local, the RFC
  1918 and unique-local ranges, plus whatever the operator adds, for example the edge ranges of
  their proxy). A ban that overlaps a protected network is refused with 422 `IPBAN_PROTECTED`. The
  feed renderer runs the same check before it writes a line, so a bad row cannot reach the firewall.
- Roles: `VIEWER` reads. `MODERATOR` creates and lifts bans that expire within
  `blackhole.ipban.moderator-max-duration` (default `P30D`). `ADMIN` creates permanent bans and
  lifts any. Enforced in the service, not in the controller.
- Overlaps: a new ban that lies inside an active range is accepted with a `warnings` entry, not
  refused, because staff may want a narrow ban inside a wide one for a clear reason.

### 6. The proxy layer: a second, always-on check

- Backend: `GET /ipban/active` (role `PROXY`) returns the active rows as `{id, network, expiresAt,
  reason}`. It is the full list; it is small (thousands at most) and cached on the proxy.
- Velocity: `IpBanCache` keeps the networks in a sorted structure with a prefix match, refreshed on
  the `blackhole.events.ipban.changed` NATS event and polled every `blackhole.ipban.proxy.poll`
  (default `PT60S`). Refresh runs off the login thread.
- Login: after the existing ban check, a match denies the login with the ban's reason through the
  disconnect screen. The check uses only the local cache; there is no HTTP call per login.
- Fail-open with a metric: if the list has never loaded, the login is allowed, a warning is logged
  and `blackhole.ipban.proxy.unloaded` increments. Once loaded, a failed refresh keeps the last
  known list and increments `blackhole.ipban.proxy.refresh_failures`.
- The proxy enforces the address Velocity sees. Behind an edge that is whatever real-IP forwarding
  provides (decision 11).

Rejected: an HTTP check on every login. It couples every login to backend availability and adds
latency to the login path that the evasion sighting already makes.

### 7. Vanilla import of `banned-ips.json`

- Each entry: `ip` (single address; a CIDR is accepted too), `created`, `source`, `expires`,
  `reason`. Expiry follows the player rules (`forever` or empty is permanent; a bad date marks the
  entry invalid).
- `templateKey`/`reasonTemplates` are used as reason sources only. No section, no ELO, no escalation
  (the same "records, not offences" rule as player imports).
- `import_key` = the entry's canonical network plus its `created` timestamp. Re-importing the same
  file is a no-op.
- Expired entries are stored lifted, so they appear in history and never reach the feed.
- The summary gains `ipsImported`, `ipsInvalid` and `ipsSkipped`; the dry run computes the same
  counts and writes nothing.

Rejected: keeping the skip. A server that moves to Blackhole would lose every IP ban it had.

### 8. Metrics and the proxy check (no firewall health indicator)

The backend does not check the firewall. Whether the firewall pulls is visible on the firewall, and
the backend only reports what it serves.

- Counter `blackhole.ipban.feed.requests`, tagged `feed` (`ips`, `networks`), `client` (the configured
  name) and `outcome` (`200`, `304`, `404`, `503`).
- Gauge `blackhole.ipban.feed.entries`, tagged `feed`, from the last successful build.
- Gauge `blackhole.ipban.feed.last_fetch_seconds`, tagged `client`: the time since that client last
  got a `200` or `304`. An operator alert on it shows a firewall that has stopped pulling.
- Counter `blackhole.ipban.feed.refused`: protected-network refusals at render time.
- Timer `blackhole.ipban.feed.build`; counter `blackhole.ipban.feed.build_errors`.
- No health indicator for the firewall. A DOWN indicator would stop nothing, and the firewall owns
  its own status.
- The proxy layer keeps its own check: `GET /ipban/active` and the proxy metrics of decision 6.

### 9. Client, roles and events

- New API routes under `/ipban`: `POST /ipban` (create), `GET /ipban` (paged list, active filter),
  `GET /ipban/{id}`, `POST /ipban/{id}/lift`, `GET /ipban/active` (PROXY). Each route has an
  `*Api` interface with the OpenAPI annotations, a thin controller and a sealed DTO.
- The feed routes sit outside the `/ipban` API. They are excluded from the published OpenAPI document
  and generate no client operation, because the firewall is their only caller and the token is never
  a value a Java client sends.
- Test: `FeedLoggingTest` captures the access log and the metric tags for a request with a valid token
  and asserts that neither contains the token or the raw path, only the route template.
- NATS: `blackhole.events.ipban.created`, `blackhole.events.ipban.lifted`,
  `blackhole.events.ipban.changed` (the feed cache and the proxies listen to the last one). Payloads
  carry the ban id and the kind of change, not the address, because events leave the backend.
- Client spec: the next free version after the one in `client/build.gradle.kts` (0.0.44 today) at
  implementation time.

### 10. Operator note: the firewall side

Written to `docs/` at implementation time. Once per feed:

1. Create an alias of type "URL Table (IPs)" named for the feed (for example `blackhole_ips` for
   `/ips` and `blackhole_networks` for `/networks`), with the feed URL including the token. Use
   `?comments=false` if the spike says so.
2. Create the cron job "Update and reload firewall aliases" at one-minute frequency, as the refresh.
3. Create the block rule once, on the interface that faces players, using the aliases.

Also stated in the note: entries added by hand to a URL Table are overwritten on refresh; the feed is
the source; a token is rotated by adding a second token and restarting the backend; the alias must only
be created on a direct ingress (open question 1).

### 11. Real client address and the scope of the firewall layer

The firewall sees the TCP peer of the connection. In a direct setup (client to firewall to Velocity)
that is the player's address, and the feed blocks it before the proxy sees the packet.

Behind an edge proxy, the firewall sees the edge address. A feed entry for a player's address does
nothing useful there, and an edge address in the feed would block every player behind that edge. So:

- The URL Table aliases are created only on a direct ingress. The backend cannot tell which ingress a
  request came from, so this is an operator rule, written in the operator note.
- The proxy layer (decision 6) always enforces on the address Velocity sees. For a direct ingress
  that is the player's address; behind an edge it is whatever real-IP forwarding provides. The same
  address is recorded in sightings, so the ban and the record agree.
- Open question 1 decides the real-IP mechanism. Until then, the firewall layer stays off on any
  edge-fronted deployment.

Rejected: firewall-only enforcement (silent failure behind an edge). Rejected: proxy-only enforcement
(traffic still reaches Velocity; no network-edge drop).

## Spike results (group 0)

The following are recorded in the PR description. They decide only the comments default and the operator note, and group 3 does not wait for them. The spike runs on a lab OPNsense only, against a local stub that serves a fixed file.

- `#` comment lines: ignored or rejected? Result decides the default of `?comments`.
- Basic auth in the URL: works or not? Fallback if the token path is refused.
- Table entry limit (pf table-entries) against the expected size of each feed, using the largest
  ASN networks in the GeoIP database as the estimate.
- Refresh at a one-minute cron interval: does a change appear within about two minutes?
- IPv6 bare addresses and CIDRs in the networks alias: does `URL Table (IPs)` load them, or is a
  different type needed?
- Empty body: does the table empty (intended) or keep its content?
- HTTP 503 or 404 from the feed: does the table keep its last content? The answer goes into the operator note.
- Manual entry added to the table: overwritten on refresh?

## Risks / Trade-offs

- **Edge-address collateral.** A wrong alias on a shared edge can block every player behind it.
  Mitigations: the alias is created only on a direct ingress (operator note), protected networks are
  checked in the service and again at render time, and the feed never contains a protected range.
  Residual risk: an operator adds an edge range to the protected list only after the damage;
  documented in the operator note.
- **A feed that is wrongly empty.** An empty feed removes every ban from the firewall's table.
  Mitigations: an error never produces an empty feed (decision 4), and the spike checks what the table
  does on 503 and on an empty body.
- **Spike findings.** The spike decides the comments default and the operator note. It does not gate the build. A finding that changes the format itself (bare addresses or the networks alias type) becomes a spec change before the code PR merges.
- **Stale firewall state.** The firewall lags behind the database by up to its cron interval plus the
  backend rebuild interval. Acceptable: the proxy layer enforces immediately on the change event.
- **Shared addresses.** Range bans can hit innocent players. The panel shows the account count
  before the ban is saved; the moderator-duration limit caps the cost of a mistake.
- **Import noise.** Old vanilla IP bans may cover addresses that are dynamic and no longer belong to
  the banned player. Imports keep their own `created` date and the panel labels them `IMPORT`.
- **Feed size.** Hosting and ASN networks can make the networks feed large. The spike sizes it against the pf table limit, and the result goes into the operator note.

## Migration plan

1. Land `amend-data-protection-principle`, `add-player-intelligence`, `add-vpn-proxy-detection` and
   `add-join-guard` first (prerequisites).
2. Migration `V<next>__ip_bans.sql` is additive; no existing row changes. Rollback is dropping the
   new table and the feed routes; the proxy layer then simply has no list.
3. Deploy the backend with no feed clients configured. The feed routes answer 404 and nothing changes
   on any firewall.
4. Run the spike on a lab firewall. Record the results (section "Spike results"). Then configure one
   feed client, create the two URL Table aliases and the cron job on the lab firewall, and verify the
   block end to end. Only then configure the production firewall.
5. Deploy the Velocity plugin with the proxy layer. Until it is deployed, IP bans are stored but not
   enforced at the proxy; this is the only window and is announced in the release notes.
6. Re-run the vanilla import with `dryRun=true` on a real `banned-ips.json`, review the counts, then
   import.

## Open questions

1. Real client address: which mechanism does the network use in front of Velocity (PROXY protocol
   with a reader, or a vendor forwarding plugin)? This decides whether the firewall feed can be used
   on that deployment at all. Until it is settled, no alias is created on an edge-fronted deployment.
2. Should `MODERATOR` be allowed to lift any IP ban, or only ones they created?
3. Is a `/ipban` on an offline player (last address known, no session) acceptable, or must the
   player be online?
4. Erasure on request: an IP ban outlives the player's account by design (it protects the network).
   Does the data protection owner accept that, and for how long after the last sighting?
5. Should the template reason of an IP ban be shown to the player verbatim, or is a shorter public
   reason required?

