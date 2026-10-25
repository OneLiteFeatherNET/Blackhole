## Why

The backend API has three incompatible list shapes (Micronaut `Page`, bare arrays, and
ad-hoc cursor or `limit` parameters) and at least five incompatible error bodies
(`errorMessage`/`kind`, `code`/`availableFrom`, `{"reason":...}`, plain strings, and empty
bodies). `audit.md` in this change counts 37 list routes, of which 24 have no pagination
at all, and 142 endpoints with no RFC 9457 problem anywhere in `backend/`, `web/`,
`portal/server/`, `velocity/` or `discord/`.

Clients therefore each carry their own shape knowledge. The web UI silently shows an empty
list when a wrapper changes, Velocity reads only page 0 of templates, and the portal and
Discord bot branch on ad-hoc `code` strings in the body. A single list envelope and a single
error format make the contract checkable by one contract test and remove the per-client
guesswork.

## What Changes

**Pagination (BREAKING)**

- Every list route (37, see `design.md` for the table) takes `?limit=` and `?cursor=` and
  returns `{items, nextCursor}`. `nextCursor` is opaque and `null` on the last page.
- Default `limit` is 50, maximum 200. Filters and sorting run on the server.
- A total count is offered only through a separate count route, and only where the UI
  needs it (the existing `/punishment/{reference}/evidence/count` pattern).
- Existing cursors (`seq` on the case timeline, the Discord outbox and the public
  conversation) keep their semantics and use the same envelope.
- The `page`, `size`, `sort` and `totalPages` parameters and fields are removed from the
  framework `Pageable` routes and the custom `/case` and `/punishment/active` routes.

**Errors (BREAKING)**

- Every error on every route is `application/problem+json` (RFC 9457): `type`, `title`,
  `status`, `detail`, `instance`, plus extension members.
- Machine codes move to the extension member `code`, with the same values as today
  (`NOT_ELIGIBLE`, `FROZEN`, `FORFEITED`, `NO_APPEAL`, `NOT_DECIDED`,
  `CONDITIONS_CHANGED`, `TRANSCRIPT_MISMATCH`, `PROTOCOL_REQUIRED`, `QUOTA`, `TOO_LARGE`, ...).
- Extra data becomes extension members with the same names and types as today:
  `availableFrom` (ISO string on public routes, epoch milliseconds on Discord routes),
  `backendCount` and `backendLastMessageId`.
- Special cases keep exact semantics: the uniform 401 on `POST /public/session`, the uniform
  404 for wrong, unknown or leaked IDs, and the public 401 that stops SSE reconnects.
- Security 401 and 403, and framework 400 validation errors, also become problems.
  Validation errors list fields in an `errors` extension. No Ban ID, raw UUID or IP appears
  in `detail` or `instance`.

**Hard cut**

- No dual format and no `@Version("2")` compatibility window. The old list and error shapes
  are removed in the same release as the new ones.
- Panel (`web/`), portal BFF (`portal/server`) and the Discord bot (`discord/`) switch in the
  same release as the backend.
- The generated Java client moves to a new spec version, `client/specs/blackhole-api-0.0.41.yml`
  (the next free version at implementation time; main is at 0.0.40). The client
  `inputSpec` in `client/build.gradle.kts` is bumped with it.

**Documentation and config**

- `openspec/config.yaml` says "Micronaut 5" in the stack line. The build pins Micronaut
  4.10.2 (`gradle.properties`). This change corrects the stack line to 4.10.2.

**Standards added 2026-10-25 (user decision; design D15-D20)**

- **RFC 8288 `Link`**: every list response with a `nextCursor` also sends `Link: <url>; rel="next"`,
  keeping the filters and `limit`. The body envelope is unchanged.
- **RFC 3339 timestamps**: every epoch-millis instant in a request body, response body or domain
  event becomes an RFC 3339 UTC string (`Z`). Field names stay. The inventory is design D16 (T1-T20).
  BREAKING for every client that reads those fields as numbers, including `DomainEvent.occurredAt`
  on NATS.
- **RFC 9110 conditional requests**: the case (`GET`/`PATCH /case/{reference}`) returns `ETag` and
  requires `If-Match`. A stale version is `412 PRECONDITION_FAILED` (was 409, the one status change in
  this change); a missing `If-Match` is `428 PRECONDITION_REQUIRED`. The body `expectedVersion` is
  removed; `version` stays read-only in responses. Design D17 lists the other resources checked (E1-E7).
- **Idempotency-Key** (`draft-ietf-httpapi-idempotency-key-header`, latest confirmed revision -07): every
  creating `POST` (I1-I8) replays its first 2xx response for 24 hours. New table `idempotency_key` in one
  append-only Flyway migration, next free version. Errors: 422 `IDEMPOTENCY_KEY_INVALID`, 409
  `IDEMPOTENCY_KEY_REUSED`, 409 `IDEMPOTENCY_KEY_IN_FLIGHT`. Design D18.
- **RFC 6585 `429` and `Retry-After` on clients**: Discord, portal BFF, web and Velocity honour them.
  Rate limits stay in the infrastructure, with no new app limiter. The IETF `RateLimit` headers are
  edge-optional. Design D19.
- **OpenAPI 3.1 and JSON Schema 2020-12**: planned, gated by a spike (task 5.4). The web tooling
  (`openapi-typescript` 7.x) is expected to accept 3.1. The Java generator (`openapi.generator` 7.26.0) has
  no verified 3.1 support, so 0.0.41 stays on 3.0.3 unless the spike passes. Design D20.

Kept as decided: cursor pagination, RFC 9457 problems, the hard cut, and Velocity shipping with the backend.

## Commit type and scope

This change ships as `feat(api)!` with a `BREAKING CHANGE:` footer. The implementation is
split into stacked PRs, each carrying one Conventional Commits type and a per-package scope
(see `tasks.md`). The archive and this proposal ship as `docs(openspec)`.

## Impact

- **Backend**: `backend/src/main/java/net/onelitefeather/blackhole/backend/**` (every
  controller and `*Api` interface, every `ErrorKind`/`Error` mapping, `publicapi`
  `PublicErrorFilter`, the security exception handlers). New `CursorPage`, cursor codec,
  problem model and one `ExceptionHandler`.
- **Database**: no migration unless the spike shows keyset queries need indexes. If so,
  `V25__keyset_indexes.sql` (next free after `V24__player_seen.sql`), append-only. Also one
  append-only migration for `idempotency_key` (D18), at the next free version at implementation time.
- **Client**: `client/specs/blackhole-api-0.0.41.yml`, regenerated `client/` sources.
- **Web**: `web/src/api/http.ts`, `blackhole.ts`, `uploadEvidence.ts`, `caseEvents.ts`.
  Components belong to the `redesign-ux` change and are not touched here.
- **Portal**: `portal/server/lib/bff.ts`, `portal/src/api/contract.ts`.
- **Discord**: `discord/src/main/java/.../discord/backend/HttpBackendGateway.java` and
  `TicketLifecycle` (the `TRANSCRIPT_MISMATCH` rescan) and `AppealCommands` (`NOT_YET`).
- **Velocity**: `velocity/src/main/java/.../punish/HttpBackendGateway.java`,
  `section/BackendSectionSource.java`, `clientrule/HttpClientRuleBackend.java`.
- **In-flight changes**: `add-appeal-portal`, `add-discord-appeal-tickets` and
  `add-punishment-case-timeline` define error and list shapes. They must adopt the problem and
  envelope shapes before they are applied, or be re-proposed.

### Velocity safety check (result)

The request said Velocity "only branches on the HTTP status, so it is safe". Checked against
the code and `audit.md` section 3:

- Status branches are safe. `getCode()` is used only for status (`PunishService`,
  `StaffLinkService`, `BackendRuleSource` 404, `FingerprintSubmitter` 503). No
  `getResponseBody()` call exists in `velocity/src/main`. Keeping every status code unchanged
  keeps these branches correct.
- List reads are NOT safe. Velocity deserializes list bodies through the generated client:
  `HttpBackendGateway.java:84-86` (`getContent()` for `/template`, page 0 only),
  `HttpBackendGateway.java:189` (`getItems()` for report feedback),
  `BackendSectionSource.java:22` and `HttpClientRuleBackend.java:29` (bare arrays).
  A plugin built against the 0.0.40 client cannot read the new envelope or the old
  `Page`-shaped bodies after the cut. Velocity must ship in the same release as the backend.
  See open question 2.

## Out of scope

- Authentication and authorization behaviour. Routes keep their role rules and the verified
  identity model unchanged. `openspec/config.yaml` principle 2 disagrees with the repository
  state (see open question 5); this change does not resolve that.
- Rate limiting. Infra owns it; no new in-app limiter.
- Status codes. Every status code stays the same except one: the stale case version moves from 409 to
  412 (design D17, E1), and the missing `If-Match` is the new 428. Everything else changes only the body.
- Changing ELO, appeal, Ban ID or evidence semantics. Only the wire shape changes.
- UI components (`redesign-ux` change). `web/` changes are to the HTTP client layer only.
- A dual or versioned old format. The cut is hard by decision.
- Non-list payload redesign beyond the envelope (for example DTO snapshots keep their fields
  and nest their list under `items`).
