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
  `evidence.attached|lifted|revoked:<id>`, `retemplated:<id>:<template>`), so a retry, the reconciler or a replay inserts nothing twice.
- **Case-own changes are one statement.** A change of case state that is itself history (`JdbcCaseWriter`,
  today only the reopen of a `CLOSED` case by a filed appeal) is a single data-modifying CTE, as
  `JdbcEloScoreWriter` is for scores.
- **`CaseReconciler`** (`blackhole.case.reconcile.interval`, `0` switches it off; `look-back`) finds source
  rows in the window without their dedupe key and appends them with origin `RECONSTRUCTED`, dated by the
  source row and attributed to the row's actor. `CaseDrafts` builds the events for both the live flows
  and the reconciler, so a repaired event looks like the lost one. `V12` backfills in SQL with the same
  payload shapes; `CaseMigrationIntegrationTest` reads them back as typed payloads to keep both in step.
- **Trust.** `CaseActor.ofRow` is the one rule for `VERIFIED` / `UNVERIFIED` / `SYSTEM`; `PLAYER` (`CaseActor.player()`) is a player's own message, no `oid`, never a verified badge (the SQL backfill
  mirrors it). Legacy actors are never `VERIFIED`. No actor name is stored: it is joined from
  `staff_actor` when read, so anonymize erases it everywhere.
- **Payloads** are a sealed `CaseEventPayload`; free text only in note types (not in this release). A
  type or version this release does not know is read as `Unknown` and never dropped. Do not copy an
  appeal statement, a decision note, evidence content or a file name into a payload.
- `type` is text, not an ordinal, on purpose: later changes and consumers add types.

## Case API (`/case`, `casetimeline/controller|service|dto`)

- `CaseController` is thin and implements `CaseApi`; `CaseService` answers expected failures as
  `CaseDTO.Error` (`CaseErrorKind` adds 403 and 422). Roles: `VIEWER` reads (case, timeline, list, notes),
  `MODERATOR` writes (PATCH, add and edit note), `ADMIN` redacts. `PROXY` has no rule and is denied.
- **One statement per case-own change.** `CaseChanges` (`JdbcCaseWriter`) updates `punishment_case` and
  appends one event per changed field in a single CTE guarded by `version`; zero rows means 409. A note edit
  is an `INSERT ... WHERE NOT EXISTS (redacted version)`. A new note is `CaseEventRepository.append`.
  `GET` never creates a case row; the first write does (`ensureCase`, idempotent).
- **Notes**: 1 to 4000 characters, `PiiGuard` (400), visibility forced `STAFF`, edit only by the verified
  author or an admin (`Actor.admin`). Redaction = `CaseEventRepository.redact` then `note.redacted`
  (reason is a code, deduped per note). The timeline serves `redactedAt` and an empty payload.
- **Reads** go through `CaseReader` (never write). The timeline cursor is `occurredAt:seq` (keyset on the
  timeline order), page size default 50, max 200. `actor.name` is joined from `staff_actor`.
- **Domain events** `case.<type>` (`CaseEvents`): ids, hashed owner, `seq`, trust, `from`/`to`, never text.
  Published after the write in a try/catch. Lifecycle facts are not republished as `case.*`.

## Conversation, leak rule and transcript (`/case/{reference}/messages|ban-id|transcript`)

Detail: `openspec/changes/add-appeal-portal/specs/case-conversation/spec.md` and design decisions 8, 10, 12.
`CaseConversationController` (`CaseConversationApi`) is thin; `CaseConversationService` holds the rules, answers
`CaseConversationDTO.Error` and lets an unexpected storage failure propagate (a message is never lost silently).

- **Messages are case events**: `message.added|edited|deleted|redacted` (`subject_id` = message id, payload in
  `CaseEventPayload`, author as `{kind, participantId?}`). `CaseEventDraft.visibility` is set by the server: staff
  replies and player messages are `PLAYER`; a request body has no visibility, author or channel (ignored). Staff-internal
  text is a note. `CaseEventPayload.PORTAL_VISIBLE_TYPES` is the allow-list a player-facing reader applies in SQL next to
  `visibility = 'PLAYER'`. `appeal.filed|decided` are listed but still `STAFF` visibility until the public API change shows them.
- **Current text** is the latest added/edited version (`MessageThread`); an edit is `message.edited:<id>:<n>`, a delete
  `message.deleted:<id>`, never a row change. Only the verified author or an admin may correct a *staff* message; a
  player's words are changed only by redaction. `Idempotency-Key` (header, 1-80 of `[A-Za-z0-9._:-]`) derives the message
  id, so a repeat inserts nothing and answers `repeated: true`.
- **One statement per change** (`CaseChanges`): `appendMessage` (message + `WAITING` to `IN_REVIEW` by `SYSTEM` for a
  player message), `editMessage` (guarded against a redacted/deleted message), `markBanIdLeaked`, `reissueBanId`,
  `erasePlayerMessages`. `postPlayerMessage` is the entry point for the public API (409 once the Ban ID is marked leaked).
- **Redaction** keeps type, time, trust, visibility and the payload structure and removes `text`, `attachments` and
  `embeds` (`CaseEventRepository.redact`, now also for messages); `message.redacted:<id>` is appended once. The repository
  surface stays append / redact / deleteForPunishment; the player-side erase is a `CaseChanges` statement.
- **Leak rule**: state is `punishment_case.ban_id_leaked_at|reason` (V13, no new migration). Marking is `MODERATOR`,
  idempotent (`changed: false`); reissue is `ADMIN`, replaces hash/ciphertext, raises `ban_id_gen`, clears the leak,
  payload has generations only. Deleting the punishment's player sessions belongs in the same two statements once
  `player_session` exists (public API change); until then there is nothing to delete.
- **Transcript** (`ADMIN`, `format=json|text`, `includeNotes`): `TranscriptRenderer`; redacted entries are markers;
  `transcript.exported` (format, entry count, no content) is appended *before* the file is handed out; `no-store`, attachment.
- **Decision message**: `AppealReviewDTO.playerMessage` is checked by `MessageRules` (422 on UUID/IP/Ban ID) before the
  decision and recorded by `CaseTimeline.decisionMessage` (id derived from the appeal, never fails the decision). `decisionNote` stays internal.
