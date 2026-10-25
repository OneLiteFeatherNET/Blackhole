## ADDED Requirements

### Requirement: Kick dialog on the player page and the case page

The panel SHALL offer "Kick" from the player row menu, the player page header and the case page actions. The dialog SHALL have a scope picker (network, server or section), a target select for server or section, and a reason field of 1 to 200 characters. After submit it SHALL show the outcome as a chip: `Delivered`, `Not on that server`, `Not online` or `No proxy answered`.

#### Scenario: Kick a player from the network

- **WHEN** a moderator submits the dialog with scope network and a reason
- **THEN** the request carries an `Idempotency-Key`, the dialog polls the status until it is settled, and the chip shows `Delivered` or `Not online`

#### Scenario: Reason validation

- **WHEN** the reason is empty or contains an IP address
- **THEN** submit is disabled and the field explains why

### Requirement: Mass kick in the admin area

The admin area SHALL offer a mass kick form to ADMIN users only, with target and reason. The form SHALL require the target name to be typed back before submit and SHALL show the moved count when the outcome is known. The server enforces the role regardless of the panel.

#### Scenario: Moderator sees no mass kick

- **WHEN** a MODERATOR opens the admin area
- **THEN** the mass kick form is not shown

#### Scenario: Typed confirmation

- **WHEN** an ADMIN has not typed the target name exactly
- **THEN** submit stays disabled

### Requirement: Kick rows in history

The player history SHALL show kick rows with a "Kick" badge. Kick rows MUST NOT be counted in "active" counters.

#### Scenario: Active counter

- **WHEN** a player has one kick and no active punishment
- **THEN** the active counter shows zero
