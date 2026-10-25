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
