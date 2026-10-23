# Spec Delta

## Purpose

Extends proxy enforcement with SERVER sections that target CloudNet tasks, groups and services.

## ADDED Requirements

### Requirement: CloudNet server connect enforcement
When a player connects to a backend server, the proxy SHALL deny the connection if the player has an active punishment in a SERVER section whose target kind is `CLOUDNET_TASK`, `CLOUDNET_GROUP` or `CLOUDNET_SERVICE` and the section covers that server according to the `cloudnet-integration` resolution rules. The denial, the player staying on the current server (or being disconnected when they have none) and the rendered punishment message SHALL be the same as for `PATTERN` sections.

#### Scenario: Banned from a task
- **WHEN** a player with an active ban in a section targeting task `BedWars` connects to `BedWars-3` from the lobby
- **THEN** the connect is denied, the player stays in the lobby and sees the punishment message

#### Scenario: Other task is open
- **WHEN** the same player connects to `SkyWars-1`
- **THEN** the connect is allowed

#### Scenario: Banned from a group
- **WHEN** a player with an active ban in a section targeting group `Minigames` connects to any service in that group
- **THEN** the connect is denied

#### Scenario: Banned from one service
- **WHEN** a player with an active ban in a section targeting service `BedWars-3` connects to `BedWars-4`
- **THEN** the connect is allowed

#### Scenario: First connect after login
- **WHEN** the player has no current server and the target is covered
- **THEN** the player is disconnected with the punishment message

### Requirement: Move off a newly covered CloudNet server
When a SERVER punishment of a CloudNet section becomes active while the player is on a covered server, the proxy SHALL move them to the configured fallback server, or disconnect them if none is available or if the fallback server is covered by the same punishment. A player whose current server cannot be resolved MUST be left in place.

#### Scenario: Ban arrives while on the task
- **WHEN** a task ban for `BedWars` is applied to a player on `BedWars-3`
- **THEN** the player is moved to the fallback server and shown the punishment message

#### Scenario: Fallback is covered too
- **WHEN** a group ban covers both the player's server and the fallback server
- **THEN** the player is disconnected with the punishment message

#### Scenario: Current server unknown to CloudNet
- **WHEN** the player is on a server CloudNet does not know
- **THEN** the player stays and a warning is logged once for that server

### Requirement: CloudNet deny screen text
The punishment screen of a CloudNet SERVER section SHALL name the targets: `punishment.screen.target.task`, `punishment.screen.target.group` or `punishment.screen.target.service`, with the target names joined by `, `, in English and German, in the existing screen style and followed by the moved-to-fallback line when applicable.

#### Scenario: German task screen
- **WHEN** a German-speaking player is denied by a section targeting task `BedWars`
- **THEN** the screen contains "Du bist auf BedWars gesperrt"

#### Scenario: English group screen
- **WHEN** an English-speaking player is denied by a section targeting group `Minigames`
- **THEN** the screen states that the player is banned from the group `Minigames`

#### Scenario: Both locales define the keys
- **WHEN** the i18n bundle test runs
- **THEN** every `punishment.screen.target.*` key exists in `blackhole_en.properties` and `blackhole_de.properties`

### Requirement: Section target kind on the proxy
The proxy SHALL read `targetKind` from the section list (shared cache or API) and MUST treat a missing value as `PATTERN`.

#### Scenario: Older section list
- **WHEN** the section list has no `targetKind`
- **THEN** every SERVER section is matched as glob patterns
