# Phase 1 Data Model: Player Reports

Derived from the spec's Key Entities section, cross-checked against the actual JPA
entity (`ReportEntity`) and the enums it uses (`ReportCategory`, `ReportStatus`), plus
the entities it references but does not own (`PunishmentTemplateEntity`/
`PunishmentEntity` via `PunishmentApplicationService`, `EloEventEntity` via
`EloService`).

## Report

One row per submitted complaint. Table `reports`, indexed on `identifier` and
`reporterHash`; evidence references live in a separate `report_evidence_references`
join table via `@ElementCollection`.

| Field | Type | Notes |
|---|---|---|
| `identifier` | `UUID` (PK, `GenerationType.UUID`) | Server-generated; `null` on the incoming `ReportRequestDTO`. |
| `reporterHash` | `String` | SHA-512 hash of the reporting player. Validated at the API boundary via `ReportRequestDTO`'s `@Pattern(regexp = "^[a-fA-F0-9]{128}$")`. **FR-001, FR-003** (required — request validation rejects a blank value before this entity is ever constructed). |
| `reportedHash` | `String` | SHA-512 hash of the reported player. Same pattern/validation as `reporterHash`. **FR-001, FR-003.** Nothing distinguishes it structurally from `reporterHash` — a self-report (same hash in both fields) is accepted (spec Edge Cases). |
| `category` | `ReportCategory` enum (`CHAT_ABUSE`\|`CHEATING`\|`GRIEFING`\|`OTHER`) | **FR-002** (minimum required category set), **FR-003** (required — `@NonNull` on `ReportRequestDTO`). Determines the ELO track an `ACTIONED` resolution affects — see Report Category below. |
| `description` | `String`, nullable, `@Size(max = 1000)` | Bounded free-text. **FR-001** (optional). |
| `evidenceReferences` | `List<UUID>` (`@ElementCollection`, eager fetch) | Opaque references into `PunishmentEvidenceEntity` (owned by the `punishment` feature package per spec Assumptions) — this feature does not validate a referenced entry exists. Defaults to an empty list when the request omits it (`submit()`: `submission.evidenceReferences() == null ? new ArrayList<>() : ...`). **FR-001.** |
| `status` | `ReportStatus` enum (`OPEN`\|`UNDER_REVIEW`\|`ACTIONED`\|`DISMISSED`) | `OPEN` on creation (**FR-004**); overwritten unconditionally by every `resolve()` call, with no enforced prior-state check (spec Edge Cases — see `research.md`, "No enforced report-status state machine"). |
| `createdAt` | `long` (epoch ms) | Set once at submission (`System.currentTimeMillis()`), never updated. **FR-004.** Stored as a real column (not JSON metadata, unlike most sibling entities) specifically so the rate-limit repository queries can filter on it directly — see `research.md`. |
| `updatedAt` | `long` (epoch ms) | Set equal to `createdAt` at submission; reset to `System.currentTimeMillis()` on every `resolve()` call, including a second/third resolution of the same report. **FR-009.** |
| `resolvedBy` | `UUID`, nullable | `null` until first resolved; then the caller-supplied (unverified — constitution Principle II) resolver identity from `ReportResolutionDTO.resolvedBy()`, which is itself `@NonNull` on that DTO (a resolution always names *a* resolver, even though it isn't verified as *the* actual caller). **FR-009.** |
| `resolutionNote` | `String`, nullable, `@Size(max = 1000)` | `null` until first resolved. **FR-009** (optional note). |
| `metaData` | `Map<String, Object>` (JSON column, `MapStringObjectConverter`) | Free-form extension point supplied at submission; not written to by `resolve()`. **FR-001** (optional/extensible metadata). |

**Validation rules** (from spec Requirements):
- FR-003: a submission missing `reporterHash`, `reportedHash`, or `category` is rejected
  before persistence — enforced by `@NotBlank`/`@NonNull`/`@Pattern` on `ReportRequestDTO`
  plus `@Valid`/`@Validated` on `submit()`, not by any check inside the entity or a
  service (there is no service — see `plan.md` Constitution Check, Principle III).
- FR-005/FR-006: enforced entirely in `ReportController.submit()` via
  `ReportRepository.countByCreatedAtGreaterThan`/`countByReporterHashAndCreatedAtGreaterThan`
  against `createdAt`, not as an entity-level constraint.
- FR-010: `resolve()` against a non-existent `identifier` returns `404` before any field
  is read or written (`reportRepository.findById(identifier).orElse(null)` → early
  return).
- FR-013: a resolution whose `punishmentTemplateId` fails to apply leaves every field on
  the `ReportEntity` untouched — see `research.md`, "Punishment application happens before
  the report is mutated."

**State transitions**: `status` starts at `OPEN` and can move to any of the four enum
values on any `resolve()` call, from any prior status, any number of times — there is no
enforced state machine (spec Assumptions, Edge Cases). Each `ACTIONED` transition
independently re-evaluates both ELO effects (see Relationships below); the entity itself
carries no flag recording whether a given report has already produced a standing effect.

## Report Category

Not a persisted entity — `ReportCategory` is a plain enum embedded on `Report`. Its sole
behavioral significance beyond classification is the category → `EloTrack` mapping
evaluated in `resolve()`:

| Category | Maps to `EloTrack` | Spec ref |
|---|---|---|
| `CHAT_ABUSE` | `CHAT` | FR-014 (US3 Acceptance Scenario 1) |
| `CHEATING` | `GAMEPLAY` | FR-014 (US3 Acceptance Scenario 2) |
| `GRIEFING` | `GAMEPLAY` | FR-014 (US3 Acceptance Scenario 2) |
| `OTHER` | *(none — `null`)* | FR-015 (US3 Acceptance Scenario 3) — no standing change regardless of resolution outcome |

## Report Status

Not a persisted entity — `ReportStatus` is a plain enum on `Report`. Only one value
(`ACTIONED`) has any side-effecting significance; the other three (`OPEN`, `UNDER_REVIEW`,
`DISMISSED`) are inert as far as this feature's own logic is concerned (FR-016).

## Relationships

```text
Report (1) ── reportedHash ──► PunishmentApplicationService.apply()   [by value, no FK — punishment/ package]
Report (1) ── reportedHash ──► EloService.applyDelta(REPORT_ACTIONED)  [by value, no FK — elo/ package]
Report (1) ── reporterHash ──► EloService.applyDelta(REPORT_REWARDED)  [by value, no FK — elo/ package, conditional]
Report (1) ── evidenceReferences (0..N) ──► PunishmentEvidenceEntity   [by value, no FK, unvalidated — punishment/ package]
```

None of these are database foreign keys — every cross-package reference in this feature
is joined by the shared hashed-identity value (`reportedHash`/`reporterHash`) or an opaque
UUID (`evidenceReferences`), consistent with how `ELO Event.sourceEvidenceId` relates to
`PunishmentEvidenceEntity` in `specs/001-elo-engine/data-model.md`. Both ELO relationships
are conditional on `status == ACTIONED` and the category → track mapping above; the
reporter-reward relationship is additionally conditional on a punishment having been
demonstrably applied in the same `resolve()` call (see `research.md`).
