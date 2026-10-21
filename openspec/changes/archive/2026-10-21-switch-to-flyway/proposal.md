# Proposal

## Why

Liquibase Community 5.x (the backend pulls `liquibase-core` 5.0.4 transitively) is released under
the Functional Source License: source-available, not OSI open source. Flyway Community is
Apache 2.0. Blackhole is an open-source project, so its schema-migration tool should be too.
There is no production data (confirmed by the owner), so switching tools now costs nothing in
migration effort.

## What Changes

Ships as `refactor(database)!`.

- **BREAKING** (operators): the schema is now created by Flyway. A database created by the
  Liquibase changelog is not migrated; drop and recreate it (no production data exists).
- `micronaut-liquibase` is replaced by `micronaut-flyway` plus `flyway-database-postgresql`.
- `db/changelog/changelog.xml` is replaced by one hand-maintainable
  `db/migration/V1__baseline.sql` that produces the identical PostgreSQL schema.
- `application.yml`: the `liquibase:` block becomes `flyway.datasources.default`;
  `hbm2ddl.auto: validate` stays.
- Repo tooling follows: the `protect-liquibase-changesets` hook becomes
  `protect-flyway-migrations` (blocks edits to committed `V*__*.sql`), the `liquibase-checker`
  agent becomes `migration-checker`, and `.claude/rules/database.md`, `CLAUDE.md`, `README.md`
  and `openspec/config.yaml` describe Flyway.
- The in-flight change `staff-temp-bans-elo-escalation` is reworded from Liquibase changesets
  to Flyway migrations.

Out of scope: migrating data or history out of a Liquibase-managed database, schema changes of
any kind (the schema must stay byte-for-byte equivalent), Flyway Teams/Enterprise features,
Flyway callbacks or repeatable migrations.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

_None - no externally observable behavior changes; this swaps the schema-migration tool
(`skip_specs: true`)._

## Impact

- `backend/build.gradle.kts` (dependencies)
- `backend/src/main/resources/application.yml`, `db/migration/V1__baseline.sql`,
  removal of `db/changelog/`
- `.claude/hooks`, `.claude/agents`, `.claude/rules/database.md`, `.claude/settings.json`,
  `CLAUDE.md`, `README.md`, `openspec/config.yaml`
- Operators must drop and recreate their database before upgrading.
