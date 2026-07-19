# Phase 1 Data Model: Dual-ELO Punishment Engine

Derived from the spec's Key Entities section, cross-checked against the actual JPA
entities (`EloProfileEntity`, `EloEventEntity`) and the evidence entity it references
(`PunishmentEvidenceEntity`, owned by the `punishment` feature package).

## ELO Profile

One row per player; a 1:1 extension of that player's punishment profile.

| Field | Type | Notes |
|---|---|---|
| `owner` | `String` (PK) | SHA-512 hash of the player identity — never a raw UUID (constitution Principle I). Validated at the API boundary via `ChatSignalDTO`'s `@Pattern(regexp = "^[a-fA-F0-9]{128}$")`. |
| `chatElo` | `int` | Current chat-track standing. Defaults to `blackhole.elo.baseline` (1000) on first creation. |
| `gameplayElo` | `int` | Current gameplay-track standing. Same baseline default, independent value. |
| `chatEloUpdatedAt` | `long` (epoch ms) | Last time `chatElo` changed, from any source (delta or decay). Drives decay-interval math. |
| `gameplayEloUpdatedAt` | `long` (epoch ms) | Same, for `gameplayElo`. |
| `metaData` | `Map<String, Object>` (JSON column) | Free-form extension point; not populated by any current write path but present on the entity/DTO. |

**Validation rules** (from spec Requirements):
- FR-001: created lazily on first score adjustment for an `owner` that has none yet,
  starting both tracks at baseline — never created empty/on read.
- Both tracks are fully independent (spec Assumptions) — no field or trigger couples
  `chatElo` and `gameplayElo`.

**State transitions**: no explicit state machine; `chatElo`/`gameplayElo` are plain
integers that move up (decay, positive deltas from e.g. `REPORT_REWARDED`) or down
(violations). The only *meaningful* transitions are threshold crossings, which are not
stored on the profile itself — they are derived at write time by comparing
previous/new score (see ELO Event below) and are not idempotent/replayable from the
profile alone.

## ELO Event

Append-only audit-trail record; one row per score mutation, immutable once written.

| Field | Type | Notes |
|---|---|---|
| `identifier` | `UUID` (PK, generated) | |
| `owner` | `String` | Same hashed identity as the profile it belongs to (no FK constraint — join is by value). |
| `track` | `EloTrack` enum (`CHAT`\|`GAMEPLAY`) | |
| `delta` | `int` | Signed change applied; negative for violations, positive for decay/rewards. |
| `reasonCode` | `EloReasonCode` enum | `TOXICITY_FLAG`, `ANTICHEAT_FLAG`, `REPORT_ACTIONED`, `MANUAL_ADJUSTMENT`, `DECAY_RECOVERY`, `THRESHOLD_BAN`, `PUNISHMENT_APPLIED`, `REPORT_REWARDED`. |
| `sourceEvidenceId` | `UUID`, nullable | Points at a `PunishmentEvidenceEntity` (e.g. the Chat Evidence hash) when the event has supporting evidence; null for decay/manual/etc. |
| `resultingScore` | `int` | The track's score *after* this delta was applied — makes history self-describing without replaying deltas. |
| `createdAt` | `long` (epoch ms) | |
| `metaData` | `Map<String, Object>` (JSON column) | Carries context such as `{"score": 0.62}` for a toxicity flag or `{"daysElapsed": 3}` for decay. |

**Validation rules** (from spec Requirements):
- FR-009: every mutation — automatic or manual — produces exactly one `ELO Event`; there
  is no code path that changes a profile's score without also writing one.
- Indexed on `identifier` and `owner` (history queries filter by owner, paginated).

**State transitions**: none on the event itself (write-once, never updated or deleted —
matches the constitution's append-only stance extended informally to this table, even
though it isn't Liquibase-schema-managed in the append-only sense that phrase usually
refers to).

## Chat Evidence

Not owned by this feature — a `PunishmentEvidenceEntity` (type `CHAT_MESSAGE`) created
by `ChatToxicityService` and referenced by `ELO Event.sourceEvidenceId`. Documented here
only for the fields this feature's requirements constrain (FR-003):

| Field (as used by this feature) | Notes |
|---|---|
| `type` | Always `EvidenceType.CHAT_MESSAGE` for chat-toxicity evidence. |
| `contentHash` | `SecretHasher.hash(message)` — the *only* trace of the message content ever persisted. |
| `expiresAt` | `now + blackhole.elo.chat.evidence-retention-days` (default 60 days). Expiry/purge mechanics belong to the `punishment` feature, out of scope here. |

**Validation rule** (FR-003): the raw message string is never passed to any repository,
logger, or event payload — only its hash. This is a code-level invariant, not a
database constraint; there is no field to violate on the entity itself.

## Relationships

```text
ELO Profile (1) ──── owner ────  (N) ELO Event      [not a DB foreign key — joined by value]
ELO Event   (0..1) ── sourceEvidenceId ── (1) Chat Evidence (PunishmentEvidenceEntity)
```
