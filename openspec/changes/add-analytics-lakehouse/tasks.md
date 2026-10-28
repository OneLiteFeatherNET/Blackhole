## Conventions for every PR

- Branch from the up-to-date `origin/main`, never from the checkout. One PR per group where the group is a
  code change; infrastructure groups are separate repositories.
- Red, then green, then refactor: each group starts with the failing test named in it.
- F.I.R.S.T.: no `Thread.sleep`, no system time (inject `java.time.Clock`), no real network, no shared static
  state, fresh fixtures per test (`@TempDir` for Parquet files), one behaviour per test. Integration tests with
  MinIO-compatible or Iceberg local storage run in-process.
- Commit per type. No Co-Authored-By trailer. No Flyway migration is part of this change.
- Never use `--force` or `--no-verify`.

## 1. Module skeleton and spike

- [ ] 1.1 Spike (no production code): pin the Iceberg Java version (newest of 1.11.x or 1.12.x that the chosen
  Parquet and S3 libraries support), confirm the JDBC catalog and S3FileIO path-style settings against the Ceph RGW
  endpoint in a scratch environment, and confirm the snapshot summary setter and the row-filter delete API names in
  the javadoc. Record the results in the PR description of group 2.
- [ ] 1.2 Spike: DuckDB reads an Iceberg table written by the sink with the JDBC catalog (open question 10). Record
  the result and the DuckDB version. If it fails, stop and raise open question 1 (REST catalog) before group 6.
- [ ] 1.3 Red: `analytics-sink/src/test/java/net/onelitefeather/blackhole/analytics/AnalyticsSinkModuleTest.java`
  asserts the Micronaut application context starts with the NATS and Iceberg beans stubbed.
- [ ] 1.4 Add the `analytics-sink` Gradle module (Java 25 toolchain, Micronaut 4.10.2, Micrometer Prometheus, NATS
  client, Iceberg core, Iceberg parquet, Iceberg AWS, JDBC driver) to `settings.gradle.kts` and the root build.
- [ ] 1.5 Green: `./gradlew :analytics-sink:test`.

## 2. Event envelope, family mapping and schema

- [ ] 2.1 Red: `analytics-sink/src/test/java/.../mapping/EventFamilyTest.java`: every event type in the proposal table
  maps to exactly one family table; an unknown type maps to none.
- [ ] 2.2 Red: `.../mapping/PunishmentEventMapperTest.java`: a `punishment.created` payload (owner, punishment
  identifier, template identifier, permanent, reason, expiresAt) maps to typed columns with `extra` holding the
  remaining keys.
- [ ] 2.3 Red: one mapper test per family (appeal, report, elo, client-rule, evasion, section, profile): typed columns
  and `extra` for the remaining keys. Fixtures are built in the test.
- [ ] 2.4 Red: `.../mapping/PayloadVersionTest.java`: payload version 1 is mapped; an unknown version is refused.
- [ ] 2.5 Implement the envelope record (own copy, no backend dependency), `EventFamily`, the family mappers and the
  Iceberg schema per family table.
- [ ] 2.6 Red then green: `.../mapping/PseudonymityGuardTest.java`: a UUID or IP value in a pseudonymous table's
  payload is refused and sent to the dead letter with its reason.
- [ ] 2.7 Green: `./gradlew :analytics-sink:test`.

## 3. Ingestion: consumer, batching, commit and deduplication

- [ ] 3.1 Red: `.../ingest/BatchPolicyTest.java` with a fixed `Clock`: size limit flushes, timer flushes, no flush
  before either.
- [ ] 3.2 Red: `.../ingest/CommitMarkerTest.java`: the snapshot summary carries the highest stream sequence and the
  batch id; a restart reads it and skips already committed sequences.
- [ ] 3.3 Red: `.../ingest/AckAfterCommitTest.java`: a failed commit acknowledges nothing; a successful commit
  acknowledges the whole batch once.
- [ ] 3.4 Red: `.../ingest/DeduplicationTest.java`: the same event id twice in one batch and across batches writes one
  row and increments the duplicate counter.
- [ ] 3.5 Implement the durable consumer (`deliverPolicy` All on first creation, explicit ack, filter `blackhole.events.>`),
  the batch policy, the Parquet writer through Iceberg (`S3FileIO`, path-style), the commit marker and the dedupe window.
- [ ] 3.6 Green: `./gradlew :analytics-sink:test`. Integration test with a local filesystem Iceberg table in `@TempDir`
  (no network) covering crash-between-commit-and-ack and redelivery.

## 4. Retention and erasure

- [ ] 4.1 Red: `.../retention/RetentionCutoffTest.java`: per-table cutoff computed from an injected `Clock`.
- [ ] 4.2 Red: `.../retention/RetentionJobTest.java` on a `@TempDir` table: rows older than the cutoff are deleted,
  snapshots older than the cutoff are expired, at least one snapshot is kept, orphans are removed.
- [ ] 4.3 Red: `.../erasure/EraseOwnerTest.java`: all rows of one owner are removed from every table of both zones.
- [ ] 4.4 Implement the retention job, the erasure command and the per-table configuration in
  `application.yml` (`blackhole.analytics.retention.<table>`, each tunable with an env variable and a comment).
- [ ] 4.5 Green: `./gradlew :analytics-sink:test`.

## 5. Backfill and reference snapshots

- [ ] 5.1 Red: `.../backfill/BackfillKeysetTest.java`: keyset pagination over fixture rows resumes after the last
  committed key from the snapshot summary.
- [ ] 5.2 Red: `.../backfill/BackfillDedupTest.java`: a second run writes nothing new; a fact with a live event is
  skipped.
- [ ] 5.3 Red: `.../reference/ReferenceSnapshotTest.java`: templates and sections are written partitioned by
  `snapshot_date`; staff names only to the restricted zone.
- [ ] 5.4 Implement the backfill command (read-only JDBC role, deterministic ids) and the reference export command.
- [ ] 5.5 Green: `./gradlew :analytics-sink:test`.

## 6. Metrics, health and read path

- [ ] 6.1 Red: `.../metrics/MetricLabelsTest.java`: no metric label contains an owner hash, UUID, IP or name for a
  set of sample events (captured via the registry, not logs).
- [ ] 6.2 Red: `.../metrics/DeadLetterCounterTest.java`: a dead letter increments the counter with its reason label.
- [ ] 6.3 Implement the metrics listed in design decision 11 and the `/health` readiness that reports the commit
  backlog as a warning, not as unready.
- [ ] 6.4 Green: `./gradlew :analytics-sink:test`.
- [ ] 6.5 Read path check (documentation and script, no production code): a DuckDB query script that counts
  punishments per template for a month, runnable against the pseudonymous read credential. Committed under
  `analytics-sink/queries/` with the result of task 1.2.

## 7. Helm chart and CI

- [ ] 7.1 Red: a chart test (helm template render in CI, `deploy/charts/blackhole/tests`) asserting that the
  `analyticsSink` Deployment renders with `replicas: 1` and `Recreate` strategy, and that the backfill Job renders only
  when its value is set.
- [ ] 7.2 Add `analyticsSink` to `deploy/charts/blackhole/values.yaml` and `values.schema.json`: image, batch and
  retention values, zone names, catalog URI, bucket names (from the ObjectBucketClaim Secrets via `valueFrom`), the
  read-only PostgreSQL role reference, resources, the ServiceMonitor flag.
- [ ] 7.3 Add templates: `analytics-sink-deployment.yaml`, `analytics-sink-servicemonitor.yaml`,
  `analytics-sink-cronjob-retention.yaml`, `analytics-sink-cronjob-reference.yaml`, and the backfill Job template
  rendered only when enabled.
- [ ] 7.4 Green: `helm lint deploy/charts/blackhole` and the chart tests in CI.
- [ ] 7.5 Add the Dockerfile and image build for `analytics-sink` in the existing CI pipeline (same registry as the
  backend), without publishing a new image tag in this PR.

## 8. Follow-up in other repositories (not in this PR)

- [ ] 8.1 Kubernetes-FLUX PR: two ObjectBucketClaims (`blackhole-analytics`, `blackhole-analytics-restricted`) in the
  app namespace on `ceph-bucket-fr01`; the catalog database role on the CNPG cluster; SOPS Secrets
  `blackhole-analytics`, `blackhole-analytics-restricted`, `blackhole-analytics-db`; the HelmRelease value overlay;
  alert rules for lag, commit failures and dead-letter rate.
- [ ] 8.2 Metabase: a data source for the pseudonymous read credential (and a separate group for the restricted zone)
  once open question 2 is decided.
- [ ] 8.3 Documentation: runbook entries for restore, backfill, retention and erasure, in Outline.
- [ ] 8.4 Corrections to `.claude/rules/events.md` (it still describes RabbitMQ; `main` runs NATS JetStream). Separate
  docs PR, not part of the code PR.

## 9. Open the code pull request

- [ ] 9.1 Open the code pull request from `feat/analytics-lakehouse` with the title
  `feat(analytics): add the analytics lakehouse sink and its Iceberg tables`, after groups 1 to 7 are green. Body
  filled from `.github/pull_request_template.md`. Do not merge.
