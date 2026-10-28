## ADDED Requirements

### Requirement: Appeals apply to the amended state

An amendment MAY be made while an appeal is open. A decision SHALL apply to the punishment as it stands at decision time. An amendment SHALL NOT change the appeal's status, and a granted full lift after an amendment SHALL still lift the punishment.

#### Scenario: Full lift after shortening

- **WHEN** a ban is shortened while an appeal is open and the reviewer then grants a full lift
- **THEN** the punishment is lifted and the appeal is `GRANTED_FULL_LIFT`

#### Scenario: Review sees the change

- **WHEN** a reviewer opens an appeal whose punishment was amended
- **THEN** the review shows the current expiry and the amendment history of the punishment

### Requirement: Minimum wait is not restarted by an amendment

The minimum wait period SHALL be measured from the creation of the punishment. An amendment SHALL NOT restart it.

#### Scenario: Extension does not restart the wait

- **WHEN** a manual ban created 20 days ago is extended today and the player appeals
- **THEN** the minimum-time criterion passes with 20 days elapsed

### Requirement: Severity is read at review time

The severity tier SHALL be read from the punishment's state at review time, not from the checklist snapshot taken at submission. A temporary ban made permanent by amendment SHALL be refused a full lift with 400, as any permanent network ban is. A permanent ban made temporary SHALL be eligible for a full lift. The checklist snapshot SHALL keep the tier at submission, and the review SHALL record the tier it used.

#### Scenario: Ban made permanent during an appeal

- **WHEN** a temporary ban is made permanent by an admin while its appeal is open, and the reviewer grants a full lift
- **THEN** the response is 400 and the punishment remains in force

#### Scenario: Permanent ban made temporary during an appeal

- **WHEN** a permanent ban is made temporary by an admin while its appeal is open, and the reviewer grants a full lift
- **THEN** the full lift is applied
