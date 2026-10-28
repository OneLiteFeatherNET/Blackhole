## ADDED Requirements

### Requirement: Lock and unlock on the case page

The case page SHALL offer Lock and Unlock of the appeal ticket's channel to MODERATOR and ADMIN users when the case has a ticket. Unlock SHALL NOT be offered for a freeze made by a leak; the control SHALL say why. A conflict or refusal from the server SHALL be shown with the server's text.

#### Scenario: Moderator locks

- **WHEN** a moderator presses Lock on a case with an open ticket
- **THEN** the request is sent and the control shows the locked state

#### Scenario: Leak freeze

- **WHEN** the ticket is frozen by a leak
- **THEN** Unlock is not offered and the control explains that the freeze was made by the leak check

### Requirement: Discord ticket state on the case page

The case page SHALL show the state of the Discord control message next to the ticket status: the staff member who claimed the case, whether the channel is locked and whether the appeal is decided.

#### Scenario: Claimed from Discord

- **WHEN** a staff member claims the case from Discord
- **THEN** the case page shows that staff member as the assignee without a reload
