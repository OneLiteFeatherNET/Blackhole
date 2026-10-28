## ADDED Requirements

### Requirement: Staff amend command

The proxy SHALL provide `/blackhole <player> amend duration <1d|permanent> <justification>` and `/blackhole <player> amend reason <template> <justification>` for players with `blackhole.amend`. The command SHALL read the punishment's `ETag` from the backend first and post the amendment with `If-Match`, through the backend, so the player may be on any proxy. A justification under 10 characters SHALL be refused before any call.

#### Scenario: Shorten from game

- **WHEN** a staff member with `blackhole.amend` runs an amendment with a valid justification
- **THEN** the proxy reads the `ETag`, posts the amendment with `If-Match`, and reports the new expiry

#### Scenario: Version conflict

- **WHEN** the amendment is answered 412
- **THEN** the command reports that the punishment changed meanwhile and posts nothing more

#### Scenario: Justification too short

- **WHEN** a justification of 5 characters is given
- **THEN** the command is refused and nothing is sent to the backend

#### Scenario: Role refused in game

- **WHEN** an ADMIN-only change is sent in game by a staff member the backend refuses for that change
- **THEN** the command reports the refusal and the panel is named as the place to do it

### Requirement: Amended punishments take effect on the proxy

When a `punishment.updated` event arrives, the proxy SHALL update its mirror entry so the ban screen shows the new expiry and reason on the next login. A network ban for an online player that is made permanent or extended SHALL disconnect that player with the updated screen. A shortening SHALL NOT disconnect any player.

#### Scenario: Ban screen after shortening

- **WHEN** a ban is shortened and the player logs in again
- **THEN** the ban screen shows the new expiry

#### Scenario: Online player after extension

- **WHEN** an online player's network ban is extended
- **THEN** the proxy disconnects that player with the updated screen

#### Scenario: Online player after shortening

- **WHEN** an online player's network ban is shortened
- **THEN** the player stays connected
