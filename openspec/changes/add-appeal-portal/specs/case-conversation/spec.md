# Spec Delta

## Purpose
Keeps the full conversation about an appeal as part of the case timeline: every message from the portal, the panel or an external channel, with its edits and deletions, the leak rule, erasure and export.

## ADDED Requirements

### Requirement: The backend is the record of the conversation
Every player and staff message SHALL be persisted as a `message.added` event on the punishment's case at the moment it is written, whichever channel it came from (`PORTAL`, `PANEL`, `DISCORD`). A message MUST NOT exist only in an external system, and a failure to persist a portal or panel message MUST fail that request (no silent loss).

#### Scenario: Portal message
- **WHEN** a player posts a message in the portal
- **THEN** the case has a `message.added` event with channel `PORTAL`, author kind `PLAYER` and visibility `PLAYER`

#### Scenario: Panel reply
- **WHEN** a moderator replies in the panel
- **THEN** the case has a `message.added` event with channel `PANEL`, author kind `STAFF`, the moderator's verified `oid` and visibility `PLAYER`

#### Scenario: Persistence failure
- **WHEN** storing a portal message fails
- **THEN** the player receives an error and no event is partially written

### Requirement: Visibility is set by the server
A message SHALL have visibility `PLAYER` (shown in the portal) or `STAFF` (never shown there). Requests from the portal and the panel MUST NOT carry a visibility; they always create `PLAYER` messages, and staff-internal text is a note. Portal reads MUST filter by visibility and by an allow-list of event types in the query, so an unknown type is invisible to players.

#### Scenario: Request cannot choose
- **WHEN** a panel request body sets visibility `STAFF`
- **THEN** the stored visibility is `PLAYER`

#### Scenario: Unknown future type
- **WHEN** a new event type with visibility `PLAYER` is added but not allow-listed
- **THEN** the portal conversation does not return it

### Requirement: Edits and deletes are new events
An edit SHALL append `message.edited` with the full new text and a delete SHALL append `message.deleted`; the original `message.added` row MUST NOT change. The current text is the latest added or edited version. Players cannot edit or delete in the portal. A deleted message SHALL be shown to the player as removed without text, and to staff with its last text marked deleted.

#### Scenario: Edit keeps history
- **WHEN** a message is edited twice
- **THEN** three events exist for it and the current text is the last

#### Scenario: Delete
- **WHEN** a message is deleted
- **THEN** the portal shows "message removed" and the panel shows the text marked deleted

### Requirement: Idempotent recording
Every message event SHALL carry a deterministic dedupe key (`message:<messageId>`, `message.edited:<messageId>:<n>`, `message.deleted:<messageId>`), so a retry or a replay inserts nothing twice. A client `Idempotency-Key` MUST make a repeated portal or panel post a no-op.

#### Scenario: Replay
- **WHEN** the same message event is recorded twice
- **THEN** one row exists

### Requirement: Case status follows the conversation
A player message on a case in status `WAITING` SHALL set it to `IN_REVIEW`, recorded as a `SYSTEM` `case.status_changed`; no other automatic status change is made by messages.

#### Scenario: Player answers a question
- **WHEN** a player replies on a `WAITING` case
- **THEN** the case becomes `IN_REVIEW` with a status event by `SYSTEM`

### Requirement: Marking a Ban ID leaked
`POST /case/{reference}/ban-id/leak` SHALL, for `MODERATOR` and above, record `ban_id.leak_marked` with a reason code (`POSTED_PUBLIC`, `SHARED`, `FOUND`, `OTHER`), the source (`STAFF` or `DISCORD_BOT`) and whether an appeal had been filed, delete the punishment's player sessions and set the leak state in one statement. Marking an already leaked Ban ID MUST append nothing. The payload MUST NOT contain the Ban ID.

#### Scenario: Mark with a reason
- **WHEN** a moderator marks a leak with reason `POSTED_PUBLIC`
- **THEN** the case shows the leak state, the timeline has `ban_id.leak_marked` and the player's sessions are gone

#### Scenario: Marked twice
- **WHEN** the same leak is marked again
- **THEN** no second event is appended

#### Scenario: Viewer cannot mark
- **WHEN** a `VIEWER` calls the route
- **THEN** the response is HTTP 403

### Requirement: Reissue after a leak
`POST /case/{reference}/ban-id/reissue` SHALL, for `ADMIN` only and with a mandatory reason code, issue a new Ban ID, clear the leak state, delete sessions and append `ban_id.reissued` with generation numbers only, in one statement.

#### Scenario: Reissue unfreezes
- **WHEN** an admin reissues after a leak on a frozen ticket
- **THEN** the leak state is cleared and the player can write again with the new Ban ID

### Requirement: Events carry no content
The system SHALL publish `case.message_added`, `case.message_edited`, `case.message_deleted`, `case.message_redacted`, `case.ban_id_leak_marked` and `case.ban_id_reissued` with identifiers, enums and the sequence number only, never message text, Ban IDs, file names or Discord identifiers, after the write and without failing it.

#### Scenario: Message event
- **WHEN** a message is added
- **THEN** the published `case.message_added` contains the message id, author kind, channel and visibility and no text

### Requirement: Retention of the conversation
A conversation SHALL be kept for as long as its case and punishment exist, the same rule as evidence and notes, and MUST NOT be deleted by elapsed time. The retention period is pending legal confirmation, like the staff retention statement.

#### Scenario: Expired punishment
- **WHEN** a punishment expired long ago and remains in the history
- **THEN** its conversation is still readable by staff

#### Scenario: Cascade
- **WHEN** the punishment record is deleted
- **THEN** its case and all message events are deleted with it

### Requirement: Redaction and erasure
An `ADMIN` SHALL be able to redact one message (`POST /case/{reference}/messages/{messageId}/redact`, reason code) or all player-authored messages of a case (`POST /case/{reference}/transcript/erase-player`). Redaction MUST null the text and attachment metadata on every version of the affected messages, keep type, time, author kind and visibility, and append `message.redacted`. Redacted entries MUST show as removed for data protection in the panel, the portal and the export.

#### Scenario: Redact one message with edits
- **WHEN** an admin redacts a message that was edited twice
- **THEN** all three versions have no text, the structure remains and `message.redacted` is appended

#### Scenario: Erase the player's side
- **WHEN** an admin erases the player-authored messages of a case
- **THEN** staff messages remain and every player message is redacted

#### Scenario: Moderator cannot redact
- **WHEN** a `MODERATOR` calls a redact route
- **THEN** the response is HTTP 403

#### Scenario: Stored protocol follows
- **WHEN** a message that is part of a stored protocol is redacted
- **THEN** the earlier protocol versions become unreadable and a regenerated version shows a marker (see `appeal-protocol`)

### Requirement: Transcript export
`GET /case/{reference}/transcript?format=json|text` SHALL, for `ADMIN`, return the conversation in order with messages, edits, deletions, redaction markers, visibility and the lifecycle markers of the case; notes MUST be excluded unless `includeNotes=true`; redacted entries MUST export as markers without text. Each export SHALL append `transcript.exported` with the actor, format and entry count and no content, and the response MUST be `no-store` and an attachment.

#### Scenario: Admin exports
- **WHEN** an admin exports a case as JSON
- **THEN** the file lists the entries in `seq` order and a `transcript.exported` event is on the timeline

#### Scenario: Redacted entry
- **WHEN** an entry was redacted
- **THEN** the export shows a marker and no text

#### Scenario: Moderator cannot export
- **WHEN** a `MODERATOR` calls the export route
- **THEN** the response is HTTP 403

### Requirement: Player trust level
Events written by a player SHALL carry actor trust `PLAYER`, which the UI MUST show distinctly and never with a verified badge; the trust column stays text so later values do not need a schema change.

#### Scenario: Player message in the panel
- **WHEN** staff open a case with a player message
- **THEN** the message is labelled as written by the player and not as verified staff
