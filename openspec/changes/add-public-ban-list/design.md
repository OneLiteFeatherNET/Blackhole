## Context

The code this design builds on (checked on `origin/main`, 2026-10-28):

- `backend/.../punishment/PunishmentEntity.java`: `punishments` rows carry `identifier` (String id, the internal
  punishment identifier), `short_id` (the reference shown to staff and in the player's own punishment view), `owner`
  (the hashed owner), `section_key`, `scope`, `permanent`, `lifted`, `created_at`, `escalation_group`, `elo_cost`, `kind`
  (`PUNISHMENT` or `KICK`, from `add-kick`), `meta_data`. The expiry is NOT a column: it lives in `meta_data` under
  `Expirable.META_DATA_KEY_EXPIRATION_DATE` (read by `PunishmentEvents`).
- The `owner` is computed by the caller and is a SHA-512 hex value (`utils/OwnerHash`). It cannot give the player hash of
  decision 14. The apply request therefore carries the raw player UUID as `playerUuid`. The backend may hold it under
  `amend-data-protection-principle`, and `add-player-intelligence` (decision 10) already sends it from Velocity. The
  backend computes `playerHash` from it and stores only the hash. The older rule in `OwnerHash` (raw UUIDs never reach
  the backend) is superseded by that amendment. Open question 17 is answered in decision 14.
- Expiry is applied lazily as well as by a sweep: `ActivePunishmentExpirer` and `PunishmentExpirySweeper` clear
  expired rows, and "Expired punishments are cleared on profile read" (punishment-management). A stored "expired"
  status would therefore lag behind the real expiry. This design derives EXPIRED from the expiry instant on read.
- `punishment_template` / `TemplateSpec` carry `key`, `reason` (staff text), `section`, `escalationGroup`, steps and
  `metaData`. There is no public text today.
- `section/EnforcementKind.java`: `MUTE`, `BAN`, `SERVER`. The section kind of a punishment is the kind of its section.
- `BanIdKeyGuard` / `BanIdCrypto`: the Ban ID secret `BLACKHOLE_BANID_KEY` (32 random bytes, base64). Unset is allowed
  only in `dev` and `test` (random key per run, warning); elsewhere the backend refuses to start. HKDF-SHA256 with
  labelled `info` derives the lookup HMAC and the display key from it.
- `publicapi/controller/PublicApi.java` documents "every answer is `no-store`". `.claude/rules/public-api.md` says the
  same. `/public/**` is reserved by `staff-authentication` for the later player portal, with no route under it yet
  (`openspec/specs/staff-authentication/spec.md`, "Reserved prefix for the player portal").
- `openspec/config.yaml` principle 2: every route except `GET /health` needs an Entra ID access token (JWT bearer) and
  denies by default. This change adds a deliberate exception for three routes (decision 6). The exception is not
  yet ratified (open question 1).
- `standardize-api-pagination-and-problems` (proposal on `main`, not applied): `CursorPage<T>{items, nextCursor}`,
  `limit` 1 to 200 with default 50 and a 400 instead of a clamp (D6), signed cursor
  `base64url(payload) "." base64url(HMAC-SHA256(BLACKHOLE_CURSOR_KEY, payload))` (D7), RFC 8288 `Link` (D15),
  RFC 9457 problem bodies with `code` (D1-D3), fixed-status 404 for unknown and not-permitted alike (D4).
- Flyway: the main head is `V27__kick.sql`. Client spec: `client/build.gradle.kts` points at
  `blackhole-api-0.0.44.yml`.
- `openspec/config.yaml` principle 1: PII is hashed or tokenized wherever it is stored or published. The amendment
  `amend-data-protection-principle` (prerequisite for the wider data decisions) keeps the rule that anything published
  carries no IP, no raw UUID and no name. The list carries no IP, no raw UUID and no name. The one player-derived value
  it carries is the unkeyed `playerHash` of decision 14, which the maintainer decided on 2026-10-28 so that partner
  networks can adopt bans. Open question 18 asks whether the amendment text should name that exception.

## Goals / Non-Goals

**Goals**: a read-only, paginated list of sanctions for named, authenticated clients (the website and partner
networks), where each client is identified in logs and metrics; an entry that a client can cite by a stable
reference; a list that carries no raw identifier of a player, so a partner network can match its own players by their
player hash and a reader without a UUID learns nothing that names a player; a summary that cannot be used to single out
one punishment.

**Non-Goals**: anonymous or browser access; a portal page; player search or lookup, and a server-side lookup by player
hash; any link from an entry to a player page; showing names, staff, servers or evidence; per-locale reason texts; a
feed or webhook; per-client entry sets; client management UI; changing ban, mute or section semantics; rate limiting.

## Decisions

### 1. What is listed: opt-in per template, snapshot on apply

A template gains `publicReason` (optional text, 1 to 200 characters, plain text, `PiiGuard` applied). The rule for a
punishment:

- `kind = PUNISHMENT`, and a template with a non-blank `publicReason` whose section resolves to a section kind: the
  punishment is listed. A missing UUID does not prevent listing; the entry is published with `playerHash` null
  (decision 14).
- Otherwise it is never listed: no template, a template without public reason, and every `KICK` (kicks are never bans;
  `add-kick` decision 1).

The text, the section kind and the `playerHash` are copied onto the punishment when it is applied (`public_reason`,
`public_kind`, `public_player_hash`). Later template edits do not change listed entries. The `publicReason` on a
template is the only text the list shows: it is never derived from the staff `reason`, which may name a rule detail or
a case.

Alternatives considered:
- *List every punishment and let staff hide entries one by one*: rejected. Opt-out per row invites an accidental
  listing of a row whose reason was never reviewed. Opt-in per template means one reviewed text per template.
- *List the staff `reason`*: rejected. Staff reasons are free text and can name players, servers or cases.
- *Live join to the template*: rejected. A template edit would rewrite history, and a deleted template would silently
  remove entries.

### 2. Access: named clients, HTTP Basic Auth, Argon2id hashes in Secret files

**Credentials.** The ban-list routes accept HTTP Basic Auth (RFC 7617) with a username that names a configured
client and the client's password. Clients are configured by name only:

- `blackhole.ban-list.clients` (`BLACKHOLE_BAN_LIST_CLIENTS`, comma-separated names, default empty). A name matches
  `[a-z0-9-]{1,32}`.
- For each name, a file `<blackhole.ban-list.secrets-dir>/<name>` (`BLACKHOLE_BAN_LIST_SECRETS_DIR`) holds the
  client's password hash as one PHC string, `$argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>`. The Helm chart mounts
  one key per client from an existing Kubernetes Secret as a file in that directory. No password appears in
  config, in the environment, in the image or in a log.
- A client with no readable hash file, or a malformed hash, is a startup error outside `dev` and `test`. In
  `dev` and `test` it is skipped with a warning, so the list is simply unreachable there unless a dev hash file is
  present.

**Hash algorithm.** Argon2id with the OWASP minimum parameters (m = 19 MiB, t = 2, p = 1). Bcrypt was rejected: it
truncates input at 72 bytes, and it needs its own library for no gain. The implementation uses one Argon2 library,
proposed as Bouncy Castle and confirmed at implementation time.

**Passwords** are random, at least 32 bytes, generated by the operator. Operators produce the hash offline with the
same parameters (a command documented in `.claude/rules/public-bans.md`).

**Rotation.** Rotating a client's password means writing a new hash to its Secret key and restarting the backend. The
old password stops working at the restart. Adding a client means adding its name and its Secret key, then a restart.
Removing a client means removing its name, then a restart. A leaked client is cut off by the removal and a restart.
The attribution (decision 10) shows what the client read before that.

**Storage.** No database table, no admin UI and no API to manage clients in v1. A later option is a table with
hashed passwords managed from the admin UI, which would need its own change.

Alternatives considered:
- *Entra app roles through the client credentials flow*: rejected for v1. Partner networks are outside our tenant, so
  each one would need an app registration in our tenant. The existing role mapping can take it later.
- *An API key in a custom header*: rejected. It needs the same secret handling, is not a standard that client HTTP
  libraries support out of the box, and gives no more than Basic Auth over HTTPS.
- *Mutual TLS*: rejected for v1. It needs certificate issuance and rotation at the edge, which the infrastructure
  does not provide for these clients yet.
- *One shared password for all clients*: rejected. It gives no attribution, and one leak replaces every client.
- *Plaintext passwords in config or a Secret read by the application*: rejected. The brief requires hashes only.

**Principle 2 exception.** `config.yaml` principle 2 says every route except `GET /health` needs an Entra ID access
token. The three ban-list routes do not accept one (decision 6). This is a deliberate exception. It does not amend
the principle, because this change does not own that block. It is an open question for the maintainer (question 1).

### 3. Entry fields and their vocabulary

An entry, as returned by the list and the single read:

| field | value | notes |
|---|---|---|
| `hash` | 22 characters, base64url | 128 bits of the keyed entry hash (decision 4) |
| `playerHash` | 64 lowercase hex characters, or `null` | SHA-256 of the player's UUID with the versioned prefix (decision 14); `null` when the punishment had no UUID |
| `reason` | `publicReason` snapshot | plain text, never Markdown |
| `kind` | `NETWORK_BAN`, `MUTE`, `SERVER` | from `public_kind`: `BAN` maps to `NETWORK_BAN` |
| `status` | `ACTIVE`, `EXPIRED`, `REVOKED` | decision 6 |
| `issuedOn` | `YYYY-MM-DD` (UTC) | day of `created_at` |
| `permanent` | boolean | |
| `expiresOn` | `YYYY-MM-DD` or `null` | day of the expiry instant; `null` when permanent |
| `endedOn` | `YYYY-MM-DD` or `null` | day of the revoke, or of the expiry once passed; `null` while active |

Not returned, ever: the punishment identifier, the reference, the stored `owner` value, any raw UUID or IP, the player
name, Ban ID, staff or actor identifiers, `section_key`, `scope`, `elo_cost`, evidence, escalation group, the exact
times, the duration in seconds, the server or section name, and the client's own name. The `kind` is the only
information about the section. The `playerHash` is the only player-derived value.

The `reason` is returned as stored. The API adds no player-specific text, so a reason that names a player is a
template authoring error. Decision 12 covers how that is prevented.

Alternatives considered:
- *Return the section key for server bans*: rejected. A server name plus a date narrows the entry, and the section kind
  gives clients what they need to understand the sanction.
- *Return the exact duration*: rejected. The day-rounded expiry is enough, and an exact duration with an exact start is
  a timing key (decision 5).

### 4. Stable entry hash: keyed, separate, truncated

`hash = base64url( first 16 bytes of HMAC-SHA256(K, "public-ban-entry-v1" || 0x01 || identifier) )`, where `K` is derived
from `BLACKHOLE_PUBLIC_BAN_KEY` with HKDF-SHA256 (the same construction as `BanIdCrypto`, label
`blackhole-public-ban-entry-v1`). The hash is computed once when the entry is listed and stored in
`punishments.public_hash`, which is unique and indexed.

Why these choices:

- **Keyed, not a plain hash of the identifier.** An identifier is an internal id and may be guessable. Without the key
  nobody can recompute a hash from an identifier, so a reader cannot test "is this my punishment".
- **Its own secret, not `BLACKHOLE_BANID_KEY`.** The Ban ID lookup HMAC and the public entry HMAC share no key material.
  A Ban ID cannot be turned into an entry hash, and an entry hash cannot be turned into a Ban ID. Each key is derived
  with a different label from its own secret. The same rule covers `BLACKHOLE_CURSOR_KEY` from the pagination change.
- **Not derived from the reference.** The reference (`short_id`) is shown to the player in their own view. An entry
  hash built from it would let a player who knows their reference compute their own entry. The hash input is the
  identifier, which the API never returns.
- **Not the player hash.** The entry hash is keyed and identifies a sanction. The `playerHash` of decision 14 is unkeyed
  and identifies a player. The two share no input and no key.
- **128 bits, 22 characters.** Collisions are not a practical concern at this scale, and the value is short enough to
  cite. The unique index turns a collision into a failed write, which is logged without the input.
- **Stored, not recomputed per read.** A lookup by hash (`GET /public/bans/{hash}`) needs an index. Storing also means
  the key is needed only at write time and never on read.

Key rotation: changing `BLACKHOLE_PUBLIC_BAN_KEY` changes every entry hash, so every cited hash stops resolving. This
change documents that and does not add a rewrite. A rotation needs a one-shot recompute of `public_hash` (the pattern of
`BLACKHOLE_BANID_REISSUE_ALL`), which is a follow-up. The `playerHash` does not change, because it uses no key.

Guard: the key is required outside `dev` and `test`, as `BanIdKeyGuard` requires `BLACKHOLE_BANID_KEY`. In `dev` and
`test` a random key is used per run, with a warning, so hashes do not survive a restart there. The key never appears in
a log, a response or a metric.

Alternatives considered:
- *Hash of the UUID or of the name as the entry hash*: rejected. The entry hash identifies a sanction, not a player. The
  player's own hash is the separate `playerHash` (decision 14).
- *Random per-entry id stored with the punishment*: a viable alternative. A random id leaks nothing and needs no key
  management, but it cannot be recomputed from the identifier, so an operator who needs to map a cited hash back to its
  punishment needs a lookup table. The keyed HMAC was chosen for that recompute. Open question 4 asks the maintainer
  to confirm, because the random id is simpler if nobody needs the mapping.
- *Full 256-bit output*: rejected as needlessly long for a citation.

### 5. Timing: day rounding, no exact times anywhere

- `issuedOn`, `expiresOn` and `endedOn` are UTC calendar days of the stored instants. The rounding is done in the
  service from `created_at`, `public_expires_at` and `public_revoked_at`. The stored instants are not returned.
- The cursor sort keys are `public_issued_day` (epoch day, `INT`/`BIGINT`) and `public_hash`. The default sort is
  `public_issued_day DESC, public_hash DESC`. A cursor payload is base64url, so anyone holding it can read it. It
  therefore holds the day and the hash only. Sorting on the exact instant would put the exact instant into every
  cursor.
- The summary and the list use day granularity only.
- Listing: the entry becomes visible when the punishment is applied (its `playerHash` is null when no UUID was sent). No delay is built in (open
  question 2).

Why: clients republish, and a reader may know when a player was banned (their own ban, or a chat message). An exact
start time next to a reason and a kind would link an entry to a known moment. A day is the smallest unit that still says
"this week" to a reader, so the list stays useful and the moment is blurred.

### 6. Status, retention window, and the Basic Auth route rules

Status is derived on read from the stored columns and the injected `Clock`:

- `REVOKED` when `public_revoked_at` is set (a staff revoke or unban, written by the revoke path). The reason of the
  revoke is never listed, so an appeal outcome cannot be read from the list.
- `EXPIRED` when not revoked and the expiry instant (`public_expires_at`) is at or before now.
- `ACTIVE` otherwise (permanent entries stay `ACTIVE` until revoked).

Retention: an entry is listed while it is `ACTIVE`, or while `now < endedAt + blackhole.public-bans.retention`
(`BLACKHOLE_PUBLIC_BANS_RETENTION`, proposed `P90D`), where `endedAt` is `public_revoked_at`, or `public_expires_at`
when not revoked. After that it is no longer listed, and its hash answers 404. The filter is one SQL condition on
`COALESCE(public_revoked_at, public_expires_at)`, so pages never come out short.

Expiry is read from the instant the punishment stores, not from the lazy expiry sweep, so an entry shows `EXPIRED`
even when nobody has read the profile since. The list only reads the listing columns and never writes to the
punishment.

**Routes.** All three routes are read-only and sit under `/public` in `publicbans/controller/PublicBansApi` (OpenAPI
annotations on the interface), `PublicBansController` (thin, one service call each, `@Version(ApiVersion.V1)`), and
`PublicBansService` (`@Singleton`, returns the sealed `Error` variant). The route prefix stays `/public/bans` to avoid
churn; open question 2 asks whether to move it.

| route | answer | notes |
|---|---|---|
| `GET /public/bans?limit=&cursor=&kind=&status=` | `200` `{items, nextCursor}` plus `Link` (RFC 8288) when `nextCursor` is set | `limit` 1 to 200 (default 50), 400 otherwise; `kind` in `NETWORK_BAN\|MUTE\|SERVER`; `status` in `ACTIVE\|EXPIRED\|REVOKED`; a bad `cursor` is 400 `INVALID_CURSOR` |
| `GET /public/bans/{hash}` | `200` one entry | the hash shape is checked (22 base64url characters); anything else, an unknown hash and a hash outside retention are the same 404 |
| `GET /public/bans/summary` | `200` `{windowDays, reasons: [{reason, kind, active, total, suppressed}]}` | counts below `blackhole.public-bans.summary-min-count` (default 5) are `null` with `suppressed: true` |

**Authentication order.** Every request is authenticated first (below), and only then is the request validated and
the resource looked up. An unauthenticated request for an unknown hash therefore gets the 401, not a 404, so the
endpoint is no oracle for which hashes exist.

**Basic Auth rules.**

- A separate Basic Auth chain covers exactly these three routes. The Entra JWT chain does not see them.
- Missing `Authorization`, a non-Basic scheme (including a Bearer token), malformed base64, a missing colon, an unknown
  client name, and a wrong password all answer **HTTP 401** with the same RFC 9457 body (`code: UNAUTHORIZED`) and the
  header `WWW-Authenticate: Basic realm="blackhole-ban-list", charset="UTF-8"`. An unknown name and a wrong password
  are indistinguishable.
- Unknown name: the verifier still runs Argon2id against a fixed dummy hash, so the response time does not reveal
  whether the name exists. The password is compared to the derived hash with `MessageDigest.isEqual` (constant time).
  The timing claim is tested by counting verifier invocations, not by timing (tests must not depend on wall-clock
  time).
- **HTTPS only.** Basic credentials are never evaluated over plain HTTP. A request that arrives over plain HTTP
  answers **HTTP 403** with code `HTTPS_REQUIRED` and carries no `WWW-Authenticate` header. The scheme comes from the
  ingress through `X-Forwarded-Proto`, which is trusted only from the configured ingress. The ingress also redirects
  HTTP to HTTPS (308). Open question 3 asks whether 403 or 421 is preferred.
- The password is never echoed, logged, or put in a metric label.

Errors are RFC 9457 problems with `code` (the pagination change's envelope). No error body echoes the input.
`/public/bans/summary` and `/public/bans/{hash}` use the same 404 body.

Alternatives considered:
- *Keep the three routes anonymous*: rejected by the maintainer decision of 2026-10-28.
- *Put the routes behind Entra as staff routes*: rejected. The clients are not staff, and a moderator role would give
  them staff reach.

### 7. Cache headers: `private, no-store`

Every answer of the three routes, including the list, the single read and the summary, carries
`Cache-Control: private, no-store`. No `ETag`, no `Vary`, no 304 path.

Why `no-store` and not `private, max-age=60`:

- The list is authenticated and per client. Caching it helps only the client that already has the answer, and the
  clients are few, so the saving is small.
- A removed or erased entry must leave a client's own cache at once. A `max-age` keeps it for up to a minute, and the
  client may republish it in that minute.
- It matches the rest of the public API (`public-api.md`), so this change needs no new exception to that rule.

The `public, max-age=60` header of the earlier draft is dropped with the anonymity it was built for.

Alternatives considered:
- *`private, max-age=60`*: rejected for the reasons above.
- *ETag and 304 for conditional reads*: rejected. Without a cache there is nothing to revalidate, and the body is small.
- *Cache in the shared Dragonfly cache*: rejected. The list is small and read rarely, and the shared cache is for hot
  reads (shared-cache spec).

### 8. Summary and suppression

`GET /public/bans/summary` counts the entries the list would show (same visibility rule, same window): per public
`reason` and `kind`, `active` (status ACTIVE) and `total` (every listed entry). A row whose `total` is below
`blackhole.public-bans.summary-min-count` (default 5) returns `active: null, total: null, suppressed: true`. The row
stays, so the reason is still listed as an existing rule.

Why: a count of one still says that one event happened in the window, and a reader who knows the reason and the week
can name the player through the `playerHash` list. The threshold keeps a count of one from being a lookup on its own.
Clients may republish the summary, so the threshold matters. It is not a complete defence (decision 11 covers the rest).

### 9. Portal page: dropped

No portal page and no portal BFF route are part of this change. The consumers are the website backend and partner
networks, and they call the API server to server. The earlier design put the list in the portal behind an anonymous
BFF route. That is removed.

Alternatives considered:
- *Keep a portal page behind Basic Auth through the BFF*: rejected. A browser visitor has no client credential, so the
  BFF would have to hold a client password and speak for all visitors with it. That is the website's job, and the
  website backend already holds its own credential.
- *Keep an anonymous portal page*: rejected by the maintainer decision of 2026-10-28.

### 10. Client attribution and logging

- The Basic Auth principal name is the client name. Every request in the list answers with the client as the principal
  and carries it in the access log as `client=<name>`.
- Metric: `blackhole_ban_list_requests_total{client, route, status}`. `client` is a configured name or `unauthenticated`.
  Label values are bounded by the configured clients, the three routes and the status codes, so cardinality is fixed.
- A failed attempt is logged as `client=unauthenticated` with the reason class (`missing`, `malformed`, `unknown-client`,
  `bad-password`). The submitted username is never logged, because it is user input and may be a password typed in the
  wrong field.
- `Authorization` is never logged, in any log line, at any level.
- Raw UUIDs, player names and IP addresses are never logged or used as metric labels on the ban-list routes. The
  `playerHash` is the value a reader sees, so it is the only player value that can appear in a response.

### 11. Abuse and linkage review

Each row is a way a reader could link an entry to a person, and what this design does about it.

1. **Ban ID correlation.** Separate key (decision 4). Entries and Ban IDs share no input and no label. A leaked Ban ID
   gains nothing from the list.
2. **Reference correlation.** The reference is not an input of the entry hash, and it is never returned.
3. **Timing correlation.** Day rounding everywhere, including the cursor (decision 5). The summary has no time buckets
   narrower than the window.
4. **Cursor leakage.** Sort keys are day and hash only; the signed cursor (pagination change D7) stops tampering but
   does not hide the payload, so the payload is kept to non-identifying values.
5. **Small-count inference in the summary.** Suppression below the minimum (decision 8).
6. **Reason inference.** A reason that names a rule detail specific enough to point at one case is the largest remaining
   risk. Mitigation: the `publicReason` is written by staff once per template, reviewed like the template itself. Only
   the ADMIN role may set it (open question 8), a `PiiGuard` refuses IPs, UUIDs and Ban IDs, and the length cap is 200.
   Names cannot be detected by a rule, so this is a review step, documented in `.claude/rules/public-bans.md`.
7. **Republication by a client.** Clients are trusted, but they may republish. The entry fields and the day rounding
   limit what a republished entry reveals (decisions 3 and 5), and the summary threshold limits counts (decision 8).
8. **Credential leak.** A leaked client password is cut off by removing the client and restarting. The attribution
   (decision 10) shows what that client read. Passwords are at least 32 random bytes, so guessing is not a route.
9. **Brute force on the Basic Auth routes.** Argon2id makes each guess expensive, and the client names are not secret
   but are not useful without a password. Rate limiting belongs to the edge (`config.yaml` principle 5). The
   application adds no limiter.
10. **Erasure.** On a valid erasure request for an owner, the punishments of that owner are erased by the existing
    erasure path. That path also clears `public_reason`, `public_kind`, `public_player_hash`, `public_hash`, the two
    instants and the day, so the entry leaves the list at once and its hash answers 404. No cache holds it, because
    every answer is `no-store` (decision 7). Copies that a partner network has already taken are outside this change
    (see Risks).
11. **Pseudonymity and name lookup.** The `playerHash` is personal data, not anonymous data. Whoever holds a player's
    UUID can check whether that player is listed, and a name is enough to get there for many players: Mojang's API maps
    names to UUIDs, and offline-mode UUIDs are derived from the name alone. The list is therefore pseudonymised, not
    anonymised. The limits are Basic Auth on the routes (decision 2) and no-store (decision 7). The decision to publish
    the hash is the maintainer's (decision 14). Listing is justified by the operator's decision (open question 10).
    The minimisation here is that entries carry no more than the list needs, and the retention window bounds how long
    they stay.

Item 6 of this list is the reason review; item 11 is the pseudonymity note. Decision 12 covers the template review step.

### 12. Template review for public reasons

The `publicReason` is a template-level text. It is set by an ADMIN (open question 8), refused when it contains an IP,
a UUID or a Ban ID (validator, not echoed), capped at 200 characters, and reviewed like the template itself. A
validator cannot detect a name or a case number, so that review is a documented step in `.claude/rules/public-bans.md`.

### 13. Data platform and other consumers

No event is published for listed entries. The list reads the punishment table directly. The Velocity plugin sends `playerUuid` on the apply request (decision 14) and does not read the list. The Discord bot does not read the list. The
generated client gets the new routes, the Basic Auth scheme for them, the template field and the optional apply field
as additive changes.

### 14. Player hash: unkeyed, versioned SHA-256 of the UUID

Decision of the maintainer, 2026-10-28: "Internally staff may see who was banned. The list we publish may only carry a
hash, together with the reason, so other networks can adopt the bans. We never publish the raw UUID."

The published entry carries a `playerHash`:

```
playerHash = lowercase_hex( SHA-256( UTF-8( "blackhole-ban-list-v1:" || uuid ) ) )
```

`uuid` is the player's UUID in lowercase dashed form (`8-4-4-4-12`). The result is 64 lowercase hex characters.

Test vector: UUID `123e4567-e89b-12d3-a456-426614174000` gives
`9efc1073aa30a316b4d4930dbf73bf852be666299041e930c9763de9f0c03029`, computed with
`printf '%s' 'blackhole-ban-list-v1:123e4567-e89b-12d3-a456-426614174000' | sha256sum`.

**Who computes it.** The backend, from the `playerUuid` of the apply request. Velocity, the backend import path, the
admin web and the Discord bot send the raw UUID the same way, next to `owner`, when they know it. The backend normalises
the UUID to lowercase dashed form, computes `playerHash` with the scheme above, and stores only the hash on the
punishment. The raw UUID is not published. Internal staff views may show the raw UUID through `add-player-intelligence`
(its decision 10). The ban list never does. One implementation serves the entry list and the test vector, so partners
and our own code agree on the value. A punishment without a UUID gets `playerHash` null and is published without it.

Prerequisites: `amend-data-protection-principle` allows the backend to hold raw UUIDs, and `add-player-intelligence`
already sends them from Velocity. Both are required before the code PR.

**Why unkeyed.** A partner network has no secret from us, so it must compute the same value from the UUIDs of its own
players. A key shared with every partner is no key at all, and a key kept from them defeats the adoption. The prefix is
versioned, so a later scheme uses `blackhole-ban-list-v2:` and the API documents the scheme in use.

**What the internal views keep.** Staff views (the admin web, profile reads) keep the clear-text player data they
already have, under `amend-data-protection-principle`. Only the published list carries the hash. The hash is not a
replacement for the internal data.

Alternatives considered:
- *No player reference at all*: rejected by the maintainer. Partner networks cannot adopt bans, which is the purpose
  of the list.
- *Keyed HMAC with a secret key*: rejected. Partners cannot match without the key. Sharing the key with every partner
  makes it effectively unkeyed, and withholding it leaves them unable to match.
- *Raw UUID*: rejected by the maintainer. Anyone with list access could read who is banned.
- *Velocity computes the hash and sends `playerHash`*: rejected. Velocity, imports, the admin web and the Discord bot
  would each need the same code, and imports, the admin web and the Discord bot have no Velocity. One implementation in
  the backend is the single place for the scheme and its test vector.

Risks and trade-offs, stated plainly:

- **The hash is pseudonymisation, not anonymisation.** Anyone who knows a player's UUID and has list access can check
  whether that player is listed. Offline-mode UUIDs are derived from the name, so a name is enough for those players.
- **Basic Auth limits who can read the list.** It does not limit what an authorised client does with the entries it
  reads, and it does not stop a client from republishing them.
- **A wrong UUID gives a wrong hash.** The backend checks the format of `playerUuid` and computes the hash, but it
  cannot check that the UUID belongs to the player. A caller that sends another player's UUID lists the ban under that
  player's hash. Callers send the UUID they already use for `owner`, and a test pins the test vector (task 1.2).
- **Copies outside the list.** A partner that has copied an entry keeps its copy after erasure or revocation. The list
  cannot reach into partner systems.
- **The hash scheme is fixed in the API.** A change of scheme means a new prefix and new hashes. Partners must follow
  the change. Open question 16 asks whether entries should carry the scheme version.

### Risks / Trade-offs

- **Staff write a reason that identifies a player.** Mitigated by the review step and the ADMIN-only rule (decision 12),
  not by code. A test can only refuse UUIDs, IPs and Ban IDs.
- **Client passwords are long-lived secrets in Secrets.** Accepted for v1 with rotation by restart. The later option is
  managed credentials (open question 5).
- **Argon2id costs CPU on every request to the three routes.** Accepted. The clients are few. The cost is bounded by
  the edge rate limit (decision 11.9).
- **Key loss or rotation breaks every cited entry hash.** Accepted and documented. The key is backed up like the Ban ID
  key. The `playerHash` survives key rotation, because it uses no key.
- **A summary with a higher threshold hides real rules.** The threshold is configurable, and rows stay with `suppressed`.
- **Backfill is not done.** Existing bans are not on the list until a maintainer decides to list them (open question 6).
  Existing rows have no `playerHash`, so they could not be listed without a source of UUIDs.
- **The principle 2 exception is not yet ratified.** Until open question 1 is closed, this change cannot be merged on its
  own terms.
- **Callers that do not know the UUID.** Such punishments are published with `playerHash` null, so partners cannot
  match them. Open question 17 is answered in decision 14.

## Migration plan

Flyway, append-only, the next free version at implementation time (`V<next>__public_ban_list.sql`, main head
`V27__kick.sql`). The migration:

1. `ALTER TABLE punishment_template ADD COLUMN public_reason VARCHAR(200) NULL`.
2. `ALTER TABLE punishments ADD COLUMN public_reason VARCHAR(200) NULL, public_kind VARCHAR(16) NULL,
   public_player_hash VARCHAR(64) NULL, public_hash VARCHAR(22) NULL, public_issued_day BIGINT NULL,
   public_expires_at BIGINT NULL, public_revoked_at BIGINT NULL`.
3. `CREATE UNIQUE INDEX ux_punishments_public_hash ON punishments (public_hash)` (where supported; the engine is
   confirmed from `docker/docker-compose.yml`, see the pagination change D8).
4. `CREATE INDEX ix_punishments_public_order ON punishments (public_issued_day, public_hash)`.

Existing rows: every new column is NULL, so no existing punishment is listed. No backfill runs in this migration.
Templates have no public reason until a staff member sets one. A later maintainer decision can list old punishments by
a one-shot job that copies the template's public reason onto the punishment and computes the hash, the same pattern as
`BanIdBackfill`. Such a job has no `playerHash` source for old rows unless the caller can supply one, so that is part of
open question 6.

Client configuration needs no migration. The first deploy with the feature needs the Secret keys and the client names
in the Helm values, and without them the routes answer 401 to everyone. Until the callers send `playerUuid`, listed punishments carry a `playerHash` of null.

Rollback: the columns and the index are unused by any other code, so dropping them restores the previous behaviour. The
migration is not edited once applied.

## Open questions

1. **Principle 2 exception.** Three routes accept HTTP Basic Auth with named clients and no Entra token. Confirm the
   exception for this change only, or amend `openspec/config.yaml` principle 2 in a separate change that names the
   exception (for example `amend-entra-principle-for-machine-clients`). The code PR waits for the answer.
2. **Route prefix.** Keep `/public/bans` (proposed, to avoid churn, and the `/public/**` reservation stays for the
   player portal), or move the list to `/ban-list` now that it is no longer public?
3. **Plain HTTP.** Answer 403 `HTTPS_REQUIRED` without evaluating credentials (proposed), or 421 Misdirected Request, or
   let the ingress alone decide?
4. **Entry hash construction.** Keyed HMAC of the identifier (proposed, lets the operator recompute during investigations)
   or a random id stored with the punishment (no key to manage, no recompute)? The random id is the simpler option if
   nobody needs to recompute.
5. **Client credential management.** Restart-based rotation with Secret files (proposed for v1), or a managed table with
   hashed passwords and an admin UI in a later change? Also confirm that partner networks may hold a password hash-only
   credential, with the plaintext given to them out of band.
6. **Backfill.** List existing punishments whose templates have a public reason? Proposed: no, only new ones, and a
   separate decision. A backfill also needs a source of the player UUID or a `playerHash` for old rows.
7. **Erasure.** Remove the entry on an erasure request (proposed), or keep an entry without owner data? Removal is the
   conservative choice and matches the retention intent.
8. **Who sets the public reason.** ADMIN only (proposed), or MODERATOR as well? It is a template-level text, so it is a
   policy decision, not a per-case one.
9. **Summary.** Keep the per-reason summary at all, or drop it in favour of counts per kind? The summary adds a second
   way to read the list. The threshold of 5 is a proposal.
10. **Legal basis for listing.** `amend-data-protection-principle` settles which raw data may be published, namely no IPs,
    UUIDs or names. The list now publishes a `playerHash` (decision 14), which is pseudonymous personal data, so the
    amendment has to cover it. It does not decide whether a sanction against a player may be shared with the website and
    partner networks at all. That decision (legal basis for the disclosure, the transparency notice, and the basis for
    partners to match on the hash) belongs to the maintainer and is not made here.
11. **Amendments.** An amendment that changes the expiry must update `public_expires_at`. Does an amendment that changes
    the staff reason change the public reason too (proposed: no, the public reason is a snapshot)?
12. **Localisation.** One text per template in this change. Should templates later carry one text per locale, as
    `add-i18n-platform` may require, for clients that serve several languages?
13. **Dependencies on other changes.** The code PR waits for `standardize-api-pagination-and-problems` to be applied, so
    the envelope, the problem body and `BLACKHOLE_CURSOR_KEY` exist. Is that acceptable, or does this change adopt them
    ahead of that change?
14. **Micronaut Security and the no-auth note.** `CLAUDE.md` says there is no `micronaut-security` layer, while the
    Entra work reintroduces authentication. Confirm that the Basic Auth chain may use Micronaut Security's
    authentication support, and that `CLAUDE.md` is updated when that lands.
15. **Lookup by player hash.** Partners match the full list on their side in v1. Should `GET /public/bans` take a
    `playerHash` filter so a partner can ask for one player without paging through everything? It is a small addition,
    and it makes the list easier to query for one player, so it also makes name-to-hash checks cheaper for a reader.
16. **Scheme version per entry.** The scheme is named only by the prefix in the hash input and in the OpenAPI text. Should
    each entry carry `playerHashScheme` (for example `blackhole-ban-list-v1`), so a partner sees a scheme change without
    reading the docs? The brief asks to keep the entry fields as they are, so this is a question, not a change.
17. **Callers without a UUID.** Answered by decision 14: the backend computes `playerHash` from the `playerUuid` of the
    apply request. A punishment without a UUID is published with `playerHash` null. Backfill is covered by question 6.
18. **Amendment text.** Should `amend-data-protection-principle` name the `playerHash` exception explicitly, or is the
    rule "no raw identifiers, hashes allowed" enough? This change does not edit that change.
