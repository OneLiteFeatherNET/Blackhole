---
paths:
  - "backend/src/main/java/**/database/**/*.java"
  - "backend/src/main/resources/db/**/*.xml"
---

# Database layer (Hibernate/JPA + Liquibase)

Hibernate/JPA entities + Micronaut Data repositories. Liquibase
(`db/changelog/db.changelog-master.xml`, a single consolidated XML changelog) is the sole
schema owner; `jpa.default.properties.hibernate.hbm2ddl.auto` is deliberately `none`, not
`validate` — Hibernate's own JSON-column type expectations disagree with MariaDB's physical
representation of a JSON column, an unrelated dialect mismatch that would make `validate` fail
spuriously. Every changeSet uses fully native Liquibase changeTypes (`<createTable>`,
`<createIndex>`, `<addForeignKeyConstraint>`) rather than raw `<sql>`, keeping the changelog
database-portable rather than MariaDB-specific. One consequence: native Liquibase XML has no
changeType for arbitrary `CHECK` expressions on any database, so the JSON validity checks and
the `tinyint` ordinal-enum range checks MariaDB previously enforced at the DB layer are not
recreated here — enforcement of those is an application/Hibernate-layer concern only, not a
DB-layer guarantee.

Add new changes as a new `<changeSet>` appended to the same file; never edit an already-applied
changeSet, always add a new one. This is enforced by a `PreToolUse` hook
(`.claude/hooks/protect-liquibase-changesets.sh`) that blocks edits removing/modifying an
existing `<changeSet>` block — but understand *why* before working around it.
