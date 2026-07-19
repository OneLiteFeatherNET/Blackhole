# Phase 1 Data Model: Vanilla Ban-List Import

Derived from the spec's Key Entities section, cross-checked against the actual record/
entity types (`VanillaBanEntry`, `VanillaIpBanEntry`, `VanillaImportResultDTO`) and the
`punishment`/`profile`-owned JPA entities this feature writes into
(`PunishmentEntity`, `PunishmentTemplateEntity`, `PunishmentProfileEntity`). This feature
owns no schema/table of its own — see plan.md "Storage".

## Vanilla Ban List Entry (`VanillaBanEntry`)

One record from the uploaded `banned-players.json`, deserialized directly (field names
match vanilla's own format exactly, per the record's Javadoc) — never persisted as-is.

| Field | Type | Notes |
|---|---|---|
| `uuid` | `String` | Raw player UUID as vanilla stores it. **Never persisted** — converted via `UUIDHasher.hash(UUID.fromString(uuid))` to the same SHA-512 `owner` representation used everywhere else (spec FR-002); the parsed `UUID` itself is a local variable that goes out of scope once hashed. |
| `name` | `String` | Display name at ban time. Read only for `invalidEntries` troubleshooting messages (`"Invalid uuid for entry '" + entry.name() + "'"`); never stored on any entity. |
| `created` | `String` | Vanilla timestamp format (`yyyy-MM-dd HH:mm:ss Z`). Parsed via `parseCreated`; missing/blank defaults to `System.currentTimeMillis()` (FR-008) rather than rejecting the entry. |
| `source` | `String` | The banning operator's display name as recorded by the *source* server. **Read but never persisted or used as an issuer** — spec Assumptions explicitly rules this out as a verifiable staff identity (constitution Principle II: no per-actor identity exists to validate it against). |
| `expires` | `String` | Same timestamp format, or absent/`"forever"` for a permanent ban. Parsed via `parseExpiration`; unparseable-but-present values reject the entry (FR-007); absent/`"forever"` (case-insensitive) yields `Optional.empty()` → permanent punishment (FR-004). |
| `reason` | `String` | Free text. Missing/blank defaults to `DEFAULT_REASON = "Imported vanilla ban"` (FR-008) rather than rejecting the entry. |

**Validation rules** (traced to spec FRs):
- FR-002: `uuid` is converted to a hash before any repository/log/event use; the raw
  string form is discarded after `UUIDHasher.hash` returns.
- FR-007: an `uuid` that fails `UUID.fromString` or an `expires` that fails
  `OffsetDateTime.parse` against `VANILLA_DATE_FORMAT` rejects **only this entry**
  (`invalid++`, a message appended to `invalidEntries`, `continue` to the next entry) —
  the rest of the file still processes.
- FR-008: a missing/blank `reason` or `created` does not reject the entry — see table
  above for the applied defaults.
- FR-003/FR-004: not a field-level validation on the entry itself, but a derived
  classification — see "Vanilla Ban List Entry → Punishment mapping" below.

## Vanilla IP Ban Entry (`VanillaIpBanEntry`)

One record from the optional uploaded `banned-ips.json`. Deserialized only to be
**counted** — never converted, never inspected field-by-field, never persisted in any
form (spec Key Entities, FR-010).

| Field | Type | Notes |
|---|---|---|
| `ip` | `String` | Present on the record for faithful deserialization of the source format only. **No code path ever reads this field** — `VanillaImportService` only calls `ipEntries.size()`, satisfying FR-010's "no raw IP address... may be persisted in any form" by construction rather than by a redaction step. |
| `created`, `source`, `expires`, `reason` | `String` | Same — deserialized, never read individually. |

**Validation rules**: none — this type has no valid/invalid distinction in this feature;
every entry in the file counts toward `ipsTotal`/`ipsSkipped` unconditionally, including
zero (spec Edge Cases: "a ban list contains only IP ban entries... the import still
completes and returns a valid, zero-count summary").

## Import Outcome (`VanillaImportResultDTO`)

Summary of one import operation (real or preview, per `dryRun`). Returned as the `200 OK`
body of `POST /admin/import/vanilla`; see `contracts/import-api.md`.

| Field | Type | Notes |
|---|---|---|
| `dryRun` | `boolean` | Echoes the `dryRun` query parameter — whether this run wrote anything (FR-009). |
| `playersTotal` | `int` | `entries.size()` — every entry found in `banned-players.json`, regardless of outcome. |
| `playersImported` | `int` | Entries that were (real run) or would be (`dryRun`) imported — incremented identically in both branches (see research.md "Decision: Dry run reuses the same skip/import decision"). |
| `playersSkippedExisting` | `int` | Entries skipped because `profile.getActiveBan() != null` at lookup time (FR-006, US3) — includes both a genuinely pre-existing Blackhole punishment and a duplicate within the same file/run (Edge Cases: "the same player appears twice... the second occurrence sees the punishment already created by the first occurrence"). |
| `playersInvalid` | `int` | Entries whose `uuid` or `expires` could not be parsed (FR-007). |
| `invalidEntries` | `List<String>` | One human-readable description per invalid entry (e.g. `"Invalid uuid for entry 'Foo': not-a-uuid"`), enough detail to locate it in the source file (FR-007, FR-013). |
| `ipsTotal` | `int` | Total entries found in `banned-ips.json`, or `0` if none was provided (FR-010, Edge Cases). |
| `ipsSkipped` | `int` | Always equal to `ipsTotal` — every IP entry is unconditionally not imported (FR-010). |

**Validation rules** (from spec Requirements):
- FR-013: every field above is populated for both a real run and a `dryRun` preview —
  there is no reduced/partial summary shape for either mode.
- Invariant (not separately enforced, but true by construction of the loop):
  `playersTotal == playersImported + playersSkippedExisting + playersInvalid`.

## Vanilla Ban List Entry → Punishment mapping (derived classification)

Not a persisted entity itself — the decision `VanillaImportService` makes per entry,
combining the entry's own fields with the existing profile lookup. Traced to FR-003/
FR-004/FR-005/FR-006:

| Condition | Outcome |
|---|---|
| `profile != null && profile.getActiveBan() != null` | Skip — `playersSkippedExisting++`, no write (FR-006). |
| `expires` absent, blank, or `"forever"` (case-insensitive) | Permanent `PunishmentEntity` (no `Expirable.META_DATA_KEY_EXPIRATION_DATE` in metadata) placed into `profile.activeBan` (FR-004). |
| `expires` present, parses, and is in the future | Temporary `PunishmentEntity` (`Expirable.META_DATA_KEY_EXPIRATION_DATE` set) placed into `profile.activeBan` (FR-004). |
| `expires` present, parses, and is `<= System.currentTimeMillis()` | `PunishmentEntity` created with the expiry metadata set, but appended to `profile.history` instead of `profile.activeBan` — recorded as already-inactive, never live (FR-004, US1 Scenario 2). |
| `expires` present but fails to parse | Entry rejected entirely (`invalid++`); no `PunishmentEntity` created (FR-007). |

Every branch that does create a `PunishmentEntity` sets:
- `type = PunishType.NETWORK` (FR-003 — always network-wide, never `SERVER`/`CHAT`).
- `source = SYSTEM_IMPORT_SOURCE` (FR-005 — the fixed deterministic import identity; see
  research.md).
- `template` = the result of `findOrCreateTemplate(reason, templateCache)` — an existing
  `PunishmentTemplateEntity` matched by exact `(reason, PunishType.NETWORK)`, or a newly
  created one with `eloDelta = 0` and `metaData = {"imported": true, "importSource":
  "vanilla"}` if none matches. `templateCache` is a per-request `Map<String,
  PunishmentTemplateEntity>` so repeated reasons within one uploaded file hit the
  repository (or create a template) only once (spec Edge Cases: "two entries... share
  identical reason text: they are treated as the same reason").

## Relationships

```text
VanillaBanEntry (transient, request-only)
      │  UUIDHasher.hash(uuid) → owner
      ▼
PunishmentProfileEntity (owner=hash)  ──activeBan/history──►  PunishmentEntity
                                                                     │ template
                                                                     ▼
                                                          PunishmentTemplateEntity
                                                          (matched/created by reason,
                                                           eloDelta always 0 when created
                                                           by this feature)

VanillaIpBanEntry (transient, counted only) ──X──► nothing persisted

VanillaImportResultDTO — response-only, not stored; one per HTTP call
```

`PunishmentEntity`, `PunishmentTemplateEntity`, and `PunishmentProfileEntity` are owned
by the `punishment`/`profile` feature packages (see `specs/002-punishment-core/` for
their full field-level spec) — only the subset of fields this feature actually populates
is documented above; this feature introduces no new entity, column, or Liquibase
changeset.
