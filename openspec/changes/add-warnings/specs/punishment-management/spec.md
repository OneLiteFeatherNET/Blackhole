## ADDED Requirements

### Requirement: Warning punishment kind

The punishment kind set SHALL include `WARN` in addition to the kinds of `add-kick`. A punishment of kind `WARN` MUST NOT be read, expired, revoked or mirrored as an active punishment: active-state reads, expiry, revocation, profile slot placement and the proxy mirror SHALL consider only kind `PUNISHMENT`.

#### Scenario: Revocation of a warning

- **WHEN** staff try to revoke a warning through the active-punishment revoke route
- **THEN** the request is refused with 409 and nothing changes; a warning is lifted only by an appeal

#### Scenario: Warning kind is excluded from active reads

- **WHEN** the active punishments of a player with only a warning are read
- **THEN** the list is empty

### Requirement: Warning template step

A template step of kind `WARN` SHALL apply as a warning: a row of kind `WARN` with the template's reason, no duration and no slot. Its `eloCost` SHALL apply as for any template step, and it SHALL count towards escalation of its group, within the template's `warnWindow` when one is set.

#### Scenario: Warning step creates no ban

- **WHEN** a template whose first step is `WARN` is applied to a player
- **THEN** a warning row is created, the player's active slots are unchanged and no proxy mirror entry is written

#### Scenario: Warning step with a duration

- **WHEN** a template with a `WARN` step carries a duration
- **THEN** the template is refused with 400

#### Scenario: Escalation from warning to kick

- **WHEN** a template has steps `WARN`, `KICK` and `PUNISH P1D`, and a player has one warning in the group within the window
- **THEN** the next application selects the `KICK` step

### Requirement: Warning domain events

The backend SHALL publish `punishment.warned` for each warning after the write, with the payload defined by `player-warning`. A failed publish SHALL NOT fail the write, and the warning stays pending until acknowledged.

#### Scenario: Publish failure

- **WHEN** the NATS publish fails after a warning is written
- **THEN** the warning is kept, the failure is logged without the reason text, and the request still succeeds
