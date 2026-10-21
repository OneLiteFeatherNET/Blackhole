---
paths:
  - "backend/src/main/java/**/database/**/*.java"
  - "backend/src/main/resources/db/**/*.sql"
---

# Database layer (Hibernate/JPA + Flyway)

Hibernate/JPA entities + Micronaut Data repositories on PostgreSQL. Flyway
(`db/migration/V<n>__<description>.sql`, starting at `V1__baseline.sql`) is the sole schema
owner. `jpa.default.properties.hibernate.hbm2ddl.auto` is `validate`: Hibernate checks the schema
at startup and never alters it. JSON columns are `jsonb`; ordinal enums are `smallint`. Plain
PostgreSQL SQL, no Flyway placeholders.
`CHECK` constraints for JSON validity and `smallint` ordinal-enum ranges are deliberately not
used; those are enforced in the application layer.

Add new changes as a new migration file with the next version number; never edit an applied
migration, always add a new one. This is enforced by a `PreToolUse` hook
(`.claude/hooks/protect-flyway-migrations.sh`) that blocks edits to migration files already
committed or present on `origin/main` - but understand *why* before working around it.
The `migration-checker` agent reviews new migrations.

Databases created by the former Liquibase changelog are not migrated: drop and recreate them.
