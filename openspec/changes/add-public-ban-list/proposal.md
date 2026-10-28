## Why

A player who is banned or muted today can only learn the details by logging in to the appeal portal
with their Ban ID. Everyone else gets no answer: not the players who saw the ban happen in chat, and
not the community that asks "why was he banned?". Moderators answer those questions by hand, and each
answer depends on who is asking.

A public ban list gives the community one fixed place to look: which kind of sanction was given, for
which published reason, when it started and when it ends. It shows the network's rules being applied
without naming anyone. Nobody needs a name or a Ban ID to read it, and nothing on it can be used to find
out whose entry it is.

## What Changes

- **New anonymous read API** under the existing public API: `GET /public/bans` (cursor paginated, with the
  envelope of `standardize-api-pagination-and-problems`: `items`, `nextCursor`, `limit`, `cursor`, signed
  cursor, `Link` header), `GET /public/bans/{hash}` for one entry, and `GET /public/bans/summary` for a
  count per public reason.
- **Entries carry only** the public reason, the section kind (`NETWORK_BAN`, `MUTE` or `SERVER`), the status
  (`ACTIVE`, `EXPIRED` or `REVOKED`), the start day, the expiry day (or `permanent`), the end day once ended,
  and a stable entry hash. There is no player reference of any kind: no name, no UUID, no hash of a UUID, no
  Ban ID, no punishment reference, no staff name, no section key and no evidence.
- **Opt-in per template.** A template gets an optional `publicReason`. A punishment is published only if its
  template has one. The text is copied onto the punishment when it is applied, so a later template edit does
  not rewrite published history. Manual punishments without a template, and every kick (`kind = KICK`), are
  never published.
- **Entry hash.** A keyed HMAC-SHA256 of the internal punishment identifier, under its own secret
  `BLACKHOLE_PUBLIC_BAN_KEY`. It lets people cite an entry. It cannot be computed from a Ban ID or from a
  punishment reference.
- **Dates are rounded to the UTC day.** No exact time is returned. The cursor sort keys are the day and the
  hash, so a cursor cannot reveal an exact time either.
- **Revoked and expired entries stay visible with their status** for a configurable retention window
  (`BLACKHOLE_PUBLIC_BANS_RETENTION`, proposed `P90D`), counted from the end. Active entries are always
  shown. After the window an entry leaves the list and its hash answers 404.
- **Summary per reason**: active and total counts per public reason and kind within the window. Counts below
  a minimum are withheld, so one entry cannot be read off the summary.
- **Cache headers**: the list and the summary answer `Cache-Control: public, max-age=<60s>` with
  `stale-while-revalidate`, not `no-store`. This is the one exception to the `no-store` rule in
  `.claude/rules/public-api.md`, and the spec states why.
- **Portal page `/bans`**: the summary, the list with a kind and status filter, and "load more" on the cursor.
  It is reached through the portal BFF allow-list (`/api/bans` maps to `/public/bans`, `session: false`) with
  a cacheable response. No search, and no lookup by name or Ban ID.
- **Template editor** (admin web) gets the optional public reason field, with a length and PII check.
- **Reserved prefix**: the `/public/**` rule of `staff-authentication` now holds exactly this anonymous,
  read-only route family. The rule that staff roles never reach `/public` is unchanged.
- **Published-data rule**: entries carry no IP, no raw UUID and no name, which is what
  `amend-data-protection-principle` requires for anything published. The list therefore does not depend on the
  amended storage rules for IPs or names. The entries are still pseudonymous data, because the operator can map
  an entry back to a punishment. The retention window and the erasure rule in `design.md` apply for that reason.

Not BREAKING. Every change is additive: a new route, an optional template field and new punishment columns.
The generated client gains a new API and an optional field. Existing clients are unaffected.

## Capabilities

### New Capabilities

- `public-ban-list`: the anonymous list and its entry fields, the entry hash, the publication rule (template
  opt-in, kicks excluded), day rounding, the retention window, the summary and its suppression, the cache
  headers, erasure, and the portal page.

### Modified Capabilities

- `punishment-management`: an optional public reason on templates, the snapshot of that reason and of the
  section kind on the punishment, and the publication fields kept current on apply, amendment and revocation.
- `staff-authentication`: the reserved `/public/**` prefix holds one anonymous read-only route family
  (`/public/bans`). The staff-role rule is unchanged.
- `admin-web-ui`: the template editor shows the public reason field.

## Impact

- **Backend**: a new `publicbans/` feature package (controller, `*Api` interface, sealed DTO marker interface,
  `@Singleton` service, JDBC reader, entry hash and day rounding helpers). Changes in `punishment/` (template
  field, publication writer on apply, amendment and revocation), in the erasure path (removes the entry with
  the punishment), in `security/` (one anonymous rule for `/public/bans`), and in `application.yml`
  (`blackhole.public-bans.*` and `BLACKHOLE_PUBLIC_BAN_KEY`, with a guard that refuses to start without the key
  outside `dev` and `test`, as `BanIdKeyGuard` does).
- **Database**: one new Flyway migration `V<next>__public_ban_list.sql`. The main head is `V27__kick.sql`, and the
  number is taken at implementation time. It adds `punishment_template.public_reason`, and on `punishments`:
  `public_reason`, `public_kind`, `public_hash` (unique), `public_issued_day`, `public_expires_at`,
  `public_revoked_at`, with an index on `(public_issued_day, public_hash)`. Additive only. Existing rows stay
  unpublished (see `design.md`, migration plan).
- **API and client**: `GET /public/bans`, `GET /public/bans/{hash}`, `GET /public/bans/summary` and the template
  field. The spec moves to the next version after `0.0.44` (`client/build.gradle.kts`), set at implementation
  time, and `client/` is regenerated. Not breaking.
- **Portal**: `portal/server/lib/bff.ts` (the route spec gets a public, cacheable flag), `portal/src/api/contract.ts`,
  a `BansView` in `portal/src/views/` routed by pathname as `/leave` is in `portal/src/main.ts`, and locale strings.
- **Web**: the template editor field and its validation in `web/`.
- **Docs**: `.claude/rules/public-bans.md` (path-scoped), one line in `.claude/rules/public-api.md` naming the list
  as the `no-store` exception, and a pointer in `CLAUDE.md`.
- **Dependencies**: `standardize-api-pagination-and-problems` must be applied before the code PR. Otherwise the
  code uses the same envelope and is adjusted when it lands. `amend-data-protection-principle` is the
  published-data rule this change relies on. `add-kick` is the source of the `KICK` kind, which this change
  excludes. An expiry amendment must update `public_expires_at` (see `design.md`).

## Out of scope

- Player search, lookup by name, UUID or Ban ID, and any player page that shows the player's own entry.
- Names of players or staff, and section keys. A server ban shows its kind only.
- Entries for kicks, warnings, and appeal outcomes beyond `REVOKED`.
- Per-locale copies of the public reason. A template has one text in this change.
- Discord or RSS feeds of the list, and webhooks.
- Rate limiting. The edge owns it (`config.yaml` principle 5).
- Backfilling existing punishments. Only punishments applied after their template has a public reason are
  published, unless the maintainer decides otherwise (open question 6).
- Rotating `BLACKHOLE_PUBLIC_BAN_KEY` with a rewrite of stored hashes. This change documents that rotation changes
  every hash, and leaves the recompute job to a follow-up.

Ships as: feat(public): add the anonymous ban list with a summary per reason
