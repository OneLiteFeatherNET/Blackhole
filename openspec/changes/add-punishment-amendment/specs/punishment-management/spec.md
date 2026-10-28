## ADDED Requirements

### Requirement: Punishment version

Every punishment SHALL carry a `version` counter that starts at 0 and increases by one on every write that changes the row: an amendment, a revoke, a lift, an expiry and a template link. The version SHALL be returned on punishment reads and SHALL be the value of the punishment's `ETag`.

#### Scenario: Revoke increments the version

- **WHEN** a temporary ban at version 2 is revoked
- **THEN** the revoked punishment is at version 3

#### Scenario: Existing rows

- **WHEN** the migration runs over existing punishments
- **THEN** every row has version 0 and no row is otherwise rewritten

### Requirement: Amendment is recorded in the case and published

An amendment SHALL be recorded as `punishment.amended` on the punishment's case and as `punishment.updated` on the event bus, as defined by `punishment-amendment`. The apply, revoke and expiry paths SHALL keep their existing events.

#### Scenario: Revoke keeps its event

- **WHEN** a punishment is revoked after an amendment
- **THEN** `punishment.revoked` is published as before
