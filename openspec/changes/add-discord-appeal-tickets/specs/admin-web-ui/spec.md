# Spec Delta

## Purpose
Shows the Discord ticket state in the case page and lets staff link their Discord account.

## ADDED Requirements

### Requirement: Ticket status on the case page
The case page SHALL show, for each Discord ticket of the case, its number, state, transcript status and protocol version (with a link to the protocol list of the case), a banner when a ticket has no `ticket.transcript_complete` or no stored protocol after closing or lost its channel early, and, for Discord-written entries, the channel and author trust (verified, unverified, player, bot). It MUST NOT show Discord user ids.

#### Scenario: Incomplete transcript
- **WHEN** a closed ticket has no completeness event
- **THEN** a banner states that the transcript may be incomplete and since when

#### Scenario: Protocol missing
- **WHEN** a closed ticket is complete but has no stored protocol
- **THEN** a banner states that the channel is not archived or deleted until the protocol exists

#### Scenario: Entry from Discord
- **WHEN** the timeline holds a Discord message by unverified staff
- **THEN** it is labelled Discord and unverified, with no Discord id

### Requirement: Link a Discord account
The account-link dialog SHALL explain that the same code can be used in Discord with `/link`, and show the linked state and an unlink action for Discord next to the Minecraft link.

#### Scenario: Show state
- **WHEN** a staff member has linked Discord
- **THEN** the dialog shows it as linked with an unlink action

### Requirement: Participant erasure
`ADMIN` SHALL be able to erase a Discord participant from the case page with a confirmation; the entry then shows an anonymous author.

#### Scenario: Erase
- **WHEN** an admin confirms the erasure of the player participant
- **THEN** the entries remain with an anonymous author
