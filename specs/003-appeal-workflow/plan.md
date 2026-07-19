# Implementation Plan: Appeal Workflow

**Branch**: `003-appeal-workflow` | **Date**: 2026-07-19 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-appeal-workflow/spec.md`

**Note**: This is a **retroactive** plan — the feature is already implemented
(`backend/src/main/java/net/onelitefeather/blackhole/backend/appeal/`). It documents the
actual shipped technical approach against the spec, and runs the Constitution Check
against real code rather than a proposed design, so gaps are surfaced honestly instead
of glossed over.

## Summary

Let a player contest a punishment with a written statement (spec FR-001), gate every
submission through a fixed, versioned, configuration-driven eligibility checklist before
any human ever sees it (FR-003/FR-006), and let a network operator decide an eligible
appeal as exactly one of three outcomes — full lift, duration reduction, or denial
(FR-008) — with a structural ceiling that the network's most severe punishment tier can
never be fully lifted through an appeal, only shortened (FR-007/FR-009). The checklist
requires a minimum wait since the punishment was issued, deliberately shorter for
auto-issued punishments than manually-issued ones (FR-004), and blocks a new appeal for a
configurable cooldown after a prior one was denied or ruled ineligible (FR-005) —
regardless of pending appeals, which the cooldown intentionally ignores. Eligibility
evaluation (`AppealEligibilityService`) and decision mechanics (`AppealDecisionService`)
are each their own service, but `AppealController` also holds business logic itself
(decision validity, self-review rejection, the severe-tier full-lift ban, expiry
validation) inline rather than delegating all of it — documented honestly below rather
than glossed over.

## Technical Context

**Language/Version**: Java 25 (Gradle toolchain, `options.release = 25` set at the root
build for every subproject).

**Primary Dependencies**: Micronaut 5.0.2 platform (`micronaut-http`,
`micronaut-data-spring-jpa` + `micronaut-hibernate-jpa` for persistence,
`micronaut-serde-jackson` for DTO serialization, `micronaut-validation` for request
validation, `micronaut-openapi` for the Swagger annotations); the existing `punishment`
feature package (`PunishmentEntity`, `PunishmentRepository`, `PunishmentProfileEntity`,
`PunishmentProfileRepository`, `CacheInvalidationPublisher`) and `elo` feature package
(`EloProfileEntity`, `EloProfileRepository`, `EloService.SYSTEM_ELO_SOURCE`, `EloTrack`)
are consumed, not owned, by this feature; `events` package (`DomainEventPublisher`) is
used directly from the controller (not wrapped by a service) to publish
`appeal.submitted`/`appeal.resolved`; `phoca` (`Expirable`, `Metadata`) supplies the
metadata-key constants used to read/write a punishment's creation date and expiration.

**Storage**: MariaDB via Hibernate/JPA, schema owned by Liquibase changesets under
`backend/src/main/resources/db/changelog/db.changelog-master.xml`. One table: `appeals`
(`create-appeals` changeset, `constraints-appeals` for its indexes and the FK to
`punishments`, plus a later `drop-appeals-tenant` changeset removing a `tenant_id` column
left over from the pre-single-tenant schema — consistent with the tenant-removal history,
not a new violation). `eligibility_check_result` and `meta_data` are JSON columns via the
same `MapStringObjectConverter` pattern used elsewhere in the backend; `status` is a
`tinyint` ordinal enum column, enforced only at the Hibernate layer (native Liquibase has
no `CHECK`-expression changeType — see `.claude/rules/database.md`).

**Testing**: JUnit 5 is wired at the root (`useJUnitPlatform()`), but **no test sources
exist** for this feature, or any feature — no `backend/src/test` directory exists yet in
this repo. Same gap already recorded in `specs/001-elo-engine/plan.md`; not re-litigated
here.

**Target Platform**: Linux server, single Micronaut HTTP API deployment per network
(this repo's `backend` module); no offline/embedded execution mode.

**Project Type**: Web service — a feature package within an existing multi-module
backend, not a standalone project.

**Performance Goals**: No formal target was set when this feature was built. Both write
paths (`POST /appeal/` submission, `POST /appeal/{id}/review`) run synchronously in the
request path with a handful of single-row lookups each (punishment, ELO profile, prior
appeals by punishment+status) — no batch or background processing is involved, unlike the
ELO engine's nightly decay sweep.

**Constraints**: `@Transactional` is unusable in this codebase (constitution Principle
VI) — `AppealDecisionService.applyDecision`'s profile/punishment read-modify-write is not
atomic with `AppealController.review`'s subsequent `appealRepository.update` that marks
the appeal decided. A crash between the two would leave the punishment revoked/reduced
but the appeal still `ELIGIBLE_PENDING_REVIEW`, or vice versa — an accepted race window in
the same spirit as the one already documented for `EloService`, not newly introduced by
this plan. All tunables (`min-days-manual`, `min-days-auto`, `repeat-appeal-cooldown-days`)
are `@Value`-injected from `blackhole.appeal.*` keys in `application.yml`, per the
constitution's env-var-driven config convention.

**Scale/Scope**: Single-network deployment (no multi-tenancy, per constitution Principle
I); scope is one appeal per submission against exactly one punishment, with an unbounded,
paginated `GET /appeal/` list for operators — no bulk-decision or queue-assignment
functionality exists or is implied by the spec.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Status | Notes |
|---|-----------|--------|-------|
| I | GDPR-by-Design, Single-Tenant | ✅ PASS | `appellantHash` is enforced as a 128-hex-char SHA-512 hash by `AppealSubmissionDTO`'s `@Pattern`, never a raw player UUID; `AppealEntity` carries no `tenantId` (a leftover `tenant_id` column from the pre-single-tenant schema was dropped by the later `drop-appeals-tenant` changeset, appended not edited). |
| II | No Auth Layer, Trust via Network Boundary | ✅ PASS | No `@Secured` anywhere in `AppealController`. The self-review check (`review.reviewerId().equals(punishment.getSource())`, FR-011) is exactly the kind of best-effort, unverified-identity comparison the constitution anticipates — both values are client-supplied UUIDs with nothing behind them proving who actually sent the request. `decidedBy`/`reviewerId` are stored as unverified client input, matching the documented "known gap." |
| III | Layered Backend: Controller / Service / DTO Contract | ❌ **FAIL** | `AppealController` injects **both** `AppealRepository` and `PunishmentRepository` directly (not just one, unlike the ELO engine's read-only exception) and performs substantial business/validation branching inline in `review()`: the "awaiting review" status guard, the valid-decision-set check, the self-review rejection, the SEVERE-tier full-lift ban, and the future-expiry validation for duration reductions all live in the controller before it ever calls `AppealDecisionService.applyDecision`. Only the eligibility evaluation (`submit()` → `AppealEligibilityService.evaluate`) and the actual revoke/reduce mechanics (`review()` → `AppealDecisionService.applyDecision`) are properly delegated. `@Operation`/`@ApiResponse` annotations also live directly on `AppealController`'s methods rather than a dedicated `*Api` interface (`micronaut-openapi-contract`), and `AppealDTO`/`AppealSubmissionDTO`/`AppealReviewDTO` are plain records with no sealed-marker `Error` variant — failures are HTTP status codes (404/400/403/409), not a DTO variant (`micronaut-dto-contract`). This is a real, more extensive violation than the ELO engine's read-only one, not a rubber-stamp PASS. See Complexity Tracking. |
| IV | Dual-ELO Punishment Engine Stays Automatic | ✅ PASS | This feature never gates or delays the automatic threshold-crossing punishment itself — it only governs appeals filed *after* a punishment (automatic or manual) already exists. `isAutoTriggered` reads `EloService.SYSTEM_ELO_SOURCE` to shorten the wait period for auto-issued punishments (FR-004), and the supporting-standing signal (FR-018, US5) reads `EloProfileEntity` purely as informational context that never affects eligibility — consistent with "works without a human in the loop" staying intact for the punishment side, with the human only ever entering at the appeal-review step. |
| V | No App-Level Hardening Where Infra Already Owns It | ✅ PASS / N/A | No caller-controlled URL surface, no new rate limiter added by this feature. |
| VI | Schema Append-Only, `@Transactional` Off the Table | ✅ PASS | No `@Transactional` used anywhere in the feature; the `appeals` table's three changesets (`create-appeals`, `constraints-appeals`, `drop-appeals-tenant`) are each appended, none edited in place. The resulting non-atomicity between `AppealDecisionService.applyDecision` and the controller's subsequent `appealRepository.update` is an accepted, documented race window (see Technical Context), not silently ignored. |

**Gate result**: one FAIL (Principle III), on pre-existing code, more substantial than the
ELO engine's PARTIAL. Not blocking this retroactive plan — the gate's purpose is to stop
*new* violations, and this documents an existing one for visibility — but it is real and
should become a task via `/speckit-tasks`, ideally re-checked by `/speckit-converge`.

**Post-Phase-1 re-check**: `research.md`, `data-model.md`, `contracts/appeal-api.md`, and
`quickstart.md` describe the existing implementation as-is and prescribe no new code — the
Phase 1 design artifacts introduce no additional Constitution violations beyond the
Principle III finding above. Gate result unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/003-appeal-workflow/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md         # Phase 1 output
├── quickstart.md         # Phase 1 output
├── contracts/
│   └── appeal-api.md     # Phase 1 output
└── tasks.md              # Phase 2 output (/speckit-tasks — not created by this command)
```

### Source Code (repository root)

```text
backend/src/main/java/net/onelitefeather/blackhole/backend/appeal/
├── AppealEntity.java              # JPA entity: appeals
├── AppealRepository.java
├── AppealStatus.java              # enum: lifecycle state
├── DecisionOutcome.java           # enum: APPLIED | PUNISHMENT_NOT_ACTIVE
├── EligibilityResult.java         # record: eligible/severe/checklistSnapshot
├── controller/
│   └── AppealController.java      # POST /appeal/, GET /appeal/, POST /appeal/{id}/review
├── dto/
│   ├── AppealDTO.java
│   ├── AppealReviewDTO.java
│   └── AppealSubmissionDTO.java
└── service/
    ├── AppealDecisionService.java     # revoke/reduce mechanics against an active punishment
    └── AppealEligibilityService.java  # versioned checklist evaluation at submission time

backend/src/main/resources/
├── application.yml               # blackhole.appeal.* tunables
└── db/changelog/                 # Liquibase changesets for the appeals table

backend/src/test/                 # does not exist yet — see Technical Context "Testing"
```

**Structure Decision**: No new module or service boundary — the appeal workflow is fully
contained in the existing `appeal/` feature package of the `backend` module, following
the same package-by-feature layout (`controller/`, `service/`, `dto/` subpackages plus
top-level entity/repository/enum classes) as every other backend feature. This matches
the "Option: single Micronaut web-service module" shape, not a multi-project split.

## Complexity Tracking

> Fill ONLY if Constitution Check has violations that must be justified

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|---------------------------------------|
| `AppealController` injects `AppealRepository`/`PunishmentRepository` directly and performs decision-validity, self-review, SEVERE-tier, and expiry-validation checks inline instead of in a service (Principle III) | Not "needed" — accepted historical debt, not a justified design choice. Recorded here rather than silently passed so it stays visible. | An `AppealReviewService` wrapping the lookup + all four inline checks + the call into `AppealDecisionService` would restore the controller to a thin HTTP adapter with minimal behavioral risk; recommended as a task via `/speckit-tasks` and to be re-checked by `/speckit-converge` rather than fixed silently inside this planning pass. |
