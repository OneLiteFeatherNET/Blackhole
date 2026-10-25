## MODIFIED Requirements

### Requirement: Protocol authors and excluded data
Players MUST be shown under their pseudonymous case label (as on the case timeline, with the opaque participant id in the JSON). Staff members MUST be shown by the display name recorded with each message in protocol version 2; in protocol version 1 they keep the labels they had. Unverified staff and other bots are shown with their kind label. The protocol MUST NOT contain a Ban ID, a Discord user id, a Minecraft UUID, an owner hash or an Entra name, and internal notes are excluded.

#### Scenario: Author labels in version 2
- **WHEN** a player message and a verified staff message are listed in a version 2 protocol
- **THEN** the player appears under the case's pseudonymous label and the staff member under the recorded display name

#### Scenario: Version 1 is unchanged
- **WHEN** a stored version 1 protocol is read
- **THEN** its staff labels are exactly the stored ones and it is not regenerated

#### Scenario: No identifiers
- **WHEN** a version 2 protocol is captured in a test with a known Ban ID, Discord user id, Minecraft UUID and owner hash
- **THEN** none of them occurs in either rendering

## ADDED Requirements

### Requirement: Protocol version 2 carries the staff notice
Protocol version 2 SHALL state in its header, in the language of the document, that staff members appear by the display name they chose and that their names are recorded in the chat. The wording is pending legal confirmation.

#### Scenario: Header
- **WHEN** a version 2 text protocol is rendered
- **THEN** its header contains the staff notice

### Requirement: Regeneration after staff anonymisation
When a staff member is anonymised, every stored protocol that names them SHALL be regenerated as a new version with the name removed, and the earlier versions SHALL be marked superseded so that the old name is no longer served.

#### Scenario: Anonymised moderator
- **WHEN** a staff member who appears in two stored protocols is anonymised
- **THEN** both protocols get a new version without the name and the superseded versions are not served
