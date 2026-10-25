## MODIFIED Requirements

### Requirement: Chat interface
The portal and the panel SHALL present the conversation as a chat: own messages on one side, the other side for the other party, grouped, with timestamps, day separators, system markers, thumbnails with a lightbox, file cards for PDF and text, and a composer with attach button, drag and drop, paste, per-file state and a counter. Send MUST stay disabled until every file is ready. A staff message SHALL show the staff member's recorded display name and avatar to the player, on the first message of each run by the same staff member; the panel SHALL show the same name and avatar with the staff member's verification status as a separate badge. Drafts stay in memory only. Player-facing strings are English in the chat, as decided for `add-appeal-chat`.

#### Scenario: Attach by paste
- **WHEN** a player pastes an image into the composer
- **THEN** a chip appears, goes through uploading and scanning to ready, and Send becomes enabled

#### Scenario: Rejected file
- **WHEN** the server rejects a file
- **THEN** its chip shows the fixed reason text and Send ignores that chip

#### Scenario: Staff name shown to the player
- **WHEN** a verified staff member with the display name "Mia K." replies
- **THEN** the player sees "Mia K." and that member's avatar, and no Entra name

#### Scenario: Staff without a name
- **WHEN** a staff message has no recorded name
- **THEN** the player sees "Staff" and no avatar

## ADDED Requirements

### Requirement: Shared evidence card names the sharer
A shared-evidence card in the chat SHALL name the staff member who shared the evidence, using the display name recorded for that share, instead of "the moderation team".

#### Scenario: Share card
- **WHEN** a staff member with the display name "Mia K." shares a chat excerpt
- **THEN** the card reads "Shared by Mia K."

### Requirement: Staff identity in the panel chat
The panel SHALL show the staff member's display name and avatar in one author block per message, instead of the first eight characters of the `oid`. The name SHALL be the stored name of the message, and the panel SHALL NOT show the `oid` as a name.

#### Scenario: Author block
- **WHEN** a staff message is rendered in the panel
- **THEN** the author block shows the avatar, the name and the verification badge

#### Scenario: Legacy message in the panel
- **WHEN** a staff message has no stored name
- **THEN** the panel shows "Staff member (no name yet)"
