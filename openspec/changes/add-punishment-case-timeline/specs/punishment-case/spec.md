# Spec Delta

## Purpose

Defines the per-punishment case: one append-only timeline of everything that happens to a punishment, plus staff metadata and internal notes, readable and writable through the API under the permission matrix.

## ADDED Requirements

### Requirement: One case per punishment
The system SHALL keep at most one case per punishment, identified by the punishment's identifier and addressable by identifier or short id. The case MUST be created by the first event recorded for the punishment, MUST have status `OPEN`, priority `NORMAL`, no assignee and no tags by default, and MUST be deleted together with its punishment. A read MUST NOT create a case.

#### Scenario: Case exists after a punishment is applied
- **WHEN** a moderator applies a punishment
- **THEN** a case for that punishment exists with status `OPEN`, priority `NORMAL` and a `punishment.applied` event

#### Scenario: Read of a punishment without a case row
- **WHEN** a viewer reads the case of an existing punishment that has no case row yet
- **THEN** the response is the default case with an empty timeline and no row is created

#### Scenario: Ambiguous short id
- **WHEN** a short id matches two punishments
- **THEN** the response is HTTP 409 listing both identifiers

#### Scenario: Case follows the punishment record
- **WHEN** a punishment record is deleted
- **THEN** its case and all its events are deleted

### Requirement: Append-only case events
Every fact about a case SHALL be stored as a `case_event` row with an identifier, a monotonically increasing `seq`, a type, a payload version, `occurred_at` from the injected clock, an origin (`LIVE`, `BACKFILLED` or `RECONSTRUCTED`), an actor and a payload. The system MUST NOT update or delete a `case_event` row except to redact a note (see "Note redaction") and by cascade with the punishment. Ordering MUST be by `occurred_at`, then `seq`.

#### Scenario: Edit is a new event
- **WHEN** a moderator edits a note
- **THEN** a `note.edited` event is appended and the earlier `note.added` row is unchanged

#### Scenario: Two events in the same millisecond
- **WHEN** two events have equal `occurred_at`
- **THEN** the timeline orders them by `seq`

#### Scenario: Repository offers no other mutation
- **WHEN** the architecture test inspects the case event repository
- **THEN** it exposes only append, redact and delete-for-punishment

### Requirement: Event types and payload schema
The system SHALL support the types `punishment.applied`, `evidence.attached`, `evidence.lifted`, `evidence.revoked`, `note.added`, `note.edited`, `note.redacted`, `case.status_changed`, `case.assignee_changed`, `case.tags_changed`, `case.priority_changed`, `appeal.filed`, `appeal.decided`, `punishment.revoked`, `punishment.lifted` and `punishment.expired`, each with a versioned payload of ids, enums and numbers (free text only in note types).

#### Scenario: Applied payload
- **WHEN** a punishment from escalation step 2 of 3 with an ELO cost of 40 is applied
- **THEN** the `punishment.applied` payload contains `stepIndex` 2, `stepCount` 3 and `eloCost` 40

#### Scenario: Appeal text is not copied
- **WHEN** an appeal with a statement is filed
- **THEN** the `appeal.filed` payload contains the appeal id and no statement text

### Requirement: Forward-compatible event types
The types `message.added` and `ban_id.leak_marked` SHALL be reserved and not written by this change. A row with an unknown type or a newer payload version MUST be returned as an opaque event and never dropped.

#### Scenario: Unknown type
- **WHEN** the log holds a row with a type this version does not know
- **THEN** the timeline returns it with its type and no error

#### Scenario: Reserved types are not written
- **WHEN** any flow of this change records an event
- **THEN** its type is not `message.added` or `ban_id.leak_marked`

### Requirement: Actor trust
Every event SHALL record its actor trust: `VERIFIED` when the actor is a person whose `oid` came from a human token or a live staff link, `UNVERIFIED` when only an attributed source UUID or no actor is known, `SYSTEM` for escalation, the expiry sweeper, the reconciler and automatic reopen. The event MUST NOT store an actor name, and the response MUST NOT present an `UNVERIFIED` actor as verified.

#### Scenario: Web action
- **WHEN** a moderator adds a note in the web UI
- **THEN** the event has trust `VERIFIED` and `actor_oid` equal to the moderator's `oid`

### Requirement: In-game actor attribution
For an in-game action through the proxy the event SHALL carry the proxy's `oid` as `recorded_by_oid` and the linked person as `actor_oid`; without a link it SHALL carry the attributed UUID as `actor_source`.

#### Scenario: In-game ban by a linked staff member
- **WHEN** the proxy applies a punishment on behalf of a linked staff member
- **THEN** the event has `recorded_by_oid` of the proxy, `actor_oid` of the linked person and trust `VERIFIED`

#### Scenario: In-game ban by an unlinked staff member
- **WHEN** the proxy applies a punishment on behalf of a UUID with no link
- **THEN** the event has no `actor_oid`, `actor_source` of that UUID and trust `UNVERIFIED`

#### Scenario: Escalation
- **WHEN** the system applies a permanent punishment by escalation
- **THEN** the event has trust `SYSTEM` and `escalated` true

### Requirement: Backfill of existing punishments
The migration SHALL create a case for every existing punishment and backfill events from the punishment, its appeals, its evidence and its end state, each with origin `BACKFILLED`, `occurred_at` from the source row and, where a time is unknown, an `approximate` payload flag. Backfilled actors without a recorded `oid` MUST be `UNVERIFIED`. Backfilled cases of ended punishments SHALL be `CLOSED`, others `OPEN`. Running the backfill again MUST add no event.

#### Scenario: Legacy punishment
- **WHEN** the migration runs over a punishment written before SSO with one decided appeal
- **THEN** the case has `punishment.applied`, `appeal.filed` and `appeal.decided` events, all `BACKFILLED` and `UNVERIFIED`

#### Scenario: Ended punishment
- **WHEN** the migration runs over an expired punishment
- **THEN** its case is `CLOSED` and has a `punishment.expired` event at the expiry time

#### Scenario: Re-run
- **WHEN** the backfill statements run a second time
- **THEN** the number of events does not change

### Requirement: Consistent recording without a transaction
A case-own change (note, status, assignee, tags, priority) SHALL change the case state and append its event in one SQL statement, so either both happen or neither. A lifecycle recording after an existing write MUST carry a deterministic `dedupe_key`, MUST NOT fail the originating change when it fails, and a failure MUST be logged and counted.

#### Scenario: Stale metadata write
- **WHEN** a status change arrives with an `expectedVersion` older than the case's version
- **THEN** the response is HTTP 409 and neither the status nor the event changes

### Requirement: Reconciliation of lost recordings
A scheduled reconciler SHALL insert missing lifecycle events from the authoritative rows with origin `RECONSTRUCTED`, `occurred_at` from the source row and the source row's actor, and MUST NOT duplicate an event that exists.

#### Scenario: Recording fails
- **WHEN** the event append fails after a punishment was applied
- **THEN** the punishment is applied, the failure is counted, and a later reconciler run adds `punishment.applied` with origin `RECONSTRUCTED`

#### Scenario: Retry is idempotent
- **WHEN** the same lifecycle event is recorded twice
- **THEN** one row exists

#### Scenario: Reconciler uses the source time
- **WHEN** the reconciler repairs an appeal filed two hours earlier
- **THEN** the event's `occurred_at` is the appeal's creation time, not the run time

### Requirement: Case metadata
A case SHALL carry a status (`OPEN`, `IN_REVIEW`, `WAITING`, `CLOSED`), an optional assignee that is the `oid` of a known human `staff_actor`, a priority (`LOW`, `NORMAL`, `HIGH`, `URGENT`) and up to 10 tags matching `[a-z0-9-]{1,32}`. Each change SHALL append `case.status_changed`, `case.assignee_changed`, `case.priority_changed` or `case.tags_changed` with the previous and new values and MUST do nothing when the value is unchanged.

#### Scenario: Assign a case
- **WHEN** a moderator sets the assignee to a known staff `oid`
- **THEN** the case shows that assignee and a `case.assignee_changed` event records the old and new value

#### Scenario: Unknown assignee
- **WHEN** the assignee `oid` is not a known human staff actor
- **THEN** the response is HTTP 422 and nothing changes

#### Scenario: Unchanged value
- **WHEN** a patch sets the priority to the value it already has
- **THEN** no event is appended

#### Scenario: Too many or malformed tags
- **WHEN** a patch sets 11 tags, or a tag `Bad Tag!`
- **THEN** the response is HTTP 400 and nothing changes

### Requirement: Status is a workflow
Filing an appeal on a `CLOSED` case SHALL reopen it to `OPEN` with a `SYSTEM` status event. The case status MUST NOT otherwise follow the punishment's state.

#### Scenario: Appeal reopens a closed case
- **WHEN** an appeal is filed on a `CLOSED` case
- **THEN** the case becomes `OPEN` and a `SYSTEM` `case.status_changed` event records it

#### Scenario: Expiry does not close the case
- **WHEN** a punishment expires while its case is `IN_REVIEW`
- **THEN** the case stays `IN_REVIEW` and a `punishment.expired` event is appended

### Requirement: Internal notes
Staff SHALL be able to add notes of 1 to 4000 characters to a case. A note MUST be stored with visibility `STAFF` regardless of the request and MUST NOT contain a UUID or IP lookalike. An edit SHALL append `note.edited` with the full new text and the same `noteId`; only the note's verified author or an `ADMIN` may edit, and a note by an unverified actor only an `ADMIN`.

#### Scenario: Add and edit
- **WHEN** a moderator adds a note and later edits it
- **THEN** the timeline shows the edited text with an edited marker and both events exist

#### Scenario: Visibility is forced
- **WHEN** a request body sets a visibility of `PLAYER`
- **THEN** the stored visibility is `STAFF`

#### Scenario: Someone else's note
- **WHEN** a moderator edits a note written by another moderator
- **THEN** the response is HTTP 403

#### Scenario: Personal data lookalike
- **WHEN** a note contains a UUID
- **THEN** the response is HTTP 400 and nothing is stored

### Requirement: Note redaction
An `ADMIN` SHALL be able to redact a note with a mandatory reason. Redaction MUST null the payload and set `redacted_at` and `redacted_by_oid` on every version of that note, keep type, time and actor, and append `note.redacted` without text. The timeline MUST show a redacted note as removed.

#### Scenario: Redact an edited note
- **WHEN** an admin redacts a note that has an added and an edited version
- **THEN** both rows have no payload and a redaction time, and a `note.redacted` event exists

#### Scenario: Moderator cannot redact
- **WHEN** a moderator calls the redact route
- **THEN** the response is HTTP 403 and the note is unchanged

#### Scenario: Reason required
- **WHEN** the redact request has no reason
- **THEN** the response is HTTP 400

### Requirement: Paged and filterable timeline
`GET /case/{reference}/timeline` SHALL return events with a `seq` cursor and a page size (default 50, maximum 200), ordered newest or oldest first, filterable by type (including the prefix groups `note`, `appeal`, `evidence`, `case`, `punishment`), actor trust and time range. Each item SHALL include `actor` with `oid`, display name from `staff_actor` (null when anonymized) and trust, plus origin and visibility.

#### Scenario: Paging
- **WHEN** a case has 120 events and a client requests size 50 three times, following the cursor
- **THEN** it receives 50, 50 and 20 events with no overlap and no gap

#### Scenario: Filter by group
- **WHEN** the client filters by type `appeal`
- **THEN** only `appeal.filed` and `appeal.decided` events are returned

#### Scenario: Oversized page
- **WHEN** the client requests size 1000
- **THEN** at most 200 events are returned

### Requirement: Linked cases
`GET /case/{reference}` SHALL return up to 20 other punishments of the same owner, newest first, each with short id, section, punishment state, case status and creation time, and MUST NOT include the owner in clear text.

#### Scenario: Same player
- **WHEN** a player has three punishments and the case of one is read
- **THEN** `related` lists the other two

### Requirement: Case list
`GET /case` SHALL list cases filterable by status, assignee, tag, priority, section and punishment state, paged, ordered by the latest event time descending.

#### Scenario: My open cases
- **WHEN** a client lists cases with the caller's `oid` as assignee and status `OPEN`
- **THEN** only those cases are returned

### Requirement: Case domain events
The system SHALL publish `case.note_added`, `case.note_edited`, `case.note_redacted`, `case.status_changed`, `case.assignee_changed`, `case.tags_changed` and `case.priority_changed` on the `blackhole.events` exchange after the write, carrying the case event id, punishment identifier, hashed owner, `seq`, actor trust, actor `oid`, time and the non-content change. A payload MUST NOT contain note text, and a broker failure MUST NOT fail the request.

#### Scenario: Note event has no text
- **WHEN** a moderator adds a note
- **THEN** `case.note_added` is published with the note id and no text

#### Scenario: Broker down
- **WHEN** the broker is unavailable during a status change
- **THEN** the request succeeds and the change is stored

### Requirement: Lifecycle facts are not republished
Lifecycle facts (applied, appeal, evidence, ended) MUST NOT be republished as `case.*`, because `punishment.*`, `appeal.*` and `evidence.*` already carry them. The names `case.message_added` and `case.ban_id_leak_marked` are reserved.

#### Scenario: Apply publishes no case key
- **WHEN** a punishment is applied
- **THEN** `punishment.created` is published and no `case.*` event is

### Requirement: Erasure and data protection
Timeline actors SHALL be shown by the display name in `staff_actor`, so that anonymizing a staff actor shows an anonymous actor on every event while keeping the `oid`. Notes, tags and payloads MUST NOT contain or publish raw player UUIDs or IP addresses, and the player MUST be identified only by the hashed owner.

#### Scenario: Anonymized staff member
- **WHEN** an admin anonymizes a staff actor who wrote notes
- **THEN** their timeline entries show no name and still reference their `oid`

#### Scenario: Timeline holds no raw identifiers
- **WHEN** a case with events of every type is serialised
- **THEN** no field contains a raw player UUID or IP
