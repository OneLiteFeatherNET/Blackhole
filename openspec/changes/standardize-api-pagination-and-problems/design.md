## Context

`audit.md` is the evidence for every claim here. Route numbers (#) refer to its endpoint
table. Backend base path: `backend/src/main/java/net/onelitefeather/blackhole/backend/`.

Constraints from `openspec/config.yaml` and the repository:

- Thin controllers, one service call per method, business logic in a `@Singleton` service,
  expected failures answered as the resource's `Error` variant (never thrown).
- `@Transactional` is unusable (bean ambiguity, see `.claude/PROGRESS.md` history and the
  memory note). Keyset queries must not rely on it.
- Flyway is append-only (`db/migration/V<n>__*.sql`). Main is at `V24__player_seen.sql`.
- Velocity and Discord build against the in-repo `client` module (`implementation(project(":client"))`).
- Ban IDs, raw player UUIDs and IPs never appear in a response body, a log, a metric label or
  a URL other than where they already appear. This extends to `detail`, `instance` and
  `nextCursor`.

## Decisions

### D1. Problem details library: in-house record and one exception handler

Recommendation: write a small in-house `ProblemDetail` record and one global exception handler
(`@Error(global = true)` handlers plus overrides for the security rejection handlers, the same
pattern as `security/GroupsOverageExceptionHandler.java`). Do not add `micronaut-problem-json`.

Reasons:

1. The version is unclear. The 4.10.2 platform catalog pins `micronaut-problem-json` 3.9.0,
   the docs page lists 5.1.0 as latest and does not say which Micronaut it targets (audit
   section 4). Adding a module whose version and BOM compatibility are unverified is a risk
   we can avoid.
2. Two behaviours we must control are not documented: how `HttpStatusException` renders, and
   whether the module's handler covers security rejections. The audit notes the module's
   `ThrowableProblemHandler` handles `ThrowableProblem` only, so `HttpStatusException` would
   keep the default body. That is the exact case that must change.
3. The required extension members (`code`, `availableFrom`, `backendCount`,
   `backendLastMessageId`, `errors`) and the uniform public 401 need exact control of the body.
   A record with `@JsonAnyGetter` extensions gives that without a framework default.
4. The sealed `Error` variants already carry `code`, `kind` and the extension data. The mapper
   is a switch on `kind` and `code`, so the framework module adds little.

Revisit condition: if the spike (task 1.1) shows that the module renders `HttpStatusException`,
security rejections and constraint violations correctly and its version resolves against
Micronaut 4.10.2 with no BOM conflict, prefer it. Record the result in the spike notes.

### D2. Problem type URIs

One scheme: `https://blackhole.onelitefeather.net/problems/<code-kebab>`.

- If the error has a machine `code`, the slug is the code in kebab case:
  `NOT_ELIGIBLE` gives `.../problems/not-eligible`, `TRANSCRIPT_MISMATCH` gives
  `.../problems/transcript-mismatch`.
- Errors without a code use the status kind: `.../problems/validation` (400),
  `.../problems/not-found` (404), `.../problems/conflict` (409), `.../problems/unauthorized` (401),
  `.../problems/forbidden` (403), `.../problems/unavailable` (503).
- The URIs are identifiers only. They need not be dereferenceable and must not be
  documented as live pages in this change.
- Clients branch on `code` (the existing values), never on `type`. `type` is for tooling and
  dashboards.

### D3. Problem body

```json
{
  "type": "https://blackhole.onelitefeather.net/problems/not-eligible",
  "title": "Not eligible",
  "status": 409,
  "detail": "The appeal window for this punishment has not opened yet.",
  "instance": "/public/appeal",
  "code": "NOT_ELIGIBLE",
  "availableFrom": "2026-11-01T10:00:00Z"
}
```

- `title` is a fixed string per type. `detail` is a fixed sentence per error, never echoing
  input. `instance` is the request path with no identifiers substituted (see below).
- Validation (400): `"errors": [{"field": "statement", "message": "must be at most 2000 characters"}]`.
  Messages are fixed templates, never the rejected value.
- `instance`: the request path. Routes whose path contains an identifier that is a Ban ID or a
  raw UUID or IP must not carry it. Those routes do not contain Ban IDs in the path today
  (`/punishment/{reference}/ban-id` uses the punishment reference). The conformance test checks
  this with a fixed fixture.
- Response content type is `application/problem+json`. Every problem response also keeps
  `Cache-Control: no-store` where the route already sets it (public routes, Ban ID routes).

### D4. Special cases (exact semantics)

- Uniform login error, `POST /public/session` (#91): one constant error (`INVALID_BAN_ID`, status
  401). Same `type`, `title`, `detail`, `code` and `instance` for unknown, unmatched, malformed
  and expired inputs. The framework 400 on that route is rewritten to the same body, as
  `PublicErrorFilter` does today. The `failure` metric tag stays. A leaked Ban ID still succeeds
  with scope `LIMITED` (no error, unchanged).
- Uniform 404 (wrong, unknown or leaked IDs): one body per route, identical for "not found" and
  "not permitted to see". `ban-id/leak` and `POST /punishment/lookup` keep their existing
  uniform 404.
- Public 401 that stops SSE reconnects (#101, `PublicLiveController:44-46`): same status, same
  no-retry behaviour, now a problem body. The SSE client's reconnect rule depends on the status,
  not the body; the test asserts the status.
- Security 401 and 403 (staff, public, proxy): the security layer produces problem bodies with
  `code` `UNAUTHENTICATED` / `FORBIDDEN` (new, or the existing `groups-overage` reason as
  `code: "GROUPS_OVERAGE"`), `detail` fixed. The `reason` field is removed.

### D5. Error mapping inside the backend

- Services keep returning the sealed `Error` variant. No change to service signatures.
- One mapper, `ProblemMapper`, turns an `ErrorResponse` (or `PublicError`) into a `ProblemDetail`
  plus the HTTP status. Controllers call `HttpResponse.status(...)` with the mapper's result,
  never building the body themselves (keeps `micronaut-controller-layer` thin rule).
- `ErrorResponse.errorMessage()` becomes `detail()` on the marker interface. `ErrorKind` keeps
  its `status()` mapping (`ErrorKind.java:11-43`), so no status changes.
- `profile/dto/PunishProfileResponse.java:16` (`ErrorResponse(message)`, non-conforming) is
  replaced by a sealed `Error` variant with `kind` and a `code`.
- Bare string and empty bodies (audit section 2) become problems. The status is unchanged.
- The stale javadoc in `response/ErrorResponse.java:6` is fixed in the same PR.

### D6. Cursor envelope

```java
public record CursorPage<T>(List<T> items, String nextCursor) {}
```

- Request: `limit` (default 50, clamped to 1..200; a value outside that range is a 400 problem
  with an `errors` entry, not a silent clamp, so clients see their mistake) and `cursor`
  (opaque string, optional).
- Repository fetches `limit + 1` rows. If `limit + 1` rows come back, `nextCursor` encodes the
  last returned row's sort key and `items` holds `limit` rows. Otherwise `nextCursor` is `null`.
- Sort keys are per route (table below). The last key is a unique tiebreaker. The tiebreaker is
  the route's public item identifier that the response already contains. It is never a Ban ID, a
  raw UUID, an IP or an internal database surrogate id (see D7).

### D7. Cursor codec: signed base64url

Recommendation: signed. Format:

```
nextCursor = base64url(payloadJson) + "." + base64url(HMAC-SHA256(key, payloadBytes))
```

- `payloadJson` is a compact JSON array of the sort-key values in order, with a version tag:
  `{"v":1,"k":[...]}`. Timestamps are ISO-8601 instants. Ids are the public identifiers.
- The key is `BLACKHOLE_CURSOR_KEY` (env var, `application.yml` with a dev default that is random
  per run, documented in a comment, same pattern as `BLACKHOLE_BANID_KEY` and `BanIdKeyGuard`).
  An unset key outside `dev`/`test` refuses to start.
- A cursor that fails verification, decodes wrongly or has the wrong number of keys is a 400
  problem (`code: "INVALID_CURSOR"`). It is never silently treated as the first page.

Why signed rather than plain base64url:

- The contract says the cursor is opaque. Signing makes that true: the client cannot craft a
  position, and the server rejects tampered values instead of returning a skewed window.
- A plain base64url cursor is not a security boundary (sort keys are visible to anyone who
  decodes them). Its only advantage is no key management.
- Cost: cursors die on key rotation. Clients recover by requesting the first page again. Cursors
  are short-lived (one browsing session). Accepted.

No authorization is attached to a cursor. The cursor only positions a query inside the
caller's already-authorized result set; each request re-runs the route's role check and filters.

### D8. Keyset queries without `@Transactional`

- Each list route gets one repository method with an explicit `ORDER BY` and a keyset
  predicate written out as `(a < :a1) OR (a = :a1 AND b < :b1)` (JPQL has no row-value
  comparison). Micronaut Data `@Query` with `Pageable.from(0, limit + 1)` and no `count` query.
- Filters are added as optional predicates in the same method. Use one method per route, not a
  generic dynamic-builder, so each query is inspectable.
- No `@Transactional` is added. Reads are single statements. Writes are not touched by this
  change.
- The spike (task 1.1) runs `EXPLAIN` against the local stack in `docker/docker-compose.yml`
  for the three heaviest candidates (`/evidence`, `/appeal`, `/report`) to decide whether
  indexes are needed. The database engine is named inconsistently: `CLAUDE.md` says MariaDB,
  `openspec/config.yaml` and `.claude/rules/database.md` say PostgreSQL. The spike confirms the
  engine from the compose file and the JDBC URL, and the index DDL follows that engine.

### D9. Indexes

Default: none. Create `V25__keyset_indexes.sql` (next free version at implementation time) only
if the spike shows a sequential scan on a keyset route at realistic row counts. Candidate
indexes, named after the existing table names at implementation time:

- `ix_appeals_submitted_id` on `appeals (submitted_at DESC, id DESC)`
- `ix_reports_created_id` on `reports (created_at DESC, id DESC)`
- `ix_punishments_created_ref` on `punishments (created_at DESC, reference DESC)`
- `ix_evidence_created_id` on `evidence (created_at DESC, id DESC)` (both `/evidence` and the
  per-punishment list)
- `ix_client_rule_hits_hit_id` on the hits table `(hit_at DESC, id DESC)`

Migration rules: append-only, no edit of applied migrations, no backfill (indexes only), and
`hbm2ddl=validate` is unaffected. The `database` rule and the `protect-flyway-migrations`
hook apply.

### D10. Route table (37 list routes)

Sort key column lists the keys in order; the last key is the public-identifier tiebreaker.
"Filters" lists the existing query parameters kept as-is. Nothing else changes names.
The framework `page`/`size`/`sort` are removed on every route marked **framework**.

| # | route | sort key(s) | filters (kept) |
|---|---|---|---|
| 4 | GET /cloudnet/catalogue | name ASC, serverId ASC | none |
| 5 | GET /discord/staff-links | linkedAt DESC, linkId DESC | none |
| 10 | GET /discord/link/{discordUserId} | linkedAt DESC, linkId DESC | none |
| 16 | GET /admin/discord-link | linkedAt DESC, linkId DESC | none |
| 19 | GET /discord/tickets | updatedAt DESC, ticketId DESC | state (repeatable). `listLimit` config replaced by `limit` |
| 24 | GET /discord/tickets/{id}/outbox | seq ASC | none. `afterSeq` replaced by `cursor` (same semantics) |
| 30 | GET /elo/{owner} | section ASC | none (fixed section set becomes `items`) |
| 32 | GET /elo/{owner}/history | recordedAt DESC, eventId DESC | section. framework `Pageable` removed |
| 35 | GET /evasion/{owner}/sightings | seenAt DESC, sightingId DESC | none. `truncated` retired (`nextCursor` replaces it) |
| 38 | GET /punishment/{reference}/evidence | createdAt DESC, evidenceId DESC | includeInactive. framework removed |
| 40 | GET /evidence | createdAt DESC, evidenceId DESC | owner, includeInactive. framework removed |
| 48 | GET /case/{reference}/evidence-shares | createdAt DESC, shareId DESC | none |
| 50 | GET /section | key ASC | none |
| 58 | GET /profile | updatedAt DESC, owner ASC | none. framework removed |
| 59 | GET /profile/{owner} | changedAt DESC, historyId DESC (history nested as `items`) | none |
| 61 | GET /case | lastActivityAt DESC, reference DESC | existing filters. `page`/`size` and totals retired |
| 63 | GET /case/{reference}/timeline | seq ASC (existing cursor semantics) | none. `size` replaced by `limit` |
| 70 | GET /case/{reference}/protocol | createdAt ASC, evidenceId ASC | none |
| 81 | POST /admin/import/vanilla | line order ASC | none (see open question 3) |
| 82 | GET /admin/import/punishments | count DESC, reasonKey ASC | none |
| 85 | GET /appeal | submittedAt DESC, appealId DESC | none. framework removed |
| 88 | GET /appeal-conditions/versions | version DESC | none |
| 90 | GET /dashboard/summary | escalatedAt DESC, escalationId DESC | none. fixed cap 10 becomes default `limit` (open question 3) |
| 98 | GET /public/conversation | seq ASC (existing cursor semantics) | none. server page size 200 becomes `limit` default 50 |
| 105 | GET /client-rule | key ASC | none |
| 111 | GET /client-rule/{key}/hits | hitAt DESC, hitId DESC | none. `limit`-only replaced by `limit`/`cursor` |
| 112 | GET /client-rule/hits | hitAt DESC, hitId DESC | owner. `limit`-only replaced |
| 114 | GET /client-rule/catalogue | key ASC | none (snapshot becomes `items`) |
| 115 | GET /auth/me | role ASC | none (one page, `nextCursor` always null) |
| 116 | GET /admin/staff-actor | createdAt DESC, actorId DESC | none |
| 123 | GET /admin/staff-link | linkedAt DESC, linkId DESC | none |
| 125 | GET /punishment/active | sort key from the existing whitelist, then reference | 11 existing params. `page`/`size`/`sort`/`direction` retired (sort whitelist kept as an enum) |
| 129 | GET /punishment | createdAt DESC, reference DESC | existing filters. framework removed |
| 130 | GET /punishment/short/{shortId} | createdAt DESC, reference DESC | none |
| 135 | GET /template | name ASC, identifier ASC | none. framework removed. Velocity loops pages (fixes page-0 bug) |
| 138 | GET /report | createdAt DESC, reportId DESC | reportedHash. framework removed |
| 141 | GET /report/feedback/{owner} | createdAt ASC, feedbackId ASC | none |

The Velocity and Discord readers of these routes are listed in `tasks.md` group 9 and 8.

### D11. Count route

Only where a UI needs a total: `GET /case/count`, `GET /punishment/active/count`,
`GET /evidence/count` are added on the same filters. Each returns `{"count": n}`. Not added for
any route unless its consumer asks (the web UI's pagers are the only known consumer and move to
cursor "next" navigation, so the count route is optional and off by default).

### D12. OpenAPI

- One `Problem` schema in `components/schemas` (type, title, status, detail, instance, plus
  `additionalProperties: true` for extensions). Every operation's error responses reference
  it with content type `application/problem+json`. The schema is documented once, not per route.
- Shared parameters `Limit` (integer, default 50, min 1, max 200) and `Cursor` (string) in
  `components/parameters`. Every list operation references them. The `pageable` parameter
  entries (spec lines 212, 2755, 2882, 3293, 3947, 4316, 4383, 4864) are removed.
- `CursorPage` schema: `items` (array) and `nextCursor` (string, nullable).
- Fixes from `audit.md` section 1 spec mismatches, in the same spec version:
  - `POST /admin/import/vanilla` is added to the spec (mismatch 1). Not left out.
  - The list responses are documented as `CursorPage`, not `type: array` or `Page` (mismatch 3).
  - The `X-API-VERSION` header is not added: nothing in the code reads it, and the controllers'
    `@Version("1")` remains the only versioning (mismatch 4, decided: documented as not present).
- Spec version `0.0.41`. `client/build.gradle.kts` `inputSpec` moves to it. `web/package.json`
  (currently pinned to the 0.0.39 spec, audit section 3 note) moves to the same version.

### D13. Clients

- **Web** (`web/src/api/`): one `ApiError` class read from the problem body
  (`title`, `detail`, `code`, `errors`). `http.ts` accepts `application/problem+json`. A
  `paginate(fetchPage)` helper follows `nextCursor` until `null` with a bounded loop. The old
  `pageContent`, `collectPages` and `MAX_PAGES` logic is deleted once every caller moves.
  Components are out of scope and belong to the `redesign-ux` change, which must consume
  `ApiError` and `paginate`.
- **Portal BFF** (`portal/server/lib/bff.ts`, `portal/src/api/contract.ts`): read `code` and
  `availableFrom` from the problem body (keeps `CONFLICT_CODES` and `FILE_REJECTION_CODES` as
  they are). `parseJson` accepts `application/problem+json`. A non-JSON error body still becomes
  503 as today (`bff.ts:157-163`), and the test asserts that a problem body is not mistaken for
  an outage.
- **Discord** (`discord/src/main/java/net/onelitefeather/blackhole/discord/backend/HttpBackendGateway.java`):
  `failed()` reads `code`, `availableFrom` (epoch ms) and `backendCount`/`backendLastMessageId`
  from the problem extension members. The `tickets()`, `staffLinks()`, `whoami()` and `outbox()`
  readers switch to `items`/`nextCursor`. `outbox()` keeps `afterSeq`-style semantics through the
  cursor.
- **Velocity** (generated client, `velocity/src/main/java/.../punish/HttpBackendGateway.java`,
  `section/BackendSectionSource.java`, `clientrule/HttpClientRuleBackend.java`): status branches
  unchanged. List readers switch to `items` and loop `nextCursor`. Templates loop every page
  (fixes the page-0 bug at `HttpBackendGateway.java:84-86`).

### D14. Tests (F.I.R.S.T.)

- Contract test (backend): for every route in the route table, every error response is
  `application/problem+json` with the required members (`type`, `title`, `status`, `detail`,
  `instance`). Uses a fixed fixture and no time reads. Named
  `EveryErrorResponseIsAProblemTest`.
- Route coverage test (backend): every list route takes `limit` and `cursor` and returns
  `{items, nextCursor}`. Reflects over the `*Api` interfaces (`micronaut-openapi-contract`)
  against an explicit list of 37 route names, so a new list route fails the test until it is
  listed. Named `EveryListRouteUsesCursorEnvelopeTest`.
- Codec unit tests: round-trip, tampered signature, wrong key count, `limit` out of range,
  malformed base64. No clock; no sleeps.
- Uniform-error tests: `POST /public/session` returns byte-identical bodies for every failure
  input (fixed fixture set), and the body contains no Ban ID.
- No-PII test: `detail`, `instance`, `nextCursor` and `errors` never contain a Ban ID, raw UUID
  or IP (extends `BanIdNoLeakTest` pattern). Logs captured with an appender, not read from stdout.
- Client tests: `web` and `portal` unit tests on `ApiError` and `paginate` (no network);
  Discord and Velocity unit tests against a fake HTTP server or the existing `FakeTicketBackend`
  pattern (no real backend).

## Risks

- Hard cut breaks old Velocity plugin binaries on list reads (see proposal). Mitigation: ship
  Velocity in the same release; open question 2.
- Keyset queries on `count DESC` (route 82) can shift between pages while the data changes.
  Accepted: the list is an admin view, and the tiebreak keeps pages stable within a session.
- Framework default body for 400/401/403 is still unverified (audit risk 11). The spike records
  the real bodies before the mapper is written.
- In-flight changes (`add-appeal-portal`, `add-discord-appeal-tickets`,
  `add-punishment-case-timeline`) define shapes that will be wrong after this change. Their tasks must be updated before they are applied.

## Standards added 2026-10-25 (D15-D20)

Decided by the user on 2026-10-25. They extend D1-D14 and change none of them. Route numbers (#)
refer to `audit.md`. Status codes stay the same, except the one stated in D17.

### D15. RFC 8288 `Link` header on paginated lists

Every list response that carries a non-null `nextCursor` also sends
`Link: </path?query>; rel="next"`. The body envelope `{items, nextCursor}` stays.

- The target is the request path plus the caller's query string with `cursor` replaced by the new
  value. Every other parameter (`limit`, filters, `state`, `owner`, `includeInactive`, `section`,
  `reportedHash`) is kept with its value. `limit` is always written out, even when defaulted.
- Path-absolute reference, no origin. A proxy must not rewrite it.
- `nextCursor` `null` means no `Link` header. No `rel="prev"` and no `rel="first"`: no route has a
  backward cursor, and adding one needs a second codec direction (out of scope).
- Set in one place: the helper that builds `CursorPage` (task 3b.2). No route builds the header itself.
- The cursor holds no Ban ID, raw UUID or IP (D7), so the header adds no identifier. The no-PII test
  (D14) covers the header too.
- Applies to all 37 routes of D10. Nothing in 0.0.41 parses the header: web and Velocity follow
  `nextCursor`. The portal BFF relays the header unchanged on `/api/punishment`, `/api/appeal` and
  `/api/conversation`.
- Migration: none. The spec documents `Link` as a response header on every list operation.

### D16. RFC 3339 timestamps everywhere

Every instant in a request body, a response body or a domain event payload is an RFC 3339 string in
UTC with a `Z` suffix (`2026-11-01T10:00:00Z`). Epoch milliseconds (`int64`) leave the wire. Field
names keep their spelling, so the change is a type change, not a rename. The DTO field type becomes
`Instant` (serialised with `Instant.toString()`, which ends in `Z`). Nullable instants stay nullable.

Rules:

- Durations are not instants and are not converted: `offsetSeconds` (`EvidenceShareLine`,
  `PublicConversationShareLine`) and retention lengths.
- A request that sends an epoch number for an instant (`EvidenceCreateRequest.capturedAt`) is a 400
  with an `errors` entry. No silent parsing of numbers.
- Clients parse with a strict parser at their edge. Web: one helper in `web/src/api/` (open
  S-Q1 decides how components consume it, because components are out of scope).
- `DomainEvent.occurredAt` (`events/DomainEvent.java:25`) becomes an RFC 3339 string. `payloadVersion`
  is unchanged. NATS subscribers that read `occurredAt` as a number break. The in-repo consumers are
  in `events/EventConsumers.java`; external consumers do not exist in this repo (verify in task 4f.3).

Inventory (sources: `client/specs/blackhole-api-0.0.40.yml` for `format: int64` and `format: date-time`,
and the DTO records for the rest). Line numbers refer to the 0.0.40 spec or the Java file.

| # | route or payload | field(s) | current | new |
|---|---|---|---|---|
| T1 | Discord ticket 409 `NOT_YET` (`POST` ticket routes, #18-28) | `DiscordTicketError.availableFrom` (`discord/dto/DiscordTicketDTO.java:317`) | epoch ms, int64 | RFC 3339 string |
| T2 | Discord ticket (#18-28) | `DiscordTicketDTO` `createdAt` (`:95`), `editedAt` (`:225`), `occurredAt` (`:273`); `DiscordTicket` `createdAt`, `updatedAt` (`discord/ticket/DiscordTicket.java:31-32`) | epoch ms | RFC 3339 |
| T3 | Discord outbox (#24) | `DiscordOutboxItem.occurredAt` (spec 6965) | int64 | RFC 3339 |
| T4 | Discord ingest (#6-7, #22) | `DiscordIngestItem.createdAt`, `editedAt` (spec 6638-6642) | int64 | RFC 3339 |
| T5 | Discord notifications (#6-7) | `DiscordAppealNotification` `createdAt`, `updatedAt` (spec 6553-6556); `DiscordReportNotification` `createdAt`, `updatedAt` (spec 7017-7020; `discord/dto/DiscordNotificationDTO.java:38`, `:58`) | int64 | RFC 3339 |
| T6 | Discord link (#9-16) | `DiscordLinkCodeResponse.expiresAt` (spec 6768; `discord/dto/DiscordLinkDTO.java:59`); `DiscordLinkConfirmResponse.linkedAt` (6791); `DiscordLinkItem.linkedAt` (6822); `DiscordLinkStatusResponse.linkedAt` (6860, nullable); `DiscordLinkWhoamiEntry.linkedAt` (6875); `DiscordStaffLink.linkedAt` (7031) | int64 | RFC 3339 (nullable where nullable) |
| T7 | Staff link (#118-124) | `StaffLinkCodeResponse.expiresAt` (spec 9408; `security/dto/StaffLinkDTO.java:44`); `StaffLinkConfirmResponse.linkedAt` (9437); `StaffLinkItem.linkedAt` (9466); `StaffLinkStatusResponse.linkedAt` (9507) | int64 | RFC 3339 |
| T8 | Staff actors (#116-117) | `StaffActorItem.firstSeenAt`, `lastSeenAt` (spec 9385-9388; `security/dto/StaffActorDTO.java:42`) | int64 | RFC 3339 |
| T9 | Case (#61-64, #68-80) | `CaseListItem.punishmentCreatedAt`, `lastActivityAt` (spec 5423-5426); `CaseResponse.punishmentCreatedAt` (5686), `banIdLeakedAt` (5711); `CaseTimelineItem.occurredAt` (5752), `redactedAt` (5775); `CaseRelated.createdAt` (5654); `CaseProtocolVersion.createdAt` (5613; `casetimeline/dto/CaseProtocolDTO.java:34`) | int64 | RFC 3339 |
| T10 | Punishments (#125-135) | `ActivePunishmentItem.createdAt`, `expiresAt` (spec 5060-5063; `punishment/dto/ActivePunishmentDTO.java:56-57`); `PunishEntryDTO.createdAt` (spec 8544); Java `creationDate`/`updateDate`/`expirationDate` (`punishment/dto/PunishEntryDTO.java:118-128`); `PunishProfileDTO.creationDate`/`updateDate` (`profile/dto/PunishProfileDTO.java:45-50`); `PunishmentEvidenceDTO.creationDate`/`updateDate` (`punishment/dto/PunishmentEvidenceDTO.java:62-67`) | int64 | RFC 3339 (Java accessor names kept, serialised names as before) |
| T11 | Appeals (#84-86) | `AppealDTO.createdAt`, `updatedAt` (spec 5191-5194; `appeal/dto/AppealDTO.java:31-32`); `AppealReviewDTO.newExpirationAt` (spec 5215; `appeal/dto/AppealReviewDTO.java:26`) | int64 | RFC 3339 |
| T12 | ELO (#29-32) | `EloEventResponse.createdAt` (spec 7469; `elo/dto/EloDTO.java:79`) | int64 | RFC 3339 |
| T13 | Evasion (#33-35) | `EvasionLinkedOwner.firstSeen`, `lastSeen` (spec 7577, 7582); `EvasionSightingDTO.firstSeen`, `lastSeen` (`evasion/dto/EvasionSightingDTO.java:29-30`, documented as epoch millis) | int64 | RFC 3339 |
| T14 | Evidence (#36-49) | `EvidenceResponse.capturedAt`, `createdAt`, `statusChangedAt` (spec 7819-7854; `evidence/dto/EvidenceDTO.java:54`, `:134-144`); `EvidenceCreateRequest.capturedAt` (spec 7689; request, optional); `EvidenceShare.sharedAt`, `revokedAt` (spec 7892-7898; `evidence/share/EvidenceShareDTO.java:107-109`) | int64 | RFC 3339 |
| T15 | Reports (#137-142) | `ReportDTO.createdAt`, `updatedAt` (spec 8996-8999; `report/dto/ReportDTO.java:49-50`); `ReportFeedbackResponse.createdAt`, `expiresAt` (spec 9059-9062; `report/feedback/dto/ReportFeedbackDTO.java:39-40`) | int64 | RFC 3339 |
| T16 | Dashboard (#90) | `DashboardEscalation.createdAt` (spec 6477); `DashboardDTO` `createdAt` (`dashboard/DashboardDTO.java:32`) | int64 | RFC 3339 |
| T17 | Client rules (#105-114) and CloudNet (#4) | `ClientRuleResponse.createdAt`, `updatedAt` (spec 6381-6384); `ClientRuleHit.at` (6227); `ClientRuleCatalogueResponse.reportedAt` (6099), `ClientRuleCatalogueStored.reportedAt` (6133); `CatalogueResponse.reportedAt` (5872), `CatalogueStored.reportedAt` (5933) | already `date-time` (`Instant`) | no change; a test asserts the `Z` suffix |
| T18 | Public API (#91-104) | `PublicAppealDTO.Status`, `PublicPunishmentDTO.AppealSummary.availableFrom`, `PublicSessionDTO` `expiresAt`, `PublicConversationDTO.Entry` `occurredAt`, public punishment expiry (built with `Instant.toString()`, e.g. `publicapi/service/PublicAppealService.java:192`, `publicapi/service/PlayerSessionService.java:99`) | already RFC 3339 | no change; keep the test that asserts `Z` |
| T19 | Appeal conditions (#87-89) | `AppealConditionsDTO.Response.createdAt` (`appealconditions/service/AppealConditionsService.java:86`, `Instant.ofEpochMilli(...).toString()`) | already RFC 3339 | no change |
| T20 | Domain event envelope | `DomainEvent.occurredAt` (`events/DomainEvent.java:25`); payload maps built by `punishment/PunishmentEvents.java`, `evidence/EvidenceEvents.java`, `live/LiveEvents.java` | epoch ms, long | RFC 3339 string; task 4f.3 audits every payload key ending in `At`, `Date`, `Until`, `Expires` or `Seen` |

Not converted: `version`, `seq`, `ticketNo`, `size`, `count`, `offsetSeconds`, `relayedSeq`. The
Redis expiry written by `punishment/PunishmentRedisWriter.java:36-43` is an internal cache value, not
an HTTP body, and stays epoch ms. Task 4f.1 checks that `retentionExpiresAt`
(`punishment/PunishmentEvidenceEntity.java:39`) is not serialised.

Affected clients: web (`web/src/api/blackhole.ts` types and a parse helper), portal BFF
(`portal/server/lib/bff.ts` reads `availableFrom` as ISO on every route after the change), Discord
(`discord/.../HttpBackendGateway.java:188` `asLong` becomes `Instant.parse`), Velocity (generated
types for active punishment expiry), and the regenerated Java client.

Migration: the hard cut of this change. No Flyway migration: values are converted at the API edge and
keep their storage. Clients built against 0.0.40 fail on the type change, which is intended.

### D17. RFC 9110 conditional requests for optimistic locking (`ETag`, `If-Match`)

A resource with a version counter returns `ETag` on its read and requires `If-Match` on every update.
The strong entity tag is the quoted decimal version, `"<version>"`. A stale `If-Match` returns
`412 Precondition Failed` (RFC 9110 section 15.5.13), a problem with `code: PRECONDITION_FAILED`. A
missing `If-Match` on such a route returns `428 Precondition Required` (RFC 6585), a problem with
`code: PRECONDITION_REQUIRED`. `If-Match: *` is refused with the same 428, because the caller must name
the version it read.

Resources (the full list asked for; "verify" marks a check the implementation must make before the
row is final):

| # | resource | evidence in code | ETag in 0.0.41 |
|---|---|---|---|
| E1 | Case metadata: `GET /case/{reference}` and `PATCH /case/{reference}` (`CaseApi.updateCase`, spec 489-525) | `casetimeline/service/CaseService.java:192-209` compares `expectedVersion` with `CaseReader.CaseRow::version`; stale returns 409; `CaseDTO.java:29-32` documents it; the response carries `version` (spec 5704) | YES (the only route whose status changes: 409 becomes 412) |
| E2 | Discord appeal protocol version: close with `protocolVersion` (`discord/ticket/TicketService.java:254`, `DiscordTicketStore.java:133` `archive(id, protocolVersion, ...)`), `protocol/ensure` answers `result.version()` (`TicketService.java:322`, `:357`) | the version is a body field of an action | NO (S-Q3). It versions the appeal protocol, not the ticket row. |
| E3 | Case message edit, delete and redact (`casetimeline/service/CaseConversationService.java:239`, `:265`, `:466-491`) | 409 "changed, deleted or redacted in the meantime" exists; no message version field found in the audit | verify in task 4g.0; YES only if a message version exists |
| E4 | Case notes (`casetimeline/service/CaseService.java:259-278`) | every edit appends a `note.edited` version | NO (append-only history, not a lock) |
| E5 | Appeal conditions (`appealconditions/JdbcAppealConditionsStore.java:26-27`) | `POST` inserts `max(version)+1`; 409 on the number race (`appealconditions/controller/AppealConditionsApi.java:54`) | NO. The create is a POST and takes `Idempotency-Key` (D18). Its 409 race stays. |
| E6 | Evidence items and punishment evidence (`evidence/EvidenceItemEntity.java`, `statusChangedAt` at `:102`) | no version column found | NO (verify no version column) |
| E7 | Appeal (`appeal/dto/AppealDTO.java:31-32`, `updatedAt`) | state machine guards changes; no version found | NO (verify) |

Decision on the body `version`: `expectedVersion` is removed from every request body. It is required
today on `CaseUpdateRequest` (spec 5805), so the `If-Match` header replaces it. `version` stays as a
read-only member of `CaseResponse` (spec 5704), because the UI shows it. The `ETag` is derived from it.
The web UI (`web/src/components/case/CaseHeader.vue` and `web/src/api/blackhole.ts`, which send
`expectedVersion` today) reads the `ETag` from the GET and sends `If-Match`. No other client uses
`expectedVersion` (grep: `web/` only, plus old specs). The field is removed in the same release (hard
cut), not deprecated.

Problem bodies: `412` type `.../problems/precondition-failed`, title "Precondition failed", fixed detail.
`428` type `.../problems/precondition-required`, code `PRECONDITION_REQUIRED`. Both responses repeat the
current `ETag` header so a client can re-read without a second request. The change to E1 from 409 to 412
is the one status-code change in this change; the proposal and the PR body must say so.

Migration: none. `version` already exists in `CaseReader.CaseRow`.

### D18. `Idempotency-Key` on every creating POST

Standard: `draft-ietf-httpapi-idempotency-key-header`. The latest revision confirmed on the IETF
Datatracker in this session is `-07` (October 2025). A 2026 revision may exist and was not confirmed.
The implementation PR names the revision it follows (S-Q2).

Existing state that the standard unifies:

- Public message post: `publicapi/controller/PublicApi.java:176-194` takes `@Header("Idempotency-Key")`;
  `publicapi/controller/PublicController.java:107` passes it; `publicapi/service/PublicConversationService.java:92-117`
  forwards it to `CaseConversationService`.
- Case messages: `casetimeline/CaseDrafts.java:84` derives the message id from the key;
  `casetimeline/CaseChanges.java:75` uses it as the dedupe key.
- Discord ingest: `discord/ingest/JdbcDiscordIngestStore.java` and `DiscordIngestStore.java` use a
  `dedupeKey`.
- Web: `web/src/api/blackhole.ts:799-802` sends the header on `postCaseMessage`.
- Portal BFF: `portal/server/lib/bff.ts:51` (`IDEMPOTENCY_SHAPE`, `^[A-Za-z0-9-]{8,64}$`) and `:261-262`
  forward the header on state-changing routes, and silently drop a malformed key. After this change the
  BFF forwards the value unchanged and the backend answers 422.

Decision: the domain dedupe keys (`case_event` dedupe, Discord ingest `dedupeKey`) stay as the last line
of defence. One HTTP-level store is added for every creating POST and replays the first response.

Routes (controller names from the packages; each task names the exact file after checking the `*Api`
interface, because route paths are not all confirmed in this session):

| # | route | package | notes |
|---|---|---|---|
| I1 | `POST /punishment` (punishment create) | `punishment/controller/` (#126-131) | verify the path |
| I2 | `POST /report` (#137) | `report/controller/ReportController.java` | report submit keeps its in-app limiter (`ReportService.java:201`) |
| I3 | evidence upload (#36-44, `POST` on `/evidence` and on `/punishment/{reference}/evidence`) | `evidence/controller/EvidenceController.java` | multipart; fingerprint per part |
| I4 | chat file upload (#45, `ChatAttachmentController`) and public attachment upload (#102-104, `publicapi/controller/PublicAttachmentController.java`) | `evidence/`, `publicapi/controller/` | staged files; fingerprint includes the file SHA-256 |
| I5 | case and public messages, notes (#73-80, #91-100) | `casetimeline/controller/CaseConversationController`, `publicapi/controller/PublicController.java:107` | already keyed; move to the store, keep the dedupe key |
| I6 | appeal filing (#84-86 and the public appeal route, #91-100) | `appeal/controller/AppealController`, `publicapi/controller/PublicController` | verify the path |
| I7 | Discord ticket open (#18-28) | `discord/controller/DiscordTicketController` (`DiscordTicketApi`) | the bot retries after a network drop |
| I8 | imports (#81 `POST /admin/import/vanilla`, #82-83) | `imports/controller/VanillaImportController.java`, `imports/controller/ImportedPunishmentController` | an import re-run is already guarded by `alreadyImportedFromVanilla`; the store prevents a second read of the file |

Not keyed: `PATCH` and `PUT` routes, `DELETE`, `protocol/ensure` (idempotent by design,
`TicketService.java:288`), login `POST /public/session` (uniform 401, no key), and `POST /punishment/lookup`
(a read).

Storage: one new table, one Flyway migration, `V<next free>__idempotency_keys.sql`, append-only.
The next free version is taken at implementation time, since `V25` may be used by the keyset indexes
(task 4e), so whichever lands second takes the next number.

```sql
CREATE TABLE idempotency_key (
  id                  UUID PRIMARY KEY,
  caller_hash         VARCHAR(64)  NOT NULL,   -- HMAC of the caller: staff token oid, or public session token hash
  route               VARCHAR(128) NOT NULL,   -- e.g. 'POST /report'
  idem_key            VARCHAR(128) NOT NULL,   -- header value after the shape check
  request_fingerprint VARCHAR(64)  NOT NULL,   -- SHA-256 of the canonical body (per part for multipart)
  response_status     INTEGER,                 -- NULL while the first request is in flight
  response_type       VARCHAR(128),
  response_body       TEXT,
  created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at          TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uq_idempotency_key UNIQUE (caller_hash, route, idem_key)
);
CREATE INDEX ix_idempotency_key_expires ON idempotency_key (expires_at);
```

Rules:

- Scope is key, caller and route. The same key from another caller or on another route is a different request.
- The caller is never a client field: staff is the token `oid`; public is the HMAC of the session token hash
  (the `BanIdKeyGuard` pattern). No raw UUID, IP or Ban ID is stored.
- The fingerprint is the SHA-256 of the canonical request.
- TTL 24 hours. Expired rows are ignored on read; a scheduled job in the `database/` package deletes them.
  The job uses no `@Transactional`.
- Replay: same key, caller, route and fingerprint returns the stored status and body and sets
  `Idempotent-Replayed: true`.
- Only 2xx responses are stored. A 4xx (validation) is not stored, so a corrected request can reuse the key.
- Stored body: it is what the caller already received, so it adds no new disclosure. It is deleted at TTL.

Errors (Problem Details):

- `422` `code: IDEMPOTENCY_KEY_INVALID`: the header is empty, longer than 128 characters, or contains
  characters outside the draft's allowed set. The BFF used to drop this silently; now it is relayed.
- `409` `code: IDEMPOTENCY_KEY_REUSED`: the key exists for this caller and route with a different fingerprint.
- `409` `code: IDEMPOTENCY_KEY_IN_FLIGHT`: the first request has no stored response yet (S-Q4).

Migration: append-only table, no backfill. The domain dedupe keys keep working. The header name does not
change, so web and portal keep sending it.

### D19. RFC 6585 `429` and `Retry-After` on the clients

Rate limits stay in the infrastructure (`openspec/config.yaml` principle 5). The only in-app limiter is
report submission (`report/service/ReportService.java:201`). Clients must honour `429` and `Retry-After`
(RFC 9110 section 10.2.3, delay-seconds or HTTP-date).

| client | location | behaviour |
|---|---|---|
| Discord bot | `discord/src/main/java/net/onelitefeather/blackhole/discord/backend/HttpBackendGateway.java` | on 429, wait `Retry-After` capped at 60 s, then retry once. A POST is retried only when it carries an `Idempotency-Key`. |
| Portal BFF | `portal/server/lib/bff.ts` | relays `429` and `Retry-After` to the browser unchanged, no server-side retry. A 429 problem is not an outage (not a 503). |
| Web UI | `web/src/api/http.ts` | on 429, wait `Retry-After`, retry GET once. A POST is retried only with a key. The UI shows the wait from `ApiError`. |
| Velocity | `velocity/src/main/java/net/onelitefeather/blackhole/velocity/punish/HttpBackendGateway.java`, `section/BackendSectionSource.java`, `clientrule/HttpClientRuleBackend.java` | a 429 counts as "try again on the next poll"; the section keeps its last known value. The `Retry-After` value is read only if the generated `ApiException` exposes response headers. |

Edge-optional: the IETF `RateLimit` and `RateLimit-Policy` fields (`draft-ietf-httpapi-ratelimit-headers`, revision
to verify at implementation) are set by the edge proxy, not by the app. The app does not emit them in 0.0.41.

Spec: `429` with `Retry-After` and a problem body (`code: RATE_LIMITED`) is documented as a shared response on
every operation the edge limits. Migration: none for data; clients ship the handling with the backend cut.

### D20. OpenAPI 3.1 and JSON Schema 2020-12

Feasibility (checked 2026-10-25):

- Java client: `openapi.generator` `7.26.0` (`settings.gradle.kts:55`), generator `java`
  (`client/build.gradle.kts:21`), input `client/specs/blackhole-api-0.0.40.yml` (`:23`). No verified 3.1 support
  for the Java generator was found. Not assumed.
- Web: `openapi-typescript` `^7.13.0` (`web/package.json:38`), `generate:api` reads `blackhole-api-0.0.39.yml`.
  openapi-typescript 7.x documents 3.1 input. Expected to work; verified by the spike.
- Backend annotation processor: `io.micronaut.openapi` (`backend/build.gradle.kts:10`). No release note found that
  confirms 3.1 output. Not the gate: the client spec is authored in `client/specs/`.

Decision: planned but gated by a spike (task 5.4). A copy of 0.0.41 converted to 3.1 is validated, then
generated with `:client:openApiGenerate` and passed to `openapi-typescript`. If both pass, 0.0.41 ships as 3.1.
If the Java generator fails, 0.0.41 stays on `openapi: 3.0.3` and the move is deferred to a follow-up change
(`openapi-31-client`) with the generator error recorded in D20. The default is `3.0.3`.

When the move happens: JSON Schema 2020-12 dialect; `nullable: true` becomes `type: [<t>, "null"]`;
`exclusiveMinimum` is a number; `example` becomes `examples`.

## Decisions (2026-10-25)

- **S-Q1, instants in the web client.** `parseInstant(value: string): Date` lives in
  `web/src/api/blackhole.ts`. That file maps every RFC 3339 instant field to a `Date`, so no component
  takes an epoch number and no component file changes for this reason. The separate `instant.ts` helper is dropped.
- **S-Q2, Idempotency-Key revision.** Implement the revision the IETF Datatracker lists as current on the day
  task 4h starts. The PR names its number (task 4h.7). Check on that day whether the value must be a quoted
  sf-string, and change the clients in the same PR if it must.
- **S-Q3, Discord protocol version.** No ETag. `protocolVersion` stays a body field on the close action (E2).
- **S-Q4, in-flight idempotent request.** `409` with `code: IDEMPOTENCY_KEY_IN_FLIGHT` and `Retry-After: 1`.
  The retry uses the same key. Covered by a new scenario in `specs/api-idempotency/spec.md`.

## Open Questions

The numbered questions elsewhere in this change (open questions 2, 3 and 5 in the proposal and tasks) predate
these standards and keep their numbers. The four S-Q questions for D15-D20 are resolved as recorded above:

- **S-Q1:** resolved, `parseInstant` in `web/src/api/blackhole.ts`, no component change.
- **S-Q2:** resolved, the revision current on the Datatracker on the day task 4h starts.
- **S-Q3:** resolved, no ETag; `protocolVersion` stays a body field.
- **S-Q4:** resolved, `409 IDEMPOTENCY_KEY_IN_FLIGHT` with `Retry-After: 1`.
