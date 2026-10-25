## ADDED Requirements

### Requirement: Staff display name
Each staff account SHALL set its display name through `PUT /staff-profile/me`. The name SHALL be 2 to 32 characters after trimming and NFKC normalisation, SHALL use only the allowed characters, and SHALL NOT be a URL, IP address, UUID, Ban ID lookalike or reserved word. The default SHALL be empty, and the Entra `name` claim MUST NOT be copied into it.

#### Scenario: Valid name
- **WHEN** a staff member saves `Mia K.`
- **THEN** the profile holds that name and it is returned by `GET /staff-profile/me`

#### Scenario: Reserved word
- **WHEN** a staff member saves `Moderation`
- **THEN** the response is 422 with code `NAME_NOT_ALLOWED` and nothing is stored

#### Scenario: No default from the token
- **WHEN** a staff member with an Entra name "Max Muster" has never saved a profile
- **THEN** the display name is empty

### Requirement: Unique display names
Two named profiles MUST NOT have the same display name after case folding and normalisation. A conflicting save SHALL answer 409 with code `NAME_TAKEN` and SHALL store nothing.

#### Scenario: Taken name
- **WHEN** another staff member already uses `mia k.` and a second member saves `MIA K.`
- **THEN** the response is 409 with code `NAME_TAKEN`

### Requirement: Name required before writing to players
A staff account without a display name MUST NOT write anything a player can see. The writes covered are appeal chat messages, appeal decision texts with a player message, shared-evidence cards, and replies from a linked Discord account. Each such write SHALL answer 409 with code `STAFF_PROFILE_REQUIRED` and SHALL store nothing visible to the player. Reading, reviewing, deciding without a player message and dismissing reports are not affected.

#### Scenario: Chat reply blocked
- **WHEN** a staff member without a name posts a chat message
- **THEN** the response is 409 with code `STAFF_PROFILE_REQUIRED` and no message is written

#### Scenario: Decision without a message
- **WHEN** a staff member without a name grants an appeal without a player message
- **THEN** the decision is recorded and no name is required

#### Scenario: Discord reply from an unnamed linked account
- **WHEN** a linked staff member without a name writes in a ticket channel
- **THEN** the message is stored with `STAFF` visibility, is not shown to the player, and the bot replies in the channel that a name is needed

### Requirement: Avatar choice
A staff account SHALL choose the avatar mode `AUTO` or `INITIALS`. In `AUTO` mode a linked account SHALL show the head produced from its linked Minecraft account, and an unlinked account SHALL show initials. In `INITIALS` mode every account SHALL show initials. Initials SHALL be rendered without contacting any external service.

#### Scenario: Linked account, AUTO
- **WHEN** a linked staff member with `AUTO` mode is shown
- **THEN** the avatar key of its head is returned and no Minecraft UUID is returned

#### Scenario: Unlinked account
- **WHEN** an unlinked staff member is shown
- **THEN** the avatar key is null and initials are used

### Requirement: Avatars without leaking identifiers
The head SHALL be produced and cached by the backend from the linked Minecraft account, keyed by an HMAC of the UUID, and served through `GET /public/staff-avatar/{avatarKey}`. The response SHALL be a PNG with `Cache-Control: public, max-age=86400` and SHALL carry no cookie. A player's browser SHALL NOT contact a third party for an avatar, and no response SHALL contain a Minecraft UUID.

#### Scenario: Unknown key
- **WHEN** the avatar route is requested with an unknown key
- **THEN** the response is 404 with no body detail

#### Scenario: No UUID in output
- **WHEN** any player-facing response is inspected
- **THEN** no Minecraft UUID appears in it

### Requirement: Staff notice
Before a display name is saved the account holder SHALL acknowledge a notice that players see the display name and the avatar in the appeal chat, in the protocol and in the Discord ticket, that the name is recorded with the messages, and that the avatar can be changed to initials. The acknowledgement time SHALL be stored.

#### Scenario: Save without acknowledgement
- **WHEN** a staff member saves a name without acknowledging the notice
- **THEN** the response is 422 and nothing is stored

### Requirement: Retention of the staff profile
The display name and avatar key of a staff profile SHALL be kept as long as a stored record names that staff member. The avatar cache SHALL be deleted when the account is unlinked or anonymised, and SHALL be refreshed at most weekly. The retention period is pending legal confirmation.

#### Scenario: Unlink removes the avatar
- **WHEN** a staff account is unlinked from its Minecraft account
- **THEN** its cached head is deleted and its avatar is initials from then on
