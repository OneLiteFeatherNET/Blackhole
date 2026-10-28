## MODIFIED Requirements

### Requirement: Login enforcement
On every login the proxy SHALL check for an active ban using the Redis mirror, falling back to the backend profile when Redis is unavailable, and then report one session to the backend with the outcome of that check (`DENIED` for a network ban, `ALLOWED` otherwise). A player with an active ban of type NETWORK MUST be disconnected with the rendered template message; bans of other types MUST NOT block login. Allowed logins record session info (protocol version). The session report MUST NOT delay the login decision.

#### Scenario: Network-banned player connects
- **WHEN** a player whose active ban has type NETWORK logs in
- **THEN** the player is disconnected with the rendered message of the ban's template
- **AND** a session is reported with the outcome `DENIED`

#### Scenario: Server-scoped ban does not block the proxy
- **WHEN** a player whose active ban has type SERVER logs in
- **THEN** the login is allowed, a session is reported with the outcome `ALLOWED`, and session info is recorded

#### Scenario: Redis unavailable
- **WHEN** a player logs in while Redis is not connected
- **THEN** the proxy fetches the punishment profile over HTTP and applies the same NETWORK-ban decision

#### Scenario: Evasion sighting recorded for every login
- **WHEN** any player logs in, banned or not
- **THEN** the proxy reports a session with the player's owner hash, IP address and outcome to the backend, once per login

#### Scenario: Backend unreachable during the report
- **WHEN** the backend does not answer the session report
- **THEN** the login decision is unchanged and the report is dropped

#### Scenario: Backend unreachable on fallback
- **WHEN** Redis is unavailable and the backend profile request fails
- **THEN** the login is allowed

### Requirement: Client brand reporting
When a connected player's client brand becomes known, the proxy SHALL send it to the backend as session info for that player's owner hash and as an update of that player's session record, including the brand version when the client provides it; failures MUST be ignored.

#### Scenario: Brand reported
- **WHEN** a player's client reports the brand `vanilla`
- **THEN** the proxy updates that player's session info in the backend with client brand `vanilla`
- **AND** the proxy updates the player's current session record with the same brand

## ADDED Requirements

### Requirement: Session end reporting
When a connected player disconnects, the proxy SHALL report the end of that player's session to the backend, once, with the time of the disconnect. Failures MUST be ignored.

#### Scenario: Disconnect after an allowed login
- **WHEN** an allowed player disconnects
- **THEN** the proxy reports the session end once

#### Scenario: Denied login has no open session
- **WHEN** a player was denied at login and the connection then closes
- **THEN** the proxy does not report a second end for that session

### Requirement: Session reporting does not block
The proxy SHALL run session reports on a bounded background executor with a configurable timeout and SHALL drop a report when the executor's queue is full, without blocking the login or the disconnect.

#### Scenario: Queue is full
- **WHEN** the report queue is full
- **THEN** the report is dropped, the login proceeds, and the drop is logged at debug level
