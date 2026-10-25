## MODIFIED Requirements

### Requirement: Author attribution
A message SHALL be `VERIFIED` staff only when its author has the staff role at the time of writing and a live Discord link to a staff account, in which case `actor_oid` is the linked `oid`; with the role but no link it is `UNVERIFIED` staff; the ticket's bound user is the player; any other human is external. A later role or link change MUST NOT alter recorded events. The Discord channel SHALL show a verified staff message under the staff member's display name with the avatar as the author icon, and SHALL show an unverified message as "Unverified staff" with the Discord name.

#### Scenario: Linked staff with role
- **WHEN** a linked staff member with the role and a display name writes
- **THEN** the event is `VERIFIED` with their `oid` and the channel shows the display name and avatar

#### Scenario: Role without link
- **WHEN** a member with the role but no link writes
- **THEN** the event is `UNVERIFIED` and the channel shows "Unverified staff" with the Discord name

#### Scenario: Role removed later
- **WHEN** the role is removed after a message was recorded
- **THEN** the recorded event is unchanged

#### Scenario: Discord cannot decide
- **WHEN** a Discord author of any kind writes
- **THEN** no appeal decision, punishment or leak reissue is performed

## ADDED Requirements

### Requirement: Staff messages in the ticket channel use the staff identity
The bot SHALL post each verified staff message to the ticket channel as an embed whose author is the staff member's display name and whose author icon is the avatar URL on the appeal host, and whose footer carries the case reference. The embed SHALL use the English tone and sentence case of the bot's player-visible messages.

#### Scenario: Embed author
- **WHEN** a verified staff member with the display name "Mia K." replies in the panel
- **THEN** the channel embed's author is "Mia K." and its icon is the avatar URL

#### Scenario: No staff name in the channel for unverified staff
- **WHEN** an unverified staff message is posted
- **THEN** the embed author is "Unverified staff" and no icon is set

### Requirement: Unnamed staff replies are held back
A message from a linked staff member without a display name SHALL be stored with `STAFF` visibility, SHALL NOT reach the player, and the bot SHALL reply in the ticket channel that a display name must be set in the panel first.

#### Scenario: Unnamed linked staff
- **WHEN** a linked staff member without a display name writes in a ticket channel
- **THEN** the message is stored with `STAFF` visibility and the player's portal does not show it

### Requirement: Avatar URLs contain no identifiers
The avatar URL sent to Discord SHALL be built from the avatar key only and SHALL NOT contain a Minecraft UUID, a Discord id or a Ban ID.

#### Scenario: URL inspection
- **WHEN** an avatar URL is produced for a staff member
- **THEN** it has the form of the appeal host plus the avatar key
