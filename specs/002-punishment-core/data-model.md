# Phase 1 Data Model: Punishment Core

Derived from the spec's Key Entities section, cross-checked against the actual JPA
entities (`PunishmentTemplateEntity`, `PunishmentEntity`, `PunishmentEvidenceEntity` in
the `punishment` package; `PunishmentProfileEntity` in the sibling `profile` package)
and the Liquibase changelog that owns their schema.

## Punishment Template

A reusable, named definition a punishment is created from. Exists independently of any
player — table `punishment_templates`.

| Field | Type | Notes |
|---|---|---|
| `identifier` | `UUID` (PK, `GenerationType.UUID`) | `null` on create, server-generated. |
| `reason` | `String` | Free-text reason shown to staff/players. |
| `type` | `PunishType` enum (`SERVER`\|`NETWORK`\|`CHAT`), stored as `tinyint` | Which enforcement track a punishment created from this template lands on (FR-002) — `CHAT` maps to the chat slot, `SERVER`/`NETWORK` both map to the general-access ("ban") slot. |
| `eloDelta` | `int`, default `0` | Delta applied to the offender's ELO track when this template is applied via `PunishmentApplicationService.apply` (see `research.md` "system-actor" decision for the no-double-dip exception). `0` = no ELO effect — the default for pre-existing templates and the recommended value for permanent-ban templates, since dropping ELO further after a permanent ban is moot. |
| `metaData` | `Map<String, Object>` (JSON column) | Carries `Durationable.META_DATA_KEY_DURATION` as an ISO-8601 duration string (e.g. `PT1H`) when the template is temporary; **absence of this key means permanent** (FR-003). Also carries `PunishTemplateDTO.META_DATA_KEY_TRANSLATABLE` when the `reason` is a translation key rather than literal text. |

**Validation rules** (from spec Requirements):
- FR-001/FR-010: templates are created/updated/removed independently of any specific
  punishment via `PunishmentTemplateController`; nothing in the entity or repository
  couples a template to a player.
- FR-003: `metaData[duration]`, when present, must be a value `Duration.parse(...)`
  accepts — `PunishmentApplicationService.apply` calls this directly with **no
  try/catch**, so a malformed duration string throws an unhandled
  `DateTimeParseException` rather than silently producing an undefined expiry (spec
  Edge Cases: "must fail clearly rather than silently producing a punishment with an
  incorrect or undefined expiry" — satisfied, though as an uncaught exception rather
  than a modeled `Error` response, consistent with the Principle III DTO-contract gap
  noted in plan.md).
- FR-010: updating/removing a template never touches already-created `PunishmentEntity`
  rows — `PunishmentEntity.type`/`metaData` are copied at creation time (see Punishment
  below), not referenced live from the template.

**State transitions**: none — a template is a flat, directly-mutable definition with
no lifecycle of its own; `update`/`delete` in `PunishmentTemplateController` operate
directly on the row via the repository (see plan.md Constitution Check, Principle III).

## Punishment

One concrete, applied instance — table `punishments`.

| Field | Type | Notes |
|---|---|---|
| `identifier` | `String` (PK) | A 22-character URL-safe base64 encoding of a random `UUID` (`IdGenerator.generateId()`), not the UUID's string form — chosen for a shorter identifier while remaining effectively as collision-resistant. |
| `source` | `UUID` | Who/what issued the punishment (path variable on `POST /punishment/active/{owner}/{templateId}/{source}`); unverified — see constitution Principle II. `EloService.SYSTEM_ELO_SOURCE` (a deterministic `UUID.nameUUIDFromBytes` value, shared with the ELO engine) marks an automatically-issued punishment, satisfying FR-005's "distinguished from any other party." |
| `type` | `PunishType` enum, `tinyint` | Copied from the template's `type` **at creation time** and tracked independently — per the entity's own Javadoc, so a template's type changing later never retroactively reinterprets an already-applied punishment (FR-010). |
| `scope` | `String`, nullable | Optional restriction (e.g. an event/community) a punishment applies to; `null` means network-wide. Always `null` in the current `apply` implementation — the field exists on the entity/DTO but nothing in `PunishmentApplicationService` currently sets it to a non-null value. |
| `template` | `PunishmentTemplateEntity` (`@ManyToOne`, FK `template_identifier`) | The originating template, kept as a live reference (unlike `type`, which is snapshotted) — used by `toDTO()` to embed the full template. |
| `metaData` | `Map<String, Object>` (JSON column) | Carries `Metadata.META_DATA_KEY_CREATION_DATE`/`_UPDATE_DATE` (both set at creation), `Expirable.META_DATA_KEY_EXPIRATION_DATE` (epoch millis, present only if the template had a duration — absence means permanent, FR-003), plus any `extraMetaData` a caller passed (e.g. the ELO engine's `punishmentIdentifier`/`templateIdentifier` breadcrumb) and, once revoked, a `revokedBy` key (see State transitions). |

**Validation rules** (from spec Requirements):
- FR-012: `PunishmentApplicationService.apply` returns `Optional.empty()` (mapped to
  HTTP 404 by `PunishmentEntityController.add`) when `templateId` doesn't resolve —
  no `PunishmentEntity` is created.
- FR-003: permanent vs. temporary is entirely encoded by the presence/absence of the
  `expirationDate` metadata key — there is no separate boolean/enum field for it.

**State transitions**: "Immutable in substance once created, aside from being marked
revoked" (spec Key Entities). In code, a revoke doesn't change `type`/`template`/
`identifier` — it only adds `metaData["revokedBy"]` and bumps
`META_DATA_KEY_UPDATE_DATE` before moving the row out of whichever profile slot held
it and into `history`. Natural expiry does **not** write any extra metadata to the
punishment row itself — only the profile's slot/history pointers change — so "expired"
vs. "revoked" is distinguished by the *absence* of `revokedBy` on an out-of-slot
punishment whose `expirationDate` has passed, not by an explicit status field (spec US4
Acceptance Scenario 1: "marked as revoked (not expired)" — true at the profile-history
level, but there is no single `status` enum on `PunishmentEntity` itself capturing
"active/expired/revoked" as one queryable value).

## Punishment Profile

A player's current enforcement state — owned by the `profile` package, table
`punishment_profiles`.

| Field | Type | Notes |
|---|---|---|
| `owner` | `String` (PK) | SHA-512 hash, validated at every controller boundary via `@Pattern(regexp = "^[a-fA-F0-9]{128}$")` — never a raw UUID (constitution Principle I). |
| `activeChatBan` | `PunishmentEntity` (`@OneToOne`, FK `active_chat_ban_identifier`, **unique**) | The CHAT track's sole active punishment, or `null`. The DB-level `unique` constraint is what makes "at most one" structurally enforced, not merely application logic (see `research.md`). |
| `activeBan` | `PunishmentEntity` (`@OneToOne`, FK `active_ban_identifier`, **unique**) | The general-access track's (SERVER or NETWORK) sole active punishment, or `null`. Independent of `activeChatBan` — nothing couples the two tracks (spec US2 Acceptance Scenario 2). |
| `history` | `List<PunishmentEntity>` (`@OneToMany`, join table `punishment_profiles_punishments`, `FetchType.EAGER`) | Every punishment that has ever left an active slot (expiry, revocation, or supersession) — append-only in practice (nothing in the codebase removes an entry), though not database-enforced as immutable the way Liquibase changesets are. Eagerly fetched: a profile read loads its **entire** history every time, no pagination on this specific field (see plan.md Scale/Scope). |
| `metaData` | `Map<String, Object>` (JSON column) | Also carries unrelated session telemetry (`sessionInfo.protocolVersion`/`clientBrand`/`lastSeenAt`) written by `ProfileController.updateSessionInfo` — a Phase-7 dashboard feature sharing this same entity/column rather than a punishment-specific field. |

**Validation rules** (from spec Requirements):
- FR-004/SC-002: enforced twice — application-level (`apply`/`revoke`/sweep/lazy-expiry
  all move the prior occupant to `history` before clearing or replacing a slot) and
  DB-level (unique constraint on each slot's FK column).
- Spec Edge Cases ("player who has never received one before"): `apply` creates a new,
  otherwise-empty `PunishmentProfileEntity` (`activeBan=null`, `activeChatBan=null`,
  `history=[]`) on first punishment rather than failing — same lazy-creation pattern
  `EloProfileEntity` uses (`specs/001-elo-engine/data-model.md`).

**State transitions**: A punishment moves `null slot -> occupies slot -> history` via
exactly one of four paths (apply's supersession rotation, `revoke`, the expiry
sweeper, or `ProfileController.getById`'s lazy-expiry check) — see `research.md`'s
"fourth implementation" decision for why these four paths are independent code rather
than one shared helper.

## Punishment Evidence

A record supporting why a punishment was issued — table `punishment_evidence`, owned
by the `punishment` package.

| Field | Type | Notes |
|---|---|---|
| `identifier` | `UUID` (PK, `GenerationType.UUID`) | |
| `punishment` | `PunishmentEntity` (`@ManyToOne`, FK `punishment_identifier`) | The single punishment this evidence supports — not shared across punishments. |
| `evidenceType` | `EvidenceType` enum (`CHAT_MESSAGE`\|`ANTICHEAT_FLAG`\|`REPORT`\|`MANUAL_NOTE`), `tinyint` | Where the evidence originated from. |
| `referenceId` | `String`, nullable | An external reference (e.g. a chat log message id), if any. |
| `capturedContentHash` | `String`, nullable | A hash of the captured content — the *only* trace of the underlying content ever persisted (FR-011). |
| `retentionExpiresAt` | `Long` (epoch millis), nullable | When set, the point after which this evidence should be erased — bounded retention window per FR-011; `null` means unbounded (no current code path purges expired evidence automatically within this package — purge mechanics, if any, are out of this package's scope). |
| `metaData` | `Map<String, Object>` (JSON column) | Free-form extension point. |

**Validation rule** (FR-011): there is no raw-content column on this entity at all —
"never retaining more ... than a hash and/or external reference" is a structural
property of the schema, not merely a convention a writer could violate. This mirrors
the ELO engine's identical treatment of chat evidence (`specs/001-elo-engine/
data-model.md`'s "Chat Evidence" section) — the same table, `punishment_evidence`, is
the referenced entity in both specs.

## Relationships

```text
Punishment Template (1) ──── template_identifier ────  (N) Punishment
Punishment           (0..1) ── active_ban_identifier ──  (1) Punishment Profile   [unique FK]
Punishment           (0..1) ── active_chat_ban_identifier ── (1) Punishment Profile [unique FK]
Punishment Profile   (1) ──── history (join table) ────  (N) Punishment
Punishment           (1) ──── punishment_identifier ────  (N) Punishment Evidence
```

Note the FK direction on the active slots: `punishment_profiles.active_*_identifier`
points *at* a `punishments` row, so a punishment's "is this active" status is entirely
a property of whether some profile currently points at it (or has moved it into its
`history` join rows) — `PunishmentEntity` itself carries no `active`/`status` field.
