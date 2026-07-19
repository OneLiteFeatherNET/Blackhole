# Phase 1 Data Model: Appeal Workflow

Derived from the spec's Key Entities section, cross-checked against the actual JPA
entity (`AppealEntity`), its Liquibase changesets (`create-appeals`,
`constraints-appeals`, `drop-appeals-tenant` in
`backend/src/main/resources/db/changelog/db.changelog-master.xml`), and the entities it
references (`PunishmentEntity`, owned by the `punishment` feature package; `EloProfileEntity`,
owned by the `elo` feature package).

## Appeal

One row per submitted appeal; always tied to exactly one punishment (`ManyToOne`, FK
`punishment_identifier` → `punishments.identifier`). Table `appeals`.

| Field | Type (Java / DB) | Notes |
|---|---|---|
| `identifier` | `UUID` (PK, `GenerationType.UUID`) | |
| `punishment` | `PunishmentEntity` / `punishment_identifier varchar(255)` FK | The punishment being contested. Not nullable in practice — `AppealController.submit` returns HTTP 404 before ever constructing an `AppealEntity` if the referenced punishment doesn't exist (FR-002). |
| `appellantHash` | `String` / `appellant_hash varchar(255)` | The (already-hashed) appellant identity, validated at the API boundary via `AppealSubmissionDTO`'s `@Pattern(regexp = "^[a-fA-F0-9]{128}$")` — a SHA-512 hash, never a raw player UUID (constitution Principle I). Indexed (`IDX9faypy3gxh4xmiqii41ypsj5k`). |
| `statement` | `String` / `statement varchar(2000)` | The player's written appeal text. `@NotBlank @Size(max = 2000)` at the DTO boundary. |
| `status` | `AppealStatus` enum / `status tinyint` | See State Transitions below. Ordinal-enum column; range enforcement is Hibernate-layer only (native Liquibase has no `CHECK` changeType — `.claude/rules/database.md`). |
| `eligibilityCheckResult` | `Map<String, Object>` (JSON) / `eligibility_check_result json` | The full versioned checklist snapshot from `AppealEligibilityService.evaluate`, stored verbatim at submission time (FR-006). Never recomputed or overwritten after creation. |
| `decidedBy` | `UUID`, nullable / `decided_by uuid` | The reviewer's client-supplied identity (FR-017). `null` until a review decision is recorded; unverified per constitution Principle II. |
| `decisionNote` | `String`, nullable / `decision_note varchar(2000)` | Optional reviewer explanation (FR-017). `@Size(max = 2000)` at the DTO boundary when present. |
| `createdAt` | `long` (epoch ms) / `created_at bigint NOT NULL` | Set once at submission. Real bigint column (not JSON metadata) — per `AppealEntity`'s own class Javadoc, the minimum-time-since-punishment eligibility check needs to filter/compare against it directly, same reasoning as `ReportEntity`. |
| `updatedAt` | `long` (epoch ms) / `updated_at bigint NOT NULL` | Set at submission, updated on every review decision. |
| `metaData` | `Map<String, Object>` (JSON) / `meta_data json` | Free-form extension point on the entity/DTO; not populated by any current write path (mirrors the same unused-today field pattern on `EloProfileEntity`/`EloEventEntity`). |

**Fields present in the Liquibase table but not on the current entity**: `tenant_id`
(`uuid`) was created by `create-appeals` and indexed by `constraints-appeals`, then
dropped by the later `drop-appeals-tenant` changeset (appended, not edited in place) —
historical residue from before the single-tenant simplification, already fully removed
from both the entity and the live schema; noted here only so the changelog's three
appeal-related changesets read coherently together.

**Validation rules** (from spec Requirements):
- FR-001/FR-002: an `AppealEntity` is only ever constructed after
  `PunishmentRepository.findById(punishmentIdentifier)` succeeds — there is no code path
  that persists an appeal against a nonexistent punishment.
- FR-003/FR-006: `eligibilityCheckResult` is always populated by
  `AppealEligibilityService.evaluate` synchronously during submission, before the entity
  is ever saved — there is no "submitted but not yet evaluated" persisted state.
- FR-017: `decidedBy`/`decisionNote` are only ever set together with `status` transitioning
  to one of the three terminal decision states, inside `AppealController.review`.

## Eligibility Checklist Result (`EligibilityResult` / checklist snapshot)

Not its own table — a `Map<String, Object>` produced by
`AppealEligibilityService.evaluate` and stored verbatim inside `Appeal.eligibilityCheckResult`.
Documented here as its own conceptual entity per the spec's Key Entities section, since it
has a stable, meaningful shape even though it isn't independently persisted.

| Key | Type | Notes |
|---|---|---|
| `checklistVersion` | `int` | `AppealEligibilityService.CHECKLIST_VERSION` (currently `1`). Written on every evaluation so a future rule change never retroactively reinterprets an already-decided appeal (FR-006). |
| `minDaysRequired` | `int` | `blackhole.appeal.min-days-auto` (default 3) if the punishment's `source` equals `EloService.SYSTEM_ELO_SOURCE`, else `blackhole.appeal.min-days-manual` (default 14). |
| `daysSincePunishment` | `double` | `(now - punishment creation date) / 1 day`, read from `punishment.getMetaData().get(Metadata.META_DATA_KEY_CREATION_DATE)`. |
| `minTimeElapsed` | `boolean` | `daysSincePunishment >= minDaysRequired`. |
| `isRepeatAppeal` | `boolean` | `true` if any prior appeal against the *same* punishment has status `DENIED` or `INELIGIBLE` and was created within `repeatAppealCooldownDays` of now. Appeals still `ELIGIBLE_PENDING_REVIEW` are deliberately excluded from this check (spec Edge Cases). |
| `repeatAppealCooldownDays` | `int` | `blackhole.appeal.repeat-appeal-cooldown-days` (default 7), echoed for audit context. |
| `isAutoTriggered` | `boolean` | Whether the punishment's `source` is the ELO engine's fixed system-actor UUID. |
| `eloTriggerReasonCode` | `String`, nullable | Copied from `punishment.getMetaData().get("eloTriggerReasonCode")` if present (e.g. `TOXICITY_FLAG`, `ANTICHEAT_FLAG`); `null` for manually-issued punishments. |
| `severityTier` | `"SEVERE"` \| `"STANDARD"` | `"SEVERE"` iff `PunishType.NETWORK` **and** no `Expirable.META_DATA_KEY_EXPIRATION_DATE` present (a permanent, network-wide punishment specifically — FR-007). |
| `supportingEloTrack` | `"CHAT"` \| `"GAMEPLAY"` | The track that did *not* trigger this punishment (FR-018, US5) — see `research.md` for the inference rule when the exact trigger isn't recorded. |
| `supportingEloScore` | `int` | The appellant's current score on `supportingEloTrack`; `blackhole.elo.baseline` (default 1000) if no `EloProfileEntity` exists yet for the appellant (deliberately permissive default, spec Edge Cases). |
| `supportingEloRecovered` | `boolean` | `supportingEloScore >= eloBaseline`. Informational only — never affects `eligible` (FR-018). |
| `eligible` | `boolean` | `minTimeElapsed && !isRepeatAppeal`. The only two hard gates (FR-004, FR-005); `severityTier` and the supporting signal never factor into this value. |

**Validation rule** (FR-006): every key above is written on every evaluation, not only
when the appeal turns out eligible — an ineligible appeal's checklist is exactly as
complete as an eligible one's, so the audit trail never has to distinguish "ineligible, no
detail recorded" from "ineligible, full detail recorded."

## Review Decision

Not its own table — the outcome of `AppealController.review`, expressed as a state
change on the `Appeal` entity itself (`status`, `decidedBy`, `decisionNote`,
`updatedAt`) plus a side effect against the underlying `PunishmentEntity`/
`PunishmentProfileEntity` via `AppealDecisionService.applyDecision`. Documented here as
its own conceptual entity per the spec's Key Entities section.

| Field | Notes |
|---|---|
| `decision` | One of exactly `GRANTED_FULL_LIFT`, `GRANTED_DURATION_REDUCTION`, `DENIED` (`AppealReviewDTO.decision`, `@NonNull AppealStatus`) — any other `AppealStatus` value is rejected by `AppealController`'s `VALID_DECISIONS` set check before `AppealDecisionService` is ever called (FR-008). |
| `decisionNote` | Optional, `@Size(max = 2000)`. |
| `reviewerId` | Required `UUID`; rejected if it equals the punishment's `source` (self-review, FR-011). |
| `newExpirationAt` | Required, and must be a future epoch-millis timestamp, when `decision == GRANTED_DURATION_REDUCTION`; ignored for the other two decisions (FR-010). |

**Validation rules** (from spec Requirements, all enforced in `AppealController.review`
before `AppealDecisionService.applyDecision` is invoked):
- FR-008: decision must be one of the three valid `AppealStatus` values.
- FR-009: `GRANTED_FULL_LIFT` is rejected outright when the appeal's stored
  `severityTier` checklist value is `"SEVERE"`.
- FR-010: `GRANTED_DURATION_REDUCTION` requires a non-null `newExpirationAt` strictly
  greater than `System.currentTimeMillis()` at review time.
- FR-011: rejected (`403`) when `reviewerId` matches `punishment.getSource()`.
- FR-012: only accepted when `appeal.getStatus()` is `ELIGIBLE_PENDING_REVIEW` or
  `IN_REVIEW` (`409` otherwise).
- FR-016: `AppealDecisionService.applyDecision` returns `PUNISHMENT_NOT_ACTIVE` (mapped
  to `409`) if the punishment is no longer the profile's active ban/chat-ban slot by the
  time the decision is applied — the appeal itself is left in its prior state, not marked
  resolved, matching spec Edge Cases.

## State Transitions (`AppealStatus`)

```text
                 submit()
                    │
        ┌───────────┴───────────┐
        ▼                       ▼
ELIGIBLE_PENDING_REVIEW      INELIGIBLE   (terminal — no further transition exists)
        │
        │ review() — GRANTED_FULL_LIFT / GRANTED_DURATION_REDUCTION / DENIED
        ▼
GRANTED_FULL_LIFT | GRANTED_DURATION_REDUCTION | DENIED   (all terminal)
```

`AppealStatus` declares two additional values, `SUBMITTED` and `IN_REVIEW`, that are
**never assigned by any code path** in this feature:
- `SUBMITTED` — `AppealController.submit` sets `ELIGIBLE_PENDING_REVIEW` or `INELIGIBLE`
  directly from the eligibility result; there is no intermediate "recorded but not yet
  evaluated" persisted state, so `SUBMITTED` is currently dead.
- `IN_REVIEW` — checked as a valid pre-review state in `AppealController.review`'s guard
  (`appeal.getStatus() != ELIGIBLE_PENDING_REVIEW && appeal.getStatus() != IN_REVIEW`),
  but nothing in this codebase ever transitions an appeal *into* `IN_REVIEW` — there is no
  "reviewer claims/locks this appeal" action. The guard accepts it defensively (e.g. for a
  future claim step) but it is unreachable today.

This is worth flagging honestly rather than silently documenting the enum as if all seven
values were live: only five of the seven `AppealStatus` values are reachable in the
current implementation (`ELIGIBLE_PENDING_REVIEW`, `INELIGIBLE`, `GRANTED_FULL_LIFT`,
`GRANTED_DURATION_REDUCTION`, `DENIED`).

## Relationships

```text
Appeal (N) ──── punishment_identifier (FK) ────  (1) Punishment
                                                       [punishment feature package]

Appeal.appellantHash ──── (by value, no FK) ────  EloProfileEntity.owner
                                                       [elo feature package — read-only,
                                                        supporting signal only]

Appeal (N) ──── punishment_identifier ────  Appeal (N)
   [self-relationship by shared punishment, used for the repeat-appeal cooldown query;
    not a DB-level self-FK, just a repository query on the shared FK value]
```
