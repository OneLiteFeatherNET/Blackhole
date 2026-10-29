## Why

Blackhole bans players by account. A banned player who makes a new account from the same
connection comes back within minutes, and the ban-evasion signal only tells staff about it after
the fact. Staff also have no way to stop a connection that is clearly abusive but has no account
yet (a bot wave, a booter, a cracked-account farm). Today a `banned-ips.json` file from a vanilla
server is skipped on import, so a server that moves to Blackhole loses every IP ban it had.

Staff need an IP ban that is a first-class record: one address or one CIDR range, with a reason,
an optional expiry and a link to the case that caused it. The ban has to hold at the network edge
where it is cheap, and also in the proxy, where it still holds when the firewall cannot see the
real client address.

## What Changes

- **New IP ban record.** An IP ban covers one IPv4 or IPv6 address, or a CIDR range, with an
  optional expiry, a reason (template reason or free text, 1 to 200 characters), the actor (the
  Entra ID token's `oid`, or the system identity for imports) and an optional link to a punishment
  and its case. Lifting an IP ban is recorded with a mandatory reason. Lifting a player's punishment
  does not lift an IP ban; the two have separate lifecycles, because one address can belong to
  several people.
- **Three ways to create one:**
  - Panel: an IP bans page, and a "Ban IP" action on the player page.
  - In game: `/ipban <player|ip> <template>` (permission `blackhole.ipban`). For a player, the
    address used is the last one on record for that player.
  - With a player ban: an "also ban last IP" option on a network ban. The IP ban shares the
    punishment's expiry and case. If no address is on record, the player ban still applies and the
    response says so.
- **Enforcement in two layers:**
  - **Firewall, pulled**: the backend serves two plain-text feeds, one for single addresses and one
    for networks, to token-gated HTTPS routes. The OPNsense firewall fetches them on its own
    schedule into URL Table aliases. The backend never calls the firewall and holds no firewall
    credentials. The networks feed also carries the VPN, hosting and proxy ranges of classes whose
    policy is `DENY`, the hosting ASNs while the HOSTING policy is `DENY`, and active ASN area bans. Country area bans are not included.
    Details in the new capability `firewall-feed`; the choice against pushing is recorded in
    `design.md`, decision 2.
  - **Proxy**: Velocity loads the active IP bans from the backend, keeps them in memory as network
    ranges, and denies a matching login with the ban reason. The proxy layer is always on. It is the
    layer that still works when the firewall sees only an edge address.
- **Vanilla import.** `banned-ips.json` is imported instead of skipped. Each entry becomes an IP ban
  with source `IMPORT`. Expired entries go into the history only. Imports are records, not
  offences: no ELO, no escalation.
- **Feed health and metrics.** The backend counts feed requests by outcome (200, 304, 404, 503),
  reports the entry counts of each feed, and exposes the time since each firewall client last
  fetched. An alert on that time shows a firewall that has stopped pulling. The backend has no
  firewall health check: it cannot see the firewall, and the firewall owns its own status.
- **Privacy.** IP ban rows store the clear address or range. The amended data protection principle
  (`amend-data-protection-principle`) allows this for network security under legitimate interest.
  The feeds carry the clear addresses to the firewall only, behind a secret token. Lifted and expired
  rows are removed by a retention sweeper. Nothing from this capability is published: the public ban
  list (`publicapi`) never shows an IP ban, and feed requests, logs and metrics name only the route
  template, never the token or the path.

Not breaking: the change only adds routes, a client API and a proxy feature. The generated client
gets new operations for `/ipban`; the feed routes are excluded from the client. No existing field or
enum changes. The Velocity plugin needs the new backend route (`GET /ipban/active`) to enforce IP
bans. A proxy built before this change keeps working and simply does not enforce them. See
`design.md`, decision 6.

## Capabilities

### New Capabilities

- `ip-bans`: the IP ban record (single address or CIDR, IPv4 and IPv6, expiry, reason, actor, case
  link), its creation and lifting rules, the active list for proxies, the `/ipban` command, the
  "also ban last IP" option, and the retention of lifted rows.
- `firewall-feed`: the two token-gated feed routes (single addresses and networks), the feed
  clients and their tokens, the content rules (DENY-class ranges, hosting and ASN networks, area bans
  excluded for countries), the comment-free mode, ETag and `304` polling, the failure rule (an error
  never empties a feed), and template-only logging.

### Modified Capabilities

- `proxy-enforcement`: login denial for an active IP ban, the `/ipban` command, the rule that the
  proxy enforces the address it sees (not the address a firewall sees), and the fallback when the
  IP ban list is not loaded yet.
- `vanilla-import`: `banned-ips.json` is imported; the summary reports IP totals, imported, skipped
  and invalid entries.
- `punishment-management`: the "also ban last IP" option on a network ban, and the case event that
  records an IP ban.
- `admin-web-ui`: the IP bans page, the "Ban IP" action on the player page and the IP ban option in
  the ban dialog.

## Out of scope

- Pushing IP bans to the firewall. The OPNsense API client, alias sync, reconciliation, read-back,
  OPNsense API credentials and a firewall health check are not part of this change. The firewall
  pulls the feeds (`design.md`, decision 2).
- Country area bans in the firewall feed. The firewall's own GeoIP aliases cover countries.
- A firewall status tile on the dashboard. It depended on the firewall health check that is removed.
  No replacement tile is part of this change.
- Hashing or tokenizing IP addresses for IP bans. The firewall and the proxy need the clear value.
  Evasion sightings keep their keyed hash (`evasion-detection`); this change does not touch them.
- Reading the last IP from sightings. That is delivered by `add-player-intelligence`, which this
  change depends on.
- Behind an edge proxy with real-IP forwarding: the firewall layer is switched off unless the
  deployment passes the real client address through (see `design.md`, decision 11). Choosing and
  configuring that forwarding is a maintainer decision and is not part of this change.
- Rate limiting of IP ban writes and feed requests. Infra owns rate limiting (`config.yaml`
  principle 5).
- VPN, hosting and proxy detection. That is `add-vpn-proxy-detection`; this change only reads its
  lists and policies.
- Any change to ban-evasion detection, the Redis punishment mirror or the appeal workflow. An IP ban
  has no appeal of its own; the existing appeal flow applies to the player ban that caused it.
- Banning IPs from Discord.

## Prerequisites

- `amend-data-protection-principle`: clear IP addresses may be stored where a feature needs them,
  with retention enforced by sweepers. Not yet on `main`.
- `add-player-intelligence`: the sightings that give the "last IP" of a player, and the offline ASN
  data used for the hosting and ASN networks of the feed. Not yet on `main`.
- `add-vpn-proxy-detection`: the VPN, hosting and proxy lists and the per-class policy the networks
  feed reads. Proposed, not on `main`.
- `add-join-guard`: the ASN area bans the networks feed reads. Proposed, not on `main`.

## Impact

- **Backend**: a new `ipban/` feature package (controller, service, DTOs, JDBC store, retention
  sweeper), a `firewall/` package that renders the two feeds (token registry, content source, cache,
  renderer, controller), the import service in `imports/`, the punishment apply path (the option),
  `application.yml` (`blackhole.ipban.*` and `blackhole.firewall.feed.*`).
- **Database**: one Flyway migration `V<next>__ip_bans.sql`, the next free version at implementation
  time (main is at V27 today). Additive only.
- **API**: routes under `/ipban`, a machine route `GET /ipban/active` for the proxy, and the two feed
  routes under `/firewall/feed/{token}/` (outside the published OpenAPI document). `client/specs/blackhole-api-*.yml`
  gets the next free version.
- **Velocity**: an in-memory IP ban list, a login check, the `/ipban` command, the NATS subscription.
- **Web**: an IP bans page, a dialog option, a player page action.
- **Infrastructure**: Helm mounts the feed client hash files from a Kubernetes Secret, and the ingress
  rejects plain HTTP on the feed path. An operator creates two URL Table (IPs) aliases, the cron job
  "Update and reload firewall aliases" and the block rule once on the firewall. Blackhole holds no
  firewall credentials.
- **Docs**: `docs/` operator note for the firewall setup (aliases, cron, token rotation, the
  direct-ingress rule), written at implementation time.

Ships as: feat(ipban): block banned IP addresses at the firewall and on the proxy
