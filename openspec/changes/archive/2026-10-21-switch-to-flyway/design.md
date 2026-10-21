# Design

## Context

The schema is one PostgreSQL-only Liquibase changelog (22 changeSets, native changeTypes, no
seed data). Hibernate runs with `hbm2ddl.auto: validate`. Liquibase 5.x moved to the
Functional Source License, which conflicts with the project being open source.

## Goals / Non-Goals

**Goals:**
- Identical resulting schema, built by Flyway Community (Apache 2.0).
- Plain SQL migrations that are easy to read and review.

**Non-Goals:**
- Preserving the Liquibase history or migrating existing databases.
- Schema changes of any kind.

## Decisions

1. **Flyway via `micronaut-flyway` + `flyway-database-postgresql`.** Flyway 10+ split database
   support into modules, so the PostgreSQL module is a runtime dependency. Versions come from the
   Micronaut platform BOM. Alternative (Liquibase 4.x pin) rejected: dead-end, still leaves the
   licence question for later upgrades.
2. **One baseline `V1__baseline.sql`, no history replay.** No production data exists, so the
   22 changeSets collapse into one SQL file. It was derived from `pg_dump --schema-only` of the
   Liquibase result and cleaned up; constraint and index names are preserved so a diff of the
   two dumps is empty.
3. **Append-only by convention and hook.** New schema changes are `V2__...sql` and later; the
   `protect-flyway-migrations` hook blocks edits to any migration already tracked by git or
   present on `origin/main`.
4. **Hibernate keeps validating.** `hbm2ddl.auto: validate` stays, so entity/schema drift fails
   startup.
5. **No CHECK constraints (unchanged).** JSON validity and ordinal-enum ranges stay enforced in
   the application layer, as before.

## Flyway migrations this change adds

`V1__baseline.sql` only. No row migration: existing databases are dropped and recreated.

## Risks / Trade-offs

- [An operator points the new version at a Liquibase-created database] -> Flyway refuses to
  baseline a non-empty schema without `baselineOnMigrate`, and V1 would fail on existing tables;
  the break is documented as BREAKING and the fix is drop and recreate.
- [Baseline drifts from the old schema] -> verified by diffing `pg_dump --schema-only` of both
  (empty diff) and by Hibernate validation at startup.
