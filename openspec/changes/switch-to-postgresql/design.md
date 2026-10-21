# Design

## Context

The schema is owned entirely by one Liquibase changelog that already uses only native
changeTypes (no raw `<sql>`), so it is portable in principle. Column types in use: `uuid`,
`json`, `tinyint` (ordinal enums), `bigint`, `int`, `varchar`, `boolean`. Entities map JSON
columns with `@JdbcTypeCode(SqlTypes.JSON)` plus a `Map<String,Object>` attribute converter.
`hbm2ddl.auto` is `none` because of a MariaDB-only JSON type mismatch. The `postgresql` JDBC
driver is already on the classpath (unused). No Docker is available in every dev sandbox, but
GitHub Actions runners have it.

## Goals / Non-Goals

**Goals:**
- A fresh PostgreSQL database is fully created by the existing changelog plus appended
  changesets, and Hibernate validates against it at startup.
- One supported database, one set of defaults, one docker-compose stack.

**Non-Goals:**
- Data migration from MariaDB, dual-database support, PostgreSQL-specific features beyond
  `jsonb` (partitioning, RLS, etc.).

## Decisions

1. **PostgreSQL only, no dual support.** Keeping MariaDB would mean testing every future
   changeset twice. Alternative (env-switchable) rejected per user decision.
2. **`jsonb` instead of `json`.** Hibernate's PostgreSQL dialect maps `SqlTypes.JSON` to
   `jsonb`; leaving the columns as `json` would make `validate` fail and relies on implicit
   casts on every write. Appended changeset `postgresql-json-to-jsonb` uses
   `<modifyDataType newDataType="jsonb">` per JSON column, guarded with `dbms="postgresql"`.
   On a fresh database it runs right after table creation, so no row conversion is involved;
   PostgreSQL's built-in `json -> jsonb` assignment cast covers any rows that do exist.
3. **`tinyint` stays as declared.** Liquibase already emits `SMALLINT` for `tinyint` on
   PostgreSQL, which is what Hibernate expects for ordinal enums - no changeset needed.
   Verified during implementation by `validate` succeeding.
4. **Re-enable `hbm2ddl.auto: validate`.** The original reason for `none` was MariaDB-specific.
   If validation surfaces another genuine mismatch, fix it with an appended changeset rather
   than switching validation off again.
5. **Testcontainers for integration tests** (`org.testcontainers:postgresql` +
   `micronaut-test-junit5`). The constitution forbids an in-memory substitute; Testcontainers
   gives the real engine in CI. Tests that need it are tagged so `./gradlew :backend:test` can
   still run unit tests on a machine without Docker (`-PexcludeTags=integration`).
6. **docker-compose image `postgres:18-alpine`**, port `5432`, database/user `blackhole`.

## Risks / Trade-offs

- [Unknown column type mismatch only visible at `validate`] -> run the backend against a real
  PostgreSQL during implementation (local binaries or compose) before committing; fix via new
  changesets.
- [Dropping H2 breaks someone's local workflow] -> H2 is unused by configuration today; the
  compose stack is the documented local path.
- [Operators upgrading an existing MariaDB deployment lose data] -> release notes mark this
  BREAKING and state that no migration tool is provided.

## Migration Plan

1. Deploy PostgreSQL, set `JDBC_URL`, `JDBC_USER`, `JDBC_PASSWORD`.
2. Start the backend; Liquibase builds the full schema on the empty database.
3. Rollback: redeploy the previous release against the old MariaDB (untouched by this change).
