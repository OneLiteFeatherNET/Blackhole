## ADDED Requirements

### Requirement: Name history command
The proxy SHALL provide `/blackhole <user> names` for players holding the permission `blackhole.user.info`. The target SHALL be resolved from an online player on this proxy first, then through the live name lookup, then through the old-name lookup of the backend. The command SHALL list up to 20 names of the target, newest last seen first, each with its first and last seen time.

#### Scenario: Online player
- **WHEN** a staff member runs the command for a player online on this proxy
- **THEN** the names are listed without a live name lookup

#### Scenario: Old name
- **WHEN** the target name is no longer a current Minecraft name but was used by an account in the history
- **THEN** the names of that account are listed

#### Scenario: Unknown name
- **WHEN** no source knows the name
- **THEN** the proxy prints a not-found message

#### Scenario: Missing permission
- **WHEN** a player without `blackhole.user.info` runs the command
- **THEN** the command is refused

#### Scenario: Backend unreachable
- **WHEN** the backend does not answer
- **THEN** the proxy prints a message that the name history is unavailable and does not throw
