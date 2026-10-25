## MODIFIED Requirements

### Requirement: The backend is the record of the conversation
Every player and staff message SHALL be persisted as a `message.added` event on the punishment's case at the moment it is written, whichever channel it came from (`PORTAL`, `PANEL`, `DISCORD`). A message MUST NOT exist only in an external system, and a failure to persist a portal or panel message MUST fail that request (no silent loss). A staff message SHALL be written only when the author has a display name; the event SHALL carry a snapshot of that name in `author.staffDisplayName` and SHALL have `payloadVersion` 2.

#### Scenario: Portal message
- **WHEN** a player posts a message in the portal
- **THEN** the case has a `message.added` event with channel `PORTAL`, author kind `PLAYER` and visibility `PLAYER`

#### Scenario: Panel reply
- **WHEN** a named moderator replies in the panel
- **THEN** the case has a `message.added` event with channel `PANEL`, author kind `STAFF`, the moderator's verified `oid`, the name snapshot and visibility `PLAYER`

#### Scenario: Persistence failure
- **WHEN** storing a portal message fails
- **THEN** the player receives an error and no event is partially written

#### Scenario: Unnamed staff reply
- **WHEN** a staff member without a display name posts a reply
- **THEN** the request is refused with code `STAFF_PROFILE_REQUIRED` and no event is written

### Requirement: Redaction and erasure
Text redaction SHALL remove the message text, and erasure of a staff member's name SHALL remove the name snapshot from their `message.added` events. Both SHALL keep the event, its type, time, author kind, visibility and sequence number, and SHALL append a marker event.

#### Scenario: Name removed on anonymisation
- **WHEN** a staff member is anonymised
- **THEN** the `staffDisplayName` of each of that member's events is null and the events keep their order and visibility

### Requirement: Events carry no content
Message events SHALL carry no text and no staff display name in the event published to the bus. Only identifiers, enums and the sequence number SHALL be published.

#### Scenario: Published event
- **WHEN** a staff message is recorded
- **THEN** the published `case.message_added` event contains no display name and no text
