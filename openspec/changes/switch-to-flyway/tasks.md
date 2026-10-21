# Tasks

## 1. Dependencies and configuration

- [x] 1.1 Replace `mn.micronaut.liquibase` with `mn.micronaut.flyway` and add `org.flywaydb:flyway-database-postgresql`; verify the resolved flyway-core version and its Apache 2.0 licence, and that `./gradlew :backend:compileJava` succeeds
- [x] 1.2 Replace the `liquibase:` block in `application.yml` with `flyway.datasources.default` (`locations: classpath:db/migration`) and update the comment; verify the backend starts against a throwaway PostgreSQL

## 2. Schema

- [x] 2.1 Write `db/migration/V1__baseline.sql` and delete `db/changelog/`; verify `pg_dump --schema-only` of the Liquibase-built and Flyway-built databases is identical
- [x] 2.2 Verify Flyway applies V1 at startup, Hibernate validation passes, `GET /health` is 200 and `flyway_schema_history` holds the V1 row

## 3. Tests

- [x] 3.1 Verify the existing PostgreSQL Testcontainers integration tests pass on Flyway: `./gradlew :backend:test` and `-PexcludeTags=integration`, and `./gradlew clean build`

## 4. Tooling and documentation

- [x] 4.1 Replace the Liquibase hook with `protect-flyway-migrations.sh` (blocks edits to committed `V*__*.sql`, allows new files) and rewrite the checker agent as `migration-checker`; verify the hook with sample tool inputs
- [x] 4.2 Update `.claude/rules/database.md`, `CLAUDE.md`, `README.md`, `openspec/config.yaml` and the in-flight `staff-temp-bans-elo-escalation` change; verify `grep -ri liquibase --exclude-dir=.git --exclude-dir=build --exclude-dir=node_modules .` only finds this change, `switch-to-postgresql` and the database rule's drop-and-recreate note

## 5. Pull request

- [ ] 5.1 Open the pull request titled `refactor(database)!: replace liquibase with flyway` with a BREAKING CHANGE note (databases created by Liquibase must be dropped and recreated); verify CI is green
