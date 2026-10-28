## Context

The code this design builds on (checked on `origin/main`, 2026-10-28):

- `backend/.../punishment/PunishmentEntity.java`: `punishments` rows carry `identifier` (String id, the internal
  punishment identifier), `short_id` (the reference shown to staff and in the player's own punishment view), `owner`
  (hashed owner), `section_key`, `scope`, `permanent`, `lifted`, `created_at`, `escalation_group`, `elo_cost`, `kind`
  (`PUNISHMENT` or `KICK`, from `add-kick`), `meta_data`. The expiry is NOT a column: it lives in `meta_data` under
  `Expirable.META_DATA_KEY_EXPIRATION_DATE` (read by `PunishmentEvents`).
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
  same, and `/public/**` is reserved by `staff-authentication` for the later player portal, with no route under it
  yet (`openspec/specs/staff-authentication/spec.md`, "Reserved prefix for the player portal").
- `portal/server/lib/bff.ts`: `respond()` sets `Cache-Control: no-store` on every BFF answer. `ROUTES` in
  `portal/src/api/contract.ts` maps `/api/<name>` to `/public/<name>`; the login route is the only one with
  `session: false`. The portal selects its page by pathname (`portal/src/main.ts`, `/leave`).
- `standardize-api-pagination-and-problems` (proposal on `main`, not applied): `CursorPage<T>{items, nextCursor}`,
  `limit` 1 to 200 with default 50 and a 400 instead of a clamp (D6), signed cursor
  `base64url(payload) "." base64url(HMAC-SHA256(BLACKHOLE_CURSOR_KEY, payload))` (D7), RFC 8288 `Link` (D15),
  RFC 9457 problem bodies with `code` (D1-D3), fixed-status 404 for unknown and not-permitted alike (D4).
- Flyway: the main head is `V27__kick.sql`. Client spec: `client/build.gradle.kts` points at
  `blackhole-api-0.0.44.yml`.
- `openspec/config.yaml` principle 1: PII is hashed or tokenized wherever it is stored or published. The amendment
  `amend-data-protection-principle` (prerequisite for the wider data decisions) keeps the rule that anything published
  carries no IP, no raw UUID and no name. This change publishes none of them.

## Goals / Non-Goals

**Goals**: an anonymous, paginated, cacheable list of published sanctions that a reader can cite by a stable
reference; a list that reveals nothing that links an entry to a player, to a Ban ID or to an exact time; a
summary that cannot be used to single out one punishment.

**Non-Goals**: player search or lookup; any link from an entry to a player page; showing names, staff, servers or
evidence; per-locale reason texts; a feed or webhook; changing ban, mute or section semantics; rate limiting.

## Decisions

### 1. What is published: opt-in per template, snapshot on apply

A template gains `publicReason` (optional text, 1 to 200 characters, plain text, `PiiGuard` applied). The rule for a
punishment:

- `kind = PUNISHMENT`, a template with a non-blank `publicReason`, and the template's section resolves to a section kind:
  the punishment is published.
- Otherwise it is never published: no template, a template without public reason, and every `KICK` (kicks are never
  bans; `add-kick` decision 1).

The text and the section kind are copied onto the punishment when it is applied (`public_reason`, `public_kind`).
Later template edits do not change published entries. The `publicReason` on a template is the only public text: it is
never derived from the staff `reason`, which may name a rule detail or a case.

Alternatives considered:
- *Publish every punishment and let staff hide entries one by one*: rejected. Opt-out per row invites an accidental
  publication of a row whose reason was never reviewed. Opt-in per template means one reviewed text per template.
- *Publish the staff `reason`*: rejected. Staff reasons are free text and can name players, servers or cases.
- *Live join to the template*: rejected. A template edit would rewrite history, and a deleted template would silently
  remove entries.

### 2. Entry fields and their vocabulary

An entry, as returned by the list and the single read:

| field | value | notes |
|---|---|---|
| `hash` | 22 characters, base64url | 128 bits of the keyed hash (decision 3) |
| `reason` | `publicReason` snapshot | plain text, never Markdown |
| `kind` | `NETWORK_BAN`, `MUTE`, `SERVER` | from `public_kind`: `BAN` maps to `NETWORK_BAN` |
| `status` | `ACTIVE`, `EXPIRED`, `REVOKED` | decision 5 |
| `issuedOn` | `YYYY-MM-DD` (UTC) | day of `created_at` |
| `permanent` | boolean | |
| `expiresOn` | `YYYY-MM-DD` or `null` | day of the expiry instant; `null` when permanent |
| `endedOn` | `YYYY-MM-DD` or `null` | day of the revoke, or of the expiry once passed; `null` while active |

Not returned, ever: the punishment identifier, the reference, the owner hash, any UUID or IP, Ban ID, staff or actor
identifiers, `section_key`, `scope`, `elo_cost`, evidence, escalation group, the exact times, the duration in
seconds, and the server or section name. The `kind` is the only information about the section.

The `reason` is returned as stored. The API adds no player-specific text, so a reason that names a player is a
template authoring error. Decision 8 covers how that is prevented.

Alternatives considered:
- *Return the section key for server bans*: rejected. A server name plus a date narrows the entry, and the section kind
  gives the reader what they need to understand the sanction.
- *Return the exact duration*: rejected. The day-rounded expiry is enough, and an exact duration with an exact start is
  a timing key (decision 4).

### 3. Stable entry hash: keyed, separate, truncated

`hash = base64url( first 16 bytes of HMAC-SHA256(K, "public-ban-entry-v1" || 0x01 || identifier) )`, where `K` is derived
from `BLACKHOLE_PUBLIC_BAN_KEY` with HKDF-SHA256 (the same construction as `BanIdCrypto`, label
`blackhole-public-ban-entry-v1`). The hash is computed once when the entry is published and stored in
`punishments.public_hash`, which is unique and indexed.

Why these choices:

- **Keyed, not a plain hash of the identifier.** An identifier is an internal id and may be guessable. Without the key
  nobody can recompute a hash from an identifier, so a reader cannot test "is this my punishment".
- **Its own secret, not `BLACKHOLE_BANID_KEY`.** The Ban ID lookup HMAC and the public entry HMAC share no key material.
  A Ban ID cannot be turned into an entry hash, and an entry hash cannot be turned into a Ban ID. Each key is derived
  with a different label from its own secret. The same rule covers `BLACKHOLE_CURSOR_KEY` from the pagination change.
- **Not derived from the reference.** The reference (`short_id`) is shown to the player in their own view. An entry
  hash built from it would let a player who knows their reference compute their own entry. The hash input is the
  identifier, which the public API never returns.
- **128 bits, 22 characters.** Collisions are not a practical concern at this scale, and the value is short enough to
  cite. The unique index turns a collision into a failed publish, which is logged without the input.
- **Stored, not recomputed per read.** A lookup by hash (`GET /public/bans/{hash}`) needs an index. Storing also means
  the key is needed only at publication time and never on read.

Key rotation: changing `BLACKHOLE_PUBLIC_BAN_KEY` changes every hash, so every cited hash stops resolving. This change
documents that and does not add a rewrite. A rotation needs a one-shot recompute of `public_hash` (the pattern of
`BLACKHOLE_BANID_REISSUE_ALL`), which is a follow-up.

Guard: the key is required outside `dev` and `test`, as `BanIdKeyGuard` requires `BLACKHOLE_BANID_KEY`. In `dev` and
`test` a random key is used per run, with a warning, so hashes do not survive a restart there. The key never appears in
a log, a response or a metric.

Alternatives considered:
- *Hash of the UUID or of the name*: rejected by the brief (no player reference, not even a hashed UUID).
- *Random per-entry id stored with the punishment*: a viable alternative. A random id leaks nothing and needs no key
  management, but it cannot be recomputed from the identifier, so an operator who needs to map a cited hash back to its
  punishment needs a lookup table. The keyed HMAC was chosen for that recompute. Open question 4 asks the maintainer
  to confirm, because the random id is simpler if nobody needs the mapping.
- *Full 256-bit output*: rejected as needlessly long for a citation.

### 4. Timing: day rounding, no exact times anywhere

- `issuedOn`, `expiresOn` and `endedOn` are UTC calendar days of the stored instants. The rounding is done in the
  service from `created_at`, `public_expires_at` and `public_revoked_at`. The stored instants are not returned.
- The cursor sort keys are `public_issued_day` (epoch day, `INT`/`BIGINT`) and `public_hash`. The default sort is
  `public_issued_day DESC, public_hash DESC`. A cursor payload is base64url, so anyone can read it. It therefore holds
  the day and the hash only. Sorting on the exact instant would put the exact instant into every cursor.
- The summary and the list use day granularity only.
- Publication: the entry becomes visible when the punishment is applied. No delay is built in (open question 2).

Why: the entries are public and a reader may know when a player was banned (their own ban, or a chat message). An exact
start time next to a reason and a kind would link an entry to a known moment. A day is the smallest unit that still
says "this week" to a reader, so the list stays useful and the moment is blurred.

### 5. Status and retention window

Derived on read from the stored columns and the injected `Clock`:

- `REVOKED` when `public_revoked_at` is set (a staff revoke or unban, written by the revoke path). The reason of the
  revoke is never published, so an appeal outcome cannot be read from the list.
- `EXPIRED` when not revoked and the expiry instant (`public_expires_at`) is at or before now.
- `ACTIVE` otherwise (permanent entries stay `ACTIVE` until revoked).

Retention: an entry is listed while it is `ACTIVE`, or while `now < endedAt + blackhole.public-bans.retention`
(`BLACKHOLE_PUBLIC_BANS_RETENTION`, proposed `P90D`), where `endedAt` is `public_revoked_at`, or `public_expires_at`
when not revoked. After that it is no longer listed, and its hash answers 404. The filter is one SQL condition on
`COALESCE(public_revoked_at, public_expires_at)`, so pages never come out short.

Expiry is read from the instant the punishment stores, not from the lazy expiry sweep, so an entry shows `EXPIRED`
even when nobody has read the profile since. The list only reads the publication columns and never writes to the
punishment.

Alternatives considered:
- *Hide revoked entries entirely*: rejected for the default. A reader who sees an ACTIVE entry disappear with no
  explanation would take it as a removal of the rule. Showing `REVOKED` with no reason is the honest state. Open
  question 1 keeps the option.
- *Store an `EXPIRED` status column*: rejected. It lags behind the expiry, as decision 5 explains.

### 6. Routes, errors and the reserved prefix

All three routes are anonymous (`@Secured(SecurityRule.IS_ANONYMOUS)`) and read-only. They sit under `/public` in
`publicbans/controller/PublicBansApi` (OpenAPI annotations on the interface), `PublicBansController` (thin, one service
call each, `@Version(ApiVersion.V1)`), and `PublicBansService` (`@Singleton`, returns the sealed `Error` variant).

| route | answer | notes |
|---|---|---|
| `GET /public/bans?limit=&cursor=&kind=&status=` | `200` `{items, nextCursor}` plus `Link` (RFC 8288) when `nextCursor` is set | `limit` 1 to 200 (default 50), 400 otherwise; `kind` in `NETWORK_BAN\|MUTE\|SERVER`; `status` in `ACTIVE\|EXPIRED\|REVOKED`; a bad `cursor` is 400 `INVALID_CURSOR` |
| `GET /public/bans/{hash}` | `200` one entry | the hash shape is checked (22 base64url characters); anything else, an unknown hash and a hash outside retention are the same 404 |
| `GET /public/bans/summary` | `200` `{windowDays, reasons: [{reason, kind, active, total}]}` | counts below `blackhole.public-bans.summary-min-count` (default 5) are `null` with `suppressed: true` |

Errors are RFC 9457 problems with `code` (the pagination change's envelope). No error body echoes the input.
`/public/bans/summary` and `/public/bans/{hash}` use the same 404 body.

The `/public/**` rule of `staff-authentication` stays strict: every route under `/public` declares its own rule, and this
change adds exactly these three anonymous routes. `PlayerSessionRule` and the `bhps_` session tokens are not involved.
A staff or proxy token is not special here, and the route-coverage test asserts that the three paths are the only
anonymous `GET` routes under `/public` apart from the login.

### 7. Cache headers

The list, the single read and the summary answer:

- `Cache-Control: public, max-age=60, stale-while-revalidate=300` (`blackhole.public-bans.max-age`, default `PT60S`,
  `BLACKHOLE_PUBLIC_BANS_MAX_AGE`), and `Vary: Accept`.
- `ETag` (a strong tag over the response body) with `If-None-Match` answered `304`.

This is the one exception to the `no-store` rule of `public-api.md`. The reason is the content: it is the same for every
reader, contains no session, and is the public record. A revoked or erased entry can stay visible at a shared cache for
up to `max-age`, and the spec accepts that bound. Errors keep `no-store`.

The portal BFF returns the same headers for these three routes (decision 9). Its `no-store` default stays on every
other route.

Alternatives considered:
- *`no-store` like every other public route*: rejected. It would send every reader to the database and prevent the
  shared cache from doing its job for a list that changes once a day at most.
- *Cache in the shared Dragonfly cache*: rejected for this change. HTTP caching is enough for an anonymous list, and the
  shared cache is for hot authorized reads (shared-cache spec).

### 8. Summary and suppression

`GET /public/bans/summary` counts the entries the list would show (same visibility rule, same window): per public
`reason` and `kind`, `active` (status ACTIVE) and `total` (every listed entry). A row whose `total` is below
`blackhole.public-bans.summary-min-count` (default 5) returns `active: null, total: null, suppressed: true`. The row
stays, so the reason is still listed as an existing rule.

Why: with one player under a reason in a week, the summary alone says who was banned that week. The threshold stops a
count of one from being a lookup. It is not a complete defence (decision 10 covers the rest).

### 9. Portal page `/bans`

- BFF allow-list in `portal/src/api/contract.ts`: `{path: '/api/bans', method: 'GET', backend: '/public/bans', session: false, query: {limit, cursor, kind, status}, publicCache: true}`,
  `{path: '/api/bans/summary', ...}`, `{path: '/api/bans/:hash', ...}` with a `param` shape of 22 base64url characters.
  `publicCache: true` makes `bff.ts` relay the backend's `Cache-Control` and `ETag` instead of `no-store`. Every other route
  keeps `no-store`, and no cookie is forwarded on these routes.
- `BansView.vue` is selected by pathname (`/bans`, and `/bans?entry=<hash>` to open one entry), like `/leave` in `main.ts`.
  It shows the summary as a table, then the list with a kind and status filter and a "load more" control that follows
  `nextCursor`. No search box, no name or Ban ID field.
- Locale strings for both locales the portal ships. The reason text is shown as stored (plain text, no Markdown).

### 10. Abuse and linkage review

Each row is a way a reader could link an entry to a person, and what this design does about it.

1. **Ban ID correlation.** Separate key (decision 3). Entries and Ban IDs share no input and no label. A leaked Ban ID
   gains nothing from the list.
2. **Reference correlation.** The reference is not an input of the hash, and it is never returned.
3. **Timing correlation.** Day rounding everywhere, including the cursor (decision 4). The summary has no time buckets
   narrower than the window.
4. **Cursor leakage.** Sort keys are day and hash only; the signed cursor (pagination change D7) stops tampering but
   does not hide the payload, so the payload is kept to non-identifying values.
5. **Small-count inference in the summary.** Suppression below the minimum (decision 8).
6. **Reason inference.** A reason that names a rule detail specific enough to point at one case is the largest remaining
   risk. Mitigation: the `publicReason` is written by staff once per template, reviewed like the template itself. Only
   the ADMIN role may set it (open question 8), a `PiiGuard` refuses IPs, UUIDs and Ban IDs, and the length cap is 200.
   Names cannot be detected by a rule, so this is a review step, documented in `.claude/rules/public-bans.md`.
7. **Scraping and enumeration of the whole list.** The list is public by design. The hash is not guessable (128-bit HMAC
   with a secret key), so enumeration of hashes is pointless and a 404 for an unknown or retired hash reveals nothing.
   Rate limiting belongs to the edge (`config.yaml` principle 5).
8. **Erasure.** On a valid erasure request for an owner, the punishments of that owner are erased by the existing
   erasure path. That path also clears `public_reason`, `public_kind`, `public_hash`, the two instants and the day,
   so the entry leaves the list at once and its hash answers 404. The cache bound in decision 7 applies.
9. **Pseudonymity.** An entry is still personal data under GDPR, because the operator can map it to a punishment and
   therefore to a player. Publication is justified by the operator's decision (open question 10). The data minimisation
   here is the reason the entries carry no more than the list needs, and the retention window bounds how long they stay.

### 11. Data platform and other consumers

No event is published for public entries. The list reads the punishment table directly. The Velocity plugin and the
Discord bot do not read the list. The generated client gets the new routes and the template field as additive changes.

## Risks / Trade-offs

- **Staff write a reason that identifies a player.** Mitigated by the review step and the ADMIN-only rule (decision 10.6),
  not by code. A test can only refuse UUIDs, IPs and Ban IDs.
- **A revoked or erased entry stays in shared caches for up to 60 seconds** (decision 7). Accepted: the bound is short
  and the content is not secret once published.
- **Key loss or rotation breaks every cited hash.** Accepted and documented. The key is backed up like the Ban ID key.
- **A summary with a higher threshold hides real rules.** The threshold is configurable, and rows stay with `suppressed`.
- **Backfill is not done.** Existing bans are not on the list until a maintainer decides to publish them (open question 6).

## Migration plan

Flyway, append-only, the next free version at implementation time (`V<next>__public_ban_list.sql`, main head
`V27__kick.sql`). The migration:

1. `ALTER TABLE punishment_template ADD COLUMN public_reason VARCHAR(200) NULL`.
2. `ALTER TABLE punishments ADD COLUMN public_reason VARCHAR(200) NULL, public_kind VARCHAR(16) NULL,
   public_hash VARCHAR(22) NULL, public_issued_day BIGINT NULL, public_expires_at BIGINT NULL,
   public_revoked_at BIGINT NULL`.
3. `CREATE UNIQUE INDEX ux_punishments_public_hash ON punishments (public_hash)` (where supported; the engine is
   confirmed from `docker/docker-compose.yml`, see the pagination change D8).
4. `CREATE INDEX ix_punishments_public_order ON punishments (public_issued_day, public_hash)`.

Existing rows: every new column is NULL, so no existing punishment is published. No backfill runs in this migration.
Templates have no public reason until a staff member sets one. A later maintainer decision can publish old punishments by
a one-shot job that copies the template's public reason onto the punishment and computes the hash, the same pattern as
`BanIdBackfill`. That job is not part of this change (open question 6).

Rollback: the columns and the index are unused by any other code, so dropping them restores the previous behaviour. The
migration is not edited once applied.

## Open questions

1. **Revoked entries.** Show them as `REVOKED` with no reason (proposed), or hide them? Showing them tells a reader the
   rule was lifted, which is a fact the community can see. Hiding them tells a reader nothing.
2. **Publication delay.** Visible at once (proposed), or after a delay such as `PT24H`, so that an entry cannot be tied
   to a same-day chat moment? A delay costs freshness; day rounding already removes most of the timing signal.
3. **Retention window.** `P90D` proposed for entries after they end. Should an active permanent ban stay listed for as
   long as it is active (proposed) and nothing more?
4. **Hash construction.** Keyed HMAC of the identifier (proposed, lets the operator recompute during investigations) or a
   random id stored with the punishment (no key to manage, no recompute)? The random id is the simpler option if nobody
   needs to recompute.
5. **Server bans.** Show the kind only (proposed), or the server name as well? The server name is not personal data, but
   with a date it narrows the entry.
6. **Backfill.** Publish existing punishments whose templates have a public reason? Proposed: no, only new ones, and a
   separate decision.
7. **Erasure.** Remove the entry on an erasure request (proposed), or keep an entry without owner data? Removal is the
   conservative choice and matches the retention intent.
8. **Who sets the public reason.** ADMIN only (proposed), or MODERATOR as well? It is a template-level text, so it is a
   policy decision, not a per-case one.
9. **Summary.** Keep the per-reason summary at all, or drop it in favour of counts per kind? The summary adds a second
   way to read the list. The threshold of 5 is a proposal.
10. **Legal basis for publication.** `amend-data-protection-principle` settles which data may be published, namely no IPs,
    UUIDs or names. It does not decide whether a sanction against a player may be published at all. That decision (legal
    basis for the publication of a sanction, and the transparency notice) belongs to the maintainer and is not made here.
11. **Amendments.** An amendment that changes the expiry must update `public_expires_at`. Does an amendment that changes
    the staff reason change the public reason too (proposed: no, the public reason is a snapshot)?
12. **Localisation.** One public text per template in this change. Should templates later carry one text per locale, as
    `add-i18n-platform` may require?
13. **Dependencies on other changes.** The code PR waits for `standardize-api-pagination-and-problems` to be applied, so
    the envelope, the problem body and `BLACKHOLE_CURSOR_KEY` exist. Is that acceptable, or does this change adopt them
    ahead of that change?
