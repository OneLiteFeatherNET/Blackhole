# Implementation Plan: Vanilla Ban-List Import

**Branch**: `005-vanilla-import` | **Date**: 2026-07-19 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-vanilla-import/spec.md`

**Note**: This is a **retroactive** plan — the feature is already implemented
(`backend/src/main/java/net/onelitefeather/blackhole/backend/imports/`). It documents the
actual shipped technical approach against the spec, and runs the Constitution Check
against real code rather than a proposed design, so gaps are surfaced honestly instead
of glossed over.

## Summary

Bulk-convert a vanilla Minecraft `banned-players.json` (and, for transparency only, a
`banned-ips.json`) into Blackhole's own punishment records in one multipart HTTP call
(spec FR-001). Each entry's raw UUID is hashed via `UUIDHasher` (the same SHA-512 scheme
Velocity's client-side `UUIDConverter` produces) before ever touching a repository or log
line, so no raw player identifier is persisted (FR-002). Every imported punishment is
created as `PunishType.NETWORK` (FR-003), attributed to a fixed deterministic
`SYSTEM_IMPORT_SOURCE` UUID distinct from both a human staff member and the ELO engine's
own `EloService.SYSTEM_ELO_SOURCE` (FR-005), and — unlike every other punishment-creation
path in the codebase — is written **directly** by `VanillaImportService` against
`PunishmentRepository`/`PunishmentProfileRepository` rather than through
`PunishmentApplicationService`. This means an imported ban never triggers an ELO delta
even if the matched template happens to carry one (FR-011 is satisfied only because this
bypass structurally cannot reach `EloService`, not because of an explicit guard). A player
who already has an active network ban is left untouched and the entry counted as skipped
(FR-006, US3); an already-expired legacy entry is written straight into profile history
instead of the active-ban slot (FR-004, US1 Scenario 2); a `dryRun` query flag previews
the same imported/skipped/invalid counts without writing anything (FR-009, US2); a
malformed UUID or expiry rejects only that entry, with a human-readable note, rather than
aborting the run (FR-007, FR-008, US4); IP ban entries are counted and logged but never
converted or persisted (FR-010, US5).

## Technical Context

**Language/Version**: Java 25 (Gradle toolchain, `options.release = 25` set at the root
build for every subproject).

**Primary Dependencies**: Micronaut 5.0.2 platform (`micronaut-http-server` for the
multipart `@Post` endpoint, `micronaut-serde-jackson`/`micronaut-json-core`'s `JsonMapper`
for decoding the uploaded JSON files, `micronaut-data-spring-jpa` +
`micronaut-hibernate-jpa` for persistence, `micronaut-openapi` for the `@Operation(hidden
= true)` annotation); the existing `punishment` feature package
(`PunishmentRepository`, `PunishmentTemplateRepository`, `PunishmentEntity`,
`PunishmentTemplateEntity`, `PunishType`) and `profile` feature package
(`PunishmentProfileRepository`, `PunishmentProfileEntity`, `CacheInvalidationPublisher`)
are consumed, not owned, by this feature; `events` package (`DomainEventPublisher`) for
`profile.created`/`punishment.created`; `phoca` (`Metadata`, `Expirable` metadata-key
constants) for building punishment metadata identically to how
`PunishmentApplicationService` does.

**Storage**: MariaDB via Hibernate/JPA — no new tables. Writes land in the same
`punishments`, `punishment_templates`, and `punishment_profiles` tables (schema owned by
the `punishment`/`profile` features' own Liquibase changesets) that every other
punishment-creation path writes to; this feature owns no schema of its own.

**Testing**: JUnit 5 is wired at the root (`useJUnitPlatform()`), but **no test sources
currently exist for this feature** — the only test in the repo so far is
`backend/src/test/java/.../playerresolver/service/PlayerResolverServiceTest.java`, an
unrelated subsystem. Same gap as every other retroactively-planned feature in this
repo; recorded here rather than silently assumed away.

**Target Platform**: Linux server, single Micronaut HTTP API deployment per network
(this repo's `backend` module); no offline/embedded execution mode. The endpoint is
intended to be invoked by an operator with direct file access to the legacy server's ban
files (spec Assumptions), not a self-service UI.

**Project Type**: Web service — a feature package within an existing multi-module
backend, not a standalone project or CLI tool (deliberately — see research.md "Decision:
import runs through the HTTP API, not a standalone CLI").

**Performance Goals**: No formal target was set when this feature was built. The whole
uploaded `banned-players.json` is deserialized into memory in one `JsonMapper.readValue`
call and iterated synchronously in the request thread — acceptable for the one-time,
operator-driven bulk-migration use case this feature targets, not designed for
recurring/streaming imports of unbounded size.

**Constraints**: `@Transactional` is unusable in this codebase (constitution Principle
VI) — each entry's profile lookup, punishment save, and profile save/update are three
separate, non-atomic repository calls, same as `PunishmentApplicationService.apply`
accepts elsewhere. A crash mid-run leaves already-processed entries committed and
unprocessed entries simply not yet seen — safely re-runnable because of the FR-006
already-active-ban skip (US3), not because of transactional rollback.

**Scale/Scope**: Single-network deployment (no multi-tenancy, per constitution Principle
I); scope is one operator-driven bulk operation per HTTP call, bounded by upload size and
available heap for `Argument.listOf(VanillaBanEntry.class)` — no chunking/streaming, no
progress reporting mid-run, no async job model.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Status | Notes |
|---|-----------|--------|-------|
| I | GDPR-by-Design, Single-Tenant | ✅ PASS | `UUIDHasher.hash` (SHA-512, matching Velocity's client-side `UUIDConverter`) runs before the raw UUID is used for anything beyond the local `rawUuid` variable — only the hashed `owner` string is ever passed to a repository, log line, or event payload. `VanillaIpBanEntry` is deserialized only to compute `.size()`; no field of it (including the raw `ip`) is read, stored, or logged individually. No `tenantId` anywhere. |
| II | No Auth Layer, Trust via Network Boundary | ✅ PASS | `VanillaImportController` carries no `@Secured`; open like every other endpoint. `@Operation(hidden = true)` keeps it out of the published Swagger spec (FR-014's "not a self-service or publicly documented capability"), which is a documentation-visibility choice, not an access-control one — the endpoint is still reachable by anyone who can reach the API at all, consistent with this principle's trust model (network-boundary enforcement, not app-level gating). |
| III | Layered Backend: Controller / Service / DTO Contract | ⚠️ **PARTIAL** | Controller is a thin adapter (reads two `CompletedFileUpload`s, calls the one service method, maps the result) — compliant. But `VanillaImportResultDTO` is a single plain record, not the sealed `CreateRequest`/`Response`/`Error` marker-interface shape `micronaut-dto-contract` prescribes: there is no request DTO at all (request is raw multipart bytes) and no `Error` variant — an `IOException` reading the upload is caught in the controller and turned into a bare `HttpResponse.badRequest(String)`, not a DTO. Pre-dates/deviates from that convention, same category of gap as `001-elo-engine`'s `ChatSignalDTO`/`ChatToxicityResult`. |
| IV | Dual-ELO Punishment Engine Stays Automatic | ⚠️ **PARTIAL** | Not a violation of "stays automatic" in the sense of requiring a human — but `VanillaImportService` writes `PunishmentEntity`/`PunishmentProfileEntity` directly instead of calling `PunishmentApplicationService.apply`, which is the one place `template.getEloDelta()` is read and forwarded to `EloService.applyDelta`. If an operator ever points the import's template lookup at (or a future change causes `findOrCreateTemplate` to reuse) a template with a nonzero `eloDelta`, that delta is silently never applied for imported punishments — not because FR-011 ("MUST NOT apply any ELO adjustment") was deliberately enforced, but because the bypass structurally can't reach `EloService` at all. See Complexity Tracking. |
| V | No App-Level Hardening Where Infra Already Owns It | ✅ PASS / N/A | No caller-controlled URL; no new rate limiter added. The endpoint's own risk surface (unbounded upload size) is a resource-limit concern, not a rate-limiting or SSRF one, and is out of this principle's scope. |
| VI | Schema Append-Only, `@Transactional` Off the Table | ✅ PASS | No `@Transactional` used. No schema owned by this feature at all — reuses `punishment`/`profile` tables verbatim; no Liquibase changeset added or edited by this feature. |

**Gate result**: two PARTIALs (Principle III, IV) on pre-existing code, both centered on
the same root cause — this feature duplicates part of what `PunishmentApplicationService`
does (template lookup, metadata construction, `PunishmentEntity` creation, active/history
profile-slot rotation, cache invalidation, event publishing) instead of calling it, and
built its own single-record DTO instead of the sealed-marker contract. Not blocking this
retroactive plan — the gate's purpose is to stop *new* violations, and this documents an
existing one for visibility — but the Principle IV finding in particular is a real
product-behavior risk (see Complexity Tracking) that should become a task.

Separately from the six-principle gate, writing this plan's quickstart surfaced a real
**functional bug**, not a constitution violation: re-importing the same file creates a
duplicate `history` punishment for any entry that was already-expired at import time,
because the skip-conflict check only inspects `activeBan`, never `history` (see
research.md "Known gap: re-importing an already-expired entry creates a duplicate history
record"). This contradicts spec US3's re-import idempotency guarantee for that entry
subset. Recorded here for visibility and carried into Complexity Tracking below; not
fixed silently by this planning pass.

**Post-Phase-1 re-check**: `research.md`, `data-model.md`, `contracts/import-api.md`, and
`quickstart.md` describe the existing implementation as-is and prescribe no new code — the
Phase 1 design artifacts introduce no additional Constitution violations beyond the
Principle III and IV findings above, and `research.md`'s "Decision: import bypasses
`PunishmentApplicationService`" makes the tradeoff explicit rather than leaving it
implicit. Gate result unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/005-vanilla-import/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md         # Phase 1 output
├── quickstart.md         # Phase 1 output
├── contracts/
│   └── import-api.md     # Phase 1 output
└── tasks.md              # Phase 2 output (/speckit-tasks — not created by this command)
```

### Source Code (repository root)

```text
backend/src/main/java/net/onelitefeather/blackhole/backend/imports/
├── VanillaBanEntry.java          # record: one banned-players.json entry (vanilla field names)
├── VanillaIpBanEntry.java        # record: one banned-ips.json entry (counted only, never imported)
├── UUIDHasher.java               # SHA-512 raw-UUID -> owner-hash, matching Velocity's UUIDConverter
├── controller/
│   └── VanillaImportController.java   # POST /admin/import/vanilla (multipart)
├── dto/
│   └── VanillaImportResultDTO.java    # single result/preview summary record
└── service/
    └── VanillaImportService.java      # sole write path: parse, hash, skip/import decision, direct entity writes

backend/src/main/java/net/onelitefeather/blackhole/backend/punishment/
└── service/PunishmentApplicationService.java   # NOT called by this feature — see research.md

backend/src/test/                 # no imports/ tests yet — see Technical Context "Testing"
```

**Structure Decision**: No new module or service boundary — the import capability is
fully contained in its own `imports/` feature package of the `backend` module, following
the same package-by-feature layout (`controller/`, `service/`, `dto/` subpackages plus
top-level record/util classes) as every other backend feature. It depends on, but does
not extend or modify, the `punishment` and `profile` feature packages' entities and
repositories. This matches the "single Micronaut web-service module" shape, not a
multi-project split or standalone CLI tool.

## Complexity Tracking

> Fill ONLY if Constitution Check has violations that must be justified

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|---------------------------------------|
| `VanillaImportService` writes `PunishmentEntity`/`PunishmentProfileEntity` directly instead of calling `PunishmentApplicationService.apply` (Principle IV finding above) | Genuinely two-sided, not a clean-cut justification: (a) import is a one-time bulk migration of *pre-existing* punishment decisions made by a different system before Blackhole was involved — treating that history as new ELO-affecting behavior the player just exhibited is arguably wrong regardless of chokepoint; (b) `PunishmentApplicationService.apply` also unconditionally creates a punishment in the *active* slot, with no parameter for "this ban's own expiry has already passed, file it straight into history instead" — the already-expired-entry path (FR-004, US1 Scenario 2) has no equivalent in the shared chokepoint today. | Calling `PunishmentApplicationService.apply` as-is would (1) route every imported ban through `EloService.applyDelta` if the matched template ever carries a nonzero `eloDelta`, silently contradicting FR-011, and (2) has no already-expired/history-only code path, so every legacy expired ban would incorrectly become a live active ban that then has to be separately swept. Either extending `apply` with an `applyElo: boolean` / `forceHistory: boolean` parameter, or keeping the deliberate bypass but adding an explicit `eloDelta == 0` assertion on the import-owned template (defense against a future config accident) is recommended as a follow-up task rather than solved silently in this planning pass. |
| `VanillaImportResultDTO` is a single plain record with no sealed `Error` variant, and there is no request DTO at all (Principle III finding above) | The request is a raw multipart file upload, not a JSON body with a natural `CreateRequest` shape; and the only failure mode surfaced today (`IOException` reading an upload) is an infra/transport failure, not a business-rule rejection that a DTO `Error` variant would meaningfully model — individual bad *entries* are already reported inside the success response's `invalidEntries` list, which is the actual FR-007 "report but don't abort" mechanism. | Retrofitting a sealed `VanillaImportDTO` marker interface purely to wrap the existing single response shape in a `Response` variant, with no corresponding `CreateRequest`/`Error` need, was judged to add ceremony without a real second variant to justify it — flagged for `/speckit-tasks` to decide rather than resolved here. |
| The re-import skip check only inspects `profile.getActiveBan()`, so an already-expired entry (written to `history`, not `activeBan`) is silently re-imported as a duplicate on every re-run of the same file (functional bug, not a constitution-principle violation — see above and research.md "Known gap") | Not needed at all — this is an unintended gap, not a design tradeoff. Recorded so it isn't lost. | The two candidate fixes (match on originating import metadata, or coarsen the skip check to "any profile exists at all") both have their own tradeoffs (see research.md) and were judged to need a deliberate decision rather than a silent patch inside a retroactive planning pass — flagged for `/speckit-tasks`. |
