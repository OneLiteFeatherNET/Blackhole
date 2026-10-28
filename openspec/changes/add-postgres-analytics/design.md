## Context

Checked on `origin/main` (2026-10-28):

- Flyway head is `V27__kick.sql`. Migrations are append-only and plain SQL, with no placeholders
  (`.claude/rules/database.md`). Flyway runs with the application's database user.
- Timestamps are epoch milliseconds in `bigint` columns (`punishments.created_at`, `appeals.created_at`,
  `reports.created_at`, `case_event.occurred_at`, `elo_events.created_at`, `client_rule_hit.at`), not
  `timestamptz`. Views convert them.
- Owner keys are SHA-512 hashes (`utils/OwnerHash`). `punishments.source` and `punishments.recorded_by_oid`
  hold a UUID; the system ELO source is the constant `EloService.SYSTEM_ELO_SOURCE`, so "automatic" means
  `source = SYSTEM_ELO_SOURCE`.
- Tables that matter here: `punishments` (`section_key`, `template_identifier`, `permanent`, `kind`,
  `actor_oid`, `created_at`, `meta_data` jsonb), `punishment_templates` (`reason`), `reports` (`category`,
  `status`, `reporter_hash`, `created_at`, `updated_at`, `meta_data`), `appeals` (`status`, `appellant_hash`,
  `punishment_identifier`, `created_at`, `updated_at`), `elo_scores` (`owner`, `section_key`, `score`),
  `elo_events` (`owner`, `section_key`, `reason_code`, `delta`), `client_rule_hit` (30-day sweeper),
  `case_event` (append-only timeline with `type`, `actor_oid`, `occurred_at`).
- `meta_data` is jsonb and not inspected for this change: a view never selects it as a whole.
- Not on `main`: any login, session or presence table. `player_session` (V16) is the appeal-portal login
  session, not player presence.
- Metabase is already operated by OLF. Its own login is outside the application.

## Goals / Non-Goals

**Goals**: answer the staff questions (punishments per day, reason and section; reports and their outcome per
reporter; appeal outcomes and decision times; ELO distribution and the share of automatic punishments; client
rule hits; staff activity) with SQL in Metabase, without slowing enforcement, with no clear-text personal data
in what Metabase can read, and with no new service to run.

**Non-Goals**: real-time numbers; a warehouse; a second database; partitioning or a replica now; a statistics UI
in the panel; players-online-per-hour (no source yet).

## Decisions

### 1. The `analytics` schema inside the existing database

The views live in the `blackhole` database, in schema `analytics`, owned by the application role. Only the
migration and the refresh job create or refresh them.

Rejected: **a second database** (PostgreSQL cannot query across databases without a foreign data wrapper, which
adds a moving part to answer the same question); **a separate PostgreSQL cluster** (a second cluster to run and
back up for a few thousand rows a day).

### 2. Materialized views, not plain views

Seven materialized views, one per question family. Grouping and columns:

- `punishments_daily`: UTC day, kind, section, template, template reason, type, permanent, automatic (`source = SYSTEM_ELO_SOURCE`); count.
- `reports_daily`: UTC day, category, status; count and median seconds from creation to last change of resolved reports.
- `report_reporters`: reporter owner hash; total reports, upheld reports, last report day.
- `appeals_daily`: UTC day of decision, outcome, automatic flag of the appealed punishment; count and median hours from creation to decision.
- `elo_distribution`: section, score bucket of 50 points; owners.
- `client_rule_hits_daily`: UTC day, rule key, action, enforced, bypassed, server name; count.
- `staff_activity_daily`: UTC day, case event type; event count and distinct acting staff.

Each is a `GROUP BY` over the base tables, with explicit column lists and no `SELECT *`.

- Each view has a **unique index** over its grouping columns, so `REFRESH MATERIALIZED VIEW CONCURRENTLY` can
  run. Nullable grouping columns are coalesced to a sentinel (`''`, or the zero UUID) in the view, so the unique
  index has no NULL keys and duplicates cannot hide behind NULL.
- Each view is created `WITH DATA` in the migration (the tables are small). A view that is not populated (for
  example after a manual drop and recreate) is refreshed without `CONCURRENTLY` once, then with it.

Rejected: **plain views** (every Metabase question would recompute the aggregate over the base tables, so
dashboards with ten cards cost ten aggregations per load, and the reader would need access to the base tables
through the view's owner rights); **a Dragonfly cache for dashboard results** (a second cache to keep consistent
with the database, for a result that a materialized view already stores in the database, with the same age).

### 3. Refresh: a backend scheduler, one runner at a time

- `AnalyticsRefreshScheduler` is a `@Scheduled(fixedDelay = "${blackhole.analytics.refresh.interval:15m}")`
  method. `blackhole.analytics.refresh.enabled` (default `true`) switches it off. Both keys are documented with a
  comment in `application.yml` and map to `BLACKHOLE_ANALYTICS_REFRESH_INTERVAL` and
  `BLACKHOLE_ANALYTICS_REFRESH_ENABLED`.
- The service takes a `java.time.Clock`. For each run it first takes a session-level advisory lock with
  `pg_try_advisory_lock` on a fixed key. If the lock is held (another replica is refreshing), the run does nothing
  and logs at debug level. Otherwise it refreshes the views in a fixed order on the same connection and releases
  the lock in a `finally`.
- Each view is one statement on autocommit. A failing view is logged and counted, and the others still run. A
  failed view keeps its old data and its old refresh time.
- After each successful refresh it writes `analytics.refresh_state(view_name, refreshed_at)` (upsert). This table is
  the data age that Metabase shows.
- The job does not use `@Transactional`, which is unusable in this codebase (`.claude/rules/` and the project
  notes), and it does not need one: each statement is independent.

Rejected: **a PostgreSQL `pg_cron` job** (a database extension the CNPG cluster does not run for OLF, and the
schedule would live outside the application's config and tests); **refresh on every write** (a trigger per write
path would tie enforcement latency to aggregate work); **an external CronJob running SQL** (a second deployment
unit with its own credentials, for a job the backend already has the connection for).

Rejected alternative for the lock: **no lock** (two replicas would run the same refresh at once; `CONCURRENTLY`
keeps readers safe, but the work doubles for nothing).

### 4. Partitioning: none now, measured triggers later

No table is created by this change, so none is partitioned. Existing append-heavy tables are not partitioned here:

- `case_event` and `elo_events` would need the partition key in their primary key (a table rewrite and a
  migration that changes keys used by the application). That is not a cheap change, so it waits for its trigger.
- `client_rule_hit` and `player_session` already have sweepers that bound their size.

Estimate, to be confirmed after deployment: about 10,000 punishments a day plus their case events and ELO events
means tens of millions of rows after five years. A single table reaches 100 million rows only long after that.
Partitioning by month becomes the answer when a trigger in "Revisit" fires, not before.

### 5. Reporting role: a group role in the migration, the login role outside it

Flyway runs with the application user, which may not have `CREATEROLE`. The migration therefore does this and
nothing more about roles:

- `DO $$ ... IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'analytics_reader') THEN CREATE ROLE
  analytics_reader NOLOGIN; END IF; $$`. A group role with no login and no password. If the application role
  lacks the right to create it, the migration fails at deploy, which is the safe failure: no silent missing
  grants.
- `GRANT USAGE ON SCHEMA analytics TO analytics_reader`, `GRANT SELECT ON ALL TABLES IN SCHEMA analytics`, and
  `ALTER DEFAULT PRIVILEGES IN SCHEMA analytics GRANT SELECT ON TABLES TO analytics_reader`, so later views in the
  schema are readable without a new migration that touches privileges. Nothing is granted on `public` or on any
  base table.

The login role `metabase_analytics` is **not** in the migration. The operator creates it as a CNPG managed role
(`LOGIN`, `inRoles: [analytics_reader]`) with its password in a SOPS-encrypted Kubernetes Secret, in
Kubernetes-FLUX. Reasons: a password in a migration lands in Git history and in every database copy; Flyway
placeholders are not used in this repository; and rotating a password must not need a migration.

Rejected: **a `LOGIN` role with a password from a Flyway placeholder** (placeholders are not used here, and the
secret would be in the repository history); **the migration creating the login role with a generated password
and no way to read it back** (nobody can connect); **Metabase connecting as the application user** (it could
write, and it sees the base tables).

### 6. Privacy in the views and retention

- Each view selects explicit columns. Allowed: day, enum-like codes, counts, sums, medians, section and rule keys,
  server names (infrastructure names), and owner hashes in `report_reporters` only. Forbidden: IP addresses, raw
  UUIDs, names, `meta_data`, `ban_id_*`, `actor_oid`, `recorded_by_oid`, `appellant_hash` (appeal outcomes are
  counted, not listed per appellant). A test enforces this with a column allowlist per view.
- Staff activity is `count(*)` and `count(DISTINCT actor_oid)` per day and event type. The distinct count is a
  number; no OID leaves the view.
- Retention: the views are derived data. They have no retention of their own. They follow their base tables:
  when a sweeper deletes rows (for example `client_rule_hit` after 30 days), the next refresh drops them from the
  views. Erasure of a player (owner hash) is visible in the views after at most one refresh interval; the runbook
  says so, and an erasure may trigger a refresh.

### 7. Time and buckets

Days are UTC. Metabase shows the timezone of the data; local-time charts are a Metabase setting, not a view
change. Appeal decision time is `appeals.updated_at` of a resolved appeal, which is the last change of the appeal,
an approximation documented in the view comment. Exact decision times stay in `case_event` and are not used here.

### 8. Metrics and logs

Micrometer (`blackhole.analytics.refresh.duration` timer per view, `blackhole.analytics.refresh.failures` counter
per view, `blackhole.analytics.refresh.last_success` gauge in epoch seconds) on the backend's existing Prometheus
endpoint. Logs carry counts and durations, never owner hashes or identifiers.

### 9. Helm chart

No new workload. The backend chart gets one environment variable for the refresh interval (default in the chart
values, `15m`) and a documentation note for the Metabase Secret: the name `blackhole-analytics-metabase`, the keys
`host`, `port`, `database`, `username`, `password`, and the fact that the Secret is created in Kubernetes-FLUX,
not by the chart.

## Revisit: measurable triggers

Measured from Prometheus and the database's own statistics, reviewed once a quarter and after any slow dashboard.

| Trigger | Threshold | First response |
|---|---|---|
| A single base table grows large | more than 100 million rows (watch `case_event` and `elo_events` first) | Partition that table by month (a planned migration that changes its primary key), or archive by retention |
| Refresh takes too long | total refresh cycle over 60 s on two consecutive runs, or any view over 30 s | Narrow the view's window (for example a rolling 400 days), or split the heavy view |
| Primary load from refresh | refresh windows push primary CPU or write I/O more than 10 percentage points above the surrounding hours for a week | Move Metabase and refresh to a read replica (CNPG replica cluster), accept the replication lag in the data age |
| Freshness is not enough | staff ask for data younger than the interval | Lower the interval first; then consider incremental refresh with a change feed. Not before a trigger above |
| Outgrown PostgreSQL | a query class needs a columnar engine that Metabase cannot wait for | Revisit the lakehouse option in a new change, with the volume numbers from these triggers |

Each trigger has a dashboard in Metabase or an alert in Grafana, owned by the maintainer. The views and this table
are the first place to look when a dashboard gets slow.

## Rejected alternatives (summary)

- **A lakehouse** (Iceberg on Ceph S3, DuckDB, an analytics-sink service and its own Helm workload): overkill for
  thousands of rows a day, three extra moving parts for a volunteer team, and a second copy of personal data
  with its own retention and erasure machinery.
- **A read replica now**: solves a load problem that does not exist yet, at the cost of a second cluster and
  replication monitoring. It is the first answer to a trigger in "Revisit".
- **A Dragonfly cache for dashboards**: duplicates what the materialized view already stores, with a second
  invalidation path.
- **Plain views**: recompute on every Metabase query and widen the reader's rights.

## Risks / Trade-offs

- **Refresh load on the primary** grows with history. Mitigation: aggregates only, 15-minute interval, the
  triggers above, and the read-replica option.
- **Stale data after erasure or retention** for up to one interval. Mitigation: stated in the runbook; an erasure
  can trigger a refresh.
- **Views hide a privacy mistake** if a column is added later. Mitigation: the column allowlist test per view
  fails the build.
- **Enum decoding drifts** from the Java enums (`appeals.status`, `reports.status`, `reports.category`). Mitigation:
  the migration comments the mapping; a test asserts that every ordinal of each Java enum maps to a non-null name.
- **Two-replica race** on the refresh. Mitigation: the advisory lock.

## Migration

1. One Flyway file `V<next>__analytics_schema.sql`, next number at implementation time (`V28` expected). It
   creates schema `analytics`, the group role (guarded), grants, default privileges, `analytics.refresh_state`,
   and the seven views with their unique indexes, each `WITH DATA`.
2. No existing row is changed. The migration is additive and can be rolled back by dropping the schema.
3. Operator, after the migration: create the CNPG managed role `metabase_analytics` with its SOPS Secret, grant the
   group role. Then connect Metabase with the schema filter `analytics`.
4. Rollback: disable the scheduler (`BLACKHOLE_ANALYTICS_REFRESH_ENABLED=false`); the views stay readable with their
   last data. Dropping the schema is a manual step, never automatic.

## Open questions

1. Reporter quality: is "upheld" the right outcome for a reporter's quality, and which `reports.status` codes
   count as upheld? The maintainer decides; the view can change without a schema change.
2. Reports per server: is the server recorded on `reports`, in `meta_data`, or not at all? If it is in `meta_data`,
   a key is read in the view and documented; if it is not recorded, per-server counts wait for a report field.
3. Staff workload per person: needs a keyed pseudonym for staff OIDs (a secret-keyed hash, not a plain hash of a
   UUID, which could be reversed from a known staff list). A separate decision.
4. Players online per hour: depends on a login or presence source that is not on `main`.
5. Refresh interval default (15 minutes) and the 60-second trigger: confirm after the first week of measurements.
