## Context

The code this design builds on (checked on `origin/main`, 2026-10-28):

- `backend/.../events/DomainEvent.java`: the envelope `{eventId, eventType, occurredAt, payloadVersion,
  payload}`. `payloadVersion` is 1 (`DomainEventPublisher.CURRENT_PAYLOAD_VERSION`). `eventId` is a random
  UUID or a caller-chosen id, and it is the NATS `Nats-Msg-Id`.
- `backend/.../events/EventSubjects.java`: subject `blackhole.events.<eventType>`; `EventSubjects.ALL` is
  `blackhole.events.>`. `NatsStreams.java`: stream `BLACKHOLE_EVENTS`, file storage, limits retention,
  `discardPolicy Old`, `denyDelete`, `denyPurge`, duplicate window for `Nats-Msg-Id`. Retention is set by
  `NatsSettings.streamMaxAge` and `streamMaxBytes`. Durable consumers use explicit ack and `maxDeliver(1)`.
- Event types published today: `punishment.created|updated|expired|revoked`, `profile.created|updated|deleted`,
  `section.changed`, `section.escalated`, `elo.threshold_crossed`, `appeal.submitted|resolved`,
  `report.created|resolved|feedback`, `evasion.detected`, `evasion.client_match`, `client-rule.hit`.
  Payloads are built in `PunishmentEvents.java` (hashed owner, identifiers, `reason`, `permanent`,
  `expiresAt`, `templateIdentifier`, `revokedBy`, `recordedByOid`) and inline `Map.of` calls in the
  services. Owners are SHA-512 hashes (`OwnerHash`); no raw UUID, no IP.
- Flyway head on `main` is `V27__kick.sql` (in the kick change). This change appends none.
- `deploy/charts/blackhole` has per-component templates and values (`backend`, `web`, `portal`, `discord`,
  `clamav`). S3 evidence already uses Ceph RGW through an ObjectBucketClaim (`deploy-to-kubernetes`,
  design section 6): internal RGW endpoint, path-style on, secrets from the OBC Secret.
- `openspec/config.yaml` principles: GDPR-by-design (hashes only) is amended by
  `amend-data-protection-principle`, which allows clear-text IP, UUID and name where a feature needs them,
  with retention enforced by sweepers and erasure on request. Publication stays pseudonymous.
- Stale rule file: `.claude/rules/events.md` still describes RabbitMQ. It is out of date and is not
  edited here (see open questions).

### Facts checked for this design (2026-10-28)

- Apache Iceberg Java: the docs site lists `1.12.0` as latest (menu of
  https://iceberg.apache.org/docs/latest/java-api-quickstart/); a 2026 write-up reports `1.11.0` released
  in May 2026 (https://dev.to/alexmercedcoder/an-in-depth-overview-of-the-apache-iceberg-1110-release-4l1n is
  about 1.11.0; the page list also mentions 1.10.1). The version is pinned at implementation time to the
  newest release that the sink's Parquet and S3 libraries support.
- Catalog properties (REST, JDBC, S3 FileIO) are documented at
  https://iceberg.apache.org/docs/latest/catalog-properties/ . The JDBC catalog page is linked from there.
  The REST catalog supports Basic, OAuth2, SigV4 and Google auth
  (https://iceberg.apache.org/docs/latest/ , as reported by the search result).
- Maintenance (expire snapshots, remove old metadata, delete orphan files, compact data files, rewrite
  manifests) is documented at https://iceberg.apache.org/docs/latest/maintenance/ . The expire-snapshots
  Java API and its properties (`history.expire.max-snapshot-age-ms`, `history.expire.min-snapshots-to-keep`)
  were not readable through the fetch tool. Check the exact names against the javadoc at implementation time.
- DuckDB Iceberg writes: https://duckdb.org/2025/11/28/iceberg-writes-in-duckdb (write support from v1.4.0,
  update and delete for v2 tables from v1.4.2). The writes page
  (https://duckdb.org/docs/current/core_extensions/iceberg/writing_to_iceberg.html) states that writes need an
  attached catalog and that `iceberg_scan` on a path is read-only. Limits in 1.4.x: update, insert and delete
  on partitioned or sorted tables raise an error; copy-on-write is not supported (merge-on-read only; tables
  whose `write.update.mode` or `write.delete.mode` is not merge-on-read fail). The 1.5 docs add bucket and
  truncate partition support. No DuckDB page found documents snapshot expiration or compaction.
- DuckDB REST catalog attach: https://duckdb.org/docs/lts/core_extensions/iceberg/iceberg_rest_catalogs.html .
  In the 2.0 docs `URI` is the option name and `ENDPOINT` is a deprecated alias.
- Cloudflare R2 Data Catalog added automatic snapshot expiration in Dec 2025
  (https://developers.cloudflare.com/changelog/2025-12-18-r2-data-catalog-snapshot-expiration). Useful as
  evidence that catalogs expect expiry to be scheduled, not manual. Not a decision input.

These facts drive the decisions below: DuckDB writes need an attached catalog (REST in the documented setup); DuckDB cannot delete or
expire, and cannot write partitioned tables on the 1.4 line; so the sink and the retention job are Java.

## Goals / Non-Goals

**Goals**: answer the question list in the proposal with SQL over complete history; keep enforcement
latency and the backend database untouched; keep personal data in one zone that can be erased and expired;
make re-delivery and backfill never double count; every table has a retention and an owner.

**Non-Goals**: real-time dashboards; a general ETL framework; ML features; changing event payloads; a second
event bus; Trino in this change.

## Decisions

### 1. Storage: Apache Iceberg tables in Parquet on Ceph RGW

Tables are Iceberg format version 2 (version 3 only when the chosen DuckDB and Iceberg releases read it
without a deletion-vector gap; decide at implementation). Data files are Parquet with ZSTD. Object storage is
the existing Ceph RGW, reached through Iceberg's `S3FileIO` with path-style access and the internal RGW
endpoint, the same way evidence is stored (`deploy-to-kubernetes` section 6).

Rejected: **a Postgres analytics schema** (the backend database is the system of record and is shared with
enforcement; heavy scans compete with writes, and retention would be row deletes instead of file removal);
**a ClickHouse or other OLAP server** (a second stateful service to run and back up, with no open table format
for the other engines that might come later); **raw JSON on S3 without a table format** (no schema evolution,
no atomic commits, no snapshot-based retention, and DuckDB would need a file glob per query).

### 2. Catalog: Iceberg JDBC catalog on PostgreSQL (recommended), REST catalog rejected for now

**Recommended: the Iceberg JDBC catalog, with table pointers in a dedicated PostgreSQL database.** Reasons:

- It follows the convention of the sister project Corvus: Postgres holds the catalog, and no extra service is
  run. Corvus ADR-CORV-0002 ("Parquet auf MinIO mit Postgres-Katalog als Data Lake", status proposed,
  https://outline.onelitefeather.dev/doc/adr-corv-0002-parquet-auf-minio-mit-postgres-katalog-als-data-lake-JvsZJBioRc)
  decided Postgres as the catalog. The two projects can then share one operational pattern (backup, restore,
  the catalog database role model), and possibly one catalog service later.
- **Difference, stated plainly:** Corvus does not use Iceberg. ADR-CORV-0002 chose a custom Postgres catalog over
  Parquet files on MinIO, and defers Iceberg "bei Bedarf". Blackhole needs Iceberg for snapshot-based retention
  (decision 7) and for atomic commits with snapshot summaries (decision 8). So the shared part is the Postgres
  catalog database and its operations; the table format is not shared. Corvus also uses MinIO, Blackhole uses
  Ceph RGW (see open question 9).
- The Iceberg JDBC catalog is part of the Iceberg Java library (`catalog-properties` page, JDBC section), so the
  sink needs no extra service. The catalog database is a separate database on the existing CNPG cluster, not the
  `blackhole` database, with its own role.
- The earlier reason for a REST catalog, "DuckDB writes need a REST catalog", does not apply: the sink writes
  from Java and DuckDB is read-only in this change (decision 12). DuckDB's ability to read a JDBC-catalog table is
  **not verified** (see open question 2). If DuckDB cannot read it, analysts use another reader, or the REST catalog
  is adopted later.

**Rejected for now: an Iceberg REST catalog service** (Apache Polaris, Lakekeeper or Nessie). Its advantages are
language neutrality, credential vending, and a single endpoint for DuckDB and Trino. Its cost is one more stateful
service to run, back up and patch on feather-core, for which there is no requirement yet. It stays the upgrade path if
DuckDB or Trino read access through the JDBC catalog does not work, or if the catalog must be shared across clusters.

### 3. Sink: separate Gradle module `analytics-sink`, its own Deployment

- New module `analytics-sink` (Micronaut application, Java 25 toolchain, Micrometer and Prometheus registry
  like the backend). It has no HTTP API except `/health` and the Prometheus endpoint.
- It keeps its own copy of the envelope record and its own NATS connection code. It must not depend on
  `backend` internals, so the backend can change without rebuilding the sink. Extracting a shared `events`
  contract module is a later refactor, mentioned, not done here.
- Rejected: **a package inside the backend** (the backend is the enforcement path; a batch writer with memory
  peaks and Parquet writes would share its heap and its restarts with the API); **a Kafka Connect or Flink job**
  (a new platform to run for ~20 event types); **a Velocity-side writer** (the proxy is subscribe-only and must
  not write data).
- Deployment: one Deployment, `replicas: 1` in the first release, because a durable consumer with explicit
  ack is shared by all replicas. Two replicas are possible later if the Iceberg commits are serialized by the
  catalog (they are optimistic); this is an open question.

### 4. Ingestion: durable consumer, batching, commit, then ack

- Durable consumer `analytics-sink` on `BLACKHOLE_EVENTS`, filter `blackhole.events.>` (all types), explicit
  ack, `deliverPolicy` set to `All` on first creation so that a new sink starts from the stream's oldest message
  (the backend's consumers start with `New`; this consumer must not). The stream's `maxAge` limits how far back
  live ingestion can go; the backfill (decision 9) covers older history.
- Batching: a batch is flushed when it holds `blackhole.analytics.batch.max-events` events (default 5000) or after
  `blackhole.analytics.batch.max-wait` (default `PT60S`), whichever comes first. One batch becomes one Iceberg
  snapshot per table written.
- Ack: messages are acknowledged **only after** the Iceberg commit of their batch has succeeded. A crash between
  commit and ack causes redelivery; decision 8 makes that harmless.
- Failure: a commit failure keeps the batch in memory and retries with backoff; messages are not acked, so the
  stream holds them until `maxAge`. If the outage outlasts the stream age, the gap is filled by backfill
  (decision 9). The sink reports the gap in metrics and in its readiness status.
- Undecodable events (unknown type, unsupported payload version, a malformed payload): written to the
  `analytics_dead_letter` table with the raw JSON, then acked. They are never dropped silently.

Rejected: **one Iceberg commit per event** (small-file explosion and a catalog round trip per event);
**ack on receive** (a crash would lose the batch silently); **a time-only flush** (a burst would make one
oversized Parquet file).

### 5. Table set, schema and versioning

Table names are fixed nouns; the event type is a column, not a table name, so a new event type of a known
family does not create a table.

| Table | Zone | Source event types | Partition |
|---|---|---|---|
| `punishment_events` | pseudonymous | `punishment.created`, `punishment.updated`, `punishment.expired`, `punishment.revoked` | `day(occurred_at)` |
| `profile_events` | pseudonymous | `profile.created`, `profile.updated`, `profile.deleted` | `day(occurred_at)` |
| `appeal_events` | pseudonymous | `appeal.submitted`, `appeal.resolved` | `day(occurred_at)` |
| `report_events` | pseudonymous | `report.created`, `report.resolved`, `report.feedback` | `day(occurred_at)` |
| `elo_events` | pseudonymous | `elo.threshold_crossed`, `section.escalated` | `day(occurred_at)` |
| `client_rule_hits` | pseudonymous | `client-rule.hit` | `day(occurred_at)` |
| `evasion_events` | pseudonymous | `evasion.detected`, `evasion.client_match` | `day(occurred_at)` |
| `section_events` | pseudonymous | `section.changed` | `day(occurred_at)` |
| `ref_templates` | pseudonymous | daily snapshot (decision 10) | `snapshot_date` |
| `ref_sections` | pseudonymous | daily snapshot | `snapshot_date` |
| `analytics_dead_letter` | pseudonymous | any undecodable event | `day(received_at)` |
| `ref_staff_names` | restricted | daily snapshot of display names | `snapshot_date` |
| `login_sessions` (reserved) | restricted | created by the session data change, not here | `day(occurred_at)` |

Common columns of every event table: `event_id` (string, the envelope id, the dedupe key), `event_type`,
`occurred_at` (timestamp with zone, UTC), `ingested_at`, `payload_version` (int), `source` (`live` or
`backfill`), `owner` (string, the SHA-512 hash, nullable where the event has none), then typed columns taken
from the payload of that event family (for example `punishment_id`, `template_identifier`, `section`,
`permanent`, `expires_at`, `revoked_by`, `verified`), and `extra` (a JSON string holding every payload key
that has no typed column). Typed columns come from the payload fields `PunishmentEvents` writes; the exact
list is the first task of the implementation (schema per event type, written as a Java record per family).

Schema versioning:

- Additive changes (a new optional payload key) become a new Iceberg schema id with an added column; old
  files stay readable, and the typed column reads null for old rows. No table is recreated.
- `payload_version` on the envelope decides the mapper. The sink supports version 1 now; an unknown version goes
  to the dead-letter table.
- A breaking change (a renamed or retyped column) creates a new table with a `_v2` suffix and runs in parallel
  until the old one is retired by its retention. Recorded as a table property `analytics.schema.version`.

Rejected: **one wide table for all events** (a sparse table of 60 columns with most cells empty, and every query
would filter by type); **one table per event type** (about 20 tables for families that share most columns and
share their retention; the family tables above keep it to eight).

### 6. Partitioning

Every event table is partitioned by `day(occurred_at)`, the Iceberg hidden partition transform, so queries on
time ranges prune files without the user writing the partition column. Reference tables partition by
`snapshot_date`. Partitioning by owner was rejected: the owner has very high cardinality and would produce many
small files; per-owner questions are answered by sorting within a day file (`occurred_at, owner`).

Partitioned tables cannot be written by DuckDB on the 1.4 line (see Context), so all writes, including deletes
and expiry, are done by the Java sink or the retention job. DuckDB is read-only for the analysts, and that is
the intended split.

### 7. Retention per table

Each table has a retention in days, set in `application.yml` (`blackhole.analytics.retention.<table>`) with
an env-variable override, following the existing convention. Defaults are a first guess, to be confirmed by
the maintainer:

| Zone | Tables | Default retention |
|---|---|---|
| pseudonymous | `punishment_events`, `profile_events`, `appeal_events`, `report_events`, `elo_events`, `section_events` | 1825 days (5 years) |
| pseudonymous | `client_rule_hits`, `evasion_events` | 730 days |
| pseudonymous | `ref_templates`, `ref_sections` | 1825 days |
| pseudonymous | `analytics_dead_letter` | 90 days |
| restricted | `ref_staff_names`, `login_sessions` | 180 days (decide with the legal basis note; see open questions) |

Enforcement is a scheduled job (a Kubernetes CronJob running the sink with `--retention`, daily):

1. For each table, delete the partitions older than the cutoff with an Iceberg **row-filter delete** on
   `occurred_at` (the Java `Table.newDelete().deleteFromRowFilter(...)` API). With a day partition this is a
   metadata operation for whole partitions. Verify at implementation time that it does not rewrite data files
   for aligned cutoffs.
2. Expire snapshots older than the same cutoff so the deleted files become unreferenced, using the Iceberg
   maintenance procedure (`expire snapshots`, see Context). Keep at least one snapshot per table.
3. Remove orphan files with the maintenance procedure, so an aborted write does not keep personal data alive.
4. Record the job's result (rows and files removed per table) in metrics and in the job log, as counts only.

This is the "retention periods stay mandatory and are enforced by sweepers" rule of the amendment.

Rejected: **S3 lifecycle rules** (they know nothing about snapshots, so they could delete files that a live
snapshot still needs, corrupting the table); **DuckDB-driven deletes** (not allowed on partitioned tables in the
1.4 line, and it would move retention into analyst tooling).

### 8. Idempotency and deduplication

Every row carries `event_id`. Dedupe happens in three layers:

1. **JetStream**: the publisher's `Nats-Msg-Id` dedupe window already drops a republished envelope with the same
   id inside the stream's duplicate window.
2. **Commit marker**: every Iceberg commit from the sink sets snapshot summary properties
   `analytics.consumer.sequence` (the highest JetStream stream sequence of the batch) and
   `analytics.batch.id`. On start, the sink reads the last snapshot summary and resumes after that sequence, so a
   redelivery after a crash skips what is already committed. Iceberg's snapshot summary is written by the
   commit itself, so this is atomic with the data (see https://iceberg.apache.org/docs/latest/java-api-quickstart/
   for the append API; confirm the summary setter name in the javadoc).
3. **Window check**: before writing a batch, the sink drops events whose `event_id` is already in the last N
   days of the same table (a bounded lookup on the partition of `occurred_at`). This catches a replay that starts
   before the stored sequence (for example after a consumer reset). The window is the duplicate window of the stream
   plus the batch age, not the whole table, so the check stays cheap.

Backfill rows use deterministic ids (`backfill:<table>:<primary key>`), so a re-run of backfill and a live event
for the same fact cannot both count. Where a live event and a backfill row describe the same fact, the live row
wins: the backfill skips facts whose `occurred_at` is after the sink's first live event.

Rejected: **a unique constraint** (Iceberg has none); **a database dedupe table in Postgres** (a second write path
on the system of record and a lock point).

### 9. Backfill from PostgreSQL

- A command of the sink (`analytics-sink backfill --table <name> --since <ts>`), run as a Kubernetes Job, not
  part of the normal process. It reads through a **read-only PostgreSQL role** that has `SELECT` on the source
  tables and no write right.
- Source tables: `punishments`, `punishment_profile`, `appeals`, `reports`, `sections`, `template` (exact names taken
  from the entities at implementation). Each row becomes one event row with `source = backfill`,
  `event_id = backfill:<table>:<pk>` and `occurred_at` taken from the row's own timestamp.
- Keyset pagination on the primary key, batches of the same size as live batches, each batch committed as an
  Iceberg snapshot with the same summary markers. The job is resumable: the last committed key is in the summary.
- The backfill reads the same hashed values the backend stores. It never reads clear-text IPs (the backend stores
  hashes for those where the amendment has not yet made clear text a requirement) and it writes into the
  pseudonymous zone only. Restricted-zone backfill (clear IPs, names) is a separate job and waits for the features
  that need it.
- Re-running backfill for a range is idempotent by decision 8.

### 10. Reference snapshots

A daily CronJob (`analytics-sink reference-export`) reads `templates`, `sections` and `staff display names` and
writes a full snapshot into `ref_templates`, `ref_sections` (pseudonymous) and `ref_staff_names` (restricted),
partitioned by `snapshot_date`. Queries that need the state at an event time join on the snapshot of that day
(the last snapshot at or before the event day). Staff display names come from the Entra-linked staff table, so
the restricted zone is the only zone that holds them.

Rejected: **slowly changing dimension type 2 in the sink** (more logic in the sink for little gain at this size);
**no snapshots, only current values** (history questions like "which template name did this punishment use" would
become wrong after a rename).

### 11. Metrics

Micrometer, exposed on the existing Prometheus endpoint and scraped through a ServiceMonitor like the backend:

- `blackhole.analytics.events.consumed` (counter, label `event_family`)
- `blackhole.analytics.events.written` (counter, label `table`, `source`)
- `blackhole.analytics.events.duplicate` (counter)
- `blackhole.analytics.events.dead_letter` (counter, label `reason`)
- `blackhole.analytics.batch.size` and `blackhole.analytics.commit.duration` (timers)
- `blackhole.analytics.lag` (gauge: the consumer's pending message count, read from JetStream)
- `blackhole.analytics.retention.removed` (counter, label `table`, `unit`: rows or files)

Alert thresholds (lag above one hour, commit failures, dead-letter rate) are added in the Flux repository, not
here. Metric names follow the backend's `blackhole.*` prefix. No label contains an owner hash or a player name.

### 12. Query and dashboards

- **DuckDB** with the `iceberg` extension, read-only: `iceberg_scan` on a table's metadata file, or an attached
  catalog if the JDBC catalog proves readable (open question 2). The documented attach path is the REST catalog
  (https://duckdb.org/docs/lts/core_extensions/iceberg/iceberg_rest_catalogs.html). Analysts run SQL from
  a workstation or from a scheduled job that writes a result file. DuckDB is read-only in this change.
- **Metabase** is the dashboard tool OLF already runs. Open question: Metabase has no built-in DuckDB driver that
  this change can rely on; the options are a DuckDB community driver, or Trino (decision deferred, see open
  questions). The chart layer does not change the table design.
- Analysts get the pseudonymous zone by default. The restricted zone is a separate catalog namespace with its own
  bucket credentials and a separate Metabase group.

### 13. Helm chart, config and secrets

- `deploy/charts/blackhole`: a new `analyticsSink` block in `values.yaml` and `values.schema.json`: image, enabled
  flag, batch settings, retention per table, zone names, catalog URI, bucket names (from the ObjectBucketClaims),
  PostgreSQL read-role reference, replicas (fixed at 1), resources, PodDisruptionBudget off (single replica, the
  Deployment has `Recreate` strategy so two consumers never run at once).
- Templates: `analytics-sink-deployment.yaml`, `analytics-sink-servicemonitor.yaml`,
  `analytics-sink-cronjob-retention.yaml`, `analytics-sink-cronjob-reference.yaml`, and a Job template for backfill
  (only rendered when a value is set). Backfill is never a default.
- Secrets (SOPS in Kubernetes-FLUX, names are the convention): `blackhole-analytics` (catalog token if the chosen
  catalog needs one), `blackhole-analytics-restricted` (restricted bucket credentials), `blackhole-analytics-db`
  (read-only PostgreSQL role, `JDBC_URL`, user, password). Bucket credentials come from the ObjectBucketClaim Secrets,
  mapped with `valueFrom` as for evidence.
- Config in `application.yml` with `${ENV_VAR:default}` and a comment per tunable, per the repository convention.
- The backend chart is unchanged, except for the NATS credentials that the sink mounts from the existing
  `blackhole-nats` Secret (the same credentials file, a second consumer identity).

### 14. Security and privacy

- The sink holds no user session and has no ingress. Its NATS identity is read-only on the stream (consumer
  creation and fetch, no publish).
- The restricted zone and its credentials are separate from the pseudonymous zone. A leak of the pseudonymous
  credentials does not expose clear IPs or names.
- Erasure on request (amendment) for the restricted zone: delete the rows by owner in the restricted tables, expire
  the snapshots, remove orphans. Pseudonymous zone: the owner hash is the subject, so erasure deletes rows by owner
  hash in the same way. The procedure is a runbook entry and a `--erase-owner` command of the sink. Not automated by
  the backend in this change.
- Backups: the Ceph object store's redundancy and the CNPG backup of the catalog database. Analytics data is
  reproducible from the backend database and the stream except for history older than the backend's own retention,
  so it is not a backup target of its own.

## Risks / Trade-offs

- **Two copies of PII** (database and lakehouse). Mitigation: the zone split, retention and erasure apply to both,
  and the pseudonymous zone holds hashes only.
- **Small files** from frequent commits. Mitigation: 60-second flush and the 5000-event batch, then the Iceberg
  maintenance procedure with compaction. Compaction in the Java library is limited; the expected tool is a Spark or
  Trino job, or a scheduled DuckDB-free maintenance step. Open question.
- **Stream retention shorter than an outage**. Mitigation: alerts on lag, and backfill is the recovery path.
- **DuckDB write limits**. Writes stay in Java; DuckDB is analytics read. If analysts later want to write, Trino or
  Spark becomes the writer.
- **Iceberg library and DuckDB version skew**. Mitigation: pin the Iceberg version the DuckDB release reads, check
  with a scratch table in CI (a read test with the DuckDB CLI), like the scratch-DB technique in the project memory.
- **Catalog availability**. If the catalog database is down the sink retries and the stream holds events; the backend
  is not affected.

## Migration plan

1. No database migration. No Flyway file is appended by this change.
2. Create the catalog database on the CNPG cluster, the two buckets and the secrets (Flux PR, follow-up).
3. Deploy the sink with live ingestion only, start from the stream's oldest message.
4. Run backfill per table from a fixed start date, verify counts against the source tables (a count query per
   table, run by the operator), then keep live ingestion.
5. Enable the retention and reference CronJobs.
6. Connect DuckDB and Metabase, publish the first dashboard set (out of scope for this change beyond the data).

Rollback: scale the sink to zero. Events stay in the stream until `maxAge`; the backend is unchanged. Tables can be
dropped without affecting enforcement.

## Open questions

1. Only if DuckDB needs an attached catalog: adopt a REST catalog (Apache Polaris, Lakekeeper or Nessie) as an
   upgrade. Not needed while the JDBC catalog is used.
2. Metabase connection to Iceberg: community DuckDB driver, or Trino (optional, adds a service). Decide with the
   dashboard owner.
3. Compaction tool: Spark job, Trino `optimize`, or a scheduled step outside the sink.
4. Retention values for the restricted zone (180 days proposed) and for pseudonymous tables; maintainer and the legal
   basis note (`amend-data-protection-principle`) must agree.
5. Whether a second sink replica is wanted; it needs catalog-side optimistic commit retries to be verified.
6. Iceberg format version 2 or 3 at implementation time, depending on the DuckDB release used in the analyst image.
7. Whether `.claude/rules/events.md` (still RabbitMQ) is corrected in the same PR as the sink or in its own docs PR.
8. Concrete primary keys and timestamp columns for the backfill sources (taken from the entities at implementation).
9. Shared convention with Corvus (ADR-CORV-0002, linked in decision 2). This change adopts the Postgres-catalog pattern
   and does not adopt Corvus's custom catalog or MinIO layout. Open: one object-store layout and bucket naming for both
   projects (Blackhole on Ceph RGW, Corvus on MinIO), one catalog database cluster, and whether Corvus adopts Iceberg,
   which its ADR currently defers. A joint layout decision belongs in its own note, not in this change.
10. Verify DuckDB can read a table whose catalog is the JDBC catalog. If not, analysts read through another engine
   (Trino or Spark later) or the REST catalog is adopted (open question 1).
