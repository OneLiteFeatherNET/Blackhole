# Implementation Plan: Dual-ELO Punishment Engine

**Branch**: `001-elo-engine` | **Date**: 2026-07-19 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-elo-engine/spec.md`

**Note**: This is a **retroactive** plan — the feature is already implemented
(`backend/src/main/java/net/onelitefeather/blackhole/backend/elo/`). It documents the
actual shipped technical approach against the spec, and runs the Constitution Check
against real code rather than a proposed design, so gaps are surfaced honestly instead
of glossed over.

## Summary

Maintain two independent per-player behavior scores (chat, gameplay) and automatically
apply a temporary or permanent punishment the moment either score crosses a soft or hard
threshold, without requiring a moderator online (spec FR-001, FR-005, FR-006). All score
mutations — chat-toxicity flags, anticheat signals, actioned reports, manual
adjustments, and nightly decay — funnel through one write path
(`EloService.applyDelta`) so the threshold check and audit trail can never be bypassed
(FR-004, FR-009). Chat messages are scored in-process by a pluggable `ToxicityScorer`
and never persisted in raw form (FR-002, FR-003, FR-014). A below-baseline score decays
back toward baseline over time via lazy on-write reconciliation plus a nightly
`@Scheduled` sweep, so recovery does not depend on the player reconnecting (FR-010,
FR-011). Two read-only REST endpoints expose current standing and full history for
operator review (FR-012).

## Technical Context

**Language/Version**: Java 25 (Gradle toolchain, `options.release = 25` set at the root
build for every subproject).

**Primary Dependencies**: Micronaut 5.0.2 platform (`micronaut-http`,
`micronaut-data-spring-jpa` + `micronaut-hibernate-jpa` for persistence,
`micronaut-serde-jackson` for DTO serialization, `micronaut-validation` for request
validation, `micronaut-scheduling` for `@Scheduled`, `micronaut-openapi` for the
Swagger annotations); `phoca` (this repo's shared utility module, `Durationable` for
punishment-duration metadata); the existing `punishment` feature package
(`PunishmentApplicationService`, `PunishmentTemplateRepository`) and `events` package
(`DomainEventPublisher`) are consumed, not owned, by this feature.

**Storage**: MariaDB via Hibernate/JPA, schema owned by Liquibase changesets under
`backend/src/main/resources/db/changelog/`. Two tables: `elo_profiles` (one row per
player, both track scores) and `elo_events` (append-only audit trail, indexed on
`identifier` and `owner`). `metaData` columns on both use a JSON column type via a
custom `MapStringObjectConverter`.

**Testing**: JUnit 5 is wired at the root (`useJUnitPlatform()`,
`jacocoTestReport` runs after `test`), but **no test sources currently exist** for this
feature (or any feature — no `backend/src/test` directory exists yet in this repo). This
is a real gap, not a design choice; recorded here rather than silently assumed away so
`/speckit-tasks` can decide whether to close it.

**Target Platform**: Linux server, single Micronaut HTTP API deployment per network
(this repo's `backend` module); no offline/embedded execution mode.

**Project Type**: Web service — a feature package within an existing multi-module
backend, not a standalone project.

**Performance Goals**: No formal target was set when this feature was built. Informal
expectation consistent with the rest of the backend: the chat-scoring path
(`POST /elo/chat`) runs synchronously in the request path per message, so it must stay
fast enough not to visibly delay chat delivery (single in-memory keyword scan by
default — see `RuleBasedToxicityScorer`); the nightly decay sweep paginates in batches
of 200 profiles and has no time budget since it runs off-peak (`03:00` cron default).

**Constraints**: `@Transactional` is unusable in this codebase (see constitution
Principle VI) — the threshold check in `EloService.checkThresholds` therefore does not
run atomically with the score write that triggered it. This is a documented, accepted
race window (two simultaneous violations on the exact same player+track could in theory
both pass the same threshold undetected), not something this plan re-attempts to fix.
All tunables (baseline, thresholds, decay rate/interval, auto-ban durations, chat
flag-threshold/delta/severity/patterns) are `@Value`-injected from
`blackhole.elo.*` keys in `application.yml`, per the constitution's env-var-driven
config convention.

**Scale/Scope**: Single-network deployment (no multi-tenancy, per constitution
Principle I); scope is one player's two scores plus their unbounded, paginated event
history — no cross-player aggregation or leaderboard functionality exists or is implied
by the spec.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Status | Notes |
|---|-----------|--------|-------|
| I | GDPR-by-Design, Single-Tenant | ✅ PASS | `owner` is a SHA-512 hash (enforced by `ChatSignalDTO`'s `@Pattern`), never a raw player UUID; no `tenantId` anywhere in `elo_profiles`/`elo_events`. |
| II | No Auth Layer, Trust via Network Boundary | ✅ PASS | `EloController` carries no `@Secured`; all three endpoints are open, consistent with the rest of the backend. |
| III | Layered Backend: Controller / Service / DTO Contract | ⚠️ **PARTIAL** | `EloController.getProfile` and `getHistory` inject `EloProfileRepository`/`EloEventRepository` **directly**, bypassing the service layer — a documented violation of `micronaut-controller-layer`. The write path (`POST /elo/chat`) is compliant (delegates to `ChatToxicityService`). See Complexity Tracking below. The DTO shape also doesn't follow the sealed-marker `CreateRequest`/`Response`/`Error` contract from `micronaut-dto-contract` (plain records, no `Error` variant — failures are HTTP 404, not a DTO variant) — pre-dates that convention. |
| IV | Dual-ELO Punishment Engine Stays Automatic | ✅ PASS | `checkThresholds` fires unconditionally inside `applyDelta`, with no online-moderator dependency; matches spec FR-005/FR-006/FR-007 exactly. |
| V | No App-Level Hardening Where Infra Already Owns It | ✅ PASS / N/A | This feature adds no caller-controlled-URL surface and no new rate limiter. |
| VI | Schema Append-Only, `@Transactional` Off the Table | ✅ PASS | No `@Transactional` used; the resulting race window is explicitly documented in `EloService`'s class Javadoc rather than papered over. Liquibase changesets for `elo_profiles`/`elo_events` have not been retroactively edited. |

**Gate result**: one PARTIAL (Principle III) on pre-existing code. Not blocking this
retroactive plan — the gate's purpose is to stop *new* violations, and this documents an
existing one for visibility — but it is real and should become a task (see Complexity
Tracking and the recommendation to run `/speckit-converge` after `/speckit-tasks`).

**Post-Phase-1 re-check**: `research.md`, `data-model.md`, `contracts/elo-api.md`, and
`quickstart.md` describe the existing implementation as-is and prescribe no new code —
the Phase 1 design artifacts introduce no additional Constitution violations beyond the
Principle III finding above. Gate result unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/001-elo-engine/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md         # Phase 1 output
├── quickstart.md         # Phase 1 output
├── contracts/
│   └── elo-api.md        # Phase 1 output
└── tasks.md              # Phase 2 output (/speckit-tasks — not created by this command)
```

### Source Code (repository root)

```text
backend/src/main/java/net/onelitefeather/blackhole/backend/elo/
├── EloTrack.java                 # enum: CHAT | GAMEPLAY
├── EloReasonCode.java            # enum: audit-trail cause codes
├── EloProfileEntity.java         # JPA entity: elo_profiles
├── EloProfileRepository.java
├── EloEventEntity.java           # JPA entity: elo_events (append-only)
├── EloEventRepository.java
├── EffectiveEloSettings.java     # resolved config record (baseline/thresholds/templates)
├── EloDecaySweeper.java          # nightly @Scheduled decay backstop
├── ToxicityScorer.java           # pluggable scoring interface
├── RuleBasedToxicityScorer.java  # default placeholder implementation
├── controller/
│   └── EloController.java        # POST /elo/chat, GET /elo/{owner}, GET /elo/{owner}/history
├── dto/
│   ├── ChatSignalDTO.java
│   ├── ChatToxicityResult.java
│   ├── EloEventDTO.java
│   └── EloProfileDTO.java
└── service/
    ├── EloService.java           # sole write path: applyDelta, threshold checks, decay
    └── ChatToxicityService.java  # chat scoring + evidence recording

backend/src/main/resources/
├── application.yml               # blackhole.elo.* tunables
└── db/changelog/                 # Liquibase changesets for elo_profiles / elo_events

backend/src/test/                 # does not exist yet — see Technical Context "Testing"
```

**Structure Decision**: No new module or service boundary — the ELO engine is fully
contained in the existing `elo/` feature package of the `backend` module, following the
same package-by-feature layout (`controller/`, `service/`, `dto/` subpackages plus
top-level entity/repository/enum classes) as every other backend feature. This matches
the "Option: single Micronaut web-service module" shape, not a multi-project split.

## Complexity Tracking

> Fill ONLY if Constitution Check has violations that must be justified

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|---------------------------------------|
| `EloController` injects `EloProfileRepository`/`EloEventRepository` directly for its two read-only GET endpoints, instead of going through a service (Principle III) | Not "needed" — this is accepted historical debt, not a justified design choice. Recorded here rather than silently passed so it stays visible. | A thin `EloQueryService` wrapping `findById`/`findByOwner` would fix it with minimal risk; recommended as a task via `/speckit-tasks` and to be re-checked by `/speckit-converge` rather than fixed silently inside this planning pass. |
