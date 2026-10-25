## ADDED Requirements

### Requirement: Staff kick command

The proxy SHALL provide `/kick <player> [server|section] <reason>` for players with `blackhole.kick`, and `/kickall <server|section> <reason>` for players with `blackhole.kick.mass`. Both SHALL tab-complete player names and targets, and both SHALL send the request through the backend, so the player may be on any proxy.

#### Scenario: Kick a player on another proxy

- **WHEN** a staff member with `blackhole.kick` runs `/kick Alex lobby spam` and Alex is on a different proxy
- **THEN** the backend records the kick, that proxy acts on it, and the command reports the outcome

#### Scenario: Missing permission

- **WHEN** a player without `blackhole.kick.mass` runs `/kickall survival restart`
- **THEN** the command is refused and nothing is sent to the backend

#### Scenario: Backend unavailable

- **WHEN** the backend cannot be reached
- **THEN** the command reports that the kick was not recorded and performs no local disconnect

### Requirement: Kick handler reuses the enforcement building blocks

The proxy SHALL handle a kick event with the existing player lookup by owner hash, the coverage check for the current server, and the fallback move. The fallback move SHALL be a single method used by both the server punishment path and the kick path.

#### Scenario: Fallback shared with server punishments

- **WHEN** a server punishment and a server kick both move a player
- **THEN** both use the same fallback rule and the same fallback server

### Requirement: Kick acknowledgement

After acting on a `punishment.kick` or `kick.mass` event, or after finding that the player is not connected, the proxy SHALL post an acknowledgement to `POST /kick/{id}/deliveries` with `found` and `moved`. The proxy MUST NOT publish to NATS. A failed acknowledgement SHALL be logged without player names and MUST NOT retry forever.

#### Scenario: Player not connected here

- **WHEN** a kick event arrives for an owner hash that is not connected to this proxy
- **THEN** the proxy acknowledges `found: false, moved: false` and does nothing else

#### Scenario: Acknowledgement fails

- **WHEN** the acknowledgement call fails
- **THEN** the failure is logged once and the player's kick still takes effect

### Requirement: Kick screen is English only

The kick screen SHALL be English for every client locale. This is a deliberate exception to the localized screens of this capability, as with ban messages.

#### Scenario: Kick screen for a German client

- **WHEN** a German client is kicked
- **THEN** the screen text is English

### Requirement: Kick subscription

The proxy SHALL subscribe, subscribe-only and without a JetStream consumer, to `blackhole.events.punishment.kick` and `blackhole.events.kick.mass`. A `punishment.kick` event SHALL NOT evict the proxy's punishment cache.

#### Scenario: Kick does not touch the cache

- **WHEN** a `punishment.kick` event arrives
- **THEN** the punishment cache for that player is unchanged
