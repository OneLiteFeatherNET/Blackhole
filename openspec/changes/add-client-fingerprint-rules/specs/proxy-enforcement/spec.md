# Spec Delta

## Purpose
Collects client traits on the proxy, enforces client rules on login, connect and command, and tells staff about hits.

## ADDED Requirements

### Requirement: Client trait collection
The proxy SHALL collect, per connected player and in memory only, the protocol version, the client brand, the registered plugin channels and the legacy mod list, from the corresponding Velocity events and values, and SHALL drop them when the player disconnects. The existing client brand reporting to the backend SHALL stay unchanged. Collection SHALL be switchable with `clientRules.enabled` (default on).

#### Scenario: Channels registered mid-session
- **WHEN** a client registers a channel after joining a server
- **THEN** the trait set is updated and the rules are checked again

#### Scenario: Disconnect
- **WHEN** a player disconnects
- **THEN** the proxy keeps none of that player's traits

#### Scenario: Switched off
- **WHEN** `clientRules.enabled` is false
- **THEN** no rule is evaluated and nothing is sent to the backend

### Requirement: Rule enforcement on the proxy
The proxy SHALL enforce enabled client rules at login, on server connect, when traits arrive and when commands are executed as described by the client-rules capability, SHALL report each hit to the backend asynchronously and MUST NOT delay a connection on the backend's answer except to learn the result of a `TEMPLATE` hit. Exempt players are those holding `blackhole.clientrules.bypass`.

#### Scenario: Denied connect keeps the player where they are
- **WHEN** a scoped `DENY` rule hits a player who is already on another server
- **THEN** the connect is refused, the player stays and sees the rule's message

#### Scenario: Template hit
- **WHEN** a `TEMPLATE` rule hits and the backend applies a ban
- **THEN** the player is shown the normal punishment screen

### Requirement: Fail-open client rules
When the rules cannot be loaded, the backend cannot be reached or the server is unknown to CloudNet, the proxy SHALL allow the connection or command and SHALL log one warning per cause.

#### Scenario: Rules never loaded
- **WHEN** the plugin starts while the backend is down
- **THEN** no rule is enforced until the rules load
