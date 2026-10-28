## Why

Staff answer questions about the network today by reading the panel one player at a time, or by
asking the backend database. Nobody can see the shape of the network: which templates are used
most, which sections escalate, whether appeals that succeed take long, how good a reporter's
reports are over months, or how many players share an alt cluster. The backend database is the
system of record and is tuned for single-player reads, not for those questions, and running
heavy reports on it would compete with enforcement.

The team needs a separate analytical store that holds the domain events over time, can be queried
with SQL, and can be charted in the dashboard tool OLF already operates. Questions that must be
answerable: punishments per template, section and staff over time; appeal outcomes and durations;
report quality per reporter; ELO distribution; client-rule hits; login and session volumes (once
the session data change lands); alt clusters; VPN share; repeat offenders; staff workload.

## What Changes

- **New analytics lakehouse.** Domain events published on NATS JetStream are written by a new
  `analytics-sink` service into Apache Iceberg tables stored as Parquet on the Ceph RGW S3 that the
  feather-core cluster already runs. Tables are partitioned by day. An Iceberg JDBC catalog on a
  dedicated PostgreSQL database holds the table metadata, the same Postgres-catalog pattern Corvus uses.
- **New `analytics-sink` Gradle module** and its own Deployment in the Helm chart. It has a durable
  JetStream consumer, batches events into Parquet, commits Iceberg snapshots, dedupes by event id,
  and exposes Prometheus metrics. It has no public HTTP API.
- **Reference snapshots.** A periodic export of the reference data (templates, sections, staff
  display names) into daily snapshot tables, so historic reports can show the template or section
  as it was.
- **Backfill.** A one-off, re-runnable backfill reads the history from PostgreSQL (punishments,
  profiles, appeals, reports, sections) through a read-only role and writes it into the same tables
  with deterministic event ids, so backfilled and live rows never double count.
- **Per-table retention.** Each table has a retention period. A scheduled job deletes expired
  partitions and expires old snapshots, so the physical files go away.
- **Two storage zones for personal data.** Pseudonymous tables hold owner hashes only. Anything that
  needs a clear-text IP address, raw player UUID or player name lives in a separate restricted
  namespace with its own bucket credentials, its own retention and its own dashboard access.
  This follows `amend-data-protection-principle`.
- **Query and dashboards.** DuckDB (with its Iceberg extension) reads the tables through the catalog
  catalog for analysts and the scheduled reports. Metabase charts the results. No dashboard is built
  into the staff panel.
- **Helm chart and secrets.** A new `analyticsSink` component in `deploy/charts/blackhole` with
  values for the catalog, the bucket and the retention, and a Secret reference for the S3 and catalog
  credentials. The Kubernetes-FLUX repository changes are a separate follow-up (see Impact).
- **No breaking change.** The backend gains no route, no payload and no migration. The generated
  client and the Velocity plugin are not affected.

## Capabilities

### New Capabilities

- `analytics-lakehouse`: the event sink, the Iceberg table set and schemas, partitioning, retention,
  the pseudonymous and restricted zones, idempotent ingestion, backfill, reference snapshots,
  metrics and the catalog and query contract for analysts.

### Modified Capabilities

None. The event payloads and the NATS subjects are read as they are; no existing spec changes its
behaviour. The sink is a consumer, like the Redis mirror.

## Impact

- **New module** `analytics-sink/` (Micronaut application, Java 25 toolchain, `settings.gradle.kts`
  and the root build updated). Depends on the Iceberg Java libraries and the NATS client.
- **Backend**: no code change in this change. It keeps publishing the events it already publishes.
  The event envelope (`DomainEvent`) is read by the sink; the sink keeps its own copy of the record
  because it must not depend on backend internals.
- **Database**: no Flyway migration. The sink reads PostgreSQL through a read-only role for backfill
  and reference snapshots. No table is added to the backend schema.
- **Storage**: one Ceph RGW bucket for the pseudonymous zone and one for the restricted zone, each
  through an ObjectBucketClaim in the app namespace; one PostgreSQL database for the Iceberg JDBC catalog
  (a separate database on the existing CNPG cluster, see design decision 2).
- **Helm chart**: `analyticsSink` values, a Deployment, a ServiceMonitor, a CronJob for retention and
  reference export, and an `existingSecret` reference.
- **Kubernetes-FLUX (follow-up, separate repository and PR)**: ObjectBucketClaims for both zones,
  the catalog Deployment or database, the SOPS Secret `blackhole-analytics`, the read-only PostgreSQL
  role, the HelmRelease value overlay, and a Metabase data source. Listed in `tasks.md` group 8.
- **Documentation**: a runbook entry for restore and for the restricted-zone erasure procedure.

## Out of scope

- Real-time dashboards in the staff panel. Analytics is a batch view with minutes of lag.
- Machine learning, scoring models and anomaly detection. The tables are the input for those later.
- Trino or Spark. Trino is an option once DuckDB is not enough; it is not part of this change.
- Login and session tables. They are fed by the session data change; this change only reserves the
  table name and the restricted zone for them.
- Publishing anything to third parties. Published pages and third-party events never carry clear
  IP addresses, UUIDs or names (amended principle, unchanged for publication).
- A change to the event bus itself (stream retention, subjects). The sink works within the existing
  `BLACKHOLE_EVENTS` stream and its limits.

Ships as: `feat(analytics): add the analytics lakehouse sink and its Iceberg tables`

Prerequisite: `amend-data-protection-principle` (clear-text IP, UUID and name storage with
retention). Also depends on `switch-event-bus-to-nats` being archived, since the sink reads its
stream.
