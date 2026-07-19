# Phase 1 Data Model: Ban-Evasion Detection

Derived from the spec's Key Entities section, cross-checked against the actual JPA
entity (`IpCorrelationTokenEntity`) and the request DTO it's populated from
(`EvasionRecordDTO`). Unlike the ELO engine, this feature has exactly one persisted
entity and produces one non-persisted, in-flight "signal" concept (the domain event) —
there is no second table.

## Correlation Sighting (`IpCorrelationTokenEntity` / `ip_correlation_tokens`)

One row per distinct `(token, ownerHash)` pair ever observed — spec's "Correlation
Sighting" entity. This table is itself personal data (a keyed-hashed IP plus a hashed
account identity) and is subject to its own bounded retention window, not kept
indefinitely.

| Field | Type | Notes |
|---|---|---|
| `identifier` | `UUID` (PK, `GenerationType.UUID`) | Row identity only; never referenced by any other table (no FK anywhere into or out of this table). |
| `token` | `String` | `HMAC-SHA512(rawIp, blackhole.evasion.ip-salt)`, hex-encoded (128 hex chars). The IP address in its one-way transformed form — this is the "IP address" the spec's Key Entities section refers to; the raw address is never a field on this entity at all (FR-003). Indexed (`@Index(columnList = "token")`) — every correlation lookup filters by this column. |
| `ownerHash` | `String` | The connecting account's already-hashed identity, as received in `EvasionRecordDTO.owner` (validated upstream as a 128-hex-char SHA-512 string by that DTO's `@Pattern`). This feature does not compute this hash itself — it is produced by the caller (Velocity's `UUIDConverter.convertToSHA`) before the request ever arrives (spec Assumptions). Indexed (`@Index(columnList = "identifier")` — see note below). |
| `firstSeen` | `long` (epoch ms) | Set once, at row creation; never updated afterward. |
| `lastSeen` | `long` (epoch ms) | Refreshed on every repeat sighting of the same `(token, ownerHash)` pair (FR-007). Drives both the detection-window query (`findByTokenAndLastSeenGreaterThanEquals`) and the retention sweep (`findByLastSeenLessThan`). |
| `occurrenceCount` | `int` | Incremented by 1 on every repeat sighting; starts at 1 on creation. Not read by any query today — carried for future observability, not currently consumed by the correlation or retention logic itself. |
| `metaData` | `Map<String, Object>` (JSON column, `MapStringObjectConverter`) | Present on the entity (mirrors the same free-form-extension-point pattern as `EloProfileEntity`/`EloEventEntity`) but not populated by any current write path — always `{}` in practice. |

**Note on the `identifier` index**: the class Javadoc's stated intent
("`identifier` … `token`" indexed columns) matches the `@Index` annotations on the
entity, but `identifier` is also the `@Id` (primary key) — most JPA providers already
index the primary key implicitly, so this second explicit index is likely redundant in
practice. Not corrected here since this document describes the implementation as-is.

**Validation rules** (from spec Requirements):
- FR-001: created for every login unconditionally — no pre-flagging or opt-in gate on
  either the account or the IP address.
- FR-002/FR-003: `token` is written only via `hmacSha512(rawIp, salt)`; there is no code
  path in `IpCorrelationService` that persists `rawIp` itself, and no column exists on
  the entity that could hold it.
- FR-006: no row is ever created (and no lookup/correlation check ever runs) when
  `IpCorrelationService.isConfigured()` is `false` — `recordLogin` throws
  `IllegalStateException` before doing anything if called anyway, and the controller
  never calls it in that state.
- FR-007: `(token, ownerHash)` acts as a de facto uniqueness key at the application
  level (`findByTokenAndOwnerHash` is checked before every insert) — refresh-in-place,
  not a duplicate row, though this is not enforced by a database-level unique
  constraint, only by the service's find-before-write logic (see plan.md's Constraints
  note on the resulting non-atomic race).
- FR-008/FR-009: `lastSeen` past `blackhole.evasion.retention-days` (default 90,
  independently configurable) makes a row eligible for deletion by
  `IpCorrelationRetentionSweeper`, which runs on `blackhole.evasion.retention-sweep-cron`
  (default `0 30 3 * * *`).

**State transitions**: no explicit state machine. A row is created once, optionally
refreshed any number of times (`lastSeen`/`occurrenceCount` only), and eventually deleted
outright by the retention sweep — there is no soft-delete/tombstone state (see
research.md's retention-deletion decision).

## Evasion Signal (not a persisted entity — the `evasion.detected` domain event payload)

Spec's "Evasion Signal" entity is not a database row; it is the payload of a
`DomainEvent` published on the `blackhole.events` topic exchange (routing key
`evasion.detected`), constructed inline in `IpCorrelationService.checkEvasion` and never
stored. Included here because the spec treats it as a Key Entity in its own right.

| Field | Type | Notes |
|---|---|---|
| `token` | `String` | Same keyed-hash value as the Correlation Sighting row(s) it was derived from — never the raw IP (FR-003, SC-003). |
| `owners` | `List<String>` | Every distinct `ownerHash` found within the detection window for this `token` (`sightings.stream().map(...).distinct().toList()`) — always ≥ 2 elements when the event fires at all, since `checkEvasion` returns early (`distinctOwners.size() <= 1`) and never publishes otherwise. Handles 3+ distinct accounts identically to 2 (spec Edge Cases) — no special-casing on count beyond the `>1` check. |

The standard `DomainEvent` envelope (`DomainEventPublisher.publish`) wraps this payload
with an `eventId` (random UUID), `eventType` (`"evasion.detected"`), `timestamp`, and a
fixed `payloadVersion` (currently `1`) — identical envelope shape to every other domain
event in the system, not specific to this feature.

**Validation rules** (from spec Requirements):
- FR-004: fires precisely when `distinctOwners.size() > 1` within the detection window —
  never for exactly one distinct owner, no matter its `occurrenceCount` (FR-005, spec
  Edge Cases "same account reconnects many times").
- FR-010: this is the full extent of this feature's output — no punishment, ELO delta, or
  other consequence is applied or triggered from this method or anywhere else in the
  `evasion/` package. As of this writing, no consumer of `evasion.detected` exists
  anywhere in this repository (confirmed by search — the only occurrence of the string
  `"evasion.detected"` is the `publish` call itself).

**State transitions**: none — this is a point-in-time, fire-and-forget notice, not a
stored/updatable record. A second login producing the same correlation re-fires a new,
independent event rather than updating a prior one.

## Relationships

```text
Correlation Sighting (N, same token) ──── correlation check ────> Evasion Signal (0..1 per login, ephemeral)
                                                                    │
                                                                    └── published only, not persisted;
                                                                        no known consumer today
```

No foreign keys exist into or out of `ip_correlation_tokens` — `ownerHash` is a
by-value reference to whatever entity elsewhere in the system uses that same hashed
account identity (e.g. `PunishmentProfileEntity.owner`, `EloProfileEntity.owner`), not a
database-enforced relationship, consistent with how hashed-identity joins are handled
everywhere else in this codebase (see `specs/001-elo-engine/data-model.md`'s identical
by-value-join note for `ELO Event.owner`).
