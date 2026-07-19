# Tasks: Dual-ELO Punishment Engine

**Input**: Design documents from `specs/001-elo-engine/` (`spec.md`, `plan.md`,
`data-model.md`, `contracts/elo-api.md`, `research.md`, `quickstart.md`)

**Note**: This is a **retroactive** task breakdown — the feature is already implemented,
including the `EloQueryService`-style fix (PR #121, `EloService.getProfile`/`getHistory`)
that `plan.md`'s Constitution Check still lists as an open PARTIAL finding (that check was
written before the fix landed). T001–T023 below describe already-shipped work, included
so this list is a complete checklist `/speckit-converge` can verify against, not because
they're pending. **T024–T026 are the genuinely outstanding items.**

**Tests**: Not included — no test sources exist for this feature, and none were
explicitly requested.

**Organization**: Grouped by user story per `spec.md`'s priorities (US1/US2 = P1,
US3/US4 = P2).

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [ ] T001 Create the `elo/` feature package structure (`controller/`, `service/`,
      `dto/` subpackages) in `backend/src/main/java/net/onelitefeather/blackhole/backend/elo/`

---

## Phase 2: Foundational (Blocking Prerequisites)

- [ ] T002 [P] Define `EloTrack` enum (`CHAT`, `GAMEPLAY`) in `backend/.../elo/EloTrack.java`
- [ ] T003 [P] Define `EloReasonCode` enum (`TOXICITY_FLAG`, `ANTICHEAT_FLAG`,
      `REPORT_ACTIONED`, `MANUAL_ADJUSTMENT`, `DECAY_RECOVERY`, `THRESHOLD_BAN`,
      `PUNISHMENT_APPLIED`, `REPORT_REWARDED`) in `backend/.../elo/EloReasonCode.java`
- [ ] T004 Create `EloProfileEntity` (`elo_profiles`: `owner` PK, `chatElo`/`gameplayElo`,
      per-track `updatedAt`, `metaData`) in `backend/.../elo/EloProfileEntity.java`
- [ ] T005 Create `EloEventEntity` (`elo_events`, append-only, indexed on `identifier` and
      `owner`) in `backend/.../elo/EloEventEntity.java`
- [ ] T006 [P] Create `EloProfileRepository`/`EloEventRepository` (with `findByOwner`
      pagination) in `backend/.../elo/EloProfileRepository.java` /
      `backend/.../elo/EloEventRepository.java`
- [ ] T007 [P] Create `EffectiveEloSettings` (resolved baseline/thresholds/perma-ban
      template config record) in `backend/.../elo/EffectiveEloSettings.java`
- [ ] T008 [P] Add the Liquibase changeset(s) for `elo_profiles`/`elo_events` in
      `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- [ ] T009 [P] Add `blackhole.elo.*` config keys (baseline, soft/hard thresholds, perma-ban
      templates, auto-ban durations, decay recovery/interval/sweep-cron, chat
      flag-threshold/delta/patterns/severity, evidence-retention-days) in
      `backend/src/main/resources/application.yml`

**Checkpoint**: Foundation ready.

---

## Phase 3: User Story 1 - Automatic punishment without a moderator online (Priority: P1) 🎯 MVP

**Goal**: Any standing adjustment that crosses the soft or hard threshold downward
auto-applies a punishment, without a moderator, and never re-triggers while still below
(spec FR-004–FR-009).

**Independent Test**: Drive a standing below the soft threshold via any signal and
confirm an immediate, system-attributed temporary punishment; confirm no re-trigger on a
further violation while still below.

- [ ] T010 [US1] Implement `EloService.applyDelta` as the single write path: reconcile
      pending decay, apply the delta, persist the profile, write an `EloEventEntity`, then
      run the threshold check — in `backend/.../elo/service/EloService.java` (depends on
      T004–T007)
- [ ] T011 [US1] Implement `checkThresholds` — fire only on a genuine downward crossing
      (previously at/above, now below), never on "already below" — in
      `backend/.../elo/service/EloService.java` (depends on T010)
- [ ] T012 [US1] Implement `triggerSoftAutoBan` — find-or-create a per-track temporary
      punishment template and apply it via `PunishmentApplicationService`, attributed to
      the deterministic `SYSTEM_ELO_SOURCE` UUID — in
      `backend/.../elo/service/EloService.java` (depends on T011)
- [ ] T013 [US1] Implement `triggerPermaBan` — apply the explicitly-configured perma-ban
      template only; if none is configured, log and skip rather than inventing one, and
      publish `elo.threshold_crossed` with `templateConfigured: false` so the gap is
      observable — in `backend/.../elo/service/EloService.java` (depends on T011)

**Checkpoint**: Automatic threshold-crossing punishment is independently functional.

---

## Phase 4: User Story 2 - Chat is screened for toxicity automatically (Priority: P1)

**Goal**: Every chat message is scored; flagged messages dock chat standing and leave a
reviewable, hash-only evidence trail, never the raw text (spec FR-002, FR-003, FR-014).

**Independent Test**: Submit a message scoring above the flag threshold and confirm the
chat standing drop, an evidence record without raw text, and that a below-threshold
message leaves no trace.

- [ ] T014 [P] [US2] Define the `ToxicityScorer` pluggable interface in
      `backend/.../elo/ToxicityScorer.java`
- [ ] T015 [US2] Implement `RuleBasedToxicityScorer` (configurable keyword-substring
      placeholder, explicitly swappable) in `backend/.../elo/RuleBasedToxicityScorer.java`
      (depends on T014)
- [ ] T016 [P] [US2] Create `ChatSignalDTO` (SHA-512-pattern `owner`, message) and
      `ChatToxicityResult` (flagged, score) in `backend/.../elo/dto/ChatSignalDTO.java` /
      `backend/.../elo/dto/ChatToxicityResult.java`
- [ ] T017 [US2] Implement `ChatToxicityService.evaluate` — score via `ToxicityScorer`;
      below threshold, no-op; at/above, hash the message (never persist raw text), record
      `PunishmentEvidenceEntity`, and call `EloService.applyDelta` with `TOXICITY_FLAG` —
      in `backend/.../elo/service/ChatToxicityService.java` (depends on T010, T014–T016)
- [ ] T018 [US2] Implement `POST /elo/chat` in `EloController` delegating to
      `ChatToxicityService.evaluate` — in `backend/.../elo/controller/EloController.java`
      (depends on T017)

**Checkpoint**: Chat-toxicity scoring is independently functional.

---

## Phase 5: User Story 3 - Standing recovers when a player stops violating rules (Priority: P2)

**Goal**: A below-baseline standing recovers over time, capped at baseline, even for a
player who never reconnects (spec FR-010, FR-011).

**Independent Test**: Lower a standing, let the recovery interval elapse, confirm it
moves toward baseline (never past); confirm recovery still applies without any further
player activity.

- [ ] T019 [US3] Implement lazy `reconcileDecay` inside `applyDelta`'s call path — capped
      at baseline, capped so it only restores, never crosses it — in
      `backend/.../elo/service/EloService.java` (depends on T010)
- [ ] T020 [US3] Implement `EloDecaySweeper` — nightly `@Scheduled` (`blackhole.elo.decay.sweep-cron`),
      paginates all profiles in batches of 200, calls
      `EloService.reconcileDecayForProfile` for each — in
      `backend/.../elo/EloDecaySweeper.java` (depends on T019)

**Checkpoint**: Decay recovery is independently functional, including for idle players.

---

## Phase 6: User Story 4 - Network operators can review standing and history (Priority: P2)

**Goal**: A player's current standing and full change history are retrievable, with a
clear "none exists" signal rather than a fabricated baseline (spec FR-012).

**Independent Test**: Query standing/history for a player with data and confirm full
detail; query for one without and confirm a clear empty/404 result, not a fabricated
profile.

- [ ] T021 [P] [US4] Create `EloProfileDTO`/`EloEventDTO` response shapes in
      `backend/.../elo/dto/EloProfileDTO.java` / `backend/.../elo/dto/EloEventDTO.java`
- [ ] T022 [US4] Implement `EloService.getProfile(owner)` / `getHistory(owner, pageable)`
      wrapping the repositories — in `backend/.../elo/service/EloService.java` (depends on
      T006, T021)
- [ ] T023 [US4] Implement `GET /elo/{owner}` / `GET /elo/{owner}/history` in
      `EloController` delegating to `EloService`, mapping "no profile" to 404 rather than
      a fabricated baseline — in `backend/.../elo/controller/EloController.java` (depends
      on T022)

**Checkpoint**: Operator review is independently functional; all four user stories done.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: The genuinely remaining item after the layering fix (PR #121) already
closed `plan.md`'s Principle III PARTIAL for the read endpoints.

- [ ] T024 Consolidate `ChatSignalDTO`/`ChatToxicityResult`/`EloProfileDTO`/`EloEventDTO`
      into a sealed marker interface with `Request`/`Response`/`Error` variants per
      `micronaut-dto-contract` — in `backend/.../elo/dto/` (plan.md Constitution Check,
      Principle III — DTO-shape sub-finding, still open after PR #121's repository-access
      fix)
- [ ] T025 Extract an `EloApi` interface carrying the `@Operation`/`@ApiResponse`
      annotations currently on `EloController` directly, per `micronaut-openapi-contract`
      — in `backend/.../elo/controller/` (do this alongside T024)
- [ ] T026 [P] Run `quickstart.md`'s validation scenarios end to end against real Docker
      infra to confirm current behavior still matches

---

## Dependencies & Execution Order

- **Setup → Foundational**: Foundational blocks all user stories.
- **US1 (P1)**: Independent once Foundational is done — the MVP.
- **US2 (P1)**: Independent of US1 except sharing `EloService.applyDelta` (T010) as a
  dependency, not a behavioral coupling.
- **US3 (P2)**: Extends `applyDelta`'s call path (T010) with decay; independent of US1/US2
  otherwise.
- **US4 (P2)**: Fully independent — pure reads, no dependency on US1–US3's write paths
  beyond the entities/repositories from Foundational.
- **Polish**: Depends on all four stories (touches the DTOs/controller they all share).

### Parallel Opportunities

- T002/T003 (enums), T006/T007/T008/T009 (repos/settings/changeset/config) in parallel.
- T014/T016 (US2 interface + DTOs) in parallel.
- T021 (US4 DTOs) can start as soon as Foundational is done, in parallel with US1–US3.
- T026 (validation) in parallel with T024/T025 if validating current behavior as a
  baseline before the DTO-shape change.

## Implementation Strategy

Already delivered in full (T001–T023) via the original implementation plus the PR #121
layering fix. Remaining scope is exactly T024–T026 — recommend `/speckit-converge` next
for an independent confirmation, then `/speckit-implement` for T024–T026 specifically.
