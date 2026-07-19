# Implementation Plan: Ban-Evasion Detection

**Branch**: `004-evasion-detection` | **Date**: 2026-07-19 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-evasion-detection/spec.md`

**Note**: This is a **retroactive** plan — the feature is already implemented
(`backend/src/main/java/net/onelitefeather/blackhole/backend/evasion/`). It documents
the actual shipped technical approach against the spec, and runs the Constitution Check
against real code rather than a proposed design, so gaps are surfaced honestly instead
of glossed over.

## Summary

Every login records a sighting — a distinct-account/IP-address pair — without requiring
either side to be pre-flagged as suspicious (spec FR-001). The IP address is never
stored in a reversible form: it is run through HMAC-SHA512 keyed with a server-held
secret (`blackhole.evasion.ip-salt`) before it ever reaches persistence (FR-002, FR-003).
If that secret is unset, the endpoint hard-fails with `503` rather than silently
computing a weaker, guessable, unsalted-equivalent signal (FR-006). On every sighting,
the system checks whether more than one distinct account hash has been seen on that same
keyed token within a configurable detection window (default 7 days) and, if so, publishes
a network-wide `evasion.detected` domain event naming every distinct account found
sharing it (FR-004, FR-005) — one account reconnecting on its own IP, however many times,
never triggers it. A separate, independently configurable retention window (default 90
days) is enforced by a nightly `@Scheduled` sweeper that deletes stale sightings outright,
since the correlation table is itself personal data (FR-008, FR-009). This feature stops
at publishing the event — deciding or applying any consequence is explicitly out of
scope (FR-010); as of this writing there is no known consumer of `evasion.detected`.

## Technical Context

**Language/Version**: Java 25 (Gradle toolchain, `options.release = 25` set at the root
build for every subproject).

**Primary Dependencies**: Micronaut 5.0.2 platform (`micronaut-http`,
`micronaut-data-spring-jpa` + `micronaut-hibernate-jpa` for persistence,
`micronaut-serde-jackson` for DTO serialization, `micronaut-validation` for request
validation, `micronaut-scheduling` for `@Scheduled`, `micronaut-openapi` for the Swagger
annotations); the JDK's own `javax.crypto.Mac`/`HmacSHA512` for the keyed hash (no
external crypto library); the existing `events` package (`DomainEventPublisher`) is
consumed, not owned, by this feature. On the caller side, the Velocity plugin's
`PlayerLoginListener` computes the account hash (`UUIDConverter.convertToSHA`, SHA-512 of
the raw player UUID) before ever calling this feature — this feature receives an
already-hashed `owner`, it does not hash the account identity itself.

**Storage**: MariaDB via Hibernate/JPA, schema owned by Liquibase changesets under
`backend/src/main/resources/db/changelog/`. One table: `ip_correlation_tokens` (one row
per distinct account/IP-token pair, indexed on `identifier` and `token`). `metaData` is a
JSON column via the shared `MapStringObjectConverter`, present on the entity but not
populated by any current write path.

**Testing**: JUnit 5 is wired at the root (`useJUnitPlatform()`, `jacocoTestReport` runs
after `test`), but **no test sources currently exist** for this feature (or any feature —
no `backend/src/test` directory exists yet in this repo). Recorded here rather than
silently assumed away so `/speckit-tasks` can decide whether to close it.

**Target Platform**: Linux server, single Micronaut HTTP API deployment per network
(this repo's `backend` module); no offline/embedded execution mode. The only caller today
is the Velocity proxy plugin (`velocity` module), calling `POST /evasion/record`
synchronously inside its login flow via the generated `EvasionApi` client.

**Project Type**: Web service — a feature package within an existing multi-module
backend, not a standalone project.

**Performance Goals**: No formal target was set when this feature was built. Informal
expectation: `POST /evasion/record` runs synchronously in the Velocity login path (see
`PlayerLoginListener.recordEvasionSignal`, called unconditionally before the ban check),
so it must stay fast — one HMAC computation plus a point lookup and a bounded range query
per login, no unbounded scan. The retention sweep runs off-peak (`03:30` cron default,
configurable) with no explicit time budget.

**Constraints**: `@Transactional` is unusable in this codebase (constitution Principle
VI) — `IpCorrelationService.recordLogin`'s find-or-create-then-update is not atomic, so a
narrow race exists if the exact same account reconnects from the exact same IP in two
concurrent requests (could produce two rows briefly instead of one refreshed row).
Accepted as a low-frequency, self-correcting edge case (the next login for either row
still refreshes correctly), not something this plan re-attempts to fix. `ip-salt`,
`detection-window-days`, `retention-days`, and `retention-sweep-cron` are all
`@Value`-injected from `blackhole.evasion.*` keys in `application.yml`, per the
constitution's env-var-driven config convention (FR-009, SC-006).

**Scale/Scope**: Single-network deployment (no multi-tenancy, per constitution Principle
I; the `tenant_id` column this table originally shipped with was dropped in the
tenant-removal changesets). Scope is exactly one write endpoint plus one background
sweeper — no read/query endpoint exists for the correlation table itself, and no
enforcement action is taken by this feature (spec Assumptions).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Status | Notes |
|---|-----------|--------|-------|
| I | GDPR-by-Design, Single-Tenant | ✅ PASS | The IP address is transformed via HMAC-SHA512 with a server-held salt before it ever reaches a repository call — `IpCorrelationService.recordLogin` never passes `rawIp` anywhere but into `hmacSha512`; no raw IP field exists on `IpCorrelationTokenEntity`. `owner` arrives pre-hashed (SHA-512, enforced by `EvasionRecordDTO`'s `@Pattern`) and is stored as-is, never a raw player UUID. No `tenant_id` remains on `ip_correlation_tokens` (dropped by `drop-ip-correlation-tokens-tenant`). |
| II | No Auth Layer, Trust via Network Boundary | ✅ PASS | `EvasionController` carries no `@Secured`; `POST /evasion/record` is open, consistent with the rest of the backend. The `owner` it receives is client-supplied and unverified, matching the project's documented no-per-actor-identity gap — this feature does not assume otherwise. |
| III | Layered Backend: Controller / Service / DTO Contract | ✅ PASS | `EvasionController` injects only `IpCorrelationService` (confirmed by reading the source — no repository import, no repository field). All persistence and business logic (hashing, find-or-create, threshold check, event publish) lives in `IpCorrelationService`. **Partial** on the DTO contract specifically: `EvasionRecordDTO` is a plain record, not a sealed marker interface with `CreateRequest`/`Response`/`Error` variants per `micronaut-dto-contract` — there is no `Response`/`Error` DTO variant at all; the controller reports failure via a raw `HttpResponse.status(503, "...")` string body instead. Pre-dates that convention, same shape as the ELO engine's documented gap. |
| IV | Dual-ELO Punishment Engine Stays Automatic | N/A | This feature does not touch ELO scores or punishments; it stops at publishing `evasion.detected` (spec Assumptions). |
| V | No App-Level Hardening Where Infra Already Owns It | ✅ PASS / N/A | No caller-controlled URL is ever fetched by this feature (the IP address is used only as HMAC input, never dereferenced as a URL/host) — no SSRF surface. No new rate limiter added. |
| VI | Schema Append-Only, `@Transactional` Off the Table | ✅ PASS | No `@Transactional` used anywhere in `evasion/`; the resulting non-atomic find-or-create race is accepted and now documented here rather than papered over (see Technical Context "Constraints"). `ip_correlation_tokens` Liquibase changesets have only ever been extended (the tenant-removal changesets), never edited in place. |

**Gate result**: one PARTIAL-flavored note under Principle III (DTO contract shape, not
the layering itself — layering is clean), otherwise full PASS. Not blocking this
retroactive plan — the gate's purpose is to stop *new* violations, and controller/service
separation, the one property most likely to regress silently, is already correct here.

**Post-Phase-1 re-check**: `research.md`, `data-model.md`, `contracts/evasion-api.md`,
and `quickstart.md` describe the existing implementation as-is and prescribe no new code
— the Phase 1 design artifacts introduce no additional Constitution violations beyond the
Principle III DTO-shape note above. Gate result unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/004-evasion-detection/
├── plan.md                  # This file
├── research.md               # Phase 0 output
├── data-model.md             # Phase 1 output
├── quickstart.md             # Phase 1 output
├── contracts/
│   └── evasion-api.md        # Phase 1 output
├── checklists/
│   └── requirements.md       # Pre-existing
└── tasks.md                  # Phase 2 output (/speckit-tasks — not created by this command)
```

### Source Code (repository root)

```text
backend/src/main/java/net/onelitefeather/blackhole/backend/evasion/
├── IpCorrelationTokenEntity.java        # JPA entity: ip_correlation_tokens
├── IpCorrelationTokenRepository.java
├── IpCorrelationRetentionSweeper.java   # nightly @Scheduled retention sweep
├── controller/
│   └── EvasionController.java           # POST /evasion/record
├── dto/
│   └── EvasionRecordDTO.java
└── service/
    └── IpCorrelationService.java        # sole write path: keyed hash, find-or-create, correlation check, event publish

backend/src/main/resources/
├── application.yml                      # blackhole.evasion.* tunables
└── db/changelog/                        # Liquibase changeset for ip_correlation_tokens

velocity/src/main/java/net/onelitefeather/blackhole/velocity/listener/
└── PlayerLoginListener.java             # only current caller — recordEvasionSignal(), fire-and-forget on login

backend/src/test/                        # does not exist yet — see Technical Context "Testing"
```

**Structure Decision**: No new module or service boundary — ban-evasion detection is
fully contained in the existing `evasion/` feature package of the `backend` module,
following the same package-by-feature layout (`controller/`, `service/`, `dto/`
subpackages plus top-level entity/repository/sweeper classes) as every other backend
feature. This matches the "single Micronaut web-service module" shape, not a multi-project
split. Unlike `elo/`, there is no `events/` subscriber inside this package — it only
publishes, via the shared `DomainEventPublisher`.

## Complexity Tracking

> Fill ONLY if Constitution Check has violations that must be justified

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|---------------------------------------|
| `EvasionRecordDTO` is a plain record instead of a sealed marker interface with `CreateRequest`/`Response`/`Error` variants (Principle III, DTO contract) | Not "needed" — this is pre-existing debt from before `micronaut-dto-contract` was adopted, recorded here rather than silently passed. | Wrapping the 503 case as a proper `EvasionDTO.Error` variant returned by the service (instead of a raw string `HttpResponse.status(503, "...")`) would fix it with minimal risk; recommended as a task via `/speckit-tasks` and to be re-checked by `/speckit-converge` rather than fixed silently inside this planning pass. |
