## MODIFIED Requirements

### Requirement: Player copy of the protocol
A player session with scope `FULL` SHALL be able to download the protocol of its decided appeal through `GET /public/appeal/protocol?format=text|json`, rendered on demand from the conversation the portal already shows. The response MUST be `no-store` and an attachment, and a `LIMITED` session MUST be refused. The copy SHALL show staff entries by the display name recorded with each message, and SHALL carry the one-sentence notice about recorded staff names in its header, in the language of the copy.

#### Scenario: Player downloads
- **WHEN** a player with a decided appeal requests the text copy
- **THEN** it lists exactly the entries the portal conversation shows, with staff by their recorded display names

#### Scenario: Not yet decided
- **WHEN** the appeal is still open
- **THEN** the route answers HTTP 409

#### Scenario: Leaked Ban ID
- **WHEN** a `LIMITED` session requests the copy
- **THEN** the response is HTTP 403

### Requirement: The player copy is player-visible only
The player copy SHALL use the same visibility filter and type allow-list as the portal conversation, label the player "You", show staff by their recorded display name, show attachments as type and size, and show redacted and deleted entries as removed. A staff entry without a recorded name SHALL be labelled "Staff". The stored staff protocol, its versions and `STAFF`-visibility entries MUST NOT be reachable from a player session.

#### Scenario: Staff-only entry
- **WHEN** the conversation contains a `STAFF`-visibility bot message
- **THEN** the player copy does not contain it

#### Scenario: Named staff
- **WHEN** a staff member with the display name "Mia K." replied
- **THEN** the player copy shows "Mia K." and no Entra name, oid or Minecraft UUID

#### Scenario: Legacy message
- **WHEN** a staff message was written before names were recorded
- **THEN** the player copy labels it "Staff"

## ADDED Requirements

### Requirement: Guided appeal flow
The portal SHALL present login, the appeal terms, the appeal and the chat as one guided four-step flow on mobile and desktop. The server state SHALL decide the entry step. The player MAY go back until the appeal is filed. The current step SHALL be marked in a stepper.

#### Scenario: Resume after filing
- **WHEN** a player with a filed appeal logs in again
- **THEN** the chat opens directly

#### Scenario: Step focus
- **WHEN** the player moves from the terms to the appeal step
- **THEN** the appeal step's heading receives focus

#### Scenario: Back before filing
- **WHEN** the player goes back to the terms before filing
- **THEN** the terms step is shown with the earlier checkbox state kept

### Requirement: Chat with names and avatars
The portal chat SHALL show, for every staff message, the staff member's recorded display name and avatar: the avatar and name appear on the first message of each run of messages by the same staff member, and every staff message has an accessible name. A staff member without a recorded name SHALL be shown as "Staff". The portal SHALL render the avatar from the `avatarKey` it receives (`/api/avatars/{key}`), and SHALL render initials locally when the key is null.

#### Scenario: Two staff members
- **WHEN** two staff members reply in turn
- **THEN** each reply shows its own name and avatar

#### Scenario: Initials
- **WHEN** a staff message has no avatar key
- **THEN** initials are shown and no request is made for an image

#### Scenario: Legacy
- **WHEN** a staff message has no recorded name
- **THEN** the label "Staff" is shown

### Requirement: Older messages and new messages
The chat SHALL load the newest page of messages first, SHALL load older messages with a "Load older messages" action at the top of the thread, and SHALL poll only for messages with a sequence number after the last one shown.

#### Scenario: Poll fetches only new messages
- **WHEN** the chat polls
- **THEN** the request asks for messages after the last sequence number shown

#### Scenario: Load older
- **WHEN** the player activates "Load older messages"
- **THEN** the earlier page is added above the newest messages and focus stays on the action

### Requirement: Portal errors
The portal SHALL show every failure as the translated message for its code when the code is known, and otherwise as the generic message for the status, never as raw server text. A stream or read failure that is not a reconnect SHALL be shown in place with a retry action.

#### Scenario: Unknown refusal code
- **WHEN** the chat refuses a message with a code the portal does not know
- **THEN** the generic message is shown, not a translation key

#### Scenario: Stream failure
- **WHEN** the live connection fails and does not reconnect
- **THEN** a message with a retry action is shown in the thread

### Requirement: Portal design system
The portal SHALL use the same design tokens as the panel (one token source, see `redesign-ux` design section 3), the same spacing and type roles, the same button and chip variants and the same empty, loading and error states. The font SHALL be Roboto.

#### Scenario: Token parity
- **WHEN** the portal's generated token file is compared with the token source
- **THEN** they are equal

#### Scenario: Language parity
- **WHEN** the i18n test runs
- **THEN** both languages contain the same keys, including the new staff, flow and error keys
