## Why

A player who is banned or muted today can only learn the details by logging in to the appeal portal with their
Ban ID. Nobody else has a fixed place to look. The website and the partner networks that share our player base
want to show which sanctions are in force and why, and today every such answer is a manual copy from a moderator.
Each copy depends on who asks and when. A ban on our network is also invisible to a partner network that would
want to act on it, because there is no way to say "this player is banned here" that the partner can check.

A ban list behind credentials gives the trusted clients one machine-readable source: which kind of sanction was
given, for which published reason, when it started and when it ends, and for which player, as a hash. A partner
network hashes the UUIDs of its own players the same way and finds out which of them are on the list, so it can
adopt the ban. The list never carries a raw UUID, a player name or an IP address. Clients get it by authenticating
as a named client.

## What Changes

- **New read API for authenticated clients** under the existing `/public` route prefix: `GET /public/bans` (cursor
  paginated, with the envelope of `standardize-api-pagination-and-problems`: `items`, `nextCursor`, `limit`,
  `cursor`, signed cursor, `Link` header), `GET /public/bans/{hash}` for one entry, and `GET /public/bans/summary`
  for a count per public reason. All three require HTTP Basic Auth (RFC 7617) over HTTPS with a configured ban-list
  client. There is no anonymous access.
- **Named ban-list clients.** A client is a name (for example the website or a partner network) with a password
  hash. Clients are listed in configuration; each password hash is a file from a Kubernetes Secret. No plaintext
  password is in config, no database table holds clients, and v1 has no admin UI for them. Every request is
  attributed to its client name in logs and in a metric label.
- **Entries carry only** the public reason, the section kind (`NETWORK_BAN`, `MUTE` or `SERVER`), the status
  (`ACTIVE`, `EXPIRED` or `REVOKED`), the start day, the expiry day (or `permanent`), the end day once ended, the
  player hash, and a stable entry hash. There is no name, no raw UUID, no Ban ID, no punishment reference, no staff
  name, no section key and no evidence.
- **Player hash for partner matching.** Each listed entry carries `playerHash`: the SHA-256 of the UTF-8 string
  `blackhole-ban-list-v1:` followed by the player's UUID in lowercase dashed form, as 64 lowercase hex characters, or
  null when the punishment had no UUID. It is unkeyed on purpose, so a partner network can compute it for its own
  players and match them against the list. The prefix is versioned (`v2` would be a new scheme) and the scheme is
  documented in the API. The apply request carries the raw player UUID as `playerUuid`, next to `owner`. The backend
  computes `playerHash` from it and stores only the hash on the punishment. The ban list never shows the raw UUID.
  A punishment without a UUID is published with `playerHash` null.
- **Dates are rounded to the UTC day.** No exact time is returned. The cursor sort keys are the day and the hash, so
  a cursor cannot reveal an exact time either.
- **Revoked and expired entries stay visible with their status** for a configurable retention window
  (`BLACKHOLE_PUBLIC_BANS_RETENTION`, proposed `P90D`), counted from the end. Active entries are always listed. After
  the window an entry leaves the list and its hash answers 404.
- **Summary per reason**: active and total counts per public reason and kind within the window. Counts below a
  minimum are withheld, so a single entry cannot be read off the summary.
- **Cache headers**: `Cache-Control: private, no-store` on every answer, the same as the rest of the API. No shared
  cache and no ETag. The one-minute caching of the earlier draft is dropped.
- **Template editor** (admin web) gets the optional public reason field, with a length and PII check.
- **Authentication**: the Basic Auth chain is separate from the Entra JWT chain. These three routes are the only
  routes that accept Basic credentials, and they reject Bearer tokens. This is a deliberate exception to
  `openspec/config.yaml` principle 2, recorded in `design.md` and in the open questions.
- **Rate limiting** stays in infrastructure (`config.yaml` principle 5). The application adds no limiter for these
  routes.

Not BREAKING. Every change is additive: three routes, an optional template field, an optional `playerUuid` on the apply request and new punishment columns. The generated client gains a new API and optional fields. Existing
clients are unaffected.

## Capabilities

### New Capabilities

- `public-ban-list`: the authenticated list and its entry fields, the player hash and its input format, the
  named-client credentials and their Basic Auth check, the entry hash, the publication rule (template opt-in, kicks
  excluded), day rounding, the retention window, the summary and its suppression, the no-store headers, and erasure.

### Modified Capabilities

- `punishment-management`: an optional public reason on templates, the snapshot of that reason, of the section kind
  and of the `playerHash` computed from the apply request's `playerUuid`, and the publication fields kept current on apply, amendment and
  revocation.
- `staff-authentication`: the reserved `/public/**` prefix holds exactly the three Basic Auth ban-list routes. Those
  routes use their own credential check, not an Entra token and not a Ban ID session. The staff-role rule is
  unchanged.
- `admin-web-ui`: the template editor shows the public reason field.

## Impact

- **Backend**: a new `publicbans/` feature package (controller, `*Api` interface, sealed DTO marker interface,
  `@Singleton` service, JDBC reader, entry hash and day rounding helpers, Basic Auth parsing and the client
  registry). Changes in `punishment/` (template field, the optional `playerUuid` on the apply request, the player hash computed from it, publication
  writer on apply, amendment and revocation), in the erasure path (removes the entry with the punishment), in
  `security/` (a separate Basic Auth chain scoped to the three routes, leaving the Entra chain untouched), and in
  `application.yml` (`blackhole.ban-list.*`, `blackhole.public-bans.*`, and `BLACKHOLE_PUBLIC_BAN_KEY`, with a guard
  that refuses to start without the key outside `dev` and `test`, as `BanIdKeyGuard` does).
- **Callers send `playerUuid`**: the Velocity plugin sends the player's UUID with the apply request, next to `owner`.
  The backend import path, the admin web and the Discord bot send it the same way when they know the UUID. No caller
  computes the hash; the backend does.
- **Password hashing**: one library for Argon2id (Bouncy Castle is the proposed choice, confirmed at implementation
  time). It is the only new runtime dependency.
- **Database**: one new Flyway migration `V<next>__public_ban_list.sql`. The main head is `V27__kick.sql`, and the
  number is taken at implementation time. It adds `punishment_template.public_reason`, and on `punishments`:
  `public_reason`, `public_kind`, `public_player_hash`, `public_hash` (unique), `public_issued_day`,
  `public_expires_at`, `public_revoked_at`, with an index on `(public_issued_day, public_hash)`. Additive only.
  Existing rows stay unlisted (see `design.md`, migration plan).
- **Deploy**: `deploy/charts/blackhole` gets values for the ban-list client names and an existing Secret holding one
  password hash per client, mounted as files. `BLACKHOLE_PUBLIC_BAN_KEY` comes from a Secret too.
- **API and client**: `GET /public/bans`, `GET /public/bans/{hash}`, `GET /public/bans/summary`, the template field
  and the optional `playerUuid` on the apply request. The spec moves to the next version after `0.0.44`
  (`client/build.gradle.kts`), set at implementation time, and `client/` is regenerated. The generated client needs
  the Basic Auth scheme for these routes. Not breaking.
- **Web**: the template editor field and its validation in `web/`.
- **Docs**: `.claude/rules/public-bans.md` (path-scoped), one line in `.claude/rules/public-api.md` saying these
  routes are the Basic Auth exception to the anonymous rules and are not anonymous, and a pointer in `CLAUDE.md`.
- **Dependencies**: `standardize-api-pagination-and-problems` must be applied before the code PR. Otherwise the code
  uses the same envelope and is adjusted when it lands. `amend-data-protection-principle` is a prerequisite: it allows the backend to hold raw UUIDs, while the list
  publishes only the hash. `add-player-intelligence` is a prerequisite too: its decision 10 already has Velocity send and
  store the raw player UUID, which this change reads. `add-kick` is the source of the `KICK` kind, which this change excludes. The principle 2
  exception needs the maintainer's confirmation or an amendment of `openspec/config.yaml` (open question 1).

## Out of scope

- A portal page for the list. The API is consumed by trusted clients (the website backend and partner networks).
  No browser page is built, and no anonymous or session-based access is added.
- Player search, lookup by name, UUID or Ban ID, any player page that shows the player's own entry, and a server-side
  filter by `playerHash`. Partners match the full list on their side in v1 (open question 15).
- Names of players or staff, section keys and server names. A server ban shows its kind only.
- Entries for kicks, warnings, and appeal outcomes beyond `REVOKED`.
- Client management in the database or an admin UI. Clients are configuration plus Secret files in v1 (a later
  option, see `design.md`, decision 2).
- Per-client scopes or different entry sets per client. Every client sees the same list.
- Per-locale copies of the public reason. A template has one text in this change.
- Discord or RSS feeds of the list, and webhooks.
- Rate limiting. The edge owns it (`config.yaml` principle 5).
- Backfilling existing punishments. Only punishments applied after their template has a public reason are listed, unless the maintainer decides otherwise (open question 6).
- Rotating `BLACKHOLE_PUBLIC_BAN_KEY` with a rewrite of stored hashes. This change documents that rotation changes
  every entry hash, and leaves the recompute job to a follow-up. The `playerHash` is not affected by that rotation.

Ships as: feat(public): add the ban list for authenticated clients
