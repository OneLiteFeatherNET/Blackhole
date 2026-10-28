## ADDED Requirements

### Requirement: Staff questions are answered by seven materialized views

The system SHALL provide seven materialized views in the PostgreSQL schema `analytics`, each an aggregate over operational tables, one per staff question family: `punishments_daily`, `reports_daily`, `report_reporters`, `appeals_daily`, `elo_distribution`, `client_rule_hits_daily` and `staff_activity_daily`. Their grouping and columns are set in design decision 2.

#### Scenario: Punishments per reason and section

- **WHEN** staff query `punishments_daily` for one month grouped by section and template reason
- **THEN** the query returns counts without reading any base table

#### Scenario: Automatic share is available

- **WHEN** a punishment was applied by the ELO engine
- **THEN** its row in `punishments_daily` is marked automatic, so the automatic share is a query over one view

### Requirement: Each view is refreshed concurrently and has a unique index

Every view SHALL have a unique index over its grouping columns, with no NULL in any indexed column, so that `REFRESH MATERIALIZED VIEW CONCURRENTLY` succeeds and readers are not blocked during the refresh.

#### Scenario: Concurrent refresh succeeds

- **WHEN** the refresh job refreshes a view that holds data
- **THEN** the refresh completes without an exclusive lock that blocks readers, and the view shows the new data

#### Scenario: Unpopulated view

- **WHEN** a view is not populated
- **THEN** it is refreshed once without `CONCURRENTLY`, and later refreshes use `CONCURRENTLY`

### Requirement: A scheduled job refreshes every view at a configurable interval

The backend SHALL refresh all seven views on a schedule of `blackhole.analytics.refresh.interval`, default 15 minutes, and SHALL run the schedule only when `blackhole.analytics.refresh.enabled` is true. A failing view MUST NOT prevent the other views from being refreshed, and MUST keep its previous data.

#### Scenario: Interval from configuration

- **WHEN** the interval is not configured
- **THEN** the refresh runs every 15 minutes

#### Scenario: One failing view

- **WHEN** refreshing one view fails
- **THEN** the other views are still refreshed, the failure is counted in a metric, and the failed view keeps its previous data

### Requirement: Only one backend replica refreshes at a time

Before refreshing, a run SHALL acquire a PostgreSQL advisory lock on a fixed key. If the lock is held elsewhere, the run SHALL do nothing and SHALL NOT wait.

#### Scenario: Second replica skips

- **WHEN** another session holds the refresh lock and a run starts
- **THEN** the run refreshes nothing and returns, and the views are unchanged

### Requirement: Data age is recorded per view

After a successful refresh of a view, the system SHALL record the view name and the refresh time in `analytics.refresh_state`, using the injected clock, so that reports can show how old their data is.

#### Scenario: Refresh time is recorded

- **WHEN** `staff_activity_daily` is refreshed successfully
- **THEN** `analytics.refresh_state` holds the view name with the time of that refresh

### Requirement: A read-only reporting role can read the analytics schema only

The migration SHALL create a group role `analytics_reader` that has no login and no password, `USAGE` on schema `analytics`, and `SELECT` on the tables and views of that schema (including future ones by default privileges). It SHALL NOT grant any privilege on a base table or on the `public` schema. The login role used by Metabase is created outside the migration and is a member of `analytics_reader`.

#### Scenario: Reader selects a view

- **WHEN** a session with the reader role selects from `analytics.punishments_daily`
- **THEN** the query succeeds

#### Scenario: Reader selects a base table

- **WHEN** a session with the reader role selects from `punishments`, `appeals`, `reports`, `case_event` or `punishment_profiles`
- **THEN** the database refuses with a permission error

#### Scenario: Reader writes

- **WHEN** a session with the reader role inserts, updates, deletes or creates an object in `analytics`
- **THEN** the database refuses with a permission error

### Requirement: Views expose no clear-text personal data

No view SHALL expose a clear-text IP address, a raw player UUID, a player name, a staff OID, `meta_data`, a Ban ID value or `appellant_hash`. The only owner value a view MAY expose is the owner hash of `report_reporters`. Each view's column list SHALL be checked against an allowlist.

#### Scenario: Column allowlist

- **WHEN** a view gains a column that is not on its allowlist
- **THEN** the automated check fails until the allowlist is changed on purpose

#### Scenario: Staff activity has no identities

- **WHEN** staff activity is queried
- **THEN** the result holds the number of distinct acting staff per day and type, and no staff identifier

### Requirement: Enum codes in views match the application's enums

Views that decode ordinal enum columns (`reports.status`, `reports.category`, `appeals.status`) SHALL map every ordinal of the matching Java enum to a name, and an automated test SHALL fail when an ordinal has no mapping.

#### Scenario: New enum value without a mapping

- **WHEN** the Java enum gains a value that the view does not map
- **THEN** the mapping test fails before the change is merged
