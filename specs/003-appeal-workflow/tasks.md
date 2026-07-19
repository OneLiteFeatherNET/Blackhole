# Tasks: Appeal Workflow

**Input**: Design documents from `specs/003-appeal-workflow/` (`spec.md`, `plan.md`,
`data-model.md`, `contracts/appeal-api.md`, `research.md`, `quickstart.md`)

**Note**: This is a **retroactive** task breakdown — the feature is already implemented,
including the `AppealController` layering fix, the `revokedBy` audit-trail stamp, and the
non-atomicity Javadoc (all PR #123) that `plan.md`'s Constitution Check still lists as
open (that check predates the fix). T001–T024 describe already-shipped work, verified
and marked `[X]`. `/speckit-converge` additionally appended **T028** (`GET /appeal/`
traceability to FR-019 — already fully implemented, just wasn't referenced by a task;
closed immediately since there was nothing left to build). `/speckit-implement` then
closed **T026** (`AppealApi` extraction), verified by compiling, checking the generated
OpenAPI spec, and a live Docker test of submit/list/review including the review
status-guard (409) and not-found (404) error paths. **T025** (DTO/`Error`-variant
consolidation) is deliberately left open — same breaking-wire-format risk as every
sibling spec, needs its own scoping decision. **T027** (full `quickstart.md`
re-validation) is also left open — this feature is otherwise in noticeably better shape
than its siblings.

**Tests**: Not included — no test sources exist for this feature.

**Organization**: Grouped by user story per `spec.md`'s priorities (US1/US2/US3 = P1,
US4 = P2, US5 = P3).

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [X] T001 Create the `appeal/` feature package structure (`controller/`, `service/`,
      `dto/` subpackages) in `backend/src/main/java/net/onelitefeather/blackhole/backend/appeal/`

---

## Phase 2: Foundational (Blocking Prerequisites)

- [X] T002 [P] Define `AppealStatus` enum (`SUBMITTED`, `ELIGIBLE_PENDING_REVIEW`,
      `INELIGIBLE`, `IN_REVIEW`, `GRANTED_FULL_LIFT`, `GRANTED_DURATION_REDUCTION`,
      `DENIED` — note `SUBMITTED`/`IN_REVIEW` are intentionally unreachable today, per
      `data-model.md`'s State Transitions section) in `backend/.../appeal/AppealStatus.java`
- [X] T003 [P] Define `DecisionOutcome` enum (`APPLIED`, `PUNISHMENT_NOT_ACTIVE`) in
      `backend/.../appeal/DecisionOutcome.java`
- [X] T004 [P] Define `EligibilityResult` record (eligible/severe/checklistSnapshot) in
      `backend/.../appeal/EligibilityResult.java`
- [X] T005 Create `AppealEntity` (`appeals`: FK to `punishments`, `appellantHash`
      SHA-512-validated, `statement`, `status`, `eligibilityCheckResult` JSON,
      `decidedBy`/`decisionNote` nullable, real `createdAt`/`updatedAt` long columns) in
      `backend/.../appeal/AppealEntity.java` (depends on T002)
- [X] T006 [P] Create `AppealRepository` in `backend/.../appeal/AppealRepository.java`
      (depends on T005)
- [X] T007 [P] Add the Liquibase changeset(s) for `appeals` (append-only —
      `drop-appeals-tenant` as a separate later changeset, never editing the original
      `create-appeals`) in `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- [X] T008 [P] Add `blackhole.appeal.min-days-manual`/`min-days-auto`/
      `repeat-appeal-cooldown-days` config keys in `backend/src/main/resources/application.yml`

**Checkpoint**: Foundation ready.

---

## Phase 3: User Story 1 - A punished player contests a punishment (Priority: P1) 🎯 MVP

**Goal**: Submit an appeal against an existing punishment; it's immediately evaluated and
recorded with its full checklist result (spec FR-001–FR-003, FR-006).

**Independent Test**: Submit against a real punishment and confirm it's recorded with an
eligible/ineligible verdict and full checklist; submit against a nonexistent punishment
and confirm rejection.

- [X] T009 [P] [US1] Create `AppealSubmissionDTO`/`AppealDTO` in
      `backend/.../appeal/dto/AppealSubmissionDTO.java` /
      `backend/.../appeal/dto/AppealDTO.java`
- [X] T010 [US1] Implement `AppealEligibilityService.submitAppeal` — look up the
      punishment (empty if missing), evaluate the checklist, persist the appeal as
      `ELIGIBLE_PENDING_REVIEW` or `INELIGIBLE` — in
      `backend/.../appeal/service/AppealEligibilityService.java` (depends on T004–T007,
      T009)
- [X] T011 [US1] Implement `POST /appeal/` in `AppealController`, delegating to
      `submitAppeal`, publishing `appeal.submitted` — in
      `backend/.../appeal/controller/AppealController.java` (depends on T010)

**Checkpoint**: Appeal submission + immediate evaluation is independently functional.

---

## Phase 4: User Story 2 - Appeals are gated by a fixed, auditable checklist (Priority: P1)

**Goal**: The checklist requires a minimum wait (shorter for auto-issued punishments) and
blocks a submission within the repeat-appeal cooldown, both without human involvement
(spec FR-004, FR-005).

**Independent Test**: Appeal before the wait elapses and confirm ineligible; appeal again
within the cooldown after a denial/ineligible verdict and confirm ineligible.

- [X] T012 [US2] Implement the wait-period check in `AppealEligibilityService.evaluate` —
      `minDaysRequired` = `min-days-auto` if `punishment.source == EloService.SYSTEM_ELO_SOURCE`
      else `min-days-manual`; `minTimeElapsed` from the punishment's creation-date metadata
      — in `backend/.../appeal/service/AppealEligibilityService.java` (depends on T010)
- [X] T013 [US2] Implement the repeat-appeal cooldown check — `isRepeatAppeal` true iff a
      prior appeal against the *same* punishment has status `DENIED`/`INELIGIBLE` and was
      created within `repeat-appeal-cooldown-days`; appeals still
      `ELIGIBLE_PENDING_REVIEW` are excluded — in
      `backend/.../appeal/service/AppealEligibilityService.java` (depends on T012)
- [X] T014 [US2] Set `eligible = minTimeElapsed && !isRepeatAppeal` and version the
      checklist snapshot (`CHECKLIST_VERSION`) — in
      `backend/.../appeal/service/AppealEligibilityService.java` (depends on T013)

**Checkpoint**: Eligibility gating is independently functional and objective.

---

## Phase 5: User Story 3 - A network operator reviews and decides an eligible appeal (Priority: P1)

**Goal**: Decide an eligible appeal as full lift, duration reduction, or denial; reject
self-review and non-awaiting-review appeals (spec FR-008, FR-011, FR-012, FR-013–FR-017).

**Independent Test**: Review with each of the three decisions and confirm the punishment
state and the appeal's decision record; attempt self-review and a second decision on an
already-decided appeal and confirm both rejected.

- [X] T015 [P] [US3] Create `AppealReviewDTO` and `AppealReviewResult` (sealed outcome
      record covering `NOT_FOUND`/`NOT_AWAITING_REVIEW`/`INVALID_DECISION`/`SELF_REVIEW`/
      `SEVERE_FULL_LIFT_DISALLOWED`/`DURATION_REDUCTION_MISSING_EXPIRY`/
      `DURATION_REDUCTION_EXPIRY_NOT_FUTURE`/`PUNISHMENT_NOT_ACTIVE`/`DECIDED`) in
      `backend/.../appeal/dto/AppealReviewDTO.java` / `backend/.../appeal/AppealReviewResult.java`
- [X] T016 [US3] Implement `AppealDecisionService.reviewAppeal` — status guard
      (`ELIGIBLE_PENDING_REVIEW`/`IN_REVIEW` only), decision-validity check
      (`GRANTED_FULL_LIFT`/`GRANTED_DURATION_REDUCTION`/`DENIED` only), self-review
      rejection (`reviewerId == punishment.source`) — in
      `backend/.../appeal/service/AppealDecisionService.java` (depends on T003, T005,
      T006, T015)
- [X] T017 [US3] Implement `AppealDecisionService.applyDecision` — `DENIED` is a no-op;
      `GRANTED_FULL_LIFT`/`GRANTED_DURATION_REDUCTION` require the punishment to still be
      the profile's active slot occupant (else `PUNISHMENT_NOT_ACTIVE`), then revoke or
      reduce via the same active-slot/history mechanics as
      `specs/002-punishment-core/spec.md` — in
      `backend/.../appeal/service/AppealDecisionService.java` (depends on T016)
- [X] T018 [US3] Stamp `decidedBy`/`decisionNote`/`status`/`updatedAt` on the appeal and
      persist, returning the decided appeal — in
      `backend/.../appeal/service/AppealDecisionService.java` (depends on T017)
- [X] T019 [US3] Implement `POST /appeal/{identifier}/review` in `AppealController`,
      delegating to `reviewAppeal` and mapping every `AppealReviewResult.Kind` to its
      status code (404/409/400/403/200), publishing `appeal.resolved` on `DECIDED` — in
      `backend/.../appeal/controller/AppealController.java` (depends on T016–T018)

**Checkpoint**: Review + decision is independently functional.

---

## Phase 6: User Story 4 - Severe punishments can only be shortened, never fully lifted (Priority: P2)

**Goal**: `GRANTED_FULL_LIFT` against a permanent, network-wide punishment is rejected;
duration reduction still succeeds (spec FR-007, FR-009).

**Independent Test**: Attempt a full lift against a SEVERE-tier appeal and confirm
rejection; grant a duration reduction against the same appeal and confirm success.

- [X] T020 [US4] Classify `severityTier` as `"SEVERE"` iff `PunishType.NETWORK` and no
      expiration-date metadata (permanent, network-wide) during checklist evaluation — in
      `backend/.../appeal/service/AppealEligibilityService.java` (depends on T014)
- [X] T021 [US4] Reject `GRANTED_FULL_LIFT` when the appeal's stored `severityTier` is
      `"SEVERE"` (`SEVERE_FULL_LIFT_DISALLOWED`), before `applyDecision` is ever called —
      in `backend/.../appeal/service/AppealDecisionService.java` (depends on T016, T020)
- [X] T022 [US4] Require and validate a future `newExpirationAt` for
      `GRANTED_DURATION_REDUCTION` (`DURATION_REDUCTION_MISSING_EXPIRY`/
      `DURATION_REDUCTION_EXPIRY_NOT_FUTURE`) — in
      `backend/.../appeal/service/AppealDecisionService.java` (depends on T016)

**Checkpoint**: The severe-tier ceiling is independently enforced.

---

## Phase 7: User Story 5 - Reviewers see a supporting standing signal (Priority: P3)

**Goal**: The checklist records whether the appellant's non-triggering standing track has
recovered to baseline, purely informational (spec FR-018).

**Independent Test**: Submit appeals for players with recovered vs. not-recovered
supporting standing and confirm the checklist reflects the difference without affecting
`eligible`.

- [X] T023 [US5] Infer the supporting (non-triggering) `EloTrack` from the punishment's
      `eloTriggerReasonCode` metadata when present, or a best-effort default when the
      punishment wasn't system-triggered — in
      `backend/.../appeal/service/AppealEligibilityService.java` (depends on T014)
- [X] T024 [US5] Read `supportingEloScore` from `EloProfileRepository` (default to
      `blackhole.elo.baseline` if no profile exists yet — deliberately permissive) and set
      `supportingEloRecovered = score >= baseline`, informational only — in
      `backend/.../appeal/service/AppealEligibilityService.java` (depends on T023)

**Checkpoint**: The supporting signal is independently visible; all five user stories done.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: The one genuinely remaining item — this feature is otherwise fully
converged after PR #123.

- [ ] T025 Consolidate `AppealDTO`/`AppealSubmissionDTO`/`AppealReviewDTO` into a sealed
      marker interface with `SubmitRequest`/`ReviewRequest`/`Response`/`Error` variants
      per `micronaut-dto-contract` — in `backend/.../appeal/dto/` (plan.md Constitution
      Check, Principle III)
- [X] T026 Extract an `AppealApi` interface carrying the `@Operation`/`@ApiResponse`
      annotations currently on `AppealController` directly, per
      `micronaut-openapi-contract` — in `backend/.../appeal/controller/` (do alongside
      T025)
- [ ] T027 [P] Run `quickstart.md`'s validation scenarios end to end against real Docker
      infra to confirm current behavior still matches

---

## Dependencies & Execution Order

- **Setup → Foundational**: Foundational blocks all user stories.
- **US1 (P1)**: MVP — independent once Foundational is done.
- **US2 (P1)**: Directly extends US1's `submitAppeal`/`evaluate` (T010) — implement
  together in practice.
- **US3 (P1)**: Independent write path (`review`), only needs Foundational entities.
- **US4 (P2)**: Extends US3's `reviewAppeal`/`applyDecision` (T016) directly.
- **US5 (P3)**: Extends US2's `evaluate` (T014) with an additional, non-gating field —
  fully independent of US3/US4.
- **Polish**: T025/T026 touch every story's DTOs/controller; T027 depends on everything.

### Parallel Opportunities

- T002/T003/T004 (enums/record) in parallel; T006/T007/T008 in parallel once T005 exists.
- T009 (US1 DTOs) and T015 (US3 DTOs) in parallel once Foundational is done.
- T023/T024 (US5) can run in parallel with T020–T022 (US4) — both extend `evaluate`/
  `AppealDecisionService` independently.

## Implementation Strategy

Already delivered in full (T001–T024) via the original implementation plus the PR #123
layering fix, `revokedBy` stamp, and race-window Javadoc. Remaining scope is just
**T025–T027**, all lower-priority polish shared with every sibling spec's DTO/OpenAPI
contract debt. Recommend `/speckit-converge` next for independent confirmation.

---

## Phase 9: Convergence

- [X] T028 [P] Add a task entry tracing `GET /appeal/` (`AppealController.getAll`,
      already implemented) to FR-019 — the endpoint is fully functional but no task in
      this file's Phase 3–7 currently references it, a traceability gap found by
      `/speckit-converge`, not a functional one (missing)
