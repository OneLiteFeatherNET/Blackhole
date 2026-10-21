# Tasks

## 1. Dependencies and configuration

- [x] 1.1 Remove `mn.mariadb.java.client` and `mn.h2` from `backend/build.gradle.kts`, keep `mn.postgresql`; verify `./gradlew :backend:compileJava` succeeds
- [x] 1.2 Point `datasources.default` defaults in `application.yml` at `jdbc:postgresql://127.0.0.1:5432/blackhole` / `org.postgresql.Driver` / user+password `blackhole`, with the explanatory comment updated; verify by starting the backend against a local PostgreSQL
- [x] 1.3 Replace the `mariadb` service in `docker/docker-compose.yml` with `postgres:18-alpine` (port 5432, volume, env); verify `docker compose config` parses

## 2. Schema

- [x] 2.1 Replace `db.changelog-master.xml` with one PostgreSQL-only `db/changelog/changelog.xml` (native changeTypes, `jsonb` JSON columns, no `postgresql-json-to-jsonb` changeset); verify Liquibase applies it to an empty PostgreSQL and `pg_dump --schema-only` equals the final schema of the old changelog
- [x] 2.2 Switch `jpa.default.properties.hibernate.hbm2ddl.auto` to `validate`, update its comment, and verify the backend starts with validation passing (fix any mismatch with further appended changesets)

## 3. Tests

- [x] 3.1 Add Testcontainers PostgreSQL + `micronaut-test-junit5` test dependencies and an `integration`-tagged base test; make the tag excludable via `-PexcludeTags=integration`; verify `./gradlew :backend:test -PexcludeTags=integration` passes without Docker
- [x] 3.2 Add an integration test that boots the application context against PostgreSQL and round-trips a punishment template with JSON metadata; verify it passes in CI

## 4. Documentation

- [x] 4.1 Update README (tech stack, local development, env vars), CLAUDE.md and `.claude/rules/database.md` from MariaDB to PostgreSQL; verify no remaining `mariadb` mention via `grep -ri mariadb --exclude-dir=.git .` except the historical changelog comment
