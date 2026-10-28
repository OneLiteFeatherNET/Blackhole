## ADDED Requirements

### Requirement: Domain events are written to Iceberg tables

The system SHALL run an `analytics-sink` that consumes the domain events of the `BLACKHOLE_EVENTS` JetStream stream through its own durable consumer and writes them into Apache Iceberg tables stored as Parquet on object storage. Each event MUST be written to the table of its event family, with its envelope id, type, occurrence time and payload version.

#### Scenario: Punishment event lands in its table

- **WHEN** `punishment.created` is published
- **THEN** a row with that event id and event type appears in `punishment_events` after the batch is committed

#### Scenario: Unknown event type is not dropped

- **WHEN** an event with a type the sink does not map is consumed
- **THEN** the event is written to `analytics_dead_letter` with its raw JSON and the message is acknowledged

### Requirement: Events are acknowledged only after the table commit

The sink MUST acknowledge a JetStream message only after the Iceberg snapshot that contains it has been committed. A crash between commit and acknowledgement MUST cause redelivery and MUST NOT create a duplicate row.

#### Scenario: Crash before acknowledgement

- **WHEN** the sink commits a batch and stops before acknowledging it
- **THEN** after restart the batch is redelivered and the table contains each event id once

#### Scenario: Failed commit keeps the messages

- **WHEN** an Iceberg commit fails
- **THEN** no message of that batch is acknowledged and the batch is retried

### Requirement: Deduplication by event id

Every row MUST carry its envelope `event_id`. The sink MUST NOT write a second row with an event id that is already committed in the same table within the duplicate window, and MUST count such skips in a metric.

#### Scenario: Same event delivered twice

- **WHEN** an event with an id already in the table is consumed again within the window
- **THEN** no row is written and the duplicate counter increases

### Requirement: Batching bounds the commit rate

The sink MUST commit a batch when it holds the configured maximum number of events or when the configured maximum wait has elapsed, whichever comes first. One batch MUST produce one snapshot per table it writes.

#### Scenario: Burst flushes at the size limit

- **WHEN** more events arrive than the batch size within one wait period
- **THEN** a batch is committed at the size limit without waiting for the timer

#### Scenario: Quiet period flushes at the timer

- **WHEN** fewer events than the batch size arrive and the maximum wait elapses
- **THEN** the pending events are committed

### Requirement: Tables are partitioned by day and versioned by schema

Event tables MUST be partitioned by the day of `occurred_at`. Additive payload changes MUST be applied as Iceberg schema evolution without rewriting existing files. A breaking change MUST create a new table with a `_v2` suffix and MUST NOT change the meaning of an existing column.

#### Scenario: Query prunes by day

- **WHEN** an analyst queries punishments for one day
- **THEN** only the files of that day are read

#### Scenario: Additive payload field

- **WHEN** a payload gains an optional key that has a typed column
- **THEN** the column is added, old rows read it as null, and no existing data file is rewritten

### Requirement: Retention per table is enforced by deleting partitions and expiring snapshots

Each table MUST have a retention period in days. A scheduled retention job MUST delete rows older than the cutoff, expire snapshots older than the cutoff while keeping at least one snapshot, and remove orphan files, so that the expired personal data is no longer present in the object store.

#### Scenario: Expired partition removed

- **WHEN** the retention job runs and a day is older than the table's retention
- **THEN** no query returns rows of that day and the data files of that day are removed after snapshot expiry

#### Scenario: Retention is per table

- **WHEN** two tables have different retention periods
- **THEN** each is cut off at its own period

### Requirement: Personal data is held in a restricted zone

The system SHALL keep clear-text IP addresses, raw player UUIDs and player names only in the restricted zone, which has its own storage bucket, its own credentials and its own retention. The pseudonymous zone MUST contain owner hashes only and MUST NOT contain a clear-text IP address, UUID or player name.

#### Scenario: Staff names in the restricted zone

- **WHEN** the reference export writes staff display names
- **THEN** they are written only to `ref_staff_names` in the restricted zone

#### Scenario: Pseudonymous zone rejects a UUID

- **WHEN** an event payload value of a pseudonymous table is a canonical UUID or an IP address
- **THEN** the value is not written to that table and the row is written to `analytics_dead_letter` with the reason

### Requirement: Erasure on request removes a subject from the lakehouse

The system SHALL provide an erasure command that deletes all rows of one owner from every table of both zones, expires the snapshots that still reference them and removes orphan files.

#### Scenario: Erasure of one owner

- **WHEN** erasure is run for an owner hash
- **THEN** no table returns a row for that owner after the expiry and orphan removal have run

### Requirement: Backfill from PostgreSQL is resumable and never double counts

The system SHALL provide a backfill command that reads the history of the punishment, profile, appeal, report and section tables through a read-only database role and writes it with deterministic event ids of the form `backfill:<table>:<key>`. The backfill MUST be resumable from its last committed key and MUST NOT duplicate a fact that a live event already wrote.

#### Scenario: Backfill run twice

- **WHEN** the backfill of one table runs twice over the same range
- **THEN** the table contains each backfilled fact once

#### Scenario: Live event wins over backfill

- **WHEN** a fact exists both as a live event and as a database row
- **THEN** the live row is kept and the backfill row is skipped

### Requirement: Reference snapshots keep history of templates, sections and staff names

The system SHALL export templates and sections daily into pseudonymous snapshot tables and staff display names daily into the restricted zone, each partitioned by snapshot date, so that a historic event can be joined to the reference data of its day.

#### Scenario: Renamed template keeps history

- **WHEN** a template is renamed after a punishment was created with it
- **THEN** the snapshot of the earlier day still shows the old name

### Requirement: Sink health and data quality are observable

The sink SHALL expose Prometheus metrics for consumed, written, duplicate and dead-lettered events, batch size, commit duration, consumer lag and removed rows and files, with no owner hash, player name or IP address as a metric label.

#### Scenario: Dead letter counter

- **WHEN** an event is written to the dead-letter table
- **THEN** the dead-letter counter with its reason label increases

#### Scenario: No personal data in labels

- **WHEN** metrics are scraped after events for a player were written
- **THEN** no label value contains that player's owner hash, UUID, IP address or name

### Requirement: Analysts read the lakehouse read-only

The lakehouse SHALL allow reads for analysts through a read-only credential per zone, and the sink's credentials MUST NOT be used by analysts. Analyst tooling MUST NOT write to or delete from event tables.

#### Scenario: Analyst query

- **WHEN** an analyst runs a count of punishments per template for a month with the pseudonymous read credential
- **THEN** the query returns the count without access to the restricted zone

#### Scenario: Analyst write refused

- **WHEN** an analyst attempts a delete on a table with the read credential
- **THEN** the operation is refused
