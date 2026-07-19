# Implementation Plan: Player Reports

**Branch**: `006-player-reports` | **Date**: 2026-07-19 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-player-reports/spec.md`

**Note**: This is a **retroactive** plan — the feature is already implemented
(`backend/src/main/java/net/onelitefeather/blackhole/backend/report/`). It documents the
actual shipped technical approach against the spec, and runs the Constitution Check
against real code rather than a proposed design, so gaps are surfaced honestly instead
of glossed over. The spec already flags the headline gap up front: **this package has no
service layer at all** — every resolution behavior, including the punishment-template
call and both ELO effects, lives directly in `ReportController.resolve()`.

## Summary

Accept a player report (reporter, reported player, category, optional description/
evidence/metadata) into an open, unresolved state, guarded by a two-tier rate limit —
per-`reporterHash` and network-wide aggregate — so the report intake itself can't become
a griefing vector (spec FR-001–FR-006). A network operator resolves a report by setting
its outcome status and, in the same request, may apply an existing punishment template to
the reported player (spec FR-009–FR-013) — the subsystem's actual integration point with
the punishment system (`PunishmentApplicationService`). When a resolution is `ACTIONED`
and the report's category maps to a behavioral track, the reported player's standing on
that track is docked via `EloService.applyDelta`; if a punishment was also demonstrably
applied as part of the same resolution, the reporter's standing on that same track is
separately rewarded (spec FR-014–FR-018) — this is the subsystem's payoff, feeding the
dual-ELO engine specified in `specs/001-elo-engine/spec.md`. Both submission and
resolution publish domain events (spec FR-007, FR-019). All of this — rate limiting,
punishment invocation, and both ELO effects — is orchestrated directly inside
`ReportController`, with no intervening service layer (see Constitution Check, Principle
III).

## Technical Context

**Language/Version**: Java 25 (Gradle toolchain, `options.release = 25` set at the root
build for every subproject).

**Primary Dependencies**: Micronaut 5.0.2 platform (`micronaut-http`,
`micronaut-data-spring-jpa` + `micronaut-hibernate-jpa` for persistence,
`micronaut-serde-jackson` for DTO serialization, `micronaut-validation` for request
validation, `micronaut-openapi` for the Swagger annotations); the existing `punishment`
feature package (`PunishmentApplicationService`, consumed for template application) and
`elo` feature package (`EloService`, `EloTrack`, `EloReasonCode`, consumed for the two
standing effects) are consumed, not owned, by this feature; `events` package
(`DomainEventPublisher`) for `report.created`/`report.resolved`.

**Storage**: MariaDB via Hibernate/JPA, schema owned by Liquibase changesets under
`backend/src/main/resources/db/changelog/`. One table: `reports`, plus an
`@ElementCollection` join table `report_evidence_references` for the evidence-UUID list.
`createdAt`/`updatedAt` are real `long` columns (not JSON-metadata entries, unlike most
other entities in this codebase) specifically so the rate-limit queries
(`countByReporterHashAndCreatedAtGreaterThan`, `countByCreatedAtGreaterThan`) can filter
on them directly — Micronaut Data can't derive a query against a field inside a JSON
blob. `metaData` uses the same `MapStringObjectConverter`/JSON-column pattern as other
feature packages.

**Testing**: JUnit 5 is wired at the root (`useJUnitPlatform()`), but **no test sources
exist** for this feature (or any feature — no `backend/src/test` directory exists yet in
this repo). Recorded here rather than silently assumed away, consistent with
`specs/001-elo-engine/plan.md`.

**Target Platform**: Linux server, single Micronaut HTTP API deployment per network
(this repo's `backend` module); no offline/embedded execution mode.

**Project Type**: Web service — a feature package within an existing multi-module
backend, not a standalone project.

**Performance Goals**: No formal target was set when this feature was built. `POST
/report/` runs two blocking `COUNT` queries (network-wide, then per-reporter) synchronously
in the request path before every insert — acceptable at the rate-limit window's own scale
(default 5/reporter, 50/network per 10 minutes) but a queryable cost floor per submission.
`POST /report/{id}/resolve` is a multi-step synchronous chain (optional punishment
application → report update → up to two ELO deltas → event publish) with no async
offload.

**Constraints**: `@Transactional` is unusable in this codebase (constitution Principle
VI) — `resolve()`'s chain of side effects (punishment application, report status update,
reported-player ELO delta, reporter ELO delta, event publish) is therefore **not
atomic**. A failure partway through (e.g. the report `update()` throwing after
`punishmentApplicationService.apply()` already succeeded) can leave a punishment applied
against a report that never recorded its own resolution. This is a real, currently
undocumented-in-code gap surfaced by this retroactive plan (see Complexity Tracking and
`research.md`), distinct from — but analogous to — the accepted race window already
documented in `EloService`. There is also no per-actor identity system (constitution
Principle II): `reporterHash`, `resolvedBy`, and `punishmentSource` are all
client-supplied and unverified, a limitation `ReportController`'s own Javadoc already
calls out explicitly.

**Scale/Scope**: Single-network deployment (no multi-tenancy, per constitution Principle
I); scope is the full report queue across all statuses, paginated, plus the two
downstream side effects (punishment application, ELO deltas) that a resolution can
trigger. No report-to-report correlation or deduplication exists or is implied by the
spec (Edge Cases: "two reports referencing the same reported player... are not merged or
deduplicated").

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Status | Notes |
|---|-----------|--------|-------|
| I | GDPR-by-Design, Single-Tenant | ✅ PASS | `reporterHash`/`reportedHash` are SHA-512 hashes (enforced by `ReportRequestDTO`'s `@Pattern(regexp = "^[a-fA-F0-9]{128}$")`), never raw player UUIDs; no `tenantId` anywhere in `ReportEntity` or `reports`/`report_evidence_references`. |
| II | No Auth Layer, Trust via Network Boundary | ✅ PASS | `ReportController` carries no `@Secured`; both endpoints are open. The absence of verified per-actor identity for `reporterHash`/`resolvedBy`/`punishmentSource` is the expected, documented state of this principle, not a violation of it — `ReportController`'s own class Javadoc names this explicitly as a systemic, deliberately-deferred gap. |
| III | Layered Backend: Controller / Service / DTO Contract | ❌ **FAIL** | `ReportController` injects `ReportRepository` directly and contains **all** business logic inline: rate-limit arithmetic and enforcement, punishment-template invocation, report mutation, both ELO-delta calls, and event publication all live in `submit()`/`resolve()`. There is no `report/service/` package at all — not a partial gap like `EloController`'s two read-only endpoints in `specs/001-elo-engine/plan.md`, but the complete absence of a service layer for this feature. `ReportDTO`/`ReportRequestDTO`/`ReportResolutionDTO` are also three independent top-level records, not the sealed-marker-interface `CreateRequest`/`UpdateRequest`/`Response`/`Error` shape from `micronaut-dto-contract` — there is no `Error` variant; failures are raw `HttpResponse` status codes (`404`, `400`, `429`) chosen inline in the controller. `@Operation`/`@ApiResponse` annotations also live directly on `ReportController` rather than a dedicated `*Api` interface, per `micronaut-openapi-contract`. See Complexity Tracking. |
| IV | Dual-ELO Punishment Engine Stays Automatic | ✅ PASS | Both standing effects go through `EloService.applyDelta`, the engine's one write path — `checkThresholds` and its own downstream auto-punishment logic still run unconditionally and without requiring a moderator online, even though *triggering* an ELO delta here originates from a moderator's manual resolution action (that manual step is the spec's own design for US2/US3, not a bypass of Principle IV). |
| V | No App-Level Hardening Where Infra Already Owns It | ✅ PASS | The per-reporter + network-wide submission rate limiter is the constitution's one explicitly-named exception ("the one existing report-submission limiter") — this *is* that limiter, not a new one added on top of it. No caller-controlled-URL surface exists in this feature. |
| VI | Schema Append-Only, `@Transactional` Off the Table | ⚠️ **PARTIAL** | No `@Transactional` is used (correctly, per the known bean-ambiguity issue) and the `reports` Liquibase changeset has not been retroactively edited. But unlike `EloService`, which documents its own non-atomicity in its class Javadoc, `ReportController.resolve()`'s equivalent multi-step non-atomicity (punishment apply → report update → ELO deltas → event publish, no rollback on partial failure) is **not** documented anywhere in the code. See Technical Context "Constraints" and Complexity Tracking. |

**Gate result**: one FAIL (Principle III) and one PARTIAL (Principle VI) on pre-existing
code. Not blocking this retroactive plan — the gate's purpose is to stop *new*
violations, and this documents existing ones for visibility — but the Principle III
finding in particular is significant enough that `/speckit-tasks` should treat
introducing a `ReportService` as a priority item, not an optional cleanup.

**Post-Phase-1 re-check**: `research.md`, `data-model.md`, `contracts/report-api.md`, and
`quickstart.md` describe the existing implementation as-is and prescribe no new code —
Phase 1 design introduces no additional Constitution violations beyond the Principle III
(FAIL) and Principle VI (PARTIAL) findings above. Gate result unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/006-player-reports/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md         # Phase 1 output
├── quickstart.md         # Phase 1 output
├── contracts/
│   └── report-api.md     # Phase 1 output
└── tasks.md               # Phase 2 output (/speckit-tasks — not created by this command)
```

### Source Code (repository root)

```text
backend/src/main/java/net/onelitefeather/blackhole/backend/report/
├── ReportCategory.java           # enum: CHAT_ABUSE | CHEATING | GRIEFING | OTHER
├── ReportStatus.java             # enum: OPEN | UNDER_REVIEW | ACTIONED | DISMISSED
├── ReportEntity.java             # JPA entity: reports (+ report_evidence_references)
├── ReportRepository.java         # rate-limit count queries + PageableRepository
├── controller/
│   └── ReportController.java     # POST /report/, GET /report/, POST /report/{id}/resolve
│                                  # — ALL business logic lives here; no service/ package exists
└── dto/
    ├── ReportDTO.java             # response shape (also doubles as the "full record" shape)
    ├── ReportRequestDTO.java      # submission request shape
    └── ReportResolutionDTO.java   # resolution request shape

backend/src/main/resources/
├── application.yml               # blackhole.report.* tunables (rate limit) + blackhole.elo.report.* (actioned-delta, reward-delta)
└── db/changelog/                 # Liquibase changeset(s) for reports / report_evidence_references

backend/src/test/                 # does not exist yet — see Technical Context "Testing"
```

**Structure Decision**: No new module or service boundary — reports are fully contained
in the existing `report/` feature package of the `backend` module. Unlike every sibling
feature package in this backend (which follow `controller/`, `service/`, `dto/`
subpackages), this package has **no `service/` subpackage at all** — that absence is
itself the Structure Decision worth naming explicitly, not an oversight in this plan's
description of it.

## Complexity Tracking

> Fill ONLY if Constitution Check has violations that must be justified

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|---------------------------------------|
| `ReportController` contains all business logic — rate limiting, punishment invocation, both ELO effects, and event publication — instead of delegating to a service (Principle III, FAIL) | Not "needed" — this is accepted historical debt, not a justified design choice. Recorded here rather than silently passed so it stays visible; it is more severe than `EloController`'s partial violation in `specs/001-elo-engine/plan.md` since **no** service exists for this package at all. | A `ReportService` (or `ReportSubmissionService` + `ReportResolutionService`) owning `ReportRepository` and orchestrating the punishment/ELO calls would fix it with moderate risk (the resolve path especially has real ordering subtleties — see `research.md`'s "fail before mutating" and "reward gated on demonstrated punishment" decisions — that a refactor must preserve exactly). Recommended as a task via `/speckit-tasks`, to be re-checked by `/speckit-converge` rather than fixed silently inside this planning pass. |
| `ReportDTO`/`ReportRequestDTO`/`ReportResolutionDTO` are independent top-level records with no `Error` variant, and `@Operation`/`@ApiResponse` live on the controller directly (Principle III, FAIL, DTO/OpenAPI sub-findings) | Same as above — pre-existing shape, not a considered tradeoff for this feature. | Consolidating into one sealed `ReportDTO` marker interface (`SubmitRequest`/`ResolveRequest`/`Response`/`Error`) and extracting a `ReportApi` interface would align with `micronaut-dto-contract`/`micronaut-openapi-contract`; deferred to the same follow-up as the service-layer fix so both land together rather than as two separate churn passes over the same file. |
| `ReportController.resolve()`'s multi-step side-effect chain (punishment apply → report update → up to two ELO deltas → event publish) is not documented as non-atomic anywhere in the code, unlike `EloService`'s analogous gap (Principle VI, PARTIAL) | Not needed — an omission, not a decision. | Adding a class/method Javadoc note mirroring `EloService`'s is low-risk and should land independent of (and likely before) the larger service-layer extraction above. |
