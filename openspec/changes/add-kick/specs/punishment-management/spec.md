## ADDED Requirements

### Requirement: Punishment kind

Every punishment SHALL have a kind, `PUNISHMENT` for bans, mutes and server punishments (existing rows default to it) or `KICK` for a kick. Active-state reads, expiry, revocation, profile slot placement and the proxy mirror MUST consider only kind `PUNISHMENT`.

#### Scenario: Existing rows

- **WHEN** the migration runs over existing punishments
- **THEN** every row has kind `PUNISHMENT` and no row is rewritten

#### Scenario: Revocation of a kick

- **WHEN** staff try to revoke a kick as if it were an active punishment
- **THEN** the request is refused with 409 and nothing changes

### Requirement: Template step kind

A template step SHALL have a kind, `PUNISH` (default, with a duration as today) or `KICK` (no duration). A `KICK` step with a duration, or a `PUNISH` step without a valid duration, MUST be refused by template validation.

#### Scenario: Existing template

- **WHEN** a template created before this change is read
- **THEN** each step has kind `PUNISH` and its duration and ELO cost are unchanged

#### Scenario: Kick step with duration

- **WHEN** a template with a `KICK` step carries a duration
- **THEN** the template is refused with 400

### Requirement: Escalation counts kicks

Escalation SHALL count an earlier kick of the same escalation group within the window, as it counts an earlier punishment. A kick MUST NOT be excluded from escalation counting because it is not active.

#### Scenario: Kick counts as the first step

- **WHEN** a player has one kick in the group and the template has steps `KICK` then `PUNISH P1D`
- **THEN** the next application selects step 2

### Requirement: Kick domain events

The backend SHALL publish `punishment.kick` for each kick and `kick.mass` for each mass kick, after the write, with the payload defined by `player-kick`. A failed publish SHALL NOT fail the write.

#### Scenario: Publish failure

- **WHEN** the NATS publish fails after the kick is written
- **THEN** the kick is kept, the failure is logged without the reason text, and the request still answers 202
