## ADDED Requirements

### Requirement: Stored unattended settings
The unattended settings SHALL be stored in one database row: the unattended level, the absence threshold in whole minutes, the night window (enabled flag, start and end time, end exclusive), and the IANA time zone in which the night window is read. The evaluator SHALL read the stored settings on every tick, and SHALL NOT read the `blackhole.guard.unattended.*` configuration once the row exists.

#### Scenario: A stored setting replaces the configured value
- **WHEN** the row holds an absence threshold of 45 minutes and `blackhole.guard.unattended.absence-threshold` is 20 minutes
- **THEN** the evaluator uses 45 minutes

#### Scenario: The row is seeded on first read
- **WHEN** the settings row does not exist and the first read happens
- **THEN** the row is created from the `blackhole.guard.unattended.*` values, and a second read returns the same values without writing again

#### Scenario: Two replicas seed at the same time
- **WHEN** two replicas read a missing row at the same moment
- **THEN** both reads succeed, and the row holds one set of values

### Requirement: Bounds of the unattended settings
The unattended level SHALL be `STRICT` or `LOCKDOWN`, and SHALL NOT be `OFF`. The absence threshold SHALL be a whole number of minutes from 5 to 240 inclusive. The night window times SHALL be `HH:mm` values, and the start SHALL NOT equal the end. The time zone SHALL be an IANA region id known to the runtime; an offset such as `+01:00` SHALL be refused. A value outside these bounds SHALL be refused with the `VALIDATION` kind, and nothing SHALL be written.

#### Scenario: OFF is refused as the unattended level
- **WHEN** an admin sends `unattendedLevel` `OFF`
- **THEN** the response is `400` with kind `VALIDATION`, and the stored settings are unchanged

#### Scenario: The absence threshold has a lower bound
- **WHEN** an admin sends an absence threshold of 4 minutes
- **THEN** the response is `400` with kind `VALIDATION`

#### Scenario: The absence threshold accepts its bounds
- **WHEN** an admin sends an absence threshold of 5 minutes, and then of 240 minutes
- **THEN** both are accepted

#### Scenario: A night window may cross midnight
- **WHEN** an admin enables the night window from `23:00` to `02:00`
- **THEN** the window is accepted and applies from 23:00 until 02:00 the next day

#### Scenario: A night window with equal start and end is refused
- **WHEN** an admin sends a night window whose start and end are both `03:00`
- **THEN** the response is `400` with kind `VALIDATION`

#### Scenario: An offset is not a time zone
- **WHEN** an admin sends the time zone `+01:00`
- **THEN** the response is `400` with kind `VALIDATION`, and the message asks for an IANA region id such as `Europe/Berlin`

### Requirement: Read and write of the settings
The settings SHALL be readable by every role that may read the guard, that is VIEWER and above. Only ADMIN SHALL replace them. The read SHALL include the next night window as epoch milliseconds, computed from the injected clock and the configured zone, or null for both when the window is disabled. A window that is open at the time of the read SHALL be returned as the current window, with its start in the past.

#### Scenario: A moderator cannot change the settings
- **WHEN** a moderator sends a valid settings change
- **THEN** the response is `403` and nothing is written or published

#### Scenario: A viewer reads the settings
- **WHEN** a viewer reads the settings while the night window is 01:00-07:00 Europe/Berlin and the clock is at 00:30 local time
- **THEN** the response holds the next night window starting at 01:00 local time that day and ending at 07:00

#### Scenario: A disabled window has no next window
- **WHEN** the night window is disabled
- **THEN** the next night window fields are null

### Requirement: Changes take effect on the next tick on every replica
A saved change SHALL be evaluated by the next scheduled tick of the unattended evaluator, and every replica SHALL reload the effective level in that tick. A change SHALL therefore be in force at most one tick interval (`blackhole.guard.tick-interval`, 30 seconds by default) after it was saved.

#### Scenario: Shortening the absence threshold
- **WHEN** staff have been absent for 8 minutes and an admin saves an absence threshold of 5 minutes
- **THEN** the automatic level becomes the unattended level at the next tick

#### Scenario: Disabling the night window while it is open
- **WHEN** the night window is open, staff are absent for 2 minutes, and an admin disables the window
- **THEN** the automatic level returns to `OFF` at the next tick

#### Scenario: A replica that did not save the change
- **WHEN** an admin saves a change on replica A
- **THEN** replica B applies it at its next tick, without waiting for its snapshot poll

### Requirement: Audit and event of a settings change
Each successful save SHALL write one staff audit entry with the action `guard.settings.set`, attributed to the token's `oid`. A save that changes at least one value SHALL publish one `guard.settings_changed` event. The event payload SHALL contain only the stored settings and the change time. It SHALL NOT contain an IP address, an owner hash, a player name or a UUID.

#### Scenario: A save with no change is audited but not announced
- **WHEN** an admin saves the values that are already stored
- **THEN** one audit entry is written and no `guard.settings_changed` event is published

#### Scenario: The event carries whitelisted values only
- **WHEN** an admin changes the time zone
- **THEN** the event payload holds the new settings and `changedAt`, and no value that identifies a person or an address
