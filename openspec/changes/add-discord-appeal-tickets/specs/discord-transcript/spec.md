# Spec Delta

## Purpose
Keeps the complete history of every ticket channel in the backend, provably complete at close, and readable after the channel is gone.

## ADDED Requirements

### Requirement: Every channel message is recorded
Every message in a ticket channel, from the player, from staff and from other bots, SHALL be recorded as a `message.added` event with channel `DISCORD`, its author kind and trust, the Discord timestamp as `occurred_at`, the text, and attachment and embed metadata. Nothing MAY live only in Discord.

#### Scenario: Staff message
- **WHEN** a staff member writes in the channel
- **THEN** the case has a `message.added` event for it with their kind and trust

#### Scenario: Other bot
- **WHEN** another bot posts in the channel
- **THEN** the message is recorded with author kind `BOT` and visibility `STAFF`

#### Scenario: Timestamp
- **WHEN** a message is ingested minutes after it was written
- **THEN** its `occurred_at` is the Discord timestamp

### Requirement: Author attribution
A message SHALL be `VERIFIED` staff only when its author has the staff role at the time of writing and a live Discord link to a staff account, in which case `actor_oid` is the linked `oid`; with the role but no link it is `UNVERIFIED` staff; the ticket's bound user is the player; any other human is external. A later role or link change MUST NOT alter recorded events.

#### Scenario: Linked staff with role
- **WHEN** a linked staff member with the role writes
- **THEN** the event is `VERIFIED` with their `oid`

#### Scenario: Role without link
- **WHEN** a member with the role but no link writes
- **THEN** the event is `UNVERIFIED` and is shown with the unverified badge

#### Scenario: Role removed later
- **WHEN** the role is removed after a message was recorded
- **THEN** the recorded event is unchanged

#### Scenario: Discord cannot decide
- **WHEN** a Discord author of any kind writes
- **THEN** no appeal decision, punishment or leak reissue is performed

### Requirement: Idempotent ingest
Ingest SHALL be idempotent by Discord message id with the dedupe keys `discord:<channelId>:<messageId>` (add), `discord:<channelId>:<messageId>:e:<text hash>` (edit) and `discord:<channelId>:<messageId>:d` (delete). A replay, an overlapping catch-up or a duplicate gateway event MUST insert nothing twice.

#### Scenario: Same batch twice
- **WHEN** the same batch is posted twice
- **THEN** one event per message exists

### Requirement: Edits and deletes become events
An edit SHALL append `message.edited` with the full new text only when it differs from the latest known text, and a delete SHALL append `message.deleted` keeping the last text readable to staff. A delete of a message that was never recorded MUST append `message.deleted` marked `contentUnavailable`.

#### Scenario: Unchanged text
- **WHEN** an update event carries the text already recorded
- **THEN** no `message.edited` is appended

#### Scenario: Unknown deleted message
- **WHEN** a delete arrives for an unrecorded message id
- **THEN** a `message.deleted` marked `contentUnavailable` is recorded

### Requirement: Attachments and embeds are metadata only
The system SHALL store, per attachment, the Discord attachment id, file name, content type, size and image dimensions, and per embed its type and URL host. It MUST NOT store the file, the Discord CDN URL or embed titles and descriptions. The player MUST be denied Attach Files in ticket channels. File names SHALL be part of redaction.

#### Scenario: Staff posts a file
- **WHEN** a staff member posts a file in the channel
- **THEN** the event holds name, type and size and no file bytes or URL

#### Scenario: Redaction covers file names
- **WHEN** a message with an attachment is redacted
- **THEN** the attachment metadata is removed with the text

#### Scenario: Portal view
- **WHEN** the player reads such a message in the portal
- **THEN** it shows the attachment type and size without a link

### Requirement: Catch-up at start
On start the bot SHALL read, for every ticket that is open, closing, closed or pending a channel, the channel history after the ticket's `ingested_cursor` in ascending batches and post it, so messages written while the bot, the backend or the broker was down are recorded.

#### Scenario: Bot was down
- **WHEN** three messages are written in the channel while the bot is stopped
- **THEN** after it starts all three are recorded once, in order

### Requirement: Full rescan at close
When a ticket closes the bot SHALL lock the channel and read the entire history, posting it with `complete: true`. The backend MUST insert missing messages, append `message.edited` for changed text and append `message.deleted` marked `detectedAtCatchup` for recorded messages that are no longer present.

#### Scenario: Edit while down
- **WHEN** a message was edited while the bot was down and the ticket closes
- **THEN** a `message.edited` with the new text exists

#### Scenario: Delete while down
- **WHEN** a recorded message was deleted while the bot was down
- **THEN** a `message.deleted` marked `detectedAtCatchup` exists and the last text is kept

### Requirement: Transcript completeness event
`POST /discord/tickets/{id}/close` SHALL append `ticket.transcript_complete` with the ticket id, the message count and the last Discord message id only when the supplied count equals the backend's own tally and the supplied last id is the highest recorded; otherwise it MUST answer 409 and append nothing. After three failed rescans the ticket MUST be marked closed without the event, the channel MUST NOT be deleted and the panel MUST show a banner.

#### Scenario: Complete
- **WHEN** the count and last id match
- **THEN** `ticket.transcript_complete` is on the timeline with both values

#### Scenario: Mismatch
- **WHEN** the count is one lower than the backend's tally
- **THEN** the response is HTTP 409 and no completeness event exists

#### Scenario: Incomplete ticket
- **WHEN** three rescans do not match
- **THEN** the ticket has no completeness event, the channel is kept and the panel shows a banner

### Requirement: The closed ticket becomes a protocol stored with the case
When a ticket closes and its transcript has been verified complete, `POST /discord/tickets/{id}/close` SHALL, in the same request that appends `ticket.transcript_complete`, generate the ticket's protocol from the stored case events (never from Discord), store it with the case as evidence of kind `TRANSCRIPT` as defined by `appeal-protocol`, and answer with the protocol version. If storing fails the request MUST answer HTTP 503 and the bot MUST NOT archive the channel.

#### Scenario: Complete ticket
- **WHEN** a ticket is closed with a matching count and last message id
- **THEN** `ticket.transcript_complete` and a protocol version are recorded and the answer names the version

#### Scenario: Storage fails
- **WHEN** the protocol cannot be stored
- **THEN** the close request answers HTTP 503 and the channel is not archived

#### Scenario: Incomplete transcript
- **WHEN** the completeness proof is refused
- **THEN** no protocol is created for the ticket

### Requirement: The Discord protocol equals the record
The protocol MUST be complete and human-readable with a machine-readable JSON, list every message with its author (players pseudonymised as on the case timeline, staff by display name), time, edits, deletes and attachment metadata, and carry the completeness check (count and last Discord message id). It is the same protocol as for a portal or panel appeal.

#### Scenario: Equals the record
- **WHEN** the protocol is compared with the case timeline of the ticket
- **THEN** every message, edit and delete of the timeline is in the protocol and nothing else

#### Scenario: Completeness check
- **WHEN** the protocol is read
- **THEN** it states the count and last message id of `ticket.transcript_complete`

### Requirement: Archive and deletion are gated on the protocol
The backend SHALL refuse to set a ticket to `ARCHIVED` unless a stored protocol exists, and to `DELETED` unless a stored protocol exists whose source digest is current, answering HTTP 409 `PROTOCOL_REQUIRED`. Before deleting a channel the bot SHALL call `POST /discord/tickets/{id}/protocol/ensure`, which regenerates the protocol idempotently and without the settle delay.

#### Scenario: Archive without protocol
- **WHEN** the bot asks to archive a ticket that has no protocol
- **THEN** the answer is HTTP 409 and the state is unchanged

#### Scenario: Delete with an outdated protocol
- **WHEN** a message was recorded after the last protocol version and the bot asks to delete
- **THEN** the answer is HTTP 409 until `ensure` has stored a new version

#### Scenario: Ensure is idempotent
- **WHEN** `ensure` is called twice for an unchanged ticket
- **THEN** one version exists

### Requirement: Late changes before deletion regenerate the protocol
A message, edit or delete recorded for a ticket after its protocol was stored (for example in the archived channel) SHALL lead to a new protocol version before the channel is deleted, and earlier versions MUST be kept as superseded until a redaction purges them. When Discord data is redacted or a participant is erased, the stored protocols MUST be purged and regenerated as defined by `appeal-protocol`.

#### Scenario: Late edit
- **WHEN** a staff member edits a message in the archived channel and the delete delay then passes
- **THEN** the bot's `ensure` call stores version 2 with the edit before the channel is deleted

#### Scenario: Participant erased
- **WHEN** an admin erases the Discord participant of a ticket with a stored protocol
- **THEN** the earlier versions become unreadable and the new version shows an anonymous author

### Requirement: History survives the channel
After a channel is archived or deleted, the conversation SHALL remain readable in the panel case page, with the stored protocol available for download, and, filtered to player-visible entries, in the portal, with no read from Discord. A channel deleted by a person before completion SHALL be recorded as `ticket.channel_deleted` with `transcriptComplete: false` and the last recorded message time.

#### Scenario: After deletion
- **WHEN** the channel has been deleted
- **THEN** the panel still shows every recorded message and the stored protocol, and the portal shows the player-visible ones

#### Scenario: Early manual deletion
- **WHEN** a person deletes an open ticket channel
- **THEN** the case records `ticket.channel_deleted` with `transcriptComplete` false and the panel shows a banner

### Requirement: Discord identifiers are erasable participants
Discord user ids and display names SHALL be stored only on a `case_participant` (and a staff link for staff), never in event payloads, domain events, logs, metrics, file names or the export; events reference the opaque participant id. An `ADMIN` MUST be able to erase a participant, which nulls the Discord id and name and marks it erased while the timeline structure remains. Message text SHALL store resolved display text, not raw mention ids.

#### Scenario: Erase the player's participant
- **WHEN** an admin erases the bound participant
- **THEN** the Discord id and name are gone, entries show an anonymous author and timestamps remain

#### Scenario: Mention in text
- **WHEN** a message mentions a user
- **THEN** the stored text contains the display name and no raw id

#### Scenario: Events carry no Discord id
- **WHEN** the events of a ticket are captured
- **THEN** none contains a Discord user id

### Requirement: Retention and export
The Discord transcript and its stored protocol SHALL be kept for as long as their case and punishment, pending legal confirmation, and SHALL be included in the admin transcript export with channel, author kind and trust, redaction markers and the ticket markers, without Discord identifiers.

#### Scenario: Export
- **WHEN** an admin exports a case with a Discord ticket
- **THEN** the file includes the Discord entries and `ticket.transcript_complete` and no Discord id

#### Scenario: Punishment deleted
- **WHEN** the punishment record is deleted
- **THEN** its participants, tickets, links and events are deleted with it
