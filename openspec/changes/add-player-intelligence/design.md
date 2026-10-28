## Context

The code this design builds on (checked on `main`, 2026-10-28):

- `backend/.../evasion/service/IpCorrelationService.java`: `recordLogin(ownerHash, rawIp)` computes
  `HMAC-SHA512(ip, blackhole.evasion.ip-salt)`, upserts an `IpCorrelationTokenEntity` (token, owner,
  first/last seen, occurrence count) and publishes `evasion.detected` when more than one owner shares
  a token inside `detection-window-days`. Without a salt it throws, and `EvasionController` answers 503.
- `backend/.../evasion/IpCorrelationRetentionSweeper.java`: deletes sightings older than
  `blackhole.evasion.retention-days` (90). `EvasionController` exposes `POST /evasion/record` and
  `GET /evasion/{owner}/sightings`.
- `backend/.../evasion/service/ClientFingerprintService.java` and `dto/ClientFingerprintDTO.java`:
  fingerprints from the brand, channels and mods a proxy saw. Its rules change is in flight
  (`add-client-fingerprint-rules`). This change does not touch fingerprints; it records the brand and
  version on the session.
- `backend/.../profile/dto/SessionInfoDTO.java` and `ProfileController.updateSessionInfo`: protocol
  version and client brand per owner, merged into the profile. This stays as it is.
- `velocity/.../listener/PlayerLoginListener.java`: on `LoginEvent` it reports the evasion sighting
  (owner hash and raw IP, before the ban check), checks the ban through `PunishmentState`, denies
  network bans, and then records protocol version through `PunishProfileApi.updateSessionInfo`.
- `velocity/.../listener/PlayerClientBrandListener.java`: sends the brand as session info.
- `backend/.../playerresolver/`: names are resolved live through Otis, Mojang and NameMC. The backend
  stores no player names today; that gap is what `add-name-history` closes.
- Flyway: `V27__kick.sql` is the head on `main`, so the next free version is V28 today.
- `openspec/config.yaml` principle 1 (hashed IP and UUID) is amended by `amend-data-protection-principle`,
  which this change requires to be merged first.

## Goals / Non-Goals

**Goals**
- One record per login attempt and per session, with the clear IP, that every later feature reads.
- Enrichment without any external call on the login path.
- Velocity never waits for the backend, and a backend outage never blocks a login.
- The existing ban-evasion detection keeps its behaviour across the migration.
- Staff can say who a session belongs to: the raw UUID and the name are stored, not only a hash.

**Non-Goals**
- Replay of lost reports, a message queue, or a second copy of the data in NATS. The sessions are a
  record for staff, not an event stream for third parties.
- Blocking or banning by IP. That is the OPNsense alias change.
- Publishing any session data. Nothing here goes into the public ban list or into events for other systems.

## Decisions

### 1. Transport: HTTP from Velocity, not NATS

Velocity reports sessions with `POST /intel/sessions` and `PATCH /intel/sessions/{sessionId}` through
the generated client, as it already does for evasion records and session info. The proxy is
subscribe-only on NATS (`switch-event-bus-to-nats`, D6/D7), so NATS publishing from Velocity would
break that rule. Rejected: a NATS publish from Velocity (breaks the subscribe-only rule); a separate
collector service (one more deployment for a few routes).

### 2. Session identity is client-generated

Velocity creates a random UUID v4 per connection (`sessionId`) when it sends the start report, keeps it
in memory for the connection, and uses it for the end and brand updates. The backend treats
`sessionId` as the idempotency key: a repeated start with the same id returns the stored row and does
not write a second one. The id carries no player data. Rejected: a database sequence (the proxy would
need a round trip before it can send the end); the player's UUID plus login time (two logins in the
same millisecond from one player are then indistinguishable).

### 3. Storage: one table, clear IP, owner hash, keyed token

New table `player_sighting` (the Flyway migration `V<next>__player_sighting.sql`, the next free version
after `V27` at implementation time):

| Column | Type | Notes |
|---|---|---|
| `session_id` | uuid, primary key | client-generated, decision 2 |
| `owner_hash` | char(128), indexed | SHA-512 of the UUID, the same hash every proxy sends |
| `player_uuid` | uuid, indexed | the raw player UUID in clear text, decision 10 |
| `player_name` | varchar(16) | the name at login; used by `add-name-history` |
| `ip` | varchar(45), indexed | the clear IP, IPv4 or IPv6 normalised; the amended principle allows it |
| `ip_token` | char(128), nullable, indexed | `HMAC-SHA512(ip, ip-salt)`; null while the salt is unset |
| `asn` / `as_org` | bigint / varchar(128), nullable | from decision 4 |
| `country_code` | char(2), nullable | from decision 4 |
| `client_brand`, `client_version` | varchar(64), nullable | from the brand report |
| `protocol_version` | integer, nullable | at login |
| `virtual_host` | varchar(255), nullable | the host the client typed, without the port |
| `outcome` | varchar(16) | `ALLOWED` or `DENIED` |
| `started_at`, `ended_at` | timestamp, `ended_at` nullable | `ended_at` null means the end was never reported |
| `created_at` | timestamp | |

The raw UUID and the clear IP are stored because staff must see who a player is and where a login came
from, and the ASN lookup needs the IP; the amended principle covers both under legitimate interest with the
retention below. The keyed token is kept so the ban-evasion correlation (decision 6) works on new rows without
the salt being needed to read them.

Rejected: only the clear IP (the correlation of legacy rows would have to join on a token that cannot be
recomputed for them; the token column costs little); only the token (the panel could not show the IP
or run the ASN lookup on it; the ASN lookup happens at ingest and needs the clear IP anyway).

### 4. Enrichment is offline and never blocks

The backend opens two MaxMind DB files: an ASN file (`blackhole.intel.geoip.asn-path`) and a city or
country file (`blackhole.intel.geoip.city-path`). Both are read through the `com.maxmind.db:maxmind-db`
reader, which reads the files as memory-mapped data with no network I/O. The reader reloads a file when
its modification time changes (checked at most once per hour), so a refreshed file applies without a
restart.

- Dataset: DB-IP Lite (CC BY 4.0, no account, attribution text shown in the panel footer) is the default.
  GeoLite2 (MaxMind EULA, account needed for download) is supported by the same reader, so the operator
  can choose. Open question 1 asks the maintainer to choose.
- Refresh: a Kubernetes CronJob in the Helm chart downloads the file monthly into a volume that the backend
  mounts read-only. The backend does not download anything itself.
- Failure: a missing or unreadable file leaves the enrichment columns null and logs one warning per hour.
  The session is still stored.

Rejected: a per-login call to a paid geo API (external request per login, cost, and it leaks each IP to a
third party); reading the files only at startup (a refresh would need a restart).

### 5. Velocity reports after the ban check, off the login thread

`PlayerLoginListener.onLogin` decides the outcome first and then calls the session report once, with
`outcome` set. The current order (evasion record before the ban check) is replaced, so a denied login is
reported with `DENIED` in the same call. The report runs on a bounded single-thread executor with a
`blackhole.intel.report-timeout` (default 2 seconds). If the executor queue is full or the call fails,
the report is dropped and logged at debug. Velocity keeps the `sessionId` in a map keyed by the Velocity
connection; the `DisconnectEvent` sends the end, the brand listener sends the brand as a `PATCH`.

A proxy restart loses the open sessions' end reports. Those rows keep `ended_at` null and are excluded
from any future playtime sum.

Rejected: reporting in `PostLoginEvent` (a denied login would then not be reported, and the ban outcome
would be unknown to the row); a synchronous call on the login thread (a slow backend would delay every
login).

### 6. Ban-evasion correlation reads both tables during the transition

`IpCorrelationService` and `GET /evasion/{owner}/sightings` keep their contracts. The correlation query
reads `ip_correlation_token` (legacy, keyed-hash-only rows) and `player_sighting.ip_token` (new rows)
for the same token and window, and treats the union as the sightings of the token. The `evasion.detected`
event is published as today. No backfill: legacy rows cannot be reversed, and they expire under the
90-day evasion retention. When the legacy table is empty, the union query can be removed in a later change.

Rejected: backfilling `player_sighting` from `ip_correlation_token` (impossible, the IP is not recoverable);
dropping the legacy table now (the old proxies still write to it).

### 7. Retention

`blackhole.intel.retention-days` (default 90) and `blackhole.intel.retention-sweep-cron` (default 03:45
daily, after the evasion sweep at 03:30). `IntelRetentionSweeper` deletes `player_sighting` rows whose
`started_at` is older than the retention. It runs in its own feature package and does not reuse the
evasion sweeper, so the two windows can differ.

Rejected: one shared retention for evasion and intel (the two data sets have different reasons to keep
them; the evasion window stays what the maintainer set).

### 8. Read route and roles

`GET /intel/players/{owner}/sessions` returns the newest 50 sessions by default, paginated with the
standard page parameters (`standardize-api-pagination-and-problems`). It is open to VIEWER and above and
not to PROXY, so a proxy cannot read sessions back. Writes are open to PROXY only. The sealed DTO
`SessionDTO` nests `StartRequest`, `UpdateRequest`, `Response` and `Error`; the `Error` variant carries
the failure instead of throwing. The OpenAPI annotations live on `IntelApi`.

Rejected: sessions inside the `/evasion` routes (the evasion surface is about correlation; sessions are
a record of logins, so they get their own package).

### 9. The panel shows IPs only on the player page

The "Sessions" section is on the player page, for every role that may open it. The UI shows the IP and
the ASN organisation as text, under a header with the UUID and name. It keeps nothing in browser storage (`admin-web-ui`, the no-personal-data
requirement now names IP addresses). The public pages and the ban list never receive these fields.

### 10. Player identity: the raw UUID is stored next to the owner hash

Sessions store the player's raw UUID in clear text, next to the owner hash, the name and the clear IP. Staff
views and the in-game alt lookup show the UUID and the name, because a hash cannot tell a moderator who a player
is. The owner hash stays as the join key for the proxies, the legacy evasion data and the existing profile
routes. The raw UUID follows the session retention (decision 7) and erasure on request. Nothing published carries
it: `session.started` and `session.ended` carry the owner hash and the session id only, and the public ban list
carries only its own `playerHash` (unkeyed SHA-256 of the UUID, not the owner hash), never the raw UUID, name or IP (see `add-public-ban-list`).

Rejected: hash-only UUID storage (staff cannot identify players, and the alt lookup and the name history become
unreadable to a person); storing the UUID only in the punishment tables (a session would still show a hash).

## Risks / Trade-offs

- **Clear IPs and raw UUIDs in the database** are personal data. The mitigation is the amended legal basis, the
  90-day retention enforced by a sweeper, the owner-hash join key, and the rule that nothing published
  carries them. A breach of the database exposes IPs, UUIDs and names of the last 90 days; this is accepted by the
  maintainer's amendment (`amend-data-protection-principle`).
- **Lost reports** on backend outage or proxy restart. Staff see gaps in the session list. Accepted; the
  reports are a convenience record, not evidence.
- **Geo accuracy**: IP geolocation is approximate and some VPNs look like residential providers. The
  panel labels the country as "approximate" and the ASN organisation as the provider name, not as a
  verdict.
- **Load**: one write per login and one per disconnect. A large network with 2000 logins an hour is well
  within one Micronaut instance; the write path is a single insert plus one update.

## Migration plan

1. Merge `amend-data-protection-principle`.
2. Ship the backend with the new routes and the `player_sighting` migration, behind an empty
   `blackhole.intel.enabled` default of `true`. The routes return 404 when it is false.
3. Regenerate the client from the new spec version (next free after `0.0.44`, at implementation time).
4. Ship the Velocity plugin. Old proxies keep calling `POST /evasion/record`, which still writes the
   legacy table, so correlation covers both during the rollout.
5. Deploy the MaxMind DB volume and the refresh CronJob. Until the files exist, sessions are stored
   without enrichment.
6. After the 90-day evasion retention has passed and no proxy calls `POST /evasion/record`, a later change
   removes the legacy union.

Rollback: set `blackhole.intel.enabled` to false. Velocity then gets 404 and drops its reports; the
existing ban and evasion paths are unaffected.

## Open questions

1. Dataset: DB-IP Lite (default here, no account, attribution) or GeoLite2 (account and EULA)?
2. Retention: is 90 days the right window for clear IPs, or should it be shorter (30) for IPs and longer for
   the name and session metadata?
3. Should the sessions section show denied logins to every viewer or only to moderators and above?
4. Should `GET /intel/players/{owner}/sessions` be the single source for the evasion panel later, or stay
   separate?
