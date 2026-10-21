---
paths:
  - "backend/src/main/java/**/database/**/*.java"
  - "backend/src/main/resources/db/**/*.xml"
---

# Database layer (Hibernate/JPA + Liquibase)

Hibernate/JPA entities + Micronaut Data repositories on PostgreSQL. Liquibase
(`db/changelog/changelog.xml`, one PostgreSQL-only consolidated changelog) is the sole schema
owner. `jpa.default.properties.hibernate.hbm2ddl.auto` is `validate`: Hibernate checks the schema
at startup and never alters it. JSON columns are `jsonb`. Every changeSet uses native Liquibase
changeTypes (`<createTable>`, `<createIndex>`, `<addForeignKeyConstraint>`), not raw `<sql>`.
Native Liquibase XML has no changeType for arbitrary `CHECK` expressions, so JSON validity and
`smallint` ordinal-enum range checks are enforced in the application layer, not the database.

Add new changes as a new `<changeSet>` appended to the same file; never edit an already-applied
changeSet, always add a new one. This is enforced by a `PreToolUse` hook
(`.claude/hooks/protect-liquibase-changesets.sh`) that blocks edits removing/modifying an
existing `<changeSet>` block — but understand *why* before working around it.
