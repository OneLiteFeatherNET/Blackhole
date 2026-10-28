## Why

A player who is banned or muted today can only learn the details by logging in to the appeal portal with their
Ban ID. Nobody else has a fixed place to look. The website and the partner networks that share our player base
want to show which sanctions are in force and why, and today every such answer is a manual copy from a moderator.
Each copy depends on who asks and when.

A ban list behind credentials gives the trusted clients one machine-readable source: which kind of sanction was
given, for which published reason, when it started and when it ends. The list shows the network's rules being
applied without naming anyone. Clients get it by authenticating as a named client, and nothing on it can be used
to find out whose entry it is.

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
  (`ACTIVE`, `EXPIRED` or `REVOKED`), the start day, the expiry day (or `permanent`), the end day once ended, and a
  stable entry hash. There is no player reference of any kind: no name, no UUID, no hash of a UUID, no Ban ID, no
  punishment reference, no staff name, no section key and no evidence.
- **Opt-in per template.** A template gets an optional `publicReason`. A punishment is listed only if its template
  has one. The text is copied onto the punishment when it is applied, so a later template edit does not rewrite
  history. Manual punishments without a template, and every kick (`kind = KICK`), are never listed.
- **Entry hash.** A keyed HMAC-SHA256 of the internal punishment identifier, under its own secret
  `BLACKHOLE_PUBLIC_BAN_KEY`. It lets a client cite an entry. It cannot be computed from a Ban ID or from a
  punishment reference.
- **Dates are rounded to the UTC day.** No exact time is returned. The cursor sort keys are the day and the hash, so
  a cursor cannot reveal an exact time either.
- **Revoked and expired entries stay visible with their status** for a configurable retention window
  (`BLACKHOLE_PUBLIC_BANS_RETENTION`, proposed `P90D`), counted from the end. Active entries are always listed. After
  the window an entry leaves the list and its hash answers 404.
- **Summary per reason**: active and total counts per public reason and kind within the window. Counts below a
  minimum are withheld, so one entry cannot be read off the summary.
- **Cache headers**: `Cache-Control: private, no-store` on every answer, the same as the rest of the API. No shared
  cache and no ETag. The one-minute caching of the earlier draft is dropped.
- **Template editor** (admin web) gets the optional public reason field, with a length and PII check.
- **Authentication**: the Basic Auth chain is separate from the Entra JWT chain. These three routes are the only
  routes that accept Basic credentials, and they reject Bearer tokens. This is a deliberate exception to
  `openspec/config.yaml` principle 2, recorded in `design.md` and in the open questions.
- **Rate limiting** stays in infrastructure (`config.yaml` principle 5). The application adds no limiter for these
  routes.

Not BREAKING. Every change is additive: three routes, an optional template field and new punishment columns. The
generated client gains a new API and an optional field. Existing clients are unaffected.

## Capabilities

### New Capabilities

- `public-ban-list`: the authenticated list and its entry fields, the named-client credentials and their Basic Auth
  check, the entry hash, the publication rule (template opt-in, kicks excluded), day rounding, the retention window,
  the summary and its suppression, the no-store headers, and erasure.

### Modified Capabilities

- `punishment-management`: an optional public reason on templates, the snapshot of that reason and of the section
  kind on the punishment, and the publication fields kept current on apply, amendment and revocation.
- `staff-authentication`: the reserved `/public/**` prefix holds exactly the three Basic Auth ban-list routes. Those
  routes use their own credential check, not an Entra token and not a Ban ID session. The staff-role rule is
  unchanged.
- `admin-web-ui`: the template editor shows the public reason field.

## Impact

- **Backend**: a new `publicbans/` feature package (controller, `*Api` interface, sealed DTO marker interface,
  `@Singleton` service, JDBC reader, entry hash and day rounding helpers, Basic Auth parsing and the client
  registry). Changes in `punishment/` (template field, publication writer on apply, amendment and revocation), in
  the erasure path (removes the entry with the punishment), in `security/` (a separate Basic Auth chain scoped to the
  three routes, leaving the Entra chain untouched), and in `application.yml` (`blackhole.ban-list.*`,
  `blackhole.public-bans.*`, and `BLACKHOLE_PUBLIC_BAN_KEY`, with a guard that refuses to start without the key
  outside `dev` and `test`, as `BanIdKeyGuard` does).
- **Password hashing**: one library for Argon2id (Bouncy Castle is the proposed choice, confirmed at implementation
  time). It is the only new runtime dependency.
- **Database**: one new Flyway migration `V<next>__public_ban_list.sql`. The main head is `V27__kick.sql`, and the
  number is taken at implementation time. It adds `punishment_template.public_reason`, and on `punishments`:
  `public_reason`, `public_kind`, `public_hash` (unique), `public_issued_day`, `public_expires_at`,
  `public_revoked_at`, with an index on `(public_issued_day, public_hash)`. Additive only. Existing rows stay
  unlisted (see `design.md`, migration plan).
- **Deploy**: `deploy/charts/blackhole` gets values for the ban-list client names and an existing Secret holding one
  password hash per client, mounted as files. `BLACKHOLE_PUBLIC_BAN_KEY` comes from a Secret too.
- **API and client**: `GET /public/bans`, `GET /public/bans/{hash}`, `GET /public/bans/summary` and the template
  field. The spec moves to the next version after `0.0.44` (`client/build.gradle.kts`), set at implementation time,
  and `client/` is regenerated. The generated client needs the Basic Auth scheme for these routes. Not breaking.
- **Web**: the template editor field and its validation in `web/`.
- **Docs**: `.claude/rules/public-bans.md` (path-scoped), one line in `.claude/rules/public-api.md` saying these
  routes are the Basic Auth exception to the anonymous rules and are not anonymous, and a pointer in `CLAUDE.md`.
- **Dependencies**: `standardize-api-pagination-and-problems` must be applied before the code PR. Otherwise the code
  uses the same envelope and is adjusted when it lands. `amend-data-protection-principle` is the published-data rule
  this change relies on. `add-kick` is the source of the `KICK` kind, which this change excludes. The principle 2
  exception needs the maintainer's confirmation or an amendment of `openspec/config.yaml` (open question 1).

## Out of scope

- A portal page for the list. The API is consumed by trusted clients (the website backend and partner networks).
  No browser page is built, and no anonymous or session-based access is added.
- Player search, lookup by name, UUID or Ban ID, and any player page that shows the player's own entry.
- Names of players or staff, section keys and server names. A server ban shows its kind only.
- Entries for kicks, warnings, and appeal outcomes beyond `REVOKED`.
- Client management in the database or an admin UI. Clients are configuration plus Secret files in v1 (a later
  option, see `design.md`, decision 2).
- Per-client scopes or different entry sets per client. Every client sees the same list.
- Per-locale copies of the public reason. A template has one text in this change.
- Discord or RSS feeds of the list, and webhooks.
- Rate limiting. The edge owns it (`config.yaml` principle 5).
- Backfilling existing punishments. Only punishments applied after their template has a public reason are listed,
  unless the maintainer decides otherwise (open question 6).
- Rotating `BLACKHOLE_PUBLIC_BAN_KEY` with a rewrite of stored hashes. This change documents that rotation changes
  every hash, and leaves the recompute job to a follow-up.

Ships as: feat(public): add the ban list for authenticated clients
