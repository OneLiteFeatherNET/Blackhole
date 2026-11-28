## Conventions for every PR

- Branch from the up-to-date `origin/main`. The code PR contains no file under `openspec/`.
- Red, then green, then refactor: each group starts with the failing test named in it.
- F.I.R.S.T.: no `Thread.sleep`, no system time (inject `java.time.Clock`), no shared static state, fresh
  fixtures per test, one behaviour per test. Database tests run against a Testcontainers PostgreSQL started
  in-process, with the same major version as the CNPG cluster; each test builds its own data.
- Flyway: one new file, `V<next>__analytics_schema.sql`, `next` taken from `main` at implementation time
  (`V28` expected). No existing migration is edited.
- Commit per type. No Co-Authored-By trailer. Never `--force` or `--no-verify`.

## 1. Migration and views

- [ ] 1.1 Red: `backend/src/test/java/.../analytics/AnalyticsSchemaMigrationTest.java` runs Flyway on an empty
  Testcontainers database and asserts that schema `analytics`, the seven views and `analytics.refresh_state` exist.
- [ ] 1.2 Red: `.../analytics/AnalyticsViewIndexTest.java` asserts that each view has a unique index and that no
  indexed column is nullable.
- [ ] 1.3 Red: `.../analytics/AnalyticsViewRefreshTest.java`: for each view, seed a few base rows in the test,
  `REFRESH MATERIALIZED VIEW CONCURRENTLY` succeeds, and the view returns the expected counts. One test per view.
- [ ] 1.4 Red: `.../analytics/EnumMappingTest.java`: every ordinal of the Java enums behind `reports.status`,
  `reports.category` and `appeals.status` maps to a non-null name in the view's decoding.
- [ ] 1.5 Implement the migration: schema, the seven views (`WITH DATA`), unique indexes, `refresh_state` table.
- [ ] 1.6 Green: `./gradlew :backend:test --tests "*analytics*"`.

## 2. Refresh job

- [ ] 2.1 Red: `.../analytics/AnalyticsRefreshServiceTest.java` with a fixed `Clock` and a fake view refresher:
  views are refreshed in declared order; a failing view is counted and the others still run.
- [ ] 2.2 Red: `.../analytics/AnalyticsRefreshLockTest.java` (Testcontainers, two connections): when another session
  holds the advisory lock, a run refreshes nothing and returns immediately; the lock is released after a run,
  also after a failure.
- [ ] 2.3 Red: `.../analytics/AnalyticsRefreshStateTest.java`: a successful refresh writes the view name and the
  clock's time; a failed refresh does not change the recorded time.
- [ ] 2.4 Red: `.../analytics/AnalyticsRefreshSchedulerTest.java`: the default interval is 15 minutes and the
  configured value is used; `enabled=false` removes the schedule. Use property overrides, not sleeps.
- [ ] 2.5 Implement `AnalyticsRefreshService` (lock, ordered refresh, unpopulated-view fallback, `refresh_state`,
  metrics from design decision 8), `AnalyticsRefreshScheduler` and the two configuration keys in `application.yml`
  with comments. No `@Transactional`: each statement runs on autocommit.
- [ ] 2.6 Green: `./gradlew :backend:test --tests "*analytics*"`.

## 3. Reporting role and privacy

- [ ] 3.1 Red: `.../analytics/AnalyticsReaderRoleTest.java` (Testcontainers): a session with `analytics_reader` selects
  each view successfully.
- [ ] 3.2 Red: the same test class: the reader is refused on `punishments`, `appeals`, `reports`, `case_event`,
  `punishment_profiles` and `elo_scores`, and refused on `INSERT`, `UPDATE`, `DELETE` and `CREATE` in `analytics`.
- [ ] 3.3 Red: `.../analytics/ViewColumnAllowlistTest.java`: for each view, the column list equals its allowlist;
  no column is named after an IP, UUID, name, `meta_data`, `ban_id`, `appellant_hash`, `actor_oid` or
  `recorded_by_oid`; `report_reporters` is the only view with an owner hash.
- [ ] 3.4 Red: `.../analytics/StaffActivityNoIdentityTest.java`: the view returns a count of distinct actors and no
  actor column.
- [ ] 3.5 Green: `./gradlew :backend:test --tests "*analytics*"`.

## 4. Configuration, Helm and documentation

- [ ] 4.1 Red: a chart test (`helm template` in CI, `deploy/charts/blackhole/tests`) asserts that the backend
  Deployment renders `BLACKHOLE_ANALYTICS_REFRESH_INTERVAL` from `values.yaml` and that no Secret is rendered for
  analytics.
- [ ] 4.2 Add the refresh interval to `deploy/charts/blackhole/values.yaml` and `values.schema.json` (default `15m`)
  and pass it to the backend Deployment.
- [ ] 4.3 Document in the chart README and the runbook: the Metabase connection Secret
  `blackhole-analytics-metabase` (keys `host`, `port`, `database`, `username`, `password`), that the login role is a
  CNPG managed role created in Kubernetes-FLUX, the schema filter `analytics`, the data-age panel from
  `analytics.refresh_state`, and that erasure is visible after at most one refresh interval.
- [ ] 4.4 Green: `helm lint deploy/charts/blackhole` and the chart test in CI.

## 5. Follow-up in other repositories (not in the code PR)

- [ ] 5.1 Kubernetes-FLUX PR: CNPG managed role `metabase_analytics` (login, `inRoles: [analytics_reader]`) with its
  password from a SOPS Secret `blackhole-analytics-metabase`.
- [ ] 5.2 Metabase: a data source with the login role, schema filter `analytics`, and a question per view.
  Dashboards are not part of this change.
- [ ] 5.3 Refresh measurements after the first week: refresh duration per view and primary CPU in the surrounding
  hours, recorded against the triggers in design "Revisit". Adjust the interval only with those numbers.

## 6. Open the code pull request

- [ ] 6.1 Open the code pull request from `feat/analytics-postgres` with the title
  `feat(analytics): add postgres analytics views for metabase`, after groups 1 to 4 are green. Body filled from
  `.github/pull_request_template.md`. Do not merge.
