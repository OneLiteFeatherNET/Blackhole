---
paths:
  - "backend/src/main/java/**/casetimeline/**/*.java"
  - "backend/src/main/resources/db/**/V12__punishment_case.sql"
  - "backend/src/main/java/**/discord/**/*.java"
  - "backend/src/main/resources/db/**/V18__discord_tickets.sql"
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
  `visibility = 'PLAYER'`. `appeal.filed|decided` stay `STAFF` visibility; the player reader shows them as bare markers through `PORTAL_ANY_VISIBILITY_TYPES` (decision of the public API change, see `public-api.md`).
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
  payload has generations only. Both statements delete the punishment's `player_session` rows
  (V16) in the same CTE when they change something; a repeat mark changes nothing and deletes nothing.
- **Transcript** (`ADMIN`, `format=json|text`, `includeNotes`): `TranscriptRenderer`; redacted entries are markers;
  `transcript.exported` (format, entry count, no content) is appended *before* the file is handed out; `no-store`, attachment.
- **Decision message**: `AppealReviewDTO.playerMessage` is checked by `MessageRules` (422 on UUID/IP/Ban ID) before the
  decision and recorded by `CaseTimeline.decisionMessage` (id derived from the appeal, never fails the decision). `decisionNote` stays internal.

## Appeal protocol (`casetimeline/protocol/`, `/case/{reference}/protocol`)

Detail: `openspec/changes/add-appeal-portal/specs/appeal-protocol/spec.md`, design decision 16, migration `V17__appeal_protocol.sql`.
The conversation of a *decided* appeal is generated from `case_event` + the `appeals` row only (`ProtocolSource`; no Discord, no
network) and stored as `TRANSCRIPT` evidence.

- **Model and renderers are pure.** `ProtocolModel.build` folds the appeal's messages (own `appealId`, or none and filed inside
  its window up to the next appeal), edits, deletes, redactions and the `appeal.*`/`ban_id.*`/`ticket.*` markers; notes, evidence and
  punishment events are not part of it. Only whitelisted fields are copied, so no Ban ID, Discord user id or owner hash can get in.
  `ProtocolRenderer` writes the text and JSON (`schemaVersion` 1) deterministically (fixed field order, `\n`, UTC), so the same events give
  byte-identical files apart from `generatedAt`. The digest is SHA-256 over `eventId|seq|type|redacted` lines in `seq` order and the JSON
  carries the same fields, so a reader recomputes it and the entry count. Players are `Player`, verified staff their display name
  (`Staff member (name removed)` once anonymised), others `Unverified staff`/`Bot`/`External participant`.
- **`AppealProtocolGenerator.generate(appealId, force)`** is the internal surface (the decision, the reconciler and the later Discord
  close route). It never throws: `Result.outcome` is `STORED`, `UNCHANGED`, `NOT_SETTLED`, `NOT_DECIDED`, `TICKET_INCOMPLETE`
  (a `ticket.*` event without `ticket.transcript_complete`: no protocol, even forced), `NOT_FOUND`, `TOO_LARGE` or `FAILED`. `force`
  skips only the settle delay.
- **Versions** live in `appeal_protocol` (`ProtocolStore`, `JdbcProtocolStore`). One CTE supersedes the active version and inserts the
  next one; partial unique indexes allow one `ACTIVE` per appeal and one readable row per `(appeal, digest)`, so concurrent runs store one.
  Version numbers are never reused (`PURGED` rows stay as tombstones; `evidence_id` is `ON DELETE SET NULL`). Files are written before the row;
  a run that loses the race deletes its own item. Dedupe key of `protocol.stored` is `protocol:<appealId>:v<version>`.
- **Redaction.** `JdbcCaseEventRepository.redact` (messages) and `JdbcCaseWriter.erasePlayerMessages` embed `ProtocolStore.MARK_PURGE_PENDING`
  as a CTE, so the versions are `PURGE_PENDING` (unreadable) in the redacting statement. `CaseConversationService` then calls
  `ProtocolRedaction.afterRedaction`: delete the files (`ERASURE`), regenerate with markers; a failure is logged, counted in
  `blackhole.protocol.failures{reason}` and finished by `ProtocolReconciler`. `StaffActorService.anonymize` clears the name first, then calls
  `afterStaffAnonymized`; `afterParticipantErased` is for the Discord change.
- **`ProtocolReconciler`** (`blackhole.protocol.reconcile-interval`, `0` = off): finishes purges, creates missing protocols, regenerates when
  the source changed after the settle delay (`blackhole.protocol.settle-delay`, injected clock). Candidates are an over-approximation in SQL;
  the digest decides. `blackhole.protocol.retries` bounds the look-again after a lost version race.
- **Staff routes** `GET /case/{ref}/protocol`, `GET .../protocol/{evidenceId}` (text, `VIEWED`), `GET .../protocol/{evidenceId}/download?format=text|json`
  (`DOWNLOADED`; `nosniff`, `CSP: sandbox`, `no-store`, attachment): `VIEWER` and up, `PROXY` denied (`CaseProtocolService`). Unreadable or other-case
  versions are 404. `GET /public/appeal/protocol` is the public API change's, never this stored file.

## Discord tickets and ingest (`discord/ticket|ingest|leak`, `/discord/tickets`)

Detail: `openspec/changes/add-discord-appeal-tickets/` (design decisions 2 to 8, 11, 12, 15), migration `V18__discord_tickets.sql`
(`case_participant`, `discord_ticket`, `discord_message_link`). The bot holds no state; this is where it lives.

- **Open** (`TicketService.open`): the Ban ID is checked like the portal login. Unknown, leaked and bound-to-another-user all answer the same
  404 `NOT_ELIGIBLE`. An open appeal is reused, else `TicketAppeals` (`PortalTicketAppeals`) evaluates the checklist read-only and records an appeal
  only when eligible (409 `NOT_YET` with `availableFrom`, 400 `STATEMENT_REQUIRED`). The ticket binds the Discord user as a `PLAYER`
  participant; the same user opening again gets the same ticket. Insert and `ticket.opened` are one CTE; one live ticket per appeal and player
  (partial unique index).
- **State machine** (`PATCH /discord/tickets/{id}`): `PENDING_CHANNEL -> OPEN` (channel id) `-> CLOSING -> CLOSED -> ARCHIVED -> DELETED`. `ARCHIVED` needs a
  stored protocol and a verified transcript, `DELETED` a *current* one (`AppealProtocolGenerator.isCurrent`, digest compared, nothing stored), both
  409 `PROTOCOL_REQUIRED`; `channelGone` records a channel a person deleted (`ticket.channel_deleted` with `transcriptComplete: false`, no gate). The
  backend enforces the gates, so a buggy bot cannot skip them.
- **Ingest** (`DiscordIngestService`, `DiscordIngestStore`): the bot posts a message in its *current state*; one CTE per item appends the event, the
  `discord_message_link` and advances `ingested_cursor`. New id = `message.added` (channel `DISCORD`, Discord time as `occurred_at`, key
  `discord:<channelId>:<messageId>`); known id with a different text hash = `message.edited` (`...:e:<hash>`, a repeat of the same text appends
  nothing); reported delete = `message.deleted` (`...:d`, `contentUnavailable` when never recorded); `complete: true` + `presentMessageIds` marks
  recorded messages missing from the channel `detectedAtCatchup`. The case message id is derived from the ticket and the Discord id, so replays name
  the same message. The bot never posts its own messages.
- **Authors** (`AuthorResolver`, judged at ingest time): bound user = `PLAYER`; staff role *and* a live `STAFF` `discord_link` = `STAFF`/`VERIFIED` with the
  linked `oid` and no participant; staff role only = `UNVERIFIED`; anyone else `EXTERNAL`/`UNVERIFIED`; another bot = `BOT`/`SYSTEM`, visibility `STAFF`.
  Discord can never decide, reissue or change a punishment: it writes messages only.
- **Attachments** are metadata (`MessageAttachment`: id, name, content type, size, dimensions; stickers by name; `MessageEmbed`: type and host); no
  bytes, no CDN URL. They live in the `message.added` payload and are removed with the text by a redaction.
- **Close** (`POST .../close`): accepted only when count and last id equal the backend tally of inbound, not deleted messages (else 409
  `TRANSCRIPT_MISMATCH`, nothing appended). One CTE records `ticket.transcript_complete` and moves the ticket to `CLOSED`; then
  `AppealProtocolGenerator.generate(appealId, true)` runs in the same request and the answer names `protocolVersion` (null while the appeal is undecided:
  do not archive). `FAILED`/`TOO_LARGE` answer 503, the event stays, the call is idempotent. `POST .../protocol/ensure` regenerates (forced) and answers
  the version; undecided or unverified is 409 `PROTOCOL_REQUIRED`.
- **Outbox/links**: `GET .../outbox` returns `PLAYER`-visible message events after `relayed_seq` (`ADD`/`EDIT`/`DELETE`/`REDACT`), never a message
  written in Discord, with the current text; an edit or delete is only an item for a message with a `RELAYED` link. `POST .../links` advances
  `relayed_seq` monotonically and links the case message to the Discord one. Events are a hint; this cursor is the truth.
- **Leak check** (`LeakCheckService`): normalises up to five candidates, a *current* Ban ID marks the case leaked through `CaseChanges.markBanIdLeaked`
  (reason `POSTED_PUBLIC`, source `DISCORD_BOT`, `poster` = opaque participant id, `posterIsOwner`); a look-alike leaves no event, row or log line.
- **Portal allow-list**: `ticket.opened` and `ticket.transcript_complete` (visibility `PLAYER`) are in `PORTAL_VISIBLE_TYPES`; `ticket.archived` and
  `ticket.channel_deleted` are staff facts. The admin export includes all four `ticket.*` markers.
- **Erasure**: `POST /case/{ref}/participants/{id}/erase` (`ADMIN`, `CaseParticipantService`) nulls the Discord id and name, sets `erased_at`, then
  `ProtocolRedaction.afterParticipantErased`; the conversation's player-side erase (`erasePlayerMessages`) erases the `PLAYER` participants too
  (`ParticipantErasure`).
