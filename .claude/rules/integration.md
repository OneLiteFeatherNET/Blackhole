---
paths:
  - "backend/src/main/java/net/onelitefeather/blackhole/backend/integration/**"
---

# Integration detections

Source: `openspec/changes/add-integration-evidence/design.md` (decisions 1, 2, 5). A detection is a signal from a
registered third-party integration; it never feeds the ELO engine and never creates or changes a punishment.

- **Intake.** `POST /integration/detections` is `@Secured(Roles.INTEGRATION)` only. The caller is its registration:
  the token's `azp` must match an enabled `integration` row (`findByClientId`), otherwise 403. The body's
  `sourceSystem` must equal the registration's, or the item is `SOURCE_MISMATCH`.
- **Caps.** The body is measured in bytes before it is parsed (more than 64 KiB, 413). More than 50 items is 413.
  Both answer before any item is validated or stored.
- **Items.** Each item gets its own verdict, in request order. A bad item never stops the others. Unknown envelope
  fields are `SCHEMA_INVALID`. Schemas are in `backend/src/main/resources/integration-schemas/<source>/<KIND>/v<n>.json`
  (JSON Schema 2020-12, `com.networknt:json-schema-validator`), listed in `SchemaRegistry.BUNDLED`.
- **Idempotency.** `(integration_id, detection_id)` is unique; a repeat is `DUPLICATE` and changes nothing.
- **Owner.** Stored as the SHA-512 hash (`UUIDHasher`), never the raw UUID. Logs carry ids, counts and reason codes only.
- **Rejection codes** (`dto/DetectionReason`): `SCHEMA_INVALID`, `UNKNOWN_KIND`, `PII_IN_PAYLOAD`,
  `OCCURRED_AT_OUT_OF_RANGE` (outside [now - 24 h, now + 5 min]), `SOURCE_MISMATCH`, `SERVER_OUT_OF_SCOPE`.
- **Retention sweeper.** `IntegrationDetectionSweeper` deletes unattached `integration_detection` rows whose
  `occurred_at` is older than `blackhole.integration.detection-retention`, env `BLACKHOLE_INTEGRATION_DETECTION_RETENTION`
  (ISO-8601, default `P180D`). Cron: `BLACKHOLE_INTEGRATION_DETECTION_RETENTION_SWEEP_CRON` (default 03:35 daily).
  Attached rows (`attached_evidence_id` set) are never deleted here. It logs only the count.
- **Schema changes.** A new schema version is a new file (`v2.json`), never an edit of `v1`. Add the path to `BUNDLED`.
  Migrations are append-only (`V44` is this table; the attach change adds `V45`).
