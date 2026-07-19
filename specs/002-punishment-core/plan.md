# Implementation Plan: Punishment Core

**Branch**: `002-punishment-core` | **Date**: 2026-07-19 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-punishment-core/spec.md`

**Note**: This is a **retroactive** plan — the feature is already implemented
(`backend/src/main/java/net/onelitefeather/blackhole/backend/punishment/`, with its
per-player state owned by the sibling `profile/` package). It documents the actual
shipped technical approach against the spec, and runs the Constitution Check against
real code rather than a proposed design, so gaps are surfaced honestly instead of
glossed over. This is the shared chokepoint the dual-ELO engine (`specs/001-elo-engine/`),
appeals, reports, and evasion detection all apply punishments through.

## Summary

Punishments are created only by applying a reusable `PunishmentTemplateEntity` to a
player (spec FR-001), never by specifying reason/kind/duration ad hoc — this keeps
every consequence traceable to a known, reviewable definition. Every apply/revoke
operation funnels through one chokepoint, `PunishmentApplicationService`, so template
lookup, expiry-metadata calculation, at-most-one-active-punishment-per-track rotation,
optional ELO-delta side effects, cache invalidation, and domain-event publishing can
never be bypassed by a second write path (FR-004, FR-005, FR-009). A player's
enforcement state is two independent slots (`activeBan` for SERVER/NETWORK,
`activeChatBan` for CHAT) plus an append-only `history` list on
`PunishmentProfileEntity`, with the at-most-one-active-per-track guarantee enforced
structurally by a unique DB constraint on each slot's FK column, not just application
logic (FR-004, SC-002). Expiry is evaluated twice: lazily whenever a profile is read
(`ProfileController.getById`) so a stale row is never reported as active even before
housekeeping runs, and by a 1-minute `@Scheduled` sweep
(`PunishmentExpirySweeper`) so a player who is never looked up still gets moved into
history and propagated (FR-007, SC-003). Network-wide propagation is best-effort:
domain events (`punishment.created`/`.expired`/`.revoked`) on the `blackhole.events`
topic exchange are mirrored into Redis by `RedisSyncConsumer`/`PunishmentRedisWriter`
for every Velocity proxy to read without hitting the backend on every login/chat
message, with no redelivery/retry — a missed write self-heals at the next mutation on
that profile, and proxies fall back to an HTTP call on a cache miss (FR-006, FR-008,
SC-001, SC-004). Evidence attached to a punishment
(`PunishmentEvidenceEntity`) stores only a content hash and a bounded
`retentionExpiresAt`, never the raw underlying content (FR-011).

## Technical Context

**Language/Version**: Java 25 (Gradle toolchain, `options.release = 25` at the root
build for every subproject).

**Primary Dependencies**: Micronaut 5.0.2 platform (`micronaut-http`,
`micronaut-data-spring-jpa` + `micronaut-hibernate-jpa` for persistence,
`micronaut-serde-jackson` for DTO serialization, `micronaut-validation`,
`micronaut-scheduling` for `@Scheduled`, `micronaut-openapi` for Swagger annotations,
`micronaut-redis`/Lettuce for the Redis mirror, `micronaut-rabbitmq` (raw client, not
`@RabbitListener`) for the Redis-sync consumer); `phoca` (`Metadata`/`Expirable`/
`Durationable` metadata-key abstractions shared with the ELO engine); consumes but does
not own the `events` package (`DomainEventPublisher`, `RabbitTopology`) and the `elo`
package (`EloService`, injected lazily via `BeanProvider` to break a constructor cycle
— `EloService` itself calls back into `PunishmentApplicationService.apply` to issue
auto-bans).

**Storage**: MariaDB via Hibernate/JPA, schema owned by Liquibase changesets under
`backend/src/main/resources/db/changelog/`. Four tables: `punishment_templates`,
`punishments` (FK to its originating template), `punishment_profiles` (one row per
player, `active_ban_identifier`/`active_chat_ban_identifier` each a **unique** FK into
`punishments` — the DB itself, not just application code, prevents two punishments
occupying the same active slot), `punishment_profiles_punishments` (the history join
table, one row per historical punishment), and `punishment_evidence` (FK to
`punishments`). All four originally carried a `tenant_id` column from the pre-removal
multi-tenant design; every one was dropped via a dedicated later changeset
(`drop-punishment-templates-tenant` etc.), never by editing the original `createTable`
changeset — a real, verified example of the append-only convention (constitution
Principle VI) being followed correctly. `meta_data` columns use a JSON column type via
`MapStringObjectConverter`, same pattern as the ELO engine.

**Testing**: JUnit 5 is wired at the root (`useJUnitPlatform()`, `jacocoTestReport`
runs after `test`), but **no test sources exist for this feature** — the only test in
the repo so far is
`backend/src/test/java/.../playerresolver/service/PlayerResolverServiceTest.java`, an
unrelated subsystem. Recorded here rather than silently assumed away, matching
`specs/001-elo-engine/plan.md`'s note on the same gap.

**Target Platform**: Linux server, single Micronaut HTTP API deployment per network
(this repo's `backend` module); the Redis mirror and Rabbit consumer run in-process
with the API, not as a separate service.

**Project Type**: Web service — a feature package within an existing multi-module
backend, not a standalone project. Its per-player state (`PunishmentProfileEntity`)
lives in the sibling `profile/` feature package rather than inside `punishment/`
itself — a pre-existing package split this plan documents rather than changes.

**Performance Goals**: No formal target recorded when this feature was built. The
apply/revoke path (`POST /punishment/active/...`) runs synchronously in the request
path, so it must stay fast enough for a moderator-facing or automated-caller UI not to
visibly stall; `PunishmentExpirySweeper` pages profiles with an active slot in batches
of 200 every 1 minute (`fixedDelay = "1m"`), independent of overall profile count since
it filters to `activeBan IS NOT NULL OR activeChatBan IS NOT NULL` rather than scanning
every profile.

**Constraints**: `@Transactional` is unusable in this codebase (constitution Principle
VI) — `PunishmentApplicationService.apply`'s read-profile / save-punishment /
optionally-apply-ELO-delta / rotate-active-slot / update-profile sequence is not
atomic. Two concurrent applications to the same owner+track could both read the same
"no active punishment yet" state and both attempt to occupy the same slot; the unique
DB constraint on `active_ban_identifier`/`active_chat_ban_identifier` would reject the
second write rather than silently double-occupying the slot, but the caller would see
an unhandled persistence exception rather than a clean "conflict" response — an
accepted, undocumented-in-code race window this plan surfaces rather than fixes.
Config is minimal for this feature specifically: no `blackhole.punishment.*` tunables
exist in `application.yml` today (unlike ELO's `blackhole.elo.*` block) — Redis
key/channel names (`RedisTopology`) and the sweep interval are hardcoded constants, not
env-var driven, a deviation from the constitution's stated config convention worth
flagging for `/speckit-tasks`.

**Scale/Scope**: Single-network deployment (no multi-tenancy, constitution Principle
I); scope is one player's two enforcement slots plus their unbounded history list
(eagerly fetched — `@OneToMany(fetch = FetchType.EAGER)` on `history`, so a
long-punished player's profile read grows with their entire history, no pagination on
that specific list today).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Status | Notes |
|---|-----------|--------|-------|
| I | GDPR-by-Design, Single-Tenant | ✅ PASS | `owner` on every profile/evidence path is a SHA-512 hash validated by `@Pattern(regexp = "^[a-fA-F0-9]{128}$")` at the controller boundary; no raw player UUID is ever persisted. `tenant_id` was present on every punishment table originally but is fully dropped via later changesets (verified in the changelog) — no `tenantId` remains in any entity or API surface. |
| II | No Auth Layer, Trust via Network Boundary | ✅ PASS | Neither `PunishmentEntityController` nor `PunishmentTemplateController` carries `@Secured`; both are fully open. `source`/`revokedBy` are caller-supplied `UUID` path variables with no verification — consistent with the documented "no per-actor identity yet" gap; this plan does not treat that as a defect. |
| III | Layered Backend: Controller / Service / DTO Contract | ❌ **FAIL** | Worse than the ELO engine's PARTIAL: `PunishmentTemplateController` has **no service at all** — it injects `PunishmentTemplateRepository` directly and calls `save`/`update`/`delete`/`findAll`/`findById` straight from all five endpoints (`micronaut-controller-layer` violation on every method, not just reads). `PunishmentEntityController.getAll` also injects `PunishmentRepository` directly for its one read-only listing endpoint (its `add`/`revokeBan`/`revokeMute` methods are compliant, delegating to `PunishmentApplicationService`). Separately, `PunishTemplateDTO`/`PunishTemplateRequestDTO`/`PunishEntryDTO`/`PunishmentEvidenceDTO` are plain top-level records, not the sealed-marker `CreateRequest`/`UpdateRequest`/`Response`/`Error` shape `micronaut-dto-contract` requires — failures are HTTP status codes (404/400/405), never a DTO `Error` variant. `@Operation`/`@ApiResponse` annotations also live directly on both controller classes rather than a dedicated `*Api` interface, violating `micronaut-openapi-contract` too. All three conventions under this principle are violated, not just one. |
| IV | Dual-ELO Punishment Engine Stays Automatic | ✅ PASS | `PunishmentApplicationService.apply` applies a template's `eloDelta` unconditionally when non-zero, with one explicit exception: `!EloService.SYSTEM_ELO_SOURCE.equals(source)` skips the delta when the punishment being applied was itself system-issued by the ELO engine — the exact no-double-dipping guarantee spec's Edge Cases section requires, implemented at the shared chokepoint rather than duplicated per caller. |
| V | No App-Level Hardening Where Infra Already Owns It | ✅ PASS / N/A | No caller-controlled-URL surface (Redis/Rabbit endpoints are all backend-configured, not caller-supplied) and no new app-level rate limiter added by this feature. |
| VI | Schema Append-Only, `@Transactional` Off the Table | ⚠️ **PARTIAL** | Liquibase changesets are genuinely append-only here (verified: every `tenant_id` column was removed via a **new** `dropColumn` changeset, never an edit to the original `createTable`). `@Transactional` is correctly avoided project-wide, but the resulting race window in `PunishmentApplicationService.apply`/`revoke` (see Technical Context "Constraints") is not documented in the code itself the way `EloService`'s equivalent race is documented in its class Javadoc — a real, if narrow, honesty gap versus the ELO engine's handling of the same constraint. |

**Gate result**: one FAIL (Principle III, both controllers, all three sub-conventions)
and one PARTIAL (Principle VI, undocumented race window) on pre-existing code. Not
blocking this retroactive plan — the gate's purpose is to stop *new* violations — but
Principle III's violation here is more severe than the ELO engine's precedent-setting
PARTIAL and should be prioritized accordingly by `/speckit-tasks` and re-checked by
`/speckit-converge`.

**Post-Phase-1 re-check**: `research.md`, `data-model.md`, `contracts/punishment-api.md`,
and `quickstart.md` describe the existing implementation as-is and prescribe no new
code — the Phase 1 design artifacts introduce no additional Constitution violations
beyond the Principle III and VI findings above. Gate result unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/002-punishment-core/
├── plan.md                    # This file
├── research.md                # Phase 0 output
├── data-model.md               # Phase 1 output
├── quickstart.md               # Phase 1 output
├── contracts/
│   └── punishment-api.md       # Phase 1 output
└── tasks.md                    # Phase 2 output (/speckit-tasks — not created by this command)
```

### Source Code (repository root)

```text
backend/src/main/java/net/onelitefeather/blackhole/backend/punishment/
├── PunishType.java                # enum: SERVER | NETWORK | CHAT
├── EvidenceType.java               # enum: CHAT_MESSAGE | ANTICHEAT_FLAG | REPORT | MANUAL_NOTE
├── PunishmentTemplateEntity.java   # JPA entity: punishment_templates
├── PunishmentTemplateRepository.java
├── PunishmentEntity.java           # JPA entity: punishments
├── PunishmentRepository.java
├── PunishmentEvidenceEntity.java   # JPA entity: punishment_evidence
├── PunishmentEvidenceRepository.java
├── PunishmentExpiry.java           # static isExpired() helper shared by sweep + lazy-read paths
├── PunishmentExpirySweeper.java    # 1-minute @Scheduled expiry backstop
├── PunishmentRedisWriter.java      # sole writer of active-punishment state into Redis
├── RedisSyncConsumer.java          # bridges domain events -> PunishmentRedisWriter
├── RedisTopology.java              # Redis key/channel name constants
├── PunishmentSyncMessage.java      # wire format shared with Velocity's mirror
├── controller/
│   ├── PunishmentEntityController.java   # POST /punishment/active/..., GET /punishment
│   └── PunishmentTemplateController.java # CRUD under /template
├── dto/
│   ├── PunishEntryDTO.java
│   ├── PunishmentEvidenceDTO.java
│   ├── PunishTemplateDTO.java
│   └── PunishTemplateRequestDTO.java
└── service/
    └── PunishmentApplicationService.java  # sole write path: apply, revokeBan, revokeMute

backend/src/main/java/net/onelitefeather/blackhole/backend/profile/
├── PunishmentProfileEntity.java    # JPA entity: punishment_profiles (owned here, not in punishment/)
├── PunishmentProfileRepository.java
├── CacheInvalidationPublisher.java # invoked by PunishmentApplicationService/PunishmentExpirySweeper
└── controller/ProfileController.java # GET /profile/{owner} also lazily expires on read

backend/src/main/resources/
└── db/changelog/                   # Liquibase changesets for punishment_templates/punishments/
                                     # punishment_profiles/punishment_profiles_punishments/
                                     # punishment_evidence

backend/src/test/                   # no punishment/ tests yet — see Technical Context "Testing"
```

**Structure Decision**: No new module or service boundary — the punishment core is
contained in the existing `punishment/` feature package of the `backend` module,
following the same package-by-feature layout (`controller/`, `service/`, `dto/`
subpackages plus top-level entity/repository/enum classes) as every other backend
feature, with its per-player aggregate state (`PunishmentProfileEntity`) intentionally
owned by the sibling `profile/` package rather than duplicated inside `punishment/`.
This matches the "single Micronaut web-service module" shape used throughout this repo,
not a multi-project split.

## Complexity Tracking

> Fill ONLY if Constitution Check has violations that must be justified

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|---------------------------------------|
| `PunishmentTemplateController` has no service layer at all — every endpoint injects `PunishmentTemplateRepository` directly (Principle III) | Not "needed" — accepted historical debt, not a justified design choice. Template CRUD predates the layering convention documented in `micronaut-controller-layer`/`micronaut-service-layer`. | A `PunishmentTemplateService` wrapping `save`/`update`/`delete`/`findAll`/`findById` (mirroring `PunishmentApplicationService`'s shape) would fix it with low risk; recommended as a task via `/speckit-tasks`. |
| `PunishmentEntityController.getAll` injects `PunishmentRepository` directly for its one read-only endpoint (Principle III) | Same category as `EloController`'s two read endpoints in `specs/001-elo-engine/plan.md` — not needed, pre-existing debt. | A thin query method on `PunishmentApplicationService` (or a dedicated read service) would close this; lower priority than the template controller since it's a single read-only method, not the entire CRUD surface. |
| Template/Punishment/Evidence DTOs don't follow the sealed-marker `CreateRequest`/`UpdateRequest`/`Response`/`Error` shape, and `@Operation`/`@ApiResponse` live on the controllers rather than a `*Api` interface (Principle III) | Pre-dates both conventions, same as the ELO engine's DTO note. | Restructuring into the sealed-marker contract is a larger, higher-risk refactor than the service-layer fix above (touches every caller of these DTOs, including the OpenAPI-generated `client` module); recommended as a separate, explicitly-scoped task rather than folded into this retroactive plan. |
| No code-level Javadoc documents the `apply`/`revoke` race window the way `EloService` documents its own (Principle VI) | Not a design tradeoff — an oversight relative to the precedent `EloService` already set in this codebase. | Adding the same style of class/method Javadoc to `PunishmentApplicationService` is low-risk and low-effort; recommended as a small follow-up task. |
