## ADDED Requirements

### Requirement: Staff control message in the ticket channel

The bot SHALL keep one staff control message in each appeal ticket channel. It SHALL show the case reference, the ticket state, the claimed staff member and the buttons that the state allows: Claim, Accept, Reject, Lock or Unlock, Close and Open in panel. Accept and Reject SHALL be disabled once the appeal is decided. Close SHALL be shown only once the appeal is decided.

#### Scenario: Open appeal

- **WHEN** a ticket is open and undecided
- **THEN** the control message shows Claim, Accept, Reject, Lock and Open in panel, and no Close

#### Scenario: Decided appeal

- **WHEN** the appeal is accepted
- **THEN** Accept and Reject are disabled and Close is shown

### Requirement: Only linked and authorized staff act from Discord

A button press SHALL be attributed to the staff member's Entra object id found through a live Discord staff link. A press by a Discord user without a live link SHALL be refused with an ephemeral hint to run `/link`, and nothing SHALL change. The backend SHALL refuse the action when the staff member's role snapshot does not hold MODERATOR or ADMIN or is older than the configured window.

#### Scenario: Unlinked member presses Accept

- **WHEN** a Discord user without a live staff link presses Accept
- **THEN** only that user sees a hint to run `/link`, the appeal stays undecided, and no audit entry is written

#### Scenario: Stale role snapshot

- **WHEN** a linked staff member presses Accept and their last verified request was older than the window
- **THEN** the action is refused with `STAFF_ROLE_STALE` and the message asks them to sign in to the panel once

#### Scenario: Linked moderator accepts

- **WHEN** a linked member with a fresh MODERATOR snapshot accepts an appeal from the channel
- **THEN** the decision is recorded with that member as the actor, the bot is `recorded_by`, and the player is notified as for a panel decision

### Requirement: Same result as the panel

Each Discord action SHALL call the backend route that the panel uses for the same action, and it SHALL produce the same case events, the same player notification and the same validation as the panel. Reject SHALL require a non-empty player message that passes the backend's checks, with the same length limit as the panel; the backend's refusal text SHALL be shown in the modal.

#### Scenario: Reject without a message

- **WHEN** a staff member submits the Reject modal with an empty message
- **THEN** the modal refuses to submit and nothing is sent

#### Scenario: Message with a Ban ID

- **WHEN** the player message contains a Ban ID
- **THEN** the backend answers 422, nothing is stored, and the modal shows the refusal text

#### Scenario: Self-review

- **WHEN** the staff member who issued the punishment accepts its appeal from Discord
- **THEN** the backend refuses with 403, as it does in the panel

### Requirement: Lock and unlock of a ticket

Lock and Unlock SHALL set and clear a staff freeze of the ticket's channel. A staff lock SHALL survive a leak thaw. A leak freeze SHALL NOT be lifted by Unlock. Repeating a lock or an unlock SHALL change nothing.

#### Scenario: Lock survives a leak thaw

- **WHEN** a staff member locks a ticket and the leak path later thaws the ticket
- **THEN** the channel stays locked until a staff member unlocks it

#### Scenario: Unlock of a leak freeze

- **WHEN** a staff member presses Unlock on a ticket frozen by a leak
- **THEN** the action is refused with 409 `FREEZE_BY_LEAK` and the channel stays locked

#### Scenario: Repeated lock

- **WHEN** Lock is pressed on a ticket that is already locked by staff
- **THEN** nothing changes and the message shows the locked state

### Requirement: Claim assigns the case

Claim SHALL set the case assignee to the attributed staff member through the case update the panel uses, with the version the bot read just before. A version conflict SHALL be shown as a conflict and nothing SHALL change.

#### Scenario: Claim while someone else edits

- **WHEN** a staff member presses Claim and the case changed since the bot read it
- **THEN** the claim is refused with a conflict message and the assignee is unchanged

### Requirement: Close after the decision

Close SHALL use the existing ticket close flow with the completeness proof and the protocol gate, recorded under the attributed staff member. A close before the appeal is decided SHALL be refused.

#### Scenario: Close before decision

- **WHEN** Close is requested for an undecided appeal
- **THEN** the backend refuses with `INVALID_STATE` and no staff member is recorded as closing it

### Requirement: Panel and Discord stay in sync

An action in the panel SHALL update the control message of the ticket channel within the delivery time of the case event. An action in Discord SHALL appear in the panel through the case's live stream. The control message SHALL be a view of the backend state and SHALL NOT hold a state of its own.

#### Scenario: Panel accepts

- **WHEN** a moderator accepts the appeal in the panel
- **THEN** the control message is re-rendered with the decided state and Accept and Reject are disabled

#### Scenario: Discord locks

- **WHEN** a staff member locks the channel from Discord
- **THEN** the panel's case page shows the lock without a reload

### Requirement: Failures change nothing

When the backend cannot be reached or answers a server error, the bot SHALL answer the press with an ephemeral message that the action was not done, and SHALL NOT change the control message. A refused action SHALL show the backend's text and re-render the control message from the current state.

#### Scenario: Backend down

- **WHEN** a staff member presses Accept while the backend is unreachable
- **THEN** the press answers "not done, try again", the appeal stays undecided, and the control message is unchanged

#### Scenario: Already decided

- **WHEN** two staff members press Accept and the second press arrives after the decision
- **THEN** the second press is refused with 409, the message shows the decided state, and no second decision is recorded

### Requirement: Open in panel link

The control message SHALL carry a link button to the case page of the team panel, built from the configured panel base URL and the case short id. The link SHALL NOT carry a Ban ID.

#### Scenario: Link target

- **WHEN** the control message is rendered for case `AB12CD34`
- **THEN** the Open in panel button links to `<panelUrl>/cases/AB12CD34`

### Requirement: Every Discord staff action is audited

Every successful Discord staff action SHALL write one audit entry with surface `DISCORD`, the attributed staff member as actor and the bot as `recorded_by`. A refused action SHALL write no entry.

#### Scenario: Accept from Discord

- **WHEN** a linked moderator accepts an appeal from Discord
- **THEN** one audit entry with surface `DISCORD` and action `appeal.review` is written

#### Scenario: Refused press

- **WHEN** a press is refused for an unlinked member
- **THEN** no audit entry is written
