# Design

## Context

- A punishment is `punishments` (identifier = 22 char Base64 UUID, `short_id`, `source`,
  `recorded_by_oid`, `actor_oid`, `owner` hash, `section_key`, `step_index`, `step_count`,
  `elo_cost`, `permanent`, `lifted`, `created_at`, `meta_data`). Appeals (`appeals`), evidence
  (`evidence_items`, `evidence_audit`) and ELO events reference it. State changes in place
  (`lifted`, evidence `status`), so history is not recoverable from those tables.
- Verified actor is in place: `recorded_by_oid` is the token's `oid`; for in-game actions the proxy
  is `recorded_by_oid` and `actor_oid` is the person resolved through `staff_link` (null when
  unlinked). Rows from before SSO have neither.
- `@Transactional` is unusable (CLAUDE.md, config principle 6). Precedent for atomic multi-table
  writes without it: `JdbcEloScoreWriter` (one SQL statement).
- Events: `DomainEventPublisher` is fire-and-forget on the topic exchange `blackhole.events`;
  payloads carry no content (`evidence.md`). Evidence is reachable only through
  `PunishmentEvidenceGateway`.

## Goals / Non-Goals

**Goals:** one case page per punishment; a trustworthy, ordered, append-only history; staff
metadata and notes; an event model the portal and the Discord sync can extend without a schema
change.

**Non-Goals:** see the proposal (no portal, no bot, no player view, no per-case ACL).

## Decisions

### 1. Case identity: keyed by the punishment, created with the first event

`punishment_case.punishment_identifier` is the primary key and a foreign key to `punishments` with
`ON DELETE CASCADE`. There is no separate case id: the URL is `/case/{reference}` with the same
`reference` (identifier or short id) the evidence routes already accept. A short id that matches
more than one punishment answers 409 with the candidates, like the existing short-id lookup.

The row is created **lazily by the first event** appended for the punishment
(`INSERT ... ON CONFLICT DO NOTHING` inside the append statement, decision 4). Live, that first event
is `punishment.applied`, so a case exists from the moment the punishment does. The migration
creates cases for every existing punishment (decision 3). A read for a punishment that has no case
row yet (a recording that failed and is not repaired yet) answers the default case with an empty
timeline; GET never writes.

Alternative considered: creating the row in the apply flow itself. Rejected: it adds a second write
to a flow that is already not one transaction, while the lazy upsert is idempotent.

### 2. Source of truth: write everything into `case_event` going forward

Options: (A) project the timeline on read from `punishments`, `appeals`, `evidence_*` plus new
events for notes and metadata; (B) every fact is an event row written when it happens.

**Recommended: B.** A projection cannot show what no table keeps (evidence `LIFTED` overwrites its
state, expiry has no row, notes and metadata have no table), cannot order events with equal
timestamps stably, and gives the Discord sync nothing with a stable id to resume from. B costs one
extra write per flow, which decision 4 makes safe.

The existing tables stay authoritative for **state** (what is enforced now). `case_event` is
authoritative for **history**. Decision 4 keeps the two consistent, and a reconciler repairs a lost
recording from the state tables.

### 3. Backfill of existing punishments

Migration `V<n>__punishment_case.sql` (next free version; V11 is claimed by
`integrate-cloudnet-sections`, so V12 if that lands first) creates both tables and backfills with
set-based `INSERT ... SELECT`, no application code:

| Source | Event | `occurred_at` | actor |
|---|---|---|---|
| every punishment | `punishment.applied` (step, count, ELO cost, section, permanent) | `created_at` | `recorded_by_oid` / `actor_oid` if set, else `source` as `UNVERIFIED` |
| appeals | `appeal.filed`, and `appeal.decided` when decided | `created_at` / `updated_at` | `recorded_by_oid` if set, else `decided_by` as `UNVERIFIED` |
| evidence items | `evidence.attached`, plus `.lifted` / `.revoked` by `status` | audit `ATTACHED` time, else `created_at`; the status change time is unknown, use the item's last update | audit actor |
| `lifted` punishments | `punishment.lifted` or `punishment.revoked` | recorded time if the metadata has it, else the latest related appeal or punishment time, `approximate: true` in the payload | as above |
| punishments whose expiry passed and not lifted | `punishment.expired` | the expiry time | `SYSTEM` |

Every backfilled row has `origin = BACKFILLED`. The UI marks it "reconstructed from earlier data"
and shows its actor as unverified, never with a verified badge. Backfilled cases get status
`CLOSED` if the punishment has ended (expired, revoked, lifted) and `OPEN` otherwise, priority
`NORMAL`, no assignee, no tags. A re-run inserts nothing (the `dedupe_key` of decision 5). The ELO
history is not copied event by event: the `punishment.applied` payload carries the ELO cost from
the punishment row and the score before/after only when it is known.

### 4. Consistent writes without `@Transactional`

Two kinds of write.

**Case-own writes** (note, status, assignee, tags, priority) change case state **and** are the
history, so they must not diverge. Each is **one SQL statement** (a data-modifying CTE) in
`JdbcCaseWriter`, as `JdbcEloScoreWriter` does:

```sql
WITH upd AS (
  UPDATE punishment_case SET status = :new, version = version + 1, updated_at = :now
  WHERE punishment_identifier = :id AND version = :expected RETURNING version)
INSERT INTO case_event (...) SELECT ... FROM upd
```

Zero rows from the CTE means the optimistic `version` did not match: the service answers 409
(`stale`) and nothing changed. A request that changes several fields becomes one statement with one
`INSERT ... SELECT` per changed field, joined with `UNION ALL`, all behind the same `upd`. A note
needs only the `INSERT` (the note is the event).

**Lifecycle recordings** (applied, evidence, appeal, ended) happen **after** the existing primary
write, through `CaseTimeline.record(...)`. A failure is logged and counted
(`blackhole.case.record.failures`) and never fails the punishment change, the same contract as the
evidence gateway. They carry a deterministic `dedupe_key` (`applied:<punishmentId>`,
`appeal.filed:<appealId>`, `expired:<punishmentId>`, `evidence.attached:<evidenceId>`) with a
unique index, so a retry, the reconciler or a replay inserts nothing twice.

A **`CaseReconciler`** (scheduled like `PunishmentExpirySweeper`; interval and look-back in
`blackhole.case.*`, `0` disables) repairs lost recordings: it finds punishments, appeals and
evidence items in the look-back window without their `dedupe_key` event and inserts them with
`origin = RECONSTRUCTED`, `occurred_at` taken from the source row (never "now") and the actor from
the source row. Notes and case-own changes never need repair; they are atomic by construction.

Why not an outbox table: the reconciler reads the authoritative rows that already exist, so it
needs no second write in the primary flow and cannot drift from it.

### 5. Event model

`case_event` (append-only):

| Column | Meaning |
|---|---|
| `identifier uuid` PK | stable id, what consumers resume by |
| `seq bigint generated always as identity` | total order and paging cursor; ties in `occurred_at` break by `seq` |
| `punishment_identifier varchar(255)` | FK to the case, `ON DELETE CASCADE` |
| `type varchar(48)` | dotted lower case type, **text, not ordinal** (later changes and consumers add types) |
| `payload_version smallint` | schema version of that type's payload |
| `occurred_at bigint` | epoch ms from the injected `Clock` (or the source row for backfill and reconstruction) |
| `origin varchar(16)` | `LIVE`, `BACKFILLED`, `RECONSTRUCTED` |
| `actor_trust varchar(16)` | `VERIFIED`, `UNVERIFIED`, `SYSTEM` |
| `actor_oid uuid` | the person (token `oid`, or the `staff_link` person); null if none |
| `recorded_by_oid uuid` | the verified principal that wrote it (the proxy for in-game actions) |
| `actor_source uuid` | the attributed staff Minecraft UUID when there is no `actor_oid` (unverified) |
| `subject_id varchar(64)` | what the event is about: note id, appeal id, evidence id |
| `visibility varchar(8)` | `STAFF` (default, the only value written in this change); `PLAYER` is reserved for the portal |
| `payload jsonb` | per-type body, see below; null after a redaction |
| `redacted_at bigint`, `redacted_by_oid uuid` | set only by the erasure of decision 8 |
| `dedupe_key varchar(128)` | unique when not null (decision 4) |

No actor **name** is stored. The display name is joined from `staff_actor` at read time, so the
existing anonymize route (it clears `display_name`) erases it everywhere with no change to the log
(decision 8). Indexes: `(punishment_identifier, seq)`, `(type)`, unique `(dedupe_key)`,
`(actor_oid)`.

Trust: `VERIFIED` = `actor_oid` came from a human token or from a live `staff_link`.
`UNVERIFIED` = only an attributed `source` UUID (legacy rows, unlinked in-game staff) or no actor.
`SYSTEM` = escalation, expiry sweeper, reconciler, automatic reopen. The UI never renders
`UNVERIFIED` like `VERIFIED`.

**Types and payloads.** A sealed `CaseEventPayload` interface with one record per type, serialised
with the type as discriminator. An unknown `type` or a newer `payload_version` is read as an opaque
`UnknownPayload` (kept, shown generically, never dropped), so an older backend can read what a newer
one wrote. Payloads hold ids, enums and numbers; **free text only in note types**.

| Type | Written by | Payload |
|---|---|---|
| `punishment.applied` | apply flow, system escalation | `section`, `stepIndex`, `stepCount`, `eloCost`, `scoreBefore` / `scoreAfter` (if known), `permanent`, `escalated`, `templateId`, `expiresAt` |
| `evidence.attached` | evidence gateway (on claim for pending items) | `evidenceId`, `kind`, `sourceSystem` (no content, no file name) |
| `evidence.lifted` / `evidence.revoked` | gateway | `evidenceId`, plus `appealId` for lifted |
| `note.added` | case API | `noteId`, `text` |
| `note.edited` | case API | `noteId`, `text` (the new full text) |
| `note.redacted` | erasure | `noteId` |
| `case.status_changed` | case API, automatic reopen | `from`, `to` |
| `case.assignee_changed` | case API | `from`, `to` (oids or null) |
| `case.tags_changed` | case API | `added[]`, `removed[]` |
| `case.priority_changed` | case API | `from`, `to` |
| `appeal.filed` | appeal flow | `appealId`, `eligible` (no statement text) |
| `appeal.decided` | appeal flow | `appealId`, `decision`, `newExpirationAt` (no decision note text) |
| `punishment.revoked` | revoke flow | none beyond the actor |
| `punishment.lifted` | granted full-lift appeal | `appealId` |
| `punishment.expired` | expiry sweeper | `expiredAt` |
| `message.added` | **reserved** (portal, Discord) | not defined here |
| `ban_id.leak_marked` | **reserved** (team or Discord bot) | not defined here |

`subject_id` is the `noteId` for the note types, the appeal id for appeal types and the evidence id
for evidence types. The appeal statement and decision note stay in `appeals`; the timeline links by
id and the UI reads the appeal through its existing route, so player text is not copied into a second
table (GDPR). Staff notes are the only free text this change introduces.

**Extensibility for the portal and Discord.** A later change adds types (`message.added`,
`ban_id.leak_marked`, ticket events), may write `visibility = PLAYER`, and reads or streams by `seq`
or `identifier`. It will need an actor kind for a player or a bot; this change keeps trust to the
three values above and leaves a fourth to that change (the column is text). No column here has to
change for that. The reader filters by `visibility`, and notes are forced to `STAFF`, so a staff-only
row cannot reach a player view.

### 6. Metadata

`punishment_case`: `punishment_identifier` PK/FK, `status` text (`OPEN`, `IN_REVIEW`, `WAITING`,
`CLOSED`; default `OPEN`), `assignee_oid uuid` null, `priority` text (`LOW`, `NORMAL`, `HIGH`,
`URGENT`; default `NORMAL`), `tags jsonb` (sorted unique array), `version bigint`, `created_at`,
`updated_at`.

- **Status is a staff workflow, not the punishment state.** The punishment's own state (active,
  expired, revoked, lifted) is shown beside it and filterable separately. The only automatic change:
  filing an appeal on a `CLOSED` case reopens it to `OPEN` (`case.status_changed`, `SYSTEM`).
- **Assignee:** any `staff_actor` of kind `HUMAN`; an unknown oid is rejected (422). The assignee's
  role is not checked (the backend cannot read another person's groups).
- **Tags:** `[a-z0-9-]{1,32}`, at most 10 per case; free-form, the list endpoint offers existing ones
  as suggestions. A tag that looks like a UUID or IP is rejected by the existing `PiiGuard`.
- **Notes:** staff only, `visibility = STAFF` forced server-side, text 1..4000 characters, also
  `PiiGuard`-checked (staff refer to a player by short id). A note's current text is the latest
  `note.added` / `note.edited` with that `noteId`; the timeline shows the latest text with an
  "edited" marker and the history one click away. Only the note's author or an `ADMIN` may edit
  (author = `actor_oid`; a note by an unverified actor is editable by `ADMIN` only).
- **Linked cases:** not stored. `GET /case/{reference}` returns `related`: up to 20 other
  punishments of the same hashed owner (short id, section, punishment state, case status,
  `createdAt`), newest first, by query on `punishments.owner`.

### 7. API

All under `/case`, class-level `@Version(ApiVersion.V1)`, `@Secured` per method. `CaseController`
implements `CaseApi` (OpenAPI annotations live there) and stays thin: one `CaseService` call and
the response mapping. A sealed `CaseDTO` (`Response`, `TimelinePage`, `NoteRequest`,
`UpdateRequest`, `Error`, ...) follows the `micronaut-*` skills; the service answers an expected
failure with `Error`.

| Route | Method | Role |
|---|---|---|
| `/case` | `GET` list; filters `status`, `assignee`, `tag`, `priority`, `section`, `punishmentState`; paged; newest activity first | `VIEWER` |
| `/case/{reference}` | `GET` metadata and `related` | `VIEWER` |
| `/case/{reference}/timeline` | `GET` paged by `seq` cursor and `size` (default 50, max 200); filters `type` (repeatable, prefix groups `note`, `appeal`, `evidence`, `case`, `punishment`), `actorTrust`, `from` / `to`, `order` | `VIEWER` |
| `/case/{reference}` | `PATCH` `UpdateRequest` (`expectedVersion` and any of `status`, `assigneeOid`, `priority`, `tags`) | `MODERATOR` |
| `/case/{reference}/notes` | `POST` | `MODERATOR` |
| `/case/{reference}/notes/{noteId}` | `PUT` (writes `note.edited`) | `MODERATOR` (author) or `ADMIN` |
| `/case/{reference}/notes/{noteId}/redact` | `POST`, reason mandatory | `ADMIN` |

`PROXY` has no `/case` rule and is denied. The actor is always `ActorResolver`, never a body field.
Reading notes is `VIEWER` and up (internal staff text, same as reading evidence). The
`staff-authentication` delta adds the rows; `PermissionMatrixIntegrationTest` and
`RouteCoverageIntegrationTest` get them. Timeline items return `actor` as `{oid, name, trust}` with
`name` from `staff_actor` (null when anonymized), so the UI needs no second lookup.

### 8. GDPR and erasure

- Player identity stays the hashed `owner`; the case page shows the short id and a hash prefix only,
  like the existing player views. No raw UUID or IP enters a payload or note (`PiiGuard`).
- Staff data follows the existing retention statement (kept while records reference it), which is
  **still pending legal confirmation**, as in `verified-actor`. This change only adds `oid`
  references.
- **Erasing a staff member:** the existing `POST /admin/staff-actor/{oid}/anonymize` clears the
  display name. The log stores only the `oid`, so every timeline entry shows an anonymous actor with
  no further change; the `oid` and the history stay, as for all other records.
- **Notes can contain personal data.** `POST .../notes/{noteId}/redact` (ADMIN, mandatory reason
  code) sets `payload = null`, `redacted_at` and `redacted_by_oid` on **every version of that note**
  (`note.added` and all `note.edited`), keeps type, time and actor, and appends `note.redacted`
  (no text). Redaction and cascade are the only mutations of `case_event` rows: the repository
  exposes `append`, `redact` and `deleteForPunishment` and nothing else, and an architecture test
  asserts it. A database trigger was considered and rejected because it would also fire on the
  cascade.
- **Erasing a player or deleting a punishment record:** `ON DELETE CASCADE` removes the case and all
  its events with the punishment, the same lifetime rule as evidence.
- The broker never receives note text (decision 9).

### 9. Domain events

Case-own changes publish on `blackhole.events` with routing key `case.<change>`:
`case.note_added`, `case.note_edited`, `case.note_redacted`, `case.status_changed`,
`case.assignee_changed`, `case.tags_changed`, `case.priority_changed`. Payload:
`{caseEventId, punishmentIdentifier, owner, seq, actorTrust, actorOid, occurredAt}` plus the
non-content change (`from` / `to`, tag lists, `noteId`). Never note text. Published after the write
inside a try/catch so a broker problem never fails the request (as `EvidenceService` does).

Lifecycle facts (applied, appeal, evidence, ended) are **not** republished as `case.*`: they
already have `punishment.*`, `appeal.*` and `evidence.*` keys, and a consumer that wants the full
timeline reads the API by `seq`. `case.message_added` and `case.ban_id_leak_marked` are reserved
names. Nothing in the backend binds to `case.*` yet (like `evidence.*`); the future Discord sync is
the consumer.

### 10. Admin UI

Route `/cases/:reference`, linked from the punishment rows of the player detail, plus a case list
(`/cases`, filters from the list endpoint).

- **Header:** player (short hash), section, punishment state pill, status select, assignee picker
  (staff known from `staff_actor`), tag chips (add and remove), priority select. Controls are
  disabled with an explanation for `VIEWER`; a 409 shows "changed by someone else" and reloads.
- **Timeline:** vertical list, one icon and label per type (unknown types get a generic one); the
  actor carries a verified / unverified / system badge with text and icon, not colour alone; a
  `BACKFILLED` / `RECONSTRUCTED` marker; filters for type group, actor trust and date; "load more"
  by cursor (no infinite scroll that traps keyboard focus). Edited notes show the latest text and an
  expandable history; redacted notes show "removed (data protection)".
- **Notes editor:** textarea with counter, Ctrl+Enter submits, edit own note, ADMIN redact with a
  reason dialog.
- **Evidence inline:** the existing evidence components render items referenced by `evidence.*`
  events. The timeline holds no copy of content; reveal follows the existing audited route.
- **Related cases:** a link list from `related`.
- German and English strings (`i18n.keys.test.ts` keeps parity), MD3 tokens, semantic markup
  (`ol` and `time`), visible focus, a live region for save results, contrast as in the rest of the UI.

### 11. Tests (F.I.R.S.T.)

- Backend unit: injected `Clock` / `TestClock`, fake repositories; payload (de)serialisation per
  type including unknown type and version; trust derivation; dedupe; reconciler on fixed data; note
  versioning and redaction logic. No sleeps.
- Backend integration (Testcontainers PostgreSQL, `PostgresIntegrationTest`): the migration on a
  database with representative old rows (counts, `origin`, trust, idempotent re-run); the CTE writes
  (stale version gives 409, event and state atomic); `ON DELETE CASCADE`; the routes with
  self-signed JWTs (`TestJwtIssuer`) for each role, with the matrix tests extended; the
  repository-surface architecture test.
- Web (Vitest with a fake API client): timeline rendering per type, unknown type, filters, cursor
  paging, trust badges, role gating, 409 handling, notes editor, i18n parity.
- A bug found while implementing is reproduced by a failing test first.

## Flyway migrations

One new file, `V<n>__punishment_case.sql` (next free version at implementation time): creates
`punishment_case` and `case_event` with their indexes and runs the backfill of decision 3. Existing
rows are migrated by that backfill. Append-only: later changes add columns in later files.

## Risks / Trade-offs

- [A live recording fails after the punishment was written] -> logged, counted, repaired by the
  reconciler with `origin = RECONSTRUCTED`; the event appears late but with the right time.
- [Backfill times for lifted and revoked punishments are approximate] -> flagged `approximate`,
  shown as reconstructed; no verified badge on legacy actors.
- [Free-text notes hold personal data] -> `PiiGuard`, staff instruction, ADMIN redaction of the whole
  note chain, cascade on punishment deletion, never in broker events.
- [The log grows] -> bounded by punishments x events, indexed by `(punishment_identifier, seq)`; no
  retention sweep by design (lifetime = punishment).
- [`type` as text instead of an ordinal] -> deviates from the ordinal-enum convention on purpose
  (other changes and consumers add types); validated in the application layer like the others.
- [Any MODERATOR can edit any case's metadata] -> accepted: no per-case ACL, the deployment's trust
  model; every change is attributed and visible in the timeline.
- [`staff-temp-bans-elo-escalation` is not archived yet] -> its columns (`step_index`, `elo_cost`,
  `section_key`) are already on `main`; the `punishment.applied` payload reads them and leaves them
  null for older rows.

## Migration Plan

Deploy the backend: the migration backfills, then new writes record. No proxy or client change is
needed. Rollback: the previous release ignores both tables and they can stay; events that happen
after the rollback are missing until a later release's reconciler runs.

## Open Questions

- Should `VIEWER` read internal notes, or only `MODERATOR` and up? Proposed `VIEWER`, consistent with
  evidence reads; tightening it is one matrix row.
- Should closing a case be blocked while the punishment is still active? Proposed: no block (status
  is a workflow, not enforcement).
- Is a free-form tag list enough, or does the team want an admin-managed tag set? Proposed:
  free-form now; a managed set can be added without changing the event model.
