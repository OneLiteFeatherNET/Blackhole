# Tasks: standardize API pagination and problem details

Read `design.md` (decisions D1-D14, route table D10) and `audit.md` before starting a group.
Route numbers (#) refer to the endpoint table in `audit.md`. Backend base:
`backend/src/main/java/net/onelitefeather/blackhole/backend/`. Client base:
`client/`. Clients: `web/src/api/`, `portal/server/lib/bff.ts`, `portal/src/api/contract.ts`,
`discord/src/main/java/net/onelitefeather/blackhole/discord/`, `velocity/src/main/java/net/onelitefeather/blackhole/velocity/`.

## Git and PR rules (all groups)

- Group 1 branches from up-to-date `origin/main` in a fresh branch. Every later PR branches from
  the previous PR's branch (stacked). Never branch from the main checkout.
- One Conventional Commits type per PR. Titles are lower case, no trailing period, at most 100
  characters. Breaking changes use `!` after the scope and a `BREAKING CHANGE:` footer.
- Commit trailer: `Claude-Session: https://claude.ai/code/session_01TFcvDovaYNHZewdbM2f8Ru`.
  No `Co-Authored-By` trailer.
- Never `--force`, never `--no-verify`, never bare `git stash`.
- Every group starts with failing tests (red), then production code (green), then refactor.
  Tests follow F.I.R.S.T.: no `Thread.sleep`, no system-time reads (inject `java.time.Clock`),
  no shared static state, no real network, fixtures per test.

PR titles for this change (the implementation PRs, in order):

| task | PR title |
|---|---|
| 1 | `feat(backend): add the problem details model and handler` |
| 2a-2d | `feat(backend)!: serve problem details from <group>` (one PR per group) |
| 3 | `feat(backend): add the cursor page codec` |
| 4a-4d | `feat(backend)!: serve cursor pages from <group>` (one PR per group) |
| 4e | `perf(backend): add keyset indexes` (only if the spike requires it) |
| 5 | `feat(client)!: regenerate the client for spec 0.0.41` |
| 6 | `feat(web)!: read problem details and cursor pages` |
| 7 | `feat(portal)!: map problem details in the bff` |
| 8 | `feat(discord)!: read problem details and cursor pages` |
| 9 | `feat(velocity)!: read cursor pages and every template page` |
| 10 | `docs(api): document problem details and cursor pages` |

## 1. Spike and problem infrastructure (`feat(backend)`)

- [ ] 1.1 Spike (read-only against the code, results recorded). Create `backend/src/test/java/net/onelitefeather/blackhole/backend/problem/FrameworkErrorRenderingSpikeTest.java`
  as a `@MicronautTest` that calls, in the test app, one route that throws `HttpStatusException(UNAUTHORIZED)`,
  one with a bean-validation failure (`CaseController` PATCH with an invalid body), one with no token
  (a protected route) and one with a valid token but no role (`GET /case`). Record the actual status,
  `Content-Type` and body of each in an appended section "Spike result" at the end of `design.md`.
  Then confirm D1 (in-house) or switch to `micronaut-problem-json` if D1's revisit condition holds.
  Also run `EXPLAIN` for `/evidence`, `/appeal` and `/report` keyset candidates against the local
  stack (`docker/docker-compose.yml`) and record whether an index is needed (feeds 4e).
  Record which database engine the compose file runs (D8 note).
- [ ] 1.2 Tests first: `backend/src/test/java/net/onelitefeather/blackhole/backend/problem/ProblemDetailTest.java`
  (members present; extension members flattened at top level; null extension omitted;
  `Content-Type` constant) and `ProblemTypeTest.java` (slug from `code`, slug from kind for each
  `ErrorKind`, base URI constant).
- [ ] 1.3 Create `backend/src/main/java/net/onelitefeather/blackhole/backend/problem/ProblemDetail.java`
  (record `type, title, status, detail, instance` plus `Map<String,Object> extensions` with
  `@JsonAnyGetter`), `ProblemType.java` (base URI, slug rules), `ProblemMapper.java`
  (maps `ErrorResponse` and `PublicError` to `ProblemDetail` plus status; the single mapping point
  for D5).
- [ ] 1.4 Tests first: `backend/src/test/java/net/onelitefeather/blackhole/backend/problem/ProblemExceptionHandlerTest.java`
  (`@MicronautTest`): `HttpStatusException`, constraint violation (400 with `errors`), and unknown
  exception (500 with a fixed detail, no stack trace, no input echo).
- [ ] 1.5 Create `backend/src/main/java/net/onelitefeather/blackhole/backend/problem/ProblemExceptionHandler.java`
  (`@Error(global = true)` for `HttpStatusException`, `ConstraintViolationException`,
  `JsonProcessingException`, and a catch-all), returning `application/problem+json`.
  Fix the stale package named in the javadoc of `response/ErrorResponse.java:6` in the same PR.
- [ ] 1.6 Run `./gradlew :backend:test` and confirm the new tests pass. Nothing else changes in this PR.

## 2. Migrate error mapping per package group (`feat(backend)!`), one PR per group

Each group: tests first (a contract test per group, named `<Group>ErrorsAreProblemsTest`, that
asserts `application/problem+json`, `type`, `title`, `status`, `detail`, `instance` and the
existing `code` for each error the group's routes can return). Then change the mapping. Keep every
status code unchanged. Add the BREAKING CHANGE footer:
`BREAKING CHANGE: error bodies are RFC 9457 problems; codes move to the code member.`

- [ ] 2a Discord and staff links. Controllers: `DiscordTicketController` (#18-28),
  `DiscordLinkController` (#9-11), `DiscordStaffLinksController` (#5), `DiscordNotificationController`
  (#6-7), `DiscordLeakCheckController` (#8), `PlayerDiscordLinkController` (#12),
  `StaffDiscordLinkController` (#13-15), `AdminDiscordLinkController` (#16-17), `StaffLinkController`
  (#118-122), `AdminStaffLinkController` (#123-124), `StaffActorController` (#116-117).
  Services: `discord/ticket/TicketService.java` (NOT_YET stays a 409 with `availableFrom` epoch ms,
  `backendCount` and `backendLastMessageId` kept; `NOT_ELIGIBLE` stays 404 at `:481`),
  `discord/dto/DiscordTicketDTO.java` (Error variants and codes; `TRANSCRIPT_MISMATCH` at `:41`,
  `PROTOCOL_REQUIRED` at `:43`). Attachments (#22, #23) keep binary success bodies.
- [ ] 2b Punishment, ELO, evidence, profile, sections, templates, Ban ID, CloudNet, evasion, imports.
  Controllers: `PunishmentEntityController` (#126-131), `ActivePunishmentController` (#125),
  `PunishmentTemplateController` (#132-136), `BanIdController` (#1), `SectionController` (#50-54),
  `ProfileController` (#55-60; replace `profile/dto/PunishProfileResponse.java:16` `ErrorResponse(message)`
  by a sealed `Error` variant, fix the three returns at `:173`, `:226`, `:309`), `EloController`
  (#29-32), `EvidenceController` (#36-44), `EvasionController` (#33-35), `CatalogueController` (#3-4,
  cloudnet), `ImportedPunishmentController` (#82-83), `VanillaImportController` (#81; plain-string 400
  at `:62` becomes a problem; the endpoint is in the spec after group 5).
- [ ] 2c Case, public API, attachments. Controllers: `CaseController` (#61-64), `CaseLiveController`
  (#68; no-body 403/404/503 at `:42-43` become problems; SSE stream keeps its 401/status behaviour),
  `CaseProtocolController` (#70-72), `CaseConversationController` (#73-80), `CaseParticipantController`
  (#69), `ChatAttachmentController` (#45), `EvidenceShareController` (#46-49),
  `PublicController` (#91-100; uniform login error per design D4, `respond()` at `:125-128`),
  `PublicLiveController` (#101; no-body errors at `:44-46` become problems, status unchanged),
  `PublicAttachmentController` (#102-104; the missing-file body at `:45` becomes a problem),
  `publicapi/controller/PublicErrorFilter.java` (rewrites 400 under `/public/**` to the uniform
  problem; `POST /public/session` keeps the 401 `INVALID_BAN_ID` body; keep `Cache-Control: no-store`),
  `publicapi/dto/PublicError.java` and `PublicErrorBody.java` (replaced by problem extension
  members). Preserve the uniform-error test (`PlayerSessionService.java:39`).
- [ ] 2d Appeal, reports, auth, client rules, dashboard, security. Controllers: `AppealController`
  (#84-86; plain strings at `:96`, `:99`, `:100`, `:101` become problems, same statuses),
  `AppealConditionsController` (#87-89), `ReportController` (#137-140; no-body 429 at `:54` becomes a
  problem), `ReportFeedbackController` (#141-142), `ClientRuleController` (#105-109),
  `ClientRuleHitController` (#110-112), `ClientRuleCatalogueController` (#113-114), `AuthController`
  (#115), `DashboardController` (#90), `security/GroupsOverageExceptionHandler.java` (`:28-29`, `:50-56`:
  `{"reason":"groups-overage"}` becomes `code` `GROUPS_OVERAGE`), `security/ActorResolver.java:53`
  (`HttpStatusException` 401 becomes a problem), and the framework 401/403 path (per the spike result).

## 3. Cursor infrastructure (`feat(backend)`)

- [ ] 3.1 Tests first: `backend/src/test/java/net/onelitefeather/blackhole/backend/paging/CursorCodecTest.java`
  (round trip; tampered signature rejected; tampered payload rejected; wrong key count rejected;
  malformed base64 rejected; the payload contains no Ban ID fixture), `CursorPageTest.java`
  (`nextCursor` null when `limit + 1` rows are not returned; exactly `limit` items returned),
  `LimitBoundsTest.java` (default 50; 1 and 200 accepted; 0, 201 and `limit=abc` produce an
  `errors` entry for `limit`). Use a fixed test key from `TestKeys` and a fixed `Clock`.
- [ ] 3.2 Create `backend/src/main/java/net/onelitefeather/blackhole/backend/paging/CursorPage.java`
  (record `items, nextCursor`), `CursorCodec.java` (D7), `PageRequest.java` (`limit` default 50 and
  bounds, `cursor`), `CursorKeyGuard.java` (refuses an unset `BLACKHOLE_CURSOR_KEY` outside `dev`/`test`,
  same pattern as `banid/` `BanIdKeyGuard`).
- [ ] 3.3 `backend/src/main/resources/application.yml`: add `blackhole.cursor.key: ${BLACKHOLE_CURSOR_KEY:}`
  with a comment that documents intent (D7, rotation invalidates cursors, dev default random per run).
- [ ] 3.4 Map `INVALID_CURSOR` (400, `code` `INVALID_CURSOR`) and `limit` range errors through the
  problem handler from group 1. Test: `backend/src/test/java/.../paging/CursorProblemTest.java` (`@MicronautTest`).

## 4. Migrate list routes per group (`feat(backend)!`), one PR per group

Each group: one failing test first (`EveryListRouteUsesCursorEnvelopeTest` restricted to that
group's routes, listing route names explicitly; it fails until each route accepts `limit`/`cursor`
and returns `{items, nextCursor}`). Then one repository keyset method per route (D8, no
`@Transactional`, JPQL with the expanded keyset predicate, `Pageable.from(0, limit + 1)`, no
count query), one service method returning `CursorPage<T>`, then the controller change (one
service call, `HttpResponse` mapping). Route sort keys and filters are in `design.md` D10.
Remove the framework `pageable` parameters from the matching `*Api` interfaces and their
`@ArraySchema` `Page` declarations (`appeal/controller/AppealApi.java:44-55`,
`report/controller/ReportApi.java:52-60`).

- [ ] 4a Discord and staff links: #5 `GET /discord/staff-links`, #10 `GET /discord/link/{discordUserId}`,
  #16 `GET /admin/discord-link`, #19 `GET /discord/tickets` (keep `state`), #24 `GET /discord/tickets/{id}/outbox`
  (`afterSeq` replaced by `cursor`, same seq order), #123 `GET /admin/staff-link`.
- [ ] 4b Punishment, ELO, evidence, profile, sections, templates, evasion, CloudNet, imports: #30 `GET /elo/{owner}` (sections as `items`), #32 `GET /elo/{owner}/history` (keep `section`),
  #35 `GET /evasion/{owner}/sightings` (`truncated` retired), #38 `GET /punishment/{reference}/evidence`
  (keep `includeInactive`), #40 `GET /evidence` (keep `owner`, `includeInactive`), #50 `GET /section`,
  #58 `GET /profile`, #59 `GET /profile/{owner}` (history as `items`), #129 `GET /punishment` (keep filters),
  #130 `GET /punishment/short/{shortId}`, #125 `GET /punishment/active` (drop `page`/`size`/`sort`/`direction`,
  keep the sort whitelist as an enum; `total` retired), #135 `GET /template`, #4 `GET /cloudnet/catalogue`
  (`items`), #82 `GET /admin/import/punishments` (`count DESC, reasonKey ASC`), #81 `POST /admin/import/vanilla`
  (open question 3: its result object becomes `items` with `nextCursor` `null`).
- [ ] 4c Case, public API: #61 `GET /case` (drop `page`/`size`, keep existing filters), #63 `GET /case/{reference}/timeline`
  (`size` to `limit`), #48 `GET /case/{reference}/evidence-shares`, #70 `GET /case/{reference}/protocol`,
  #98 `GET /public/conversation` (server page size 200 becomes `limit` default 50, max 200).
- [ ] 4d Appeal, reports, client rules, auth, dashboard: #85 `GET /appeal` (drop framework), #88
  `GET /appeal-conditions/versions`, #90 `GET /dashboard/summary` (cap 10 becomes default `limit`, open
  question 3), #105 `GET /client-rule`, #111 `GET /client-rule/{key}/hits` (replace `limit`-only),
  #112 `GET /client-rule/hits` (keep `owner`), #114 `GET /client-rule/catalogue` (`items`), #115 `GET /auth/me`
  (roles as `items`, `nextCursor` `null`), #116 `GET /admin/staff-actor`, #138 `GET /report` (keep
  `reportedHash`, drop framework), #141 `GET /report/feedback/{owner}`.
- [ ] 4e Only if task 1.1 says an index is needed: `perf(backend)` PR adding
  `backend/src/main/resources/db/migration/V25__keyset_indexes.sql` (next free version after
  `V24__player_seen.sql`; append-only; indexes named per D9 with the real table names; no backfill).
  Add a test that runs `EXPLAIN` against the local stack and asserts an index scan on the route
  (`@Tag("integration")`, not run in the unit suite). Update `.claude/rules/database.md` only if a new rule is needed.

## 5. Generated client and spec (`feat(client)!`)

- [ ] 5.1 Tests first: `client/src/test/java/net/onelitefeather/blackhole/client/SpecContractTest.java`
  parses `client/specs/blackhole-api-0.0.41.yml` (no network) and asserts: every list operation
  declares `limit` and `cursor` parameters and a `CursorPage` 200 response; every error response is
  `application/problem+json` with a `Problem` schema; `POST /admin/import/vanilla` exists; no
  `pageable` parameter or `Page` schema remains.
- [ ] 5.2 Create `client/specs/blackhole-api-0.0.41.yml` as a copy of `0.0.40` with D12 applied
  (shared `Limit`, `Cursor`, `Problem`, `CursorPage`; removal of `pageable` at the lines recorded in
  `audit.md` section 1 spec mismatch 2; list shapes; the vanilla import operation).
- [ ] 5.3 `client/build.gradle.kts:23`: `inputSpec` to `blackhole-api-0.0.41.yml`. Run
  `./gradlew :client:openApiGenerate :client:build`. Do not edit generated sources.

## 6. Web client (`feat(web)!`)

- [ ] 6.1 Tests first (`web/src/api/`, Vitest, no network): `apiError.test.ts` (reads `title`,
  `detail`, `code`, `errors` from a problem body; `message` returns `detail`; a non-JSON body gives
  status only), `paginate.test.ts` (follows `nextCursor` until `null`; stops at the loop bound;
  throws on `INVALID_CURSOR`), `http.test.ts` (401 renew-then-retry-once and 403 no-retry still hold,
  per `admin-ui-authentication`).
- [ ] 6.2 Create `web/src/api/apiError.ts` (`ApiError` class) and `web/src/api/paginate.ts`.
  Update `web/src/api/http.ts` (accept `application/problem+json`; build `ApiError`; replace the
  `errorMessage`, `_embedded.errors` and `kind` readers at `:75`, `:85-95`).
- [ ] 6.3 Update `web/src/api/blackhole.ts`: delete `pageContent` (`:299-307`), `collectPages`
  (`:350-360`) and `MAX_PAGES` (`:293`); each list call (evidence `:641`, cases `:746`, active
  punishments `:743`, staff and Discord links `:691`, `:698`, evidence shares `:822`, protocol `:852`,
  timeline `:757-771`, sections `:489-493`, client rules `:514-516`, `punishment/short` `:749`,
  templates, reports, appeals, elo history) reads `items` and `nextCursor` through `paginate`.
  Keep the function signatures the components call so the UI components are not touched.
- [ ] 6.4 Update `web/src/api/uploadEvidence.ts:47-53` (`errorMessage` becomes `detail`, `code` from
  the problem) and `web/src/api/caseEvents.ts` (SSE error handling unchanged, status-based).
- [ ] 6.5 `web/package.json`: move the spec pin from `0.0.39` to `0.0.41` and regenerate the types
  the web build uses. Components are out of scope (see `redesign-ux`).

## 7. Portal BFF (`feat(portal)!`)

- [ ] 7.1 Tests first (`portal/server`, unit, no network): a 409 `NOT_ELIGIBLE` problem relayed with
  its `availableFrom` (`bff.ts:142-143`); a 422 file problem with `code` in `FILE_REJECTION_CODES`
  (`bff.ts:122`); a 409 file problem (`:126`); a non-JSON error body still becomes 503 (`:157-163`, `:299`);
  a `application/problem+json` body is parsed as JSON.
- [ ] 7.2 Update `portal/server/lib/bff.ts` (`parseJson` accepts `application/problem+json`; read
  `code` and `availableFrom` from the problem members; keep the 422 non-file behaviour at `:148-149`;
  relay `/api/punishment`, `/api/appeal`, `/api/conversation` unchanged at `:298-300`, except the
  body is the problem).
- [ ] 7.3 `portal/src/api/contract.ts`: `CONFLICT_CODES` (`:206-214`) and `FILE_REJECTION_CODES` (`:218`)
  unchanged; add a test that every code in the list is a value the backend emits (use the route table).
- [ ] 7.4 Move the portal's generated-client spec pin to `0.0.41`.

## 8. Discord (`feat(discord)!`)

- [ ] 8.1 Tests first (`discord/src/test/java/...`): `HttpBackendGatewayTest` against a fake HTTP
  server (not a real backend) asserting: `failed()` reads `code`, `availableFrom` (epoch ms),
  `backendCount`, `backendLastMessageId` from the problem extension members; `tickets()` reads `items`;
  `staffLinks()` reads `items`; `outbox()` follows `nextCursor` and returns the same `seq` order;
  `whoami()` reads `items`. `TicketLifecycleTest` for the `TRANSCRIPT_MISMATCH` rescan (`TicketLifecycle.java:248-262`).
  `AppealCommandsTest` for `NOT_YET` (`command/AppealCommands.java:74-75`).
- [ ] 8.2 Update `discord/src/main/java/net/onelitefeather/blackhole/discord/backend/HttpBackendGateway.java`
  (`:98`, `:187-190`, `:220-221`, `:244-246`, `:354`), `TicketLifecycle.java` and `AppealCommands.java`.
  The fake `FakeTicketBackend.java:340-343` in tests moves to problem members.
- [ ] 8.3 Confirm `discord` builds against the regenerated client (`./gradlew :discord:test`).

## 9. Velocity (`feat(velocity)!`)

- [ ] 9.1 Tests first (`velocity/src/test/java/...`): `HttpBackendGatewayTemplatesTest` (a fake
  backend with two pages: all templates are returned, not page 0 only), `BackendSectionSourceTest`
  (reads `items`), `HttpClientRuleBackendTest` (reads `items`), and a status-branch test
  (`BackendRuleSourceTest`: 404 means absent, as today). No test reads a response body for
  an error.
- [ ] 9.2 Update `velocity/src/main/java/net/onelitefeather/blackhole/velocity/punish/HttpBackendGateway.java`
  (`:84-86` loop `nextCursor` for templates; `:189` `reportFeedback` reads `items`),
  `section/BackendSectionSource.java:22`, `clientrule/HttpClientRuleBackend.java:29`.
  `clientrule/BackendRuleSource.java:31` (404 branch) and `PunishService.java` and
  `StaffLinkService.java` status branches: no change.
- [ ] 9.3 Velocity check (record in the PR body): `grep -rn getResponseBody velocity/src/main`
  returns nothing; `getCode()` only drives status. The PR must be released with the backend.

## 10. Docs and rules (`docs(api)`), then open the PRs

- [ ] 10.1 `.claude/rules/controller-versioning.md`: state that the 0.0.41 cut is a hard cut with
  no dual format and no `@Version("2")` window (decision recorded in `proposal.md`).
- [ ] 10.2 `.claude/skills/micronaut-dto-contract/SKILL.md`: the `Error` variant maps to a problem
  through `ProblemMapper`; `ErrorResponse.errorMessage()` is now `detail()`.
- [ ] 10.3 `.claude/rules/public-api.md`: the uniform 401 problem body (D4), `limit`/`cursor` on
  `/public/conversation`. `.claude/rules/case-timeline.md`: `size` becomes `limit`, same `seq` cursor.
  `.claude/rules/evidence.md`: the evidence list is a cursor page; `includeInactive` unchanged.
- [ ] 10.4 `CLAUDE.md`: one line under cross-cutting conventions: every error is
  `application/problem+json` and every list is a cursor page (keep the file lean).
- [ ] 10.5 Update the in-flight changes `add-appeal-portal`, `add-discord-appeal-tickets` and
  `add-punishment-case-timeline` (`openspec/changes/<name>/`): their error and list requirements
  adopt the problem and envelope shapes. Do not apply them here.
- [ ] 10.6 Update `.claude/PROGRESS.md` (move this change to Active, then Recently completed on merge).
- [ ] 10.7 Run `openspec validate --all --strict`.
- [ ] 10.8 Open the implementation PRs in stack order (titles per the table above), each with the
  Velocity check (9.3) and the spike result (1.1) in the body. Do not merge. Watch the checks with
  `gh pr checks --watch`.
