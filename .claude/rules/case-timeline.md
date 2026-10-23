---
paths:
  - "backend/src/main/java/**/casetimeline/**/*.java"
  - "backend/src/main/resources/db/**/V12__punishment_case.sql"
---

# Punishment case timeline

`casetimeline/` keeps one case per punishment and an append-only `case_event` log of what happened to
it. Detail of the decisions: `openspec/changes/add-punishment-case-timeline/design.md` (the package
is not called `case`, which is a Java keyword; the case table is `punishment_case`, `case` being an
SQL keyword).

- **Append-only.** `CaseEventRepository` offers `append`, `redact` and `deleteForPunishment` and
  nothing else; `CaseEventRepositorySurfaceTest` fails when a write path is added. Reads go through
  `CaseEventReader`. A row changes only by a note redaction or by the cascade with its punishment.
- **Record after the primary write, never fail it.** Flows call `CaseTimeline` (applied, revoked,
  lifted, expired, appeal filed/decided, evidence attached/lifted/revoked) after their own write. A
  failure is logged and counted in `blackhole.case.record.failures`; it never reaches the caller. No
  `@Transactional`. Every lifecycle recording has a deterministic dedupe key (`applied:<id>`,
  `revoked:<id>`, `lifted:<id>`, `expired:<id>`, `appeal.filed:<id>`, `appeal.decided:<id>`,
  `evidence.attached|lifted|revoked:<id>`), so a retry, the reconciler or a replay inserts nothing twice.
- **Case-own changes are one statement.** A change of case state that is itself history (`JdbcCaseWriter`,
  today only the reopen of a `CLOSED` case by a filed appeal) is a single data-modifying CTE, as
  `JdbcEloScoreWriter` is for scores.
- **`CaseReconciler`** (`blackhole.case.reconcile.interval`, `0` switches it off; `look-back`) finds source
  rows in the window without their dedupe key and appends them with origin `RECONSTRUCTED`, dated by the
  source row and attributed to the row's actor. `CaseDrafts` builds the events for both the live flows
  and the reconciler, so a repaired event looks like the lost one. `V12` backfills in SQL with the same
  payload shapes; `CaseMigrationIntegrationTest` reads them back as typed payloads to keep both in step.
- **Trust.** `CaseActor.ofRow` is the one rule for `VERIFIED` / `UNVERIFIED` / `SYSTEM` (the SQL backfill
  mirrors it). Legacy actors are never `VERIFIED`. No actor name is stored: it is joined from
  `staff_actor` when read, so anonymize erases it everywhere.
- **Payloads** are a sealed `CaseEventPayload`; free text only in note types (not in this release). A
  type or version this release does not know is read as `Unknown` and never dropped. Do not copy an
  appeal statement, a decision note, evidence content or a file name into a payload.
- `type` is text, not an ordinal, on purpose: later changes and consumers add types.
