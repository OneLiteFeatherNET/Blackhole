## ADDED Requirements

### Requirement: Staff warn command

The proxy SHALL provide `/warn <player> <template>` for players with `blackhole.warn`, with player and template tab completion. It SHALL send the request through the backend's template apply route, so the player may be on any proxy, and the escalation step of the template decides what is recorded.

#### Scenario: Warn a player on another proxy

- **WHEN** a staff member with `blackhole.warn` runs `/warn Alex spam` and Alex is on a different proxy
- **THEN** the backend records the warning or the escalated step, that proxy shows the message, and the command reports the step applied

#### Scenario: Missing permission

- **WHEN** a player without `blackhole.warn` runs `/warn Alex spam`
- **THEN** the command is refused and nothing is sent to the backend

### Requirement: Warning shown and acknowledged online

The proxy SHALL subscribe, subscribe-only and without a JetStream consumer, to `blackhole.events.punishment.warned`. For a connected owner it SHALL read the warning text with `GET /warn/{id}` and show it in chat with a clickable acknowledge line.

#### Scenario: Warning shown online

- **WHEN** a `punishment.warned` event arrives for an owner connected to this proxy
- **THEN** the proxy shows the warning once with the acknowledge line

#### Scenario: Warning does not touch the cache

- **WHEN** a `punishment.warned` event arrives
- **THEN** the proxy's punishment cache for that player is unchanged

### Requirement: Acknowledgement is posted only for the owner

The acknowledge command `/blackhole warning ack <id>` SHALL act only for the owner of the warning, and SHALL post `POST /warn/{id}/acknowledge` with the owner hash. A command from any other player SHALL post nothing to the backend.

#### Scenario: Acknowledge by the owner

- **WHEN** the owner of a pending warning runs the acknowledge command
- **THEN** the proxy posts the acknowledgement with the owner hash

#### Scenario: Acknowledge by another player

- **WHEN** a player other than the owner runs the acknowledge command for a warning
- **THEN** nothing is posted to the backend and the player sees a refusal

### Requirement: Pending warnings shown on login

On login the proxy SHALL read the owner's pending warnings with `GET /warn/pending` and show each once for that login. A failed read SHALL be logged without player names and SHALL NOT refuse the login.

#### Scenario: Pending warning on login

- **WHEN** a player with one pending warning logs in
- **THEN** the proxy shows the warning once and the login proceeds

#### Scenario: Read fails on login

- **WHEN** the pending read fails
- **THEN** the login proceeds and the failure is logged once

### Requirement: Warnings stay out of the mirror

Warnings SHALL NOT be written to the proxy-facing Redis mirror and SHALL NOT be enforced by the proxy. The mirror keeps its existing rules for kind `PUNISHMENT`.

#### Scenario: Warning does not reach the mirror

- **WHEN** a warning is applied to a player
- **THEN** the mirror entry for that player and section is unchanged

### Requirement: Warning screens are English only

Warning messages SHALL be English for every client locale, as kick and ban messages are.

#### Scenario: Warning for a German client

- **WHEN** a German client receives a warning
- **THEN** the text is English
