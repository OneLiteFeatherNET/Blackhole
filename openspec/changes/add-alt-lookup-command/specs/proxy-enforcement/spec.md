## ADDED Requirements

### Requirement: Alt lookup command
The proxy SHALL provide `/alts <player>` to players holding the permission `blackhole.alts`. The target SHALL be resolved from an online player on this proxy first, then through the live name lookup, then through the old-name lookup of the backend. The command SHALL print each linked account with its UUID, its name, its reasons in words, its last seen time and its active punishment sections or "none", at most 20 entries newest first, and SHALL say when the list was cut short.

#### Scenario: Linked accounts listed
- **WHEN** a staff member runs `/alts` for a player with two linked accounts
- **THEN** both are printed with their UUIDs, names, reasons, last seen times and active punishments

#### Scenario: No linked accounts
- **WHEN** the target has no linked account
- **THEN** the proxy prints that no account shares an address or a fingerprint with the target

#### Scenario: Entries are clickable without running
- **WHEN** a staff member clicks a linked account's entry
- **THEN** `/blackhole <name> info` is placed in the chat box and is not sent

#### Scenario: No address or hash in game
- **WHEN** the command prints its output
- **THEN** no IP address, token or owner hash appears in the output

#### Scenario: Lookup times out
- **WHEN** the backend does not answer within `blackhole.alts.timeout`
- **THEN** the player is told the lookup timed out and the server thread is not blocked

#### Scenario: Missing permission
- **WHEN** a player without `blackhole.alts` runs the command
- **THEN** the command is refused

#### Scenario: Unknown target
- **WHEN** no source knows the target name
- **THEN** the proxy prints a not-found message
