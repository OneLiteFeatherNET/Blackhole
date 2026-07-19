# Tasks: Punishment Core

**Input**: Design documents from `specs/002-punishment-core/` (`spec.md`, `plan.md`,
`data-model.md`, `contracts/punishment-api.md`, `research.md`, `quickstart.md`)

**Note**: This is a **retroactive** task breakdown — the feature is already implemented,
including the `PunishmentTemplateService` extraction and sweep-interval config fix (PR
#122) that `plan.md`'s Constitution Check still lists as an open FAIL (that check
predates the fix). T001–T031 below describe already-shipped work, included so this list
is a complete checklist `/speckit-converge` can verify against. **T032–T036 are the
genuinely outstanding items** — two of which (T034, T035) are real correctness gaps
found during review, not just layering cleanup: `PunishmentEntity.template` is a live
reference rather than a snapshot, so editing a template retroactively changes historical
punishment display (contradicts spec US5 Acceptance Scenario 2); deleting a
still-referenced template throws an unhandled DB exception instead of the clean
"unaffected" behavior spec US5 Acceptance Scenario 3 describes.

**Tests**: Not included — no test sources exist for this feature.

**Organization**: Grouped by user story per `spec.md`'s priorities (US1/US2 = P1,
US3/US4 = P2, US5 = P3).

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [ ] T001 Create the `punishment/` feature package structure (`controller/`, `service/`,
      `dto/` subpackages) in `backend/src/main/java/net/onelitefeather/blackhole/backend/punishment/`

---

## Phase 2: Foundational (Blocking Prerequisites)

- [ ] T002 [P] Define `PunishType` enum (`SERVER`, `NETWORK`, `CHAT`) in
      `backend/.../punishment/PunishType.java`
- [ ] T003 [P] Define `EvidenceType` enum (`CHAT_MESSAGE`, `ANTICHEAT_FLAG`, `REPORT`,
      `MANUAL_NOTE`) in `backend/.../punishment/EvidenceType.java`
- [ ] T004 Create `PunishmentTemplateEntity` (`punishment_templates`: `identifier`,
      `reason`, `type`, `eloDelta` default 0, `metaData` carrying an optional ISO-8601
      duration) in `backend/.../punishment/PunishmentTemplateEntity.java` (depends on T002)
- [ ] T005 Create `PunishmentEntity` (`punishments`: `identifier` as a 22-char
      `IdGenerator`-produced id, `source`, `type` **snapshotted** at creation, `scope`,
      live `@ManyToOne template` FK, `metaData` with creation/update/expiration dates) in
      `backend/.../punishment/PunishmentEntity.java` (depends on T004)
- [ ] T006 [P] Create `PunishmentEvidenceEntity` (`punishment_evidence`: FK to
      `punishments`, `evidenceType`, `referenceId`, `capturedContentHash` — no raw-content
      column at all, per FR-011 — `retentionExpiresAt`) in
      `backend/.../punishment/PunishmentEvidenceEntity.java` (depends on T003, T005)
- [ ] T007 [P] Create `PunishmentProfileEntity` (`punishment_profiles` in the sibling
      `profile/` package: `owner` PK, unique-FK `activeBan`/`activeChatBan` slots,
      eager-fetched `history` join list) in
      `backend/src/main/java/net/onelitefeather/blackhole/backend/profile/PunishmentProfileEntity.java`
      (depends on T005)
- [ ] T008 [P] Create `PunishmentTemplateRepository`/`PunishmentRepository`/
      `PunishmentEvidenceRepository`/`PunishmentProfileRepository` in their respective
      packages (depends on T004–T007)
- [ ] T009 [P] Add the Liquibase changeset(s) for `punishment_templates`/`punishments`/
      `punishment_profiles`/`punishment_profiles_punishments`/`punishment_evidence`
      (append-only — `tenant_id` columns dropped via dedicated later changesets, never by
      editing the original `createTable`) in
      `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- [ ] T010 [P] Define `RedisTopology` (key/channel name constants) and
      `PunishmentSyncMessage` (wire format shared with Velocity's mirror) in
      `backend/.../punishment/RedisTopology.java` /
      `backend/.../punishment/PunishmentSyncMessage.java`

**Checkpoint**: Foundation ready.

---

## Phase 3: User Story 1 - Apply a punishment that takes effect everywhere immediately (Priority: P1) 🎯 MVP

**Goal**: Applying a template-based punishment creates it and propagates it to every
enforcement point within a bounded delay (spec FR-001–FR-003, FR-006, FR-012).

**Independent Test**: Apply a punishment, confirm the response reflects it, and confirm
it becomes visible via the Redis mirror within seconds; apply against a nonexistent
template and confirm a clear rejection.

- [ ] T011 [US1] Implement `PunishmentApplicationService.apply` — resolve the template
      (404/empty if missing), compute expiry from the template's duration metadata
      (permanent if absent), create the `PunishmentEntity`, create the profile if this is
      the player's first punishment — in
      `backend/.../punishment/service/PunishmentApplicationService.java` (depends on
      T004–T008)
- [ ] T012 [US1] Implement `PunishmentRedisWriter` (sole writer of active-punishment state
      into Redis, `psetex` with the punishment's expiry as TTL) and `RedisSyncConsumer`
      (bridges `punishment.created`/`.expired`/`.revoked`/`appeal.resolved` domain events
      to it, best-effort/no-retry) — in `backend/.../punishment/PunishmentRedisWriter.java`
      / `backend/.../punishment/RedisSyncConsumer.java` (depends on T010, T011)
- [ ] T013 [US1] Implement `POST /punishment/active/{owner}/{templateId}/{source}` in
      `PunishmentEntityController`, delegating to `apply`, mapping empty to 404 — in
      `backend/.../punishment/controller/PunishmentEntityController.java` (depends on T011)

**Checkpoint**: Applying a punishment and having it propagate is independently functional.

---

## Phase 4: User Story 2 - A player never carries two active punishments on the same track (Priority: P1)

**Goal**: A new punishment on an occupied track rotates the prior occupant into history;
the two tracks stay independent (spec FR-004, SC-002).

**Independent Test**: Apply twice to the same track and confirm only the second is
active with the first in history; apply to the other track and confirm no interference.

- [ ] T014 [US2] Implement the active-slot rotation inside `apply` — move any existing
      occupant of the target track (`activeBan` for SERVER/NETWORK, `activeChatBan` for
      CHAT) into `history` before assigning the new punishment to that slot — in
      `backend/.../punishment/service/PunishmentApplicationService.java` (depends on T011)
- [ ] T015 [US2] Add the **unique** DB constraint on
      `punishment_profiles.active_ban_identifier`/`active_chat_ban_identifier` so
      at-most-one-active-per-track is structurally enforced, not just application logic —
      in the Liquibase changeset from T009 (depends on T009)

**Checkpoint**: At-most-one-active-per-track is independently verifiable, including at
the DB level.

---

## Phase 5: User Story 3 - A temporary punishment lifts itself automatically (Priority: P2)

**Goal**: An expired temporary punishment is treated as inactive immediately on direct
evaluation, and moved into history + propagated within a bounded delay even with no
triggering read (spec FR-007, SC-003).

**Independent Test**: Apply a short-duration punishment, wait past expiry, confirm a
direct read treats it as inactive even before housekeeping runs, then confirm
housekeeping moves it to history and updates the propagated state.

- [ ] T016 [US3] Implement `PunishmentExpiry.isExpired` (shared static helper: an
      `Expirable.META_DATA_KEY_EXPIRATION_DATE` in the past means expired; absent means
      permanent) in `backend/.../punishment/PunishmentExpiry.java` (depends on T005)
- [ ] T017 [US3] Implement lazy expiry on profile read — `ProfileController.getById`
      evaluates `PunishmentExpiry.isExpired` on each active slot before returning, so a
      stale row is never reported active even before the sweep runs — in
      `backend/src/main/java/net/onelitefeather/blackhole/backend/profile/controller/ProfileController.java`
      (depends on T016)
- [ ] T018 [US3] Implement `PunishmentExpirySweeper` — `@Scheduled` (default 1 minute,
      `blackhole.punishment.expiry.sweep-interval`), pages profiles with an active slot in
      batches of 200, moves expired occupants to history, publishes `punishment.expired`
      — in `backend/.../punishment/PunishmentExpirySweeper.java` (depends on T016)

**Checkpoint**: Automatic expiry is independently functional, both lazily and via sweep.

---

## Phase 6: User Story 4 - Staff can lift a punishment early (Priority: P2)

**Goal**: An active punishment can be revoked on demand, marked revoked (not expired) in
history, and the revocation propagates network-wide (spec FR-008, FR-013).

**Independent Test**: Revoke an active punishment and confirm it's immediately inactive
and marked revoked in history; attempt to revoke a track with nothing active and confirm
a clear failure.

- [ ] T019 [US4] Implement `PunishmentApplicationService.revokeBan` /`revokeMute` — stamp
      `revokedBy`/update-date metadata, move the occupant into history, clear the slot,
      publish `punishment.revoked`; return empty if nothing is active on that track — in
      `backend/.../punishment/service/PunishmentApplicationService.java` (depends on T011,
      T014)
- [ ] T020 [US4] Implement `POST /punishment/active/{owner}/ban/revoke/{source}` and
      `.../mute/revoke/{source}` in `PunishmentEntityController`, mapping empty to 404 —
      in `backend/.../punishment/controller/PunishmentEntityController.java` (depends on
      T019)

**Checkpoint**: Manual revocation is independently functional.

---

## Phase 7: User Story 5 - Network operators maintain reusable punishment templates (Priority: P3)

**Goal**: Templates can be created, updated, and removed independently of any specific
punishment application (spec FR-010).

**Independent Test**: Create, update, and delete a template and confirm the catalog
reflects each change without affecting already-applied punishments.

- [ ] T021 [P] [US5] Create `PunishTemplateDTO`/`PunishTemplateRequestDTO` in
      `backend/.../punishment/dto/PunishTemplateDTO.java` /
      `backend/.../punishment/dto/PunishTemplateRequestDTO.java`
- [ ] T022 [US5] Implement `PunishmentTemplateService` — `create` (reject a non-null
      `identifier`), `update` (require identifier, 404 if unknown), `remove`, `findAll`,
      `find` — in `backend/.../punishment/service/PunishmentTemplateService.java`
      (depends on T004, T008, T021)
- [ ] T023 [US5] Implement the 5 endpoints of `PunishmentTemplateController`
      (`POST /template/`, `POST /template/update`, `DELETE /template/delete/{id}`,
      `GET /template/`, `GET /template/{id}`) delegating to `PunishmentTemplateService` —
      in `backend/.../punishment/controller/PunishmentTemplateController.java` (depends on
      T022)

**Checkpoint**: Template management is independently functional; all five user stories
done.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: The genuinely remaining items — two of which are real correctness gaps, not
just layering polish.

- [ ] T024 [P] Implement `PunishmentEntityController.getAll` via
      `PunishmentApplicationService.findAll(Pageable)` (already delegates post-PR #122 —
      verify, not build)
- [ ] T025 Document `PunishmentApplicationService.apply`/`revoke`'s read-modify-write race
      window (not atomic — `@Transactional` unusable) in a class/method Javadoc, matching
      `EloService`'s documented-limitation style — in
      `backend/.../punishment/service/PunishmentApplicationService.java` (plan.md
      Constitution Check, Principle VI, PARTIAL)
- [ ] T026 Consolidate `PunishTemplateDTO`/`PunishTemplateRequestDTO`/`PunishEntryDTO`/
      `PunishmentEvidenceDTO` into sealed marker interface(s) with
      `CreateRequest`/`UpdateRequest`/`Response`/`Error` variants per
      `micronaut-dto-contract` — in `backend/.../punishment/dto/` (plan.md Constitution
      Check, Principle III)
- [ ] T027 Extract `PunishmentApi`/`PunishmentTemplateApi` interfaces carrying the
      `@Operation`/`@ApiResponse` annotations currently on both controllers directly, per
      `micronaut-openapi-contract` — in `backend/.../punishment/controller/` (do alongside
      T026)
- [ ] T028 **[Correctness]** Snapshot the template's `reason` (and any other
      display-relevant fields) onto `PunishmentEntity` at creation time, the same way
      `type` is already snapshotted, instead of embedding a live `@ManyToOne template`
      reference — currently, editing a template's `reason` retroactively changes how
      already-applied historical punishments display, contradicting spec US5 Acceptance
      Scenario 2 ("already-applied punishments unaffected") — in
      `backend/.../punishment/PunishmentEntity.java` and its `toDTO()` (requires a new
      Liquibase changeset adding the snapshotted column(s) — append-only, do not edit the
      original `createTable` changeset)
- [ ] T029 **[Correctness]** Make `DELETE /template/delete/{identifier}` fail cleanly
      (e.g. a 409 or a clear error body) instead of an unhandled DB foreign-key exception
      when punishments still reference the template — currently violates FR-010's "let
      network operators... remove" (removal unconditionally throws once any punishment
      references it) and spec US5 Acceptance Scenario 3 — in
      `backend/.../punishment/service/PunishmentTemplateService.java` (depends on T022;
      resolve alongside or after T028, since T028's snapshotting is what actually makes
      "already-applied punishments unaffected" true once the FK is no longer needed for
      display — consider whether the FK/live-reference can be dropped entirely once T028
      lands)
- [ ] T030 [P] Run `quickstart.md`'s validation scenarios end to end against real Docker
      infra to confirm current behavior still matches

---

## Dependencies & Execution Order

- **Setup → Foundational**: Foundational blocks all user stories.
- **US1 (P1)**: MVP — independent once Foundational is done.
- **US2 (P1)**: Extends US1's `apply` (T011) directly — implement together in practice,
  though independently testable.
- **US3 (P2)**: Independent of US1/US2's write path; only needs Foundational entities.
- **US4 (P2)**: Extends the same rotation mechanics as US2 (T014) via `revoke`.
- **US5 (P3)**: Fully independent — templates exist and are managed separately from
  application.
- **Polish**: T024/T025 depend on US1/US2/US4's service methods existing; T026/T027
  touch every story's DTOs/controllers; T028/T029 depend on US5's template service (T022)
  and US1's entity (T005); T030 depends on everything.

### Parallel Opportunities

- T002/T003 (enums) in parallel; T006/T007/T008/T009/T010 in parallel once T004/T005
  exist.
- T021 (US5 DTOs) can start as soon as Foundational is done, in parallel with US1–US4.
- T024/T030 in parallel with T025–T029 (different files/concerns).

## Implementation Strategy

Already delivered in full (T001–T023) via the original implementation plus the PR #122
layering fix. Remaining scope is T024 (verify-only) and **T025–T030**, with **T028 and
T029 prioritized** — they're real correctness gaps against spec US5, not just layering
cleanup. Recommend `/speckit-converge` next for independent confirmation, then
`/speckit-implement` for T025–T030.

---

## Phase 9: Convergence

- [ ] T031 Decide and act on `RedisTopology`'s hardcoded key/channel-name constants per
      plan.md's Technical Context ("worth flagging for `/speckit-tasks`") — either make
      them env-var configurable (coordinated with Velocity's matching mirror
      implementation) or add an explicit code comment documenting why they're
      intentionally fixed protocol constants rather than a tunable; no existing task
      traces to this plan.md-flagged item (partial)
