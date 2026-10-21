# Proposal

## Why

The network standardises on PostgreSQL as its relational database, but Blackhole still
defaults to MariaDB (driver, JDBC URL, docker-compose, docs). MariaDB also forces workarounds:
its JSON columns are `LONGTEXT` aliases, so Hibernate schema validation is switched off for the
whole schema. Switching now - before the section/ELO and admin-UI changes add new tables - means
those tables are designed against the database they will actually run on.

## What Changes

- **BREAKING** (operators): PostgreSQL becomes the only supported database. Default
  `JDBC_URL`/`JDBC_DRIVER`/credentials in `application.yml` point at PostgreSQL; the MariaDB
  and H2 drivers are removed from the backend classpath.
- `docker/docker-compose.yml` replaces the `mariadb` service with a `postgres` service.
- An appended Liquibase changeset converts the existing `json` columns to `jsonb` on
  PostgreSQL so they match Hibernate's PostgreSQL JSON mapping; existing changesets stay
  untouched (append-only).
- Hibernate schema validation (`hbm2ddl.auto: validate`) is re-enabled, since the MariaDB
  JSON mismatch that forced `none` no longer applies.
- Backend integration tests run against a real PostgreSQL via Testcontainers.
- Docs (README, CLAUDE.md, `.claude/rules/database.md`) describe PostgreSQL instead of MariaDB.

Out of scope: migrating data out of an existing MariaDB deployment (no production data needs
to move - confirmed), connection-pool tuning, read replicas, and anything Dragonfly/cache
related (see change `adopt-dragonfly-cache`).

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

_None - no externally observable behavior changes; this is a persistence-layer swap
(`skip_specs: true`)._

## Impact

- `backend/build.gradle.kts` (drivers, Testcontainers test deps)
- `backend/src/main/resources/application.yml` (datasource defaults, hbm2ddl)
- `backend/src/main/resources/db/changelog/db.changelog-master.xml` (new changesets only)
- `docker/docker-compose.yml`, `README.md`, `CLAUDE.md`, `.claude/rules/database.md`
- Operators must provision PostgreSQL and set `JDBC_*` env vars before upgrading.
