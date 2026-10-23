# Design

## Context

- Players quote a short id (`#A1B2C3D4`): `PunishmentShortId`, the upper-case hex of the first four
  bytes of the punishment UUID, column `punishments.short_id` (V7), **not unique**, so a staff lookup
  can answer 409 with candidates. The Velocity plugin derives it locally from the identifier.
- `/public/**` is reserved for the portal (`add-team-sso`, spec "Reserved prefix for the player
  portal"); `RouteCoverageIntegrationTest` asserts it is empty and must change with this work.
- Every other route needs an Entra token; the trust model is the network boundary (CLAUDE.md).
  There is no per-player identity and no rate limiting in the app.
- `add-punishment-case-timeline` provides the case, the append-only `case_event` log (text `type`,
  `visibility`, `subject_id`, `payload jsonb`, `dedupe_key`, redaction), `case.*` events published
  without content, and reserves `message.added` and `ban_id.leak_marked`.
- ADR-ORG-0009: `onelitefeather.net` is for players and the public, with no login at the edge and
  every app defending itself; `onelitefeather.dev` is for the team. The admin UI stays on `.dev`.
- `@Transactional` is unusable. Atomic multi-table writes are single data-modifying CTE statements.
- Appeals today: `POST /appeal` (staff or tools, records `INELIGIBLE` too), eligibility checklist
  (min days 3 auto / 14 manual, repeat cooldown 7 days), decision with `decisionNote` (internal).

## Goals / Non-Goals

**Goals:** a player appeals and talks to the team with the Ban ID alone; the Ban ID is a credential
handled like one; the conversation is a first-class, complete, erasable part of the case; the
admin UI stays internal; the later Discord bot plugs into the same model.

**Non-Goals:** see the proposal. Notably no player accounts, no attachments, no app rate limiting.

## Decisions

### 1. Ban ID: format, uniqueness, existing ids

- **Alphabet:** Crockford base32 (`0123456789ABCDEFGHJKMNPQRSTVWXYZ`), 8 characters, 40 bits from
  `SecureRandom`. Input is normalised before use: strip spaces and one leading `#`, upper-case,
  `O -> 0`, `I`/`L -> 1`, reject anything else (and `U`). The staff link code uses the same alphabet.
- **Guessability, stated honestly:** 2^40 is about 1.1 * 10^12. With N live punishments the chance
  that one blind guess hits any of them is N / 2^40 (10^4 punishments: about 10^-8). That only
  holds if guessing is slow, so the control is **infra rate limiting** on the login (see 5), plus
  uniform errors and a failure metric to alert on. The user accepted 8 characters; this is the
  reason it is acceptable and the reason the rate limit is a deployment requirement, not a nicety.
  An attacker who guesses an ID gains: the punishment facts and the ability to file or write on the
  appeal of someone else's ban. Nothing else is reachable with a player session.
- **Unique:** a unique index on the lookup hash (decision 2). The generator retries on a violation.
  It also rejects a candidate equal to any legacy `short_id`, so a new Ban ID never collides with a
  staff-lookup key.
- **Existing hex short ids (decision): keep as the legacy staff key, do not accept them at login.**
  They are 32 bits, not unique, and have been shown on screens and typed into chats and evidence
  for months, so treat them as already public. `short_id` stays as is; staff lookup by short id
  works exactly as today (409 when ambiguous) and takes legacy hex ids only. **A Ban ID never goes in
  a URL or query, staff lookups included:** staff look one up with `POST /punishment/lookup` and the
  Ban ID in the body (`MODERATOR`, `PROXY`). Every miss, malformed or unknown, is the same 404 without
  the input, and the response is `no-store`. The hash is unique, so this never yields several matches.
- **Existing punishments:** an idempotent `BanIdBackfill` job (application start, batches, only rows
  without a Ban ID) issues one to every punishment that has none. No SQL backfill is possible
  because generation needs the key. Until the job reaches a row, login for it fails like an unknown
  ID. Players who are banned see the Ban ID on their next join attempt (decision 3).

### 2. Storage: hashed for login, encrypted for staff

Staff must see the Ban ID (to read it back to a player), so a one-way hash alone is not enough.
`punishments` gains:

| Column | Meaning |
|---|---|
| `ban_id_hash bytea` unique | `HMAC-SHA256(banIdKey, normalisedBanId)`; the login and lookup key |
| `ban_id_enc bytea` | `AES-256-GCM` of the Ban ID, fresh nonce, punishment identifier as AAD; staff display and the proxy |
| `ban_id_gen int` | generation, 1 for the first, +1 per reissue |

`banIdKey` is one 32 byte secret (`BLACKHOLE_BANID_KEY`, base64) from which the HMAC key and the AES key
are derived with HKDF (different `info`). A non-dev context without it refuses to start, like the
insecure-issuer guard. Consequences: a database leak alone yields neither login nor plaintext; losing
the key makes every Ban ID unreadable and unusable, and the recovery is the reissue job
(`BanIdBackfill --reissue-all`), so the key needs the same backup care as any secret. Key rotation in v1
is "reissue all": a new key makes every Ban ID unusable and `BanIdBackfill --reissue-all` issues new ones
(decided 2026-10-23). Key ids (several keys valid at once, lazy re-encryption) come in a later change, so the
stored columns need no key id column now.
Lookup input is HMACed and compared by the unique index, so there is no timing signal beyond a DB
lookup. The plain Ban ID exists in memory only (generation, the reveal route, the login request).

**Never log or publish it.** The login sends it in a POST body (never in a URL or query, in the
backend or the BFF), request body logging is off for `/public/**`, the Nitro handlers do not log
bodies, error responses never echo the input, and no domain event, metric label, trace attribute or
audit row carries it. A test scans captured logs and published events of the whole flow for the
test Ban ID. The reveal route (`GET /punishment/{ref}/ban-id`, `MODERATOR` and `PROXY`) answers
`Cache-Control: no-store`; `VIEWER` never gets it, and it is not part of any punishment DTO.

### 3. Who sees the Ban ID

- **Player:** on the Velocity ban screen. The plugin calls the reveal route when it renders the screen
  (proxy role) and keeps the value in plugin memory only; if the backend is down the screen shows
  the reference without a Ban ID (never a stale hex id). It is not put into the Dragonfly
  active-punishment read model (a second at-rest copy).
- **Staff:** the case page header shows it masked with a reveal button (calls the route; `MODERATOR`+).
- **Never** in `punishment.*`, `appeal.*`, `case.*` events, timeline payloads or notes. `PiiGuard`
  gets a Ban ID shape check so a note or message that contains a Ban ID is rejected (staff typing
  it into a note would defeat the leak rule), see decision 8.

### 4. Public API and the anonymous player session

`/public/**` is a new package `publicapi/` with its own controller family `PublicApi`.

- **No Entra token.** The routes are not `@Secured` with a staff role; they carry an explicit rule
  `PlayerSession` (the permission-matrix entry): `POST /public/session` is anonymous, every other
  route requires a valid player session. A staff role never grants access and a player session
  never grants a staff route (the route-coverage test enforces both directions).
- **Who may call it (network):** only the portal BFF. The backend is not routed publicly; the BFF is
  the single public edge. The BFF forwards the client address as `X-Forwarded-For` so infra can rate
  limit and the backend metric can be sliced, and the browser never sees backend responses
  unfiltered (the BFF maps them, decision 6).
- **Session:** on a good login the backend creates a `player_session` row and returns a random
  256 bit token once. Only its SHA-256 is stored (`token_hash`), with the punishment identifier, scope
  (`FULL` or `LIMITED`, decision 7), `csrf_token`, `created_at`, `last_seen_at`, `expires_at`.
  Expiry: idle 30 minutes (sliding) and absolute 4 hours (`blackhole.public.session.*`; both decided 2026-10-23, an unsent draft is kept in memory across the expiry page). A sweeper
  deletes expired rows; the clock is injected. A leak mark or a reissue deletes all sessions of that
  punishment in the same statement as the case change. Why not a signed token: leak and reissue
  need immediate revocation and an idle timeout, which needs server state anyway.
- **Login errors:** wrong, unknown, malformed or not-yet-backfilled input all answer the same
  `401 INVALID_BAN_ID`, same body, same status, no detail. (A *valid* leaked ID succeeds with a
  `LIMITED` session; success versus failure is the one distinction a credential check must have.)
  Format failures also answer 401, never 400.

| Route | Purpose |
|---|---|
| `POST /public/session` `{banId}` | log in; `{token, csrf, expiresAt, scope}` |
| `DELETE /public/session` | log out; deletes the row |
| `GET /public/session` | `{scope, expiresAt, csrf}` for the SPA bootstrap |
| `GET /public/punishment` | public facts, see 4a |
| `POST /public/appeal` `{statement}` | file the appeal (`FULL` scope only) |
| `GET /public/appeal` | appeal state, decision and the team's decision message |
| `GET /public/conversation?cursor` | `PLAYER`-visible events, paged by `seq` |
| `POST /public/messages` `{text}` | post a message (`FULL` scope, open and not frozen) |

**4a. Public facts** (an allow-list DTO, never the entity): reference label (no hash, no short id),
type, `reason` (template reason text), section display name, `createdAt`, `permanent`, `expiresAt`,
state (`ACTIVE`, `EXPIRED`, `LIFTED`, `REVOKED`), `evidenceCount` (the count only, as today),
`appeal` summary `{state, availableFrom}` with state one of `NOT_YET`, `AVAILABLE`, `OPEN`,
`DECIDED`, `COOLDOWN`, `FORFEITED`, `FROZEN`. Never: owner hash, staff names or oids, ELO values,
notes, evidence content, other punishments, other players' data.

### 5. Abuse and safety

- **Rate limiting is infra.** The deployment note (docs) states the required edge rules for the
  portal host: login `POST /api/session` per IP and globally (suggested start: 5 per minute per IP,
  with a Cloudflare managed challenge on repeated failures), message and appeal posts per session.
  The app has none (config principle 5). It exports `blackhole.public.login{result}` so the
  operator can alert on a failure spike, which is observability, not a limiter.
- **Message length cap** 1..2000 characters, trimmed, no control characters other than newline
  (config `blackhole.public.message.max-length`); the BFF caps request bodies at 16 KiB.
- **PII guard** as for notes (`PiiGuard`: UUID and IP lookalikes, plus a Ban ID shape): 422 with a
  generic "do not include IP addresses, UUIDs or ban codes" text. The same guard is used on the
  panel reply and the appeal statement. Mirrored Discord messages are *not* guarded (decision in the
  next change: the record must be complete).
- **Attachments: none in v1 (recommended).** Player-supplied files need a malware scan, metadata
  stripping, type detection, storage and GDPR handling; the evidence pipeline is staff-only and
  built for that, reusing it for strangers is its own change. A player can describe evidence in text
  or name an external link as text (shown as plain text, never linked or fetched, SSRF-safe).
- **Plain text only.** Messages and statements are rendered as text everywhere (portal, panel, export);
  no HTML, no Markdown, no link unfurling, so stored text cannot become markup.
- **No enumeration oracles:** a session can only ever address its own punishment; every route takes
  the punishment from the session, never from a parameter.
- **Leak rule, enforcement points:** see 7.
- **Headers:** the portal sets CSP (`default-src 'self'`), `X-Content-Type-Options`, `Referrer-Policy:
  no-referrer`, `Cache-Control: no-store` on API responses, like `web/server/lib/headers.ts`.

### 6. Portal app: separate Nitro module, not public routes in `web/`

Options: (A) public routes in the existing `web/` app; (B) a separate `portal/` module.

**Recommended: B.** `web/` is the admin UI: it serves a `config.json` with Entra authority and client
ids, forwards the whole `/api/` namespace to the backend, and sits on the team domain by
ADR-ORG-0009. Adding anonymous routes there puts the admin bundle, staff-only routes and the
backend proxy on a public host and ties the two audiences' release and incident surface together. A
separate app has: its own origin and cookies (a cookie of the portal can never reach the admin UI),
a BFF with an **allow-list** of seven routes instead of a pass-through proxy, a small public bundle,
its own container and edge rules, and no Entra configuration at all. Cost: some duplicated plumbing
(security headers, hostile-path guard, runtime config validation, MD3 tokens, i18n helper). Accepted
for v1; if the copies drift, extract a shared workspace package in a follow-up. Decided: duplicate
the few files, do not couple the builds.

- **Module:** `portal/` (Gradle module driving npm like `web/`), Vue 3 + Tailwind with the MD3 tokens,
  Nitro `node-server` preset, own `Dockerfile`, `/healthz`, CI image build and smoke check beside
  the web image. Env: `BLACKHOLE_BACKEND_URL`, `BLACKHOLE_PORTAL_ORIGIN`, validated at start like `web`.
- **Host:** `appeal.onelitefeather.net` (decided 2026-10-23). It is the BFF origin, the cookie host (`__Host-`, no Domain) and the `BLACKHOLE_PORTAL_ORIGIN` value.
- **Pages:** login (Ban ID field, `autocomplete=off`, paste friendly, normalisation preview not
  required), punishment facts, appeal form (statement counter, one-appeal notice and the date it
  becomes available), conversation (thread, composer, status, decision), "appeal forfeited" and
  "frozen" notices, session-expired page, logout.
- **Accessibility:** semantic landmarks, labelled inputs, errors in a live region, visible focus,
  contrast of MD3 tokens, message list as `ol` with `time`, full keyboard use, no timers that
  trap focus; session expiry announced with a clear re-login path that keeps an unsent draft in
  memory only.

### 7. Session transport, CSRF, logout, expiry (BFF)

Options: (A) server-issued opaque token in an HttpOnly cookie via the Nitro BFF; (B) a signed token
(JWT) held by the SPA. **Recommended: A.** B puts a bearer secret in script reach (XSS) and cannot
be revoked on a leak.

- The BFF `POST /api/session` forwards the Ban ID, receives `{token, csrf, expiresAt, scope}`, sets
  cookie `__Host-bh_portal=<token>`: `HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age` equal to the
  absolute expiry. `__Host-` forbids a Domain and forces Path `/` and Secure. The token is never
  in the response body to the browser.
- **SameSite=Lax**, not Strict, so a link from Discord or an e-mail opens the logged-in portal.
  Lax sends the cookie only on top-level GET navigations, and no GET changes state.
- **CSRF:** every state-changing BFF route (`POST`, `DELETE`) requires header `X-CSRF-Token` equal to
  the session's `csrf` (obtained from `GET /api/session`, held in memory by the SPA) **and** an
  `Origin` equal to the portal origin. Either failing answers 403 before the backend is called.
- **Logout:** `DELETE /api/session` deletes the backend row and clears the cookie. Idle or absolute
  expiry makes the backend answer 401, the BFF clears the cookie and the SPA shows the expiry page.
- **BFF allow-list:** the BFF defines only the seven routes of decision 4 (mapped one to one), strips
  everything else, and maps backend errors to a fixed set of codes. The backend session token is
  attached server-side as `Authorization: Bearer`.
- **Scope:** `FULL`, or `LIMITED` for a leaked Ban ID (decision 8): read facts and the forfeiture notice only.

### 8. Leak rule

State: `punishment_case.ban_id_leaked_at bigint null` and `ban_id_leak_reason` (a code), set only by
the one-statement CTE below. "Leaked" is a property of the *Ban ID generation*, so a reissue clears it.

- **Marking:** `POST /case/{reference}/ban-id/leak` `{reason}` (`MODERATOR`; and the Discord bot role
  in the next change). `reason` is a code: `POSTED_PUBLIC`, `SHARED`, `FOUND`, `OTHER`; free text goes
  in a normal staff note. One CTE updates `punishment_case`, deletes the punishment's player sessions
  and appends `ban_id.leak_marked` (payload: `reason`, `source` = `STAFF` or `DISCORD_BOT`, `afterFiling`
  boolean; never the ID). Marking twice appends nothing.
- **Effects:**
  - Before an appeal exists: the right is **forfeited**. `GET /public/punishment` shows
    `appeal.state = FORFEITED`; `POST /public/appeal` answers 409 `FORFEITED`. The punishment still
    runs out or can be reviewed by staff on their own initiative; a staff `POST /appeal` is unchanged.
  - After filing: the ticket is **frozen**: the player side can no longer post (`POST /public/messages`
    409 `FROZEN`), the team still replies, reviews and decides. `appeal.state = FROZEN`.
  - Sessions of the leaked ID are deleted. A later login with that (valid) ID gets a `LIMITED`
    session: facts and the notice, **no conversation and no decision text** (whoever holds a leaked
    ID must not read the player's statements). The team can send the decision to the player over
    another path (staff, in game).
- **Reissue:** `POST /case/{reference}/ban-id/reissue` (`ADMIN`, `reason` code `NOT_PLAYERS_FAULT` or
  `OTHER`, mandatory). One CTE: generation + 1, new hash and ciphertext (computed in the service
  and passed in), clears the leak state, deletes sessions, appends `ban_id.reissued` (generation
  numbers only). Rights restored: an unfiled right becomes available, a frozen ticket thaws. The old
  ID is dead. The reveal route gives staff the new ID to pass on out of band.
- **Why `ADMIN` reissues, `MODERATOR` marks:** marking is a protective, reversible act; reissue
  restores a forfeited right and mints a credential.
- **Timeline:** `ban_id.leak_marked` and `ban_id.reissued` are lifecycle events with actor and
  trust like any other; `case.ban_id_leak_marked` and `case.ban_id_reissued` are published for the
  Discord side (ids and enums only).

### 9. One appeal per Ban ID

The portal files through the existing eligibility service but **does not record ineligible
attempts**: the portal first evaluates the checklist read-only and answers 409 with
`availableFrom` when the player may not file (before the minimum days, an open appeal, or the
repeat-appeal cooldown after a `DENIED`), and records only an eligible appeal. At most one appeal
is open per punishment (a unique partial index on open statuses). After `DENIED` the existing
`repeat-appeal-cooldown-days` applies, then a new appeal is possible on the same Ban ID (the right
is not consumed forever) and the conversation continues in the same case thread, tagged by
`appealId`. A `GRANTED_*` decision closes the right with the lift or reduction. Staff-driven
`POST /appeal` keeps recording `INELIGIBLE` rows as today.

### 10. The conversation model (shared with the Discord change)

Messages are case events. No new table.

| Type | Payload (`payload_version` 1) | `subject_id` | `visibility` |
|---|---|---|---|
| `message.added` | `messageId` (uuid), `appealId`, `author` `{kind: PLAYER, STAFF, BOT, EXTERNAL; participantId?}`, `channel` `{PORTAL, PANEL, DISCORD}`, `text`, `attachments[]` and `embeds[]` metadata (see next change), `externalRef?` | `messageId` | `PLAYER` or `STAFF` |
| `message.edited` | `messageId`, `text` (new full text), `externalRef?` | `messageId` | same as the original |
| `message.deleted` | `messageId`, `deletedBy` kind | `messageId` | same as the original |
| `message.redacted` | `messageId`, reason code | `messageId` | same as the original |

- **Author and trust:** a staff reply from the panel has `actor_oid` and trust `VERIFIED` (the token's
  `oid`, never a body field). A player message has no `oid`: `author.kind = PLAYER` and `actor_trust =
  PLAYER`: a fourth trust value, because `UNVERIFIED` means a legacy or unlinked staff actor and
  must not be reused for players (the column is text and the case-timeline design reserved a
  fourth value for this). The UI shows it as "player", never with a verified badge. `BOT` and `EXTERNAL` (a Discord
  participant) are used by the next change. Discord users are referenced by an opaque
  `participantId`, never by a Discord id in the event (next change, decision on identifiers).
- **Visibility:** `PLAYER` is shown in the portal; `STAFF` is not. The panel composer and the portal
  can only create `PLAYER` messages; the request has no visibility field and the server sets it.
  `STAFF` messages exist only for system noise mirrored from Discord. Staff-internal text is a *note*.
  The portal reader filters in SQL (`visibility = 'PLAYER'` and a type allow-list: messages, appeal
  filed/decided, leak/freeze/forfeit markers, ticket open and close), never in the mapper, so a new
  type is invisible to players until it is added to the list.
- **Edits and deletes (supported, append-only):** a new event; the original row is never changed.
  The current text is the latest `message.added`/`message.edited` of that `messageId`. Players
  cannot edit or delete in the portal (v1): a statement in an appeal must not be retractable. Edits
  and deletes come from Discord (next change) or from staff correcting a panel reply. A deleted
  message shows to the player as "message removed" with no text; staff see the text, marked deleted;
  the export keeps it. Redaction (decision 12) is the only way text disappears.
- **Ordering:** by `seq`, ties on `occurred_at` as for the timeline; the portal pages with the
  `seq` cursor and polls `GET /public/conversation?cursor=` every 15 s while the tab is visible
  (no websocket in v1).
- **Idempotency:** `dedupe_key` = `message:<messageId>` (portal and panel: server-generated uuid; a
  client `Idempotency-Key` header makes a double submit a no-op), edits `message.edited:<messageId>:<n>`.
- **Events:** `case.message_added`, `case.message_edited`, `case.message_deleted`, `case.message_redacted`
  with `{caseEventId, punishmentIdentifier, owner, seq, messageId, authorKind, channel, visibility,
  actorOid, occurredAt}`, never text. Published after the write in a try/catch.
- **Case status:** a player message on a `WAITING` case sets `IN_REVIEW`; filing an appeal reopens a
  `CLOSED` case (already specified). Both are `case.status_changed` by `SYSTEM`.

### 11. The decision reaches the player

The existing `decisionNote` stays internal. The review request gets an optional `playerMessage`
(1..2000, `PiiGuard`), appended as a `PLAYER`-visible staff `message.added` right after the decision
is recorded, and `GET /public/appeal` returns the decision enum plus, on grants, the new expiry.
A decision without a `playerMessage` shows only the enum and a neutral text. The portal never reads
`decisionNote`.

### 12. Retention, erasure, export

- **Retention (default):** a conversation lives exactly as long as its case and punishment, the same
  rule as evidence and notes. **Pending legal confirmation**, like the staff retention statement in
  `verified-actor`; this change adds no sweep.
- **Erasure:** `POST /case/{reference}/messages/{messageId}/redact` (`ADMIN`, reason code) sets the
  payload text (and attachment metadata) to null on every version of that message, keeps type, time,
  author kind and visibility, and appends `message.redacted`. `POST /case/{reference}/transcript/erase-player`
  (`ADMIN`) redacts every `PLAYER`-authored message of the case in one statement. Erasing a
  player or deleting the punishment record cascades the whole case (existing rule). Appeal
  statements stay in `appeals` under the existing erasure path.
- **Export (in v1, recommended):** `GET /case/{reference}/transcript?format=json|text` (`ADMIN`). It
  returns messages, edits, deletes and the lifecycle markers of the case in order, with visibility
  and redaction markers; notes are excluded unless `includeNotes=true`. Redacted entries export as
  markers, never text. Each export appends `transcript.exported` (actor, format, count, no content).
  It is small (a read over the existing timeline) and is what the team needs for a complaint or a
  data-subject request. `Cache-Control: no-store`, `Content-Disposition: attachment`.

### 13. Panel

The case page gets a **Conversation** section: thread (player and staff messages with author
labels: staff by name, player as "Player"), composer for `MODERATOR` (Ctrl+Enter, counter, 2000 cap, `PiiGuard` errors), a
banner for `FORFEITED` and `FROZEN`, a **Mark Ban ID leaked** dialog (`MODERATOR`, reason code), a
**Reissue Ban ID** dialog (`ADMIN`, reason), a masked Ban ID with a reveal button, and **Export
transcript** (`ADMIN`). The appeals queue shows "unanswered player message" and links to the case.
`VIEWER` reads the thread but gets no composer, no reveal, no export. German and English.

### 14. Tests (F.I.R.S.T.)

- Backend unit: injected `Clock`/`TestClock`, fixed `SecureRandom` seed, fake repositories; Ban ID
  normalisation table, generator collision retry and legacy-key rejection, HKDF/HMAC/AES round trip,
  session expiry (idle, absolute) on the test clock, state machine of the appeal summary,
  payload (de)serialisation incl. unknown version, `PiiGuard` with a Ban ID.
- Backend integration (Testcontainers PostgreSQL, `PostgresIntegrationTest`): the migrations and
  backfill (idempotent), unique index, the CTE writes (leak + sessions + event atomic; reissue),
  the routes with the self-signed JWT helper for staff and a test session for players, **log and
  event scan for the Ban ID**, the matrix tests extended both ways, the visibility filter proves a
  `STAFF` row never reaches `/public/conversation`.
- Portal (Vitest, fake backend client): the BFF allow-list rejects unknown routes, cookie flags,
  CSRF and Origin checks, no body logging, uniform login error, header set, i18n parity, component
  tests per page including keyboard use.
- Protocol: byte-identical regeneration from unchanged data (fixed `Clock`), digest and count recomputed from
  the JSON, author labels and the no-identifier scan, edit/delete/attachment/redaction rendering, idempotent
  versioning (same digest, concurrent runs), settle delay and `force` on the test clock, redaction purge with a
  failing regeneration, `TRANSCRIPT` rejected by the attach routes, not counted, not marked on lift/revoke,
  player copy without `STAFF` entries or staff names, audit on open and download (Testcontainers for the unique
  indexes and the purge statement, `InMemoryBlobStore` for files).
- No sleeps: polling is driven by a fake timer in tests; expiry by clock injection. A bug found
  while implementing is first reproduced by a failing test.

### 15. Docs and operations

`docs/` gets the portal deployment note (domain, env, required edge rate-limit rules, the key and
its backup, the no-body-logging requirement) and the Entra setup is untouched by this change.

### 16. The appeal protocol (stored with the case)

Requirement of 2026-10-23: when an appeal's conversation closes, it is **turned into a protocol and stored
with the case**, so the record outlives every channel (a Discord channel is deleted 30 days after it is
archived, see `add-discord-appeal-tickets`). The protocol is the same for every appeal whatever channel the
conversation used. It is not the ADMIN export of decision 12: the export is an on-demand, complete-case file
for complaints and data-subject requests; the protocol is automatic, stored, versioned and per appeal.

- **Source:** the backend generates it **only from `case_event`**, never from Discord or any other system
  (`AppealProtocolService` in `casetimeline/protocol/`), so it equals the record. Source set: the appeal's
  message events (all channels, visibility `PLAYER` and `STAFF`, with edits, deletes, redactions) and its
  lifecycle markers (`appeal.*`, `ban_id.*`, `ticket.*`). Notes are excluded.
- **Content:** one model, two renderings: a human-readable text document and JSON (`schemaVersion` 1). Per
  entry: `seq`, time, channel, author label and kind, trust, visibility, text, earlier versions with times,
  deletion time with the kept last text, attachment metadata (no bytes, no CDN URL), embed type and host, or a
  redaction marker. Players: the case's pseudonymous label (the JSON also carries the opaque participant id);
  staff: display name; unverified staff and other bots: their kind label. Never a Ban ID, Discord user id or
  owner hash. The header carries ticket and appeal ids, generation time and version; the footer is the
  **completeness check**: entry count, first and last `seq`, SHA-256 digest of the source (event id, `seq`,
  type, redaction state, in order) and, for a Discord ticket, the `ticket.transcript_complete` count and last
  message id. A reader can recompute count and digest from the JSON.
- **When:** on the appeal decision for an appeal without a Discord ticket; for an appeal with a Discord
  ticket, in the request that accepts the completeness proof (`POST /discord/tickets/{id}/close`), and
  only then. A protocol for an unverified Discord transcript is never made.
  A storage failure after a decision does not fail the decision (same rule as the evidence gateway: log, no
  content); `ProtocolReconciler` (interval `blackhole.protocol.reconcile-interval`, default PT5M) creates
  missing protocols.
- **Where it is stored: an evidence item of the new kind `TRANSCRIPT` (recommended and chosen).** Reasons:
  (1) the evidence capability already is the audited, access-controlled home for case material kept for as long
  as the punishment: the `BlobStore` (S3 by default), the `VIEWED`/`DOWNLOADED` audit, `nosniff`/sandbox/`no-store`
  serving, cascade deletion with the punishment, and the rule "lifetime = punishment record, no expiry sweep";
  (2) a case attachment would need a second blob path, access rule, audit and erasure route for the same job;
  (3) the case page already lists evidence. What is **not** reused, deliberately: upload sniffing, the malware
  scanner, image re-encoding, `PiiGuard` and the chat IP redaction. The content is generated by the backend and
  has no uploaded bytes (attachments are metadata only), and those guards would rewrite or reject the record.
  Consequences written into the evidence delta: `TRANSCRIPT` is system-only (attach routes answer 400),
  holds a file pair (text and JSON), is never marked `LIFTED`/`REVOKED` (the protocol of a lifted ban matters
  most), is not part of `evidenceCount`, and is capped by `blackhole.protocol.max-bytes`.
  The evidence package exposes one narrow `GeneratedEvidenceGateway.store(...)` for it; the protocol service
  uses nothing else of the package. No blocker was found in the evidence spec.
- **Index table** `appeal_protocol` (`V15`): `id`, `punishment_identifier` FK cascade, `appeal_id`, `version`,
  `evidence_id` FK cascade, `source_digest`, `entry_count`, `last_seq`, `status` (`ACTIVE`, `SUPERSEDED`,
  `PURGE_PENDING`), `created_at`; unique `(appeal_id, version)` and unique `(appeal_id, source_digest)`.
  The timeline records `protocol.stored` (STAFF visibility, no content) and the domain event
  `case.protocol_stored` is published after the write.
- **Idempotent and versioned:** generation computes the digest first. Same digest as the active version: no new
  version, the existing one is returned. Different digest: version `n+1`, the previous becomes `SUPERSEDED`
  (kept, so staff can see what the record said earlier). The unique digest index makes concurrent runs
  produce one version. Dedupe key `protocol:<appealId>:<digest>`.
- **Late changes:** the reconciler also regenerates when the digest of the live source differs from the active
  version (a late edit, a staff message after the decision, a Discord message in the archived channel) once
  the conversation was quiet for `blackhole.protocol.settle-delay` (PT1M, injected clock). Callers that must be
  current at once (the Discord deletion gate) call generation with `force`.
- **Access:** staff `VIEWER` and above list, open (rendered text) and download text or JSON from the case page,
  because they already read the thread; every use is audited. The conversation's `STAFF`-visibility entries are
  inside it, which is why the audit matters.
- **Players in the portal:** yes, but only the player-visible view. `GET /public/appeal/protocol?format=text|json`
  (FULL session, appeal decided) renders **on demand**, with the same query and allow-list as the portal
  conversation (visibility `PLAYER`, allow-listed types only), labelling the player "You" and staff "Moderation
  team", no staff names, no `STAFF` entries, attachments as "attachment (type, size)", `no-store`, attachment
  disposition. It is derived, not stored, so it is never stale and needs no redaction coupling. The stored staff
  protocol is never served to a player session.
- **GDPR:** the stored protocol holds redactable text, so redaction must reach it. Defined behaviour: whenever
  message text or author data is redacted or erased (message redaction, player-side erase, Discord participant
  erasure, staff anonymisation), the same SQL statement marks all protocol versions of the affected appeals
  `PURGE_PENDING` (unreadable at once), then a **new version is regenerated** with markers and anonymous
  authors, and the purged versions' files are deleted (`evidence.deleted`, reason `ERASURE`; audit entries
  stay, without content). Regeneration is retried by the reconciler, so a failure never undoes the redaction and
  never leaves old text readable. Deleting the punishment cascades protocol rows and files as for any evidence.
- **Not done:** no signing or hash chain beyond the digest in the footer and the `protocol.stored` event (a
  tamper-evidence design is a later change if wanted).

## Flyway migrations

- `V13__ban_id.sql`: `punishments.ban_id_hash bytea` (unique index, nullable until backfilled),
  `ban_id_enc bytea`, `ban_id_gen int not null default 1`; `punishment_case.ban_id_leaked_at bigint`,
  `ban_id_leak_reason varchar(16)`; `appeals.legacy_duplicate_open boolean not null default false` and
  a partial unique index on `appeals (punishment_identifier)` for open statuses that are not legacy
  duplicates. Existing rows are migrated by `BanIdBackfill` (application side, because it needs the key).
  The migration cannot fail on existing data: where a punishment already has several open appeals
  (nothing prevented that before), all but the newest by `created_at` get `legacy_duplicate_open = true`
  and fall outside the index. No appeal status changes, no history is lost; the flag only says "this
  open appeal predates the one-open rule", for staff to close by hand. The migration test seeds two
  open appeals on one punishment.
- `V14__player_session.sql`: `player_session` (token hash PK, punishment FK `ON DELETE CASCADE`, scope,
  csrf, times) and an index on `expires_at`.
- `V15__appeal_protocol.sql`: `appeal_protocol` (decision 16) and the new evidence kind `TRANSCRIPT` (extend the
  kind column check or enum of `evidence_items`, plus a second file key column for the file pair). New table and
  a widened check: no backfill; existing evidence rows are untouched.

## Risks / Trade-offs

- [40 bits is guessable] -> unique, uniform errors, infra rate limit as a documented requirement,
  failure metric; the exposure of a guessed ID is one ban's facts and appeal, not data of others.
- [A key loss makes all Ban IDs unusable] -> key backup stated, reissue job exists.
- [Velocity depends on a backend call for the screen] -> memory cache in the plugin; the screen
  degrades to no Ban ID, enforcement is unaffected.
- [Text in the log is a second copy of personal data] -> redaction chain, cascade, export audited.
- [Duplicate plumbing between `web/` and `portal/`] -> accepted for v1, revisit.
- [A leaked-ID holder sees punishment facts] -> they are the facts the real player may see; no
  conversation or decision text is exposed in `LIMITED` scope.
- [A stored protocol is a second copy of conversation text] -> redaction purges every version and regenerates
  (decision 16); it lives under the evidence cascade; staff access is audited.
- [Version churn from late messages] -> settle delay; superseded versions are cheap text; purged on redaction.
- [Polling every 15 s] -> cheap keyset read, cache headers `no-store`; push is a later change.

## Migration Plan

Deploy the backend (migrations, key set), run the backfill (automatic at start, observable by a
gauge `blackhole.banid.missing`), roll out Velocity with the Ban ID screen, then expose the portal.
Rollback: the portal can be switched off at the edge; the new columns are ignored by the old backend.

## Decisions (settled 2026-10-23)

These were open questions; the team decided:

1. **Portal host:** `appeal.onelitefeather.net` (decision 6).
2. **Session:** 30 minutes idle, 4 hours absolute (decision 4). Long statements are protected by the in-memory
   draft kept across the expiry page; no longer limits.
3. **Key rotation:** in v1 a new `BLACKHOLE_BANID_KEY` means "reissue all" (decision 2). Key ids come in a later change.

No open questions remain.
