# Tasks: Player Reports

**Input**: Design documents from `specs/006-player-reports/` (`spec.md`, `plan.md`, `data-model.md`, `contracts/report-api.md`, `research.md`, `quickstart.md`)

**Note**: This is a **retroactive** task breakdown — the feature is already implemented,
including the `ReportService` extraction (PR #124) that `plan.md`'s Constitution Check
still describes as missing (that check was written before the extraction landed). T001–T020
describe already-shipped work, verified and marked `[X]` rather than being pending.
`/speckit-implement` additionally closed **T021** (non-atomicity Javadoc) and **T023**
(`ReportApi` extraction) — both zero-behavior-change, verified by compiling, checking the
generated OpenAPI spec, and a live Docker smoke test of all three endpoints (submit,
list, resolve) plus request validation. **T022 (DTO/`Error`-variant consolidation) is
deliberately left open** — it changes wire format and touches the generated OpenAPI
client, a real breaking-change risk explicitly deferred pending a scoping decision, not
implemented blind. **T024** (full `quickstart.md` re-validation) is also left open — the
Docker smoke test above covered the endpoints T021/T023 touched, not every scenario in
`quickstart.md`.

**Tests**: Not included — no test sources exist for this feature (`plan.md` Technical
Context "Testing"), and none were explicitly requested.

**Organization**: Tasks are grouped by user story per `spec.md`'s priorities (US1/US2 = P1,
US3 = P2, US4 = P3).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to

## Phase 1: Setup

- [X] T001 Create the `report/` feature package structure (`controller/`, `service/`,
      `dto/` subpackages) in `backend/src/main/java/net/onelitefeather/blackhole/backend/report/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared entity/enum/repository/config every user story below depends on.

- [X] T002 [P] Define `ReportCategory` enum (`CHAT_ABUSE`, `CHEATING`, `GRIEFING`, `OTHER`)
      in `backend/.../report/ReportCategory.java`
- [X] T003 [P] Define `ReportStatus` enum (`OPEN`, `UNDER_REVIEW`, `ACTIONED`, `DISMISSED`)
      in `backend/.../report/ReportStatus.java`
- [X] T004 Create `ReportEntity` JPA entity (`reports` table, real `createdAt`/`updatedAt`
      long columns so rate-limit queries can filter on them directly, plus the
      `report_evidence_references` `@ElementCollection` join table) in
      `backend/.../report/ReportEntity.java`
- [X] T005 Create `ReportRepository` with `countByCreatedAtGreaterThan` and
      `countByReporterHashAndCreatedAtGreaterThan` derived queries for rate limiting, plus
      standard `PageableRepository` access, in `backend/.../report/ReportRepository.java`
- [X] T006 [P] Add the Liquibase changeset(s) for `reports`/`report_evidence_references` in
      `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- [X] T007 [P] Add `blackhole.report.rate-limit.*` (max-reports, max-reports-network-wide,
      window) and `blackhole.elo.report.*` (actioned-delta, reward-delta) config keys in
      `backend/src/main/resources/application.yml`

**Checkpoint**: Foundation ready — user story implementation can proceed.

---

## Phase 3: User Story 1 - Submit a report without spamming the system (Priority: P1) 🎯 MVP

**Goal**: Accept a report (reporter, reported player, category, optional detail) into an
open state, guarded by a two-tier rate limit (spec FR-001–FR-007).

**Independent Test**: Submit a valid report and confirm it's accepted/stored OPEN; submit
past the per-reporter and network-wide limits and confirm rejection.

- [X] T008 [P] [US1] Create `ReportRequestDTO` (`reporterHash`/`reportedHash` required,
      SHA-512-pattern-validated; `category` required; `description` optional max 1000
      chars; `evidenceReferences`/`metaData` optional) in
      `backend/.../report/dto/ReportRequestDTO.java`
- [X] T009 [P] [US1] Create `ReportDTO` response shape (doubles as the full-record shape
      used everywhere) in `backend/.../report/dto/ReportDTO.java`
- [X] T010 [US1] Implement `ReportService.submit()`: network-wide rate-limit check, then
      per-`reporterHash` rate-limit check (either alone rejects), then persist an `OPEN`
      report and publish `report.created` — in
      `backend/.../report/service/ReportService.java` (depends on T004, T005, T008, T009)
- [X] T011 [US1] Implement `POST /report/` in `ReportController`, delegating to
      `ReportService.submit()` and mapping `SubmitResult` to 200/429 — in
      `backend/.../report/controller/ReportController.java` (depends on T010)

**Checkpoint**: Report submission + rate limiting is independently functional.

---

## Phase 4: User Story 2 - Resolve a report and punish in the same step (Priority: P1)

**Goal**: A network operator resolves a report and, optionally, applies a punishment
template to the reported player in the same request (spec FR-009–FR-013).

**Independent Test**: Resolve with a punishment template and confirm both the resolution
and the punishment land; resolve without one and confirm no punishment side effect;
resolve against a missing report/template and confirm no partial mutation.

- [X] T012 [P] [US2] Create `ReportResolutionDTO` (`status`, `resolutionNote` optional,
      `resolvedBy` required, `punishmentTemplateId`/`punishmentSource` optional pair) in
      `backend/.../report/dto/ReportResolutionDTO.java`
- [X] T013 [US2] Implement `ReportService.resolve()`'s core flow: look up by identifier
      (`NotFound` if missing), then — if `punishmentTemplateId` is set — require
      `punishmentSource` (`PunishmentSourceRequired` if missing) and call
      `PunishmentApplicationService.apply(...)` (`NotFound` if it returns empty) **strictly
      before** touching any `ReportEntity` field, then update
      `status`/`resolutionNote`/`resolvedBy`/`updatedAt` — in
      `backend/.../report/service/ReportService.java` (depends on T004, T005, T012;
      preserve the fail-fast-before-mutation ordering exactly, per spec FR-013/SC-006)
- [X] T014 [US2] Implement `POST /report/{identifier}/resolve` in `ReportController`,
      delegating to `ReportService.resolve()` and mapping `ResolveResult` to 200/400/404 —
      in `backend/.../report/controller/ReportController.java` (depends on T011, T013)

**Checkpoint**: Resolution + punishment integration is independently functional.

---

## Phase 5: User Story 3 - Actioned reports move standing and reward reporters (Priority: P2)

**Goal**: An `ACTIONED` resolution docks the reported player's matching standing track;
if a punishment was also demonstrably applied, the reporter is rewarded on that same
track (spec FR-014–FR-018).

**Independent Test**: Resolve as actioned per category and confirm the reported player's
matching track drops; confirm the reporter is rewarded only when a punishment template
was also applied in that same call, never otherwise.

- [X] T015 [US3] Add the category → `EloTrack` mapping inside `ReportService.resolve()`
      (`CHAT_ABUSE→CHAT`, `CHEATING`/`GRIEFING→GAMEPLAY`, `OTHER→` no track) — in
      `backend/.../report/service/ReportService.java` (depends on T013)
- [X] T016 [US3] When `status == ACTIONED` and the category maps to a track, call
      `EloService.applyDelta` to dock the reported player's standing on that track
      (`blackhole.elo.report.actioned-delta`, reason `REPORT_ACTIONED`) — in
      `backend/.../report/service/ReportService.java` (depends on T015)
- [X] T017 [US3] When T016 ran **and** `punishmentTemplateId` was set (a punishment was
      demonstrably applied in T013), additionally call `EloService.applyDelta` to reward
      the reporter's standing on the same track (`blackhole.elo.report.reward-delta`,
      reason `REPORT_REWARDED`) — in `backend/.../report/service/ReportService.java`
      (depends on T016)
- [X] T018 [US3] Publish `report.resolved` (`reportIdentifier`, `reportedHash`, `status`,
      `resolvedBy`) after every resolution regardless of outcome — in
      `backend/.../report/service/ReportService.java` (depends on T013)

**Checkpoint**: Standing effects are independently functional and correctly gated.

---

## Phase 6: User Story 4 - Review the full report queue (Priority: P3)

**Goal**: A network operator retrieves all reports, paginated, across every status (spec
FR-008).

**Independent Test**: Submit several reports and confirm they're all retrievable
paginated; confirm an empty queue returns an empty page, not an error.

- [X] T019 [P] [US4] Implement `ReportService.getAll(Pageable)` wrapping
      `ReportRepository.findAll` — in `backend/.../report/service/ReportService.java`
      (depends on T004, T005)
- [X] T020 [US4] Implement `GET /report/` in `ReportController`, delegating to
      `ReportService.getAll()` — in `backend/.../report/controller/ReportController.java`
      (depends on T019)

**Checkpoint**: All four user stories are independently functional.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: The two items `plan.md`'s Complexity Tracking table left open after the
service-layer extraction, plus end-to-end validation. These are the actual remaining work.

- [X] T021 Document `resolve()`'s multi-step side-effect chain (punishment apply → report
      update → up to two ELO deltas → event publish) as non-atomic in a class/method
      Javadoc on `ReportService`, matching `EloService`'s documented-limitation style — in
      `backend/.../report/service/ReportService.java` (plan.md Constitution Check,
      Principle VI, PARTIAL)
- [ ] T022 Consolidate `ReportDTO`/`ReportRequestDTO`/`ReportResolutionDTO` into one sealed
      marker interface (`SubmitRequest`/`ResolveRequest`/`Response`/`Error` variants) per
      `micronaut-dto-contract` — in `backend/.../report/dto/` (plan.md Constitution Check,
      Principle III)
- [X] T023 Extract a `ReportApi` interface carrying the `@Operation`/`@ApiResponse`
      annotations currently on `ReportController` directly, per
      `micronaut-openapi-contract` — in `backend/.../report/controller/` (plan.md
      Constitution Check, Principle III; do this alongside T022 so both land as one pass
      over the same files rather than two separate churns)
- [ ] T024 [P] Run `quickstart.md`'s validation scenarios end to end against real Docker
      infra to confirm current behavior still matches

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies.
- **Foundational (Phase 2)**: Depends on Setup — blocks all user stories.
- **User Stories (Phase 3–6)**: All depend on Foundational. US1 is independent; US2 depends
  on US1's `submit()`/`ReportController` scaffolding only incidentally (same files, not a
  behavioral dependency); US3 directly extends US2's `resolve()` flow (T015–T018 build on
  T013); US4 is independent of US1–US3.
- **Polish (Phase 7)**: Depends on all four user stories being complete (T021/T022/T023
  touch the same files US1–US4 already built).

### Parallel Opportunities

- T002/T003 (enums) in parallel.
- T006/T007 (changeset, config) in parallel with each other and with T002/T003.
- T008/T009 (US1 DTOs) in parallel.
- T012 (US2 DTO) can start in parallel with US1 once Foundational is done.
- T019 (US4 service method) has no dependency on US2/US3 and can run in parallel with them.
- T024 (validation) can run in parallel with T021–T023 (different concern, no file overlap
  until T022/T023 change the DTOs/controller T024 will then exercise — sequence T024 after
  T022/T023 if validating the *new* contract shape, or before, if validating current
  behavior first as a baseline).

## Implementation Strategy

Already delivered in full (T001–T020) via the original implementation and the PR #124
service-layer extraction. Remaining scope is exactly T021–T024 — recommend running
`/speckit-converge` next to get an independent confirmation of this same conclusion
against the actual code, then `/speckit-implement` for T021–T024 specifically.
