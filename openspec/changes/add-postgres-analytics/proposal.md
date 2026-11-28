## Why

Staff want to see the shape of the network: punishments per day, reason and section, how many of them
the ELO engine applied on its own, how reports and appeals turn out, and how many staff are active.
Today they answer that by reading the panel one player at a time or by asking someone with database
access.

The volume is small. One Minecraft network produces thousands to tens of thousands of rows per day.
That is well inside what PostgreSQL answers with plain aggregates, so a separate analytics platform
would add services to run without a matching gain. The earlier proposal, `add-analytics-lakehouse`
(Iceberg, Ceph S3, DuckDB, an analytics-sink service), is replaced by this one. It was never
implemented and its code pull requests were closed.

## What Changes

- **New `analytics` schema inside the existing PostgreSQL database.** It holds seven materialized
  views over the operational tables (punishments, reports, appeals, ELO scores and events, client-rule
  hits, case events). Each view is a small aggregate: one row per day and group, or one row per owner
  hash for reporter quality.
- **Scheduled refresh in the backend.** A `@Scheduled` job refreshes every view with
  `REFRESH MATERIALIZED VIEW CONCURRENTLY` every 15 minutes, configurable through
  `blackhole.analytics.refresh.interval`. Each view has a unique index, so readers are never blocked.
  Only one backend replica refreshes at a time (advisory lock). The job records when each view was
  last refreshed in a small table, so dashboards can show their data age.
- **Read-only reporting role.** The migration creates a `NOLOGIN` group role `analytics_reader` with
  `USAGE` and `SELECT` on the `analytics` schema only. It has no rights on any base table. The login
  role for Metabase is created by the operator, with its password in a Kubernetes Secret, never in a
  migration.
- **Privacy in the views.** Views select explicit columns. They contain owner hashes at most (only in
  reporter quality), never clear-text IP addresses, raw player UUIDs, names, `meta_data` or staff OIDs.
  Staff activity is reported as counts, not per person.
- **Metabase** (already operated by OLF) connects as the read-only role and filters its schema to
  `analytics`. Dashboards are built in Metabase and are not part of this change.
- **Helm and docs.** The chart passes the refresh interval through as an environment variable. The
  runbook documents the Metabase connection Secret and the manual step that creates the login role.
- **No breaking change.** No HTTP route, payload, client or Velocity behaviour changes. The base
  tables are not altered; the only schema change is the new `analytics` schema and its grants.

## Capabilities

### New Capabilities

- `analytics-postgres`: the `analytics` schema and its seven views, the scheduled refresh and its
  single-runner lock, the data-age record, the read-only reporting role and the privacy rules for
  what a view may contain.

### Modified Capabilities

None. The base tables, their sweepers and their specs are unchanged. The views only read them.

## Impact

- **Migration**: one new Flyway file `V<next>__analytics_schema.sql` (`V28` if no other change takes
  that number first; assigned at implementation time). It creates the schema, the views with their
  unique indexes, the refresh-state table and the group role, and grants. It alters no existing table
  and migrates no data.
- **Backend**: a new feature package `analytics/` with a refresh service and a scheduler, plus two
  configuration keys in `application.yml` with comments. No controller, no DTO, no OpenAPI change.
- **Database load**: each refresh runs aggregate queries over the base tables. Expected cost is small
  at this volume, but it is measured after deployment (see design, "Revisit").
- **Helm chart** (`deploy/charts/blackhole`): one environment variable for the refresh interval, and a
  note in the chart documentation about the Metabase Secret.
- **Kubernetes-FLUX (follow-up, separate repository)**: a CNPG managed role `metabase_analytics`
  (login, member of `analytics_reader`) whose password comes from a SOPS Secret, and the Metabase data
  source. Listed in `tasks.md`, group 5.
- **Dependencies on other changes**: none that must land first. The session and "players online per
  hour" questions depend on a login or session source that is not on `main` yet (see Out of scope).

## Out of scope

- **Players online per hour.** No login, session or presence table exists on `main`. The
  `add-player-intelligence` change named in earlier notes is not in the repository. This question is
  answered when such a source exists; it is not reserved here.
- **Staff activity per person.** Counting staff needs no identity. Per-person workload would need a
  keyed pseudonym for staff OIDs, which is a decision of its own.
- **A lakehouse, DuckDB, Iceberg, Ceph S3, Trino, an analytics-sink service or a NATS sink.** Replaced
  by this change; see design, "Rejected alternatives".
- **Partitioning of existing tables** (`case_event`, `elo_events`). Not done now; the measurable
  triggers are in the design, "Revisit".
- **A read replica.** Later, with a trigger; see design.
- **A statistics page in the staff panel.** Metabase is the UI.
- **Dashboards and charts.** Built in Metabase after the data exists.
- **Retention of the views.** They are derived data; they follow the retention of their base tables
  (see design, decision 6).
- **Publishing anything.** Published pages never carry these views' data.

Ships as: `feat(analytics): add postgres analytics views for metabase`

Prerequisite: none. The change may land before or after `add-kick` (which takes `V27`). Its migration
number is taken at implementation time.
