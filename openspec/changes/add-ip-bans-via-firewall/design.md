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

OPNsense API, checked against the official documentation on 2026-10-28
(https://docs.opnsense.org/development/api/core/firewall.html and
https://docs.opnsense.org/manual/aliases.html):

- `POST /api/firewall/alias_util/add/{alias}`, `POST /api/firewall/alias_util/delete/{alias}`,
  `GET /api/firewall/alias_util/list/{alias}`, `POST /api/firewall/alias_util/flush/{alias}`.
  The docs list only the `$alias` parameter. They do not document the request body. Community
  examples post `{"address": "<ip or cidr>"}` as JSON; this is verified in the first implementation
  task and not assumed.
- `POST /api/firewall/alias/reconfigure` applies alias changes to the running firewall. Changes made
  through `alias_util` are not live until a reconfigure runs.
- Alias types: Network accepts CIDR; Host accepts single addresses, ranges or domain names; the
  documentation of `alias_util` states no type restriction. A forum report from the 19.1 era says
  `alias_util` can overwrite the alias with a delay; the reconciliation therefore reads back what it
  wrote and does not trust a 200.
- Authentication: API key and secret, HTTP basic, per OPNsense API user. The key is read from env.

## Goals / Non-Goals

**Goals**

- A staff IP ban that holds at the firewall when the firewall sees the player's real address, and
  always holds at the proxy.
- The backend database is the only truth; a firewall outage or a manual edit on the firewall cannot
  leave a ban that was lifted in the panel, or lose one that was made.
- Imported vanilla IP bans keep their history.

**Non-Goals**

- Making the firewall the only enforcement point. It is an optimisation and a network-edge
  protection, not the authority.
- Choosing a real-IP forwarding technology for the edge. That is a deployment decision.
- Alias-based rules for ports or protocols. The alias holds addresses only.

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

### 2. The alias is derived state; the reconciliation is the mechanism

Blackhole manages one alias, configured as `blackhole.ipban.firewall.alias` (default
`blackhole_ipban`), type Network, created by the operator once. Blackhole owns its content.

- Desired set = every `ip_bans` row with `lifted_at IS NULL` and (`expires_at IS NULL` or
  `expires_at > now`), with `now` from an injected `Clock`.
- The reconciler runs (a) on a `ipban.changed` NATS event, debounced to at most one run per 5
  seconds, (b) every `blackhole.ipban.firewall.reconcile-interval` (default `PT5M`), and (c) once at
  start-up. Each run: `GET alias_util/list/{alias}`, compute the difference against the desired set,
  `POST alias_util/add` for missing entries, `POST alias_util/delete` for extra entries, then one
  `POST alias/reconfigure` when anything changed, then read the list back and compare. The run
  succeeds only when the read-back equals the desired set.
- A full-list overwrite through the alias controller (`set`, `import`) was rejected: it replaces
  configuration that the operator may share with other rules and cannot be scoped to Blackhole's
  entries.
- Drift is repaired on the next run. Manual edits on the firewall are overwritten, and the
  documentation says so.

### 3. Real client address and the scope of the firewall layer

The firewall sees the TCP peer of the connection. In a direct setup (client to firewall to Velocity)
that is the player's address, and the alias blocks it before the proxy sees the packet.

Behind an edge proxy, the firewall sees the edge address. Adding a player's address to the alias
does nothing useful, and adding an edge address would block every player behind that edge. The
design therefore has three rules:

- `blackhole.ipban.firewall.enabled` (default `false`) switches the firewall layer on. It is
  documented as "on only for a direct ingress". Operators turn it on deliberately.
- The proxy layer (decision 6) always enforces on the address Velocity sees. For a direct ingress
  that is the player's address; behind an edge it is whatever real-IP forwarding provides. The same
  address is recorded in sightings, so the ban and the record agree.
- Open question for the maintainer: which real-IP mechanism the network uses (PROXY protocol at the
  edge with a Velocity-side reader, or a vendor plugin). Until that is settled, the firewall layer
  stays off on any edge-fronted deployment.

Rejected: firewall-only enforcement (silent failure behind an edge). Rejected: proxy-only enforcement
(traffic still reaches Velocity; no network-edge drop).

### 4. Expiry, lifecycle and the link to a punishment

- Expiry is a read-time rule (`expires_at > now`), not a state change. The reconciler removes an
  expired entry from the alias on its next run; no sweeper writes to the table for expiry. Lifted
  and expired rows are kept for history and purged by a retention sweeper after
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

### 5. Validation and guard rails

- Input: a single address or a CIDR, IPv4 or IPv6. The value is parsed and normalised; the host bits
  of a CIDR must be zero or are cleared with a warning. Reason 1 to 200 characters.
- Width limits: IPv4 prefixes shorter than `/16` and IPv6 prefixes shorter than `/32` are refused
  with 422 `IPBAN_RANGE_TOO_WIDE`. Staff who need wider ranges use the firewall directly.
- Protected networks: `blackhole.ipban.protected-networks` (default: loopback, link-local, the RFC
  1918 and unique-local ranges, plus whatever the operator adds, for example the edge ranges of
  their proxy). A ban that overlaps a protected network is refused with 422 `IPBAN_PROTECTED`. The
  same check runs again in the firewall adapter before any alias write, so a bad row cannot reach
  the firewall.
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
- Expired entries are stored lifted, so they appear in history and never reach the alias.
- The summary gains `ipsImported`, `ipsInvalid` and `ipsSkipped`; the dry run computes the same
  counts and writes nothing.

Rejected: keeping the skip. A server that moves to Blackhole would lose every IP ban it had.

### 8. Health and metrics

- Micrometer gauges: `blackhole.ipban.alias.in_sync` (1 or 0), `blackhole.ipban.alias.pending`
  (size of the last computed difference), `blackhole.ipban.alias.last_success_seconds`.
- Counters: `blackhole.ipban.alias.errors` tagged `operation` (`list`, `add`, `delete`,
  `reconfigure`, `read_back`), `blackhole.ipban.alias.refused` for protected-network refusals at the
  adapter.
- Timer: `blackhole.ipban.alias.reconcile`.
- Health indicator `ipban-firewall`: UP while the last success is younger than three times the
  reconcile interval, DOWN otherwise. A DOWN indicator does not stop the application or the proxy
  layer.
- Backoff: after consecutive errors the reconciler waits 30 seconds, doubling, capped at 15 minutes,
  so an unreachable firewall gets no burst of requests.
- Credentials: `blackhole.ipban.firewall.base-url`, `.api-key`, `.api-secret` from
  `BLACKHOLE_OPNSENSE_BASE_URL`, `BLACKHOLE_OPNSENSE_API_KEY`, `BLACKHOLE_OPNSENSE_API_SECRET`, with
  local-dev defaults in `application.yml` (empty, so the adapter stays disabled). The secret is never
  logged. Requests use TLS verification by default; a self-signed certificate needs an explicit
  CA path, not an off switch.

### 9. Client, roles and events

- New API routes under `/ipban`: `POST /ipban` (create), `GET /ipban` (paged list, active filter),
  `GET /ipban/{id}`, `POST /ipban/{id}/lift`, `GET /ipban/active` (PROXY). Each route has an
  `*Api` interface with the OpenAPI annotations, a thin controller and a sealed DTO.
- NATS: `blackhole.events.ipban.created`, `blackhole.events.ipban.lifted`,
  `blackhole.events.ipban.changed` (the reconciler and the proxies listen to the last one). Payloads
  carry the ban id and the kind of change, not the address, because events leave the backend.
- Client spec: the next free version after the one in `client/build.gradle.kts` (0.0.44 today) at
  implementation time.

## Risks / Trade-offs

- **Edge-address collateral.** A wrong firewall setup can block a shared edge. Mitigations: the layer
  is off by default, protected networks are checked in the service and again in the adapter, and
  the health indicator flags it. Residual risk: an operator adds an edge range to the protected list
  only after the damage; documented in the operator note.
- **Alias API uncertainty.** The request body of `alias_util` add/delete is not in the official
  reference. The first task is a spike against a lab OPNsense; if the body differs, the adapter
  changes and nothing else does.
- **Stale firewall state.** Between two runs the alias can lag behind the database by up to the
  reconcile interval. Acceptable: the proxy layer enforces immediately on the change event.
- **Shared addresses.** Range bans can hit innocent players. The panel shows the account count
  before the ban is saved; the moderator-duration limit caps the cost of a mistake.
- **Import noise.** Old vanilla IP bans may cover addresses that are dynamic and no longer belong to
  the banned player. Imports keep their own `created` date and the panel labels them `IMPORT`.

## Migration plan

1. Land `amend-data-protection-principle` and `add-player-intelligence` first (prerequisites).
2. Migration `V<next>__ip_bans.sql` is additive; no existing row changes. Rollback is dropping the
   new table and the disabled adapter; the proxy layer then simply has no list.
3. Deploy the backend with the firewall layer off. Create the alias on the firewall as Network type
   and an API user with alias permissions. Set `blackhole.ipban.firewall.enabled=true` only after
   the spike has verified the alias write, read-back and reconfigure on the lab firewall.
4. Deploy the Velocity plugin with the proxy layer. Until it is deployed, IP bans are stored but not
   enforced at the proxy; this is the only window and is announced in the release notes.
5. Re-run the vanilla import with `dryRun=true` on a real `banned-ips.json`, review the counts, then
   import.

## Open questions

1. Real client address: which mechanism does the network use in front of Velocity (PROXY protocol
   with a reader, or a vendor forwarding plugin)? This decides whether the firewall layer can be
   used on that deployment at all.
2. Should `MODERATOR` be allowed to lift any IP ban, or only ones they created?
3. Is a `/ipban` on an offline player (last address known, no session) acceptable, or must the
   player be online?
4. Erasure on request: an IP ban outlives the player's account by design (it protects the network).
   Does the data protection owner accept that, and for how long after the last sighting?
5. Should the template reason of an IP ban be shown to the player verbatim, or is a shorter public
   reason required?
