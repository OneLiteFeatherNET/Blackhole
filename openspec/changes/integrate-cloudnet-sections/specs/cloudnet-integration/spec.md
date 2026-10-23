# Spec Delta

## Purpose

Defines how a SERVER section can target CloudNet v4 tasks, groups and single services, how the Velocity proxy resolves them in-process through the CloudNet driver API, how the integration behaves when CloudNet is absent, and how the proxy reports a catalogue of tasks, groups and services to the backend for the admin UI.

## ADDED Requirements

### Requirement: Section target kinds
A SERVER section SHALL have exactly one target kind: `PATTERN` (glob patterns over the Velocity server name), `CLOUDNET_TASK`, `CLOUDNET_GROUP` or `CLOUDNET_SERVICE` (exact CloudNet names). The section's `servers` list SHALL hold the patterns or the names. A request without `targetKind` MUST be treated as `PATTERN`, and sections that existed before this capability MUST read as `PATTERN`. Only SERVER sections MAY have a target kind other than `PATTERN`.

#### Scenario: Create a task section
- **WHEN** `POST /section` is called with key `bedwars`, enforcement SERVER, `targetKind` `CLOUDNET_TASK` and servers `["BedWars"]`
- **THEN** the section is stored and returned with `targetKind` `CLOUDNET_TASK` and servers `["BedWars"]`

#### Scenario: Create a group section
- **WHEN** a section is created with `targetKind` `CLOUDNET_GROUP` and servers `["Minigames"]`
- **THEN** it is stored with that kind

#### Scenario: Create a service section
- **WHEN** a section is created with `targetKind` `CLOUDNET_SERVICE` and servers `["BedWars-3"]`
- **THEN** it is stored with that kind

#### Scenario: Kind omitted
- **WHEN** a SERVER section is created with servers `["bedwars-*"]` and no `targetKind`
- **THEN** it is stored and returned with `targetKind` `PATTERN`

#### Scenario: Existing sections after the upgrade
- **WHEN** the backend starts on a database that already holds SERVER sections
- **THEN** every existing section reads as `PATTERN` with unchanged servers

#### Scenario: Target kind on a non-SERVER section
- **WHEN** a section with enforcement BAN or MUTE is submitted with `targetKind` `CLOUDNET_TASK`
- **THEN** the request is rejected with a validation error and nothing is stored

### Requirement: CloudNet target validation
For a CloudNet target kind the backend SHALL require at least one name and at most 50, each non-blank, at most 64 characters and made only of letters, digits, `_`, `.` and `-`. Wildcards (`*`, `?`) MUST be rejected for CloudNet kinds. Names SHALL be compared ignoring case. The backend MUST NOT reject a name because it is absent from the catalogue.

#### Scenario: Wildcard in a task name
- **WHEN** a `CLOUDNET_TASK` section is submitted with servers `["BedWars*"]`
- **THEN** the request is rejected with a validation error naming the pattern

#### Scenario: No names
- **WHEN** a `CLOUDNET_GROUP` section is submitted with no servers
- **THEN** the request is rejected with a validation error

#### Scenario: Unknown task name accepted
- **WHEN** a `CLOUDNET_TASK` section names a task that no catalogue lists
- **THEN** the section is stored

### Requirement: CloudNet resolution on the proxy
The Velocity plugin SHALL resolve a Velocity server name to a CloudNet service through the in-process CloudNet driver API (no network call from the backend), yielding its task name, its groups and its service name. A `CLOUDNET_TASK` section SHALL cover a server whose task name equals one of the section's names, a `CLOUDNET_GROUP` section a server one of whose groups equals one of the names, and a `CLOUDNET_SERVICE` section a server whose service name equals one of the names, all ignoring case.

#### Scenario: Task section covers a service of the task
- **WHEN** the section targets task `BedWars` and the player connects to server `BedWars-3` whose task is `BedWars`
- **THEN** the section covers the server

#### Scenario: Group section covers a service in the group
- **WHEN** the section targets group `Minigames` and the service `SkyWars-1` belongs to groups `Minigames` and `Global`
- **THEN** the section covers the server

#### Scenario: Service section covers one service only
- **WHEN** the section targets service `BedWars-3`
- **THEN** it covers `BedWars-3` and does not cover `BedWars-4`

#### Scenario: Case is ignored
- **WHEN** the section targets task `bedwars` and the service's task is `BedWars`
- **THEN** the section covers the server

#### Scenario: Pattern sections are unaffected
- **WHEN** a `PATTERN` section lists `lobby-*` and the proxy runs without CloudNet
- **THEN** it covers `lobby-1` exactly as before

### Requirement: Resolution stays current
The proxy SHALL keep the resolution current from CloudNet service lifecycle and update events and MUST NOT call CloudNet on the connect path except for one lookup when the server is not yet known.

#### Scenario: Service starts
- **WHEN** CloudNet reports service `BedWars-5` as running
- **THEN** the next connect to `BedWars-5` is resolved without a lookup

#### Scenario: Service stops
- **WHEN** CloudNet reports service `BedWars-5` as stopped
- **THEN** its resolution is removed and no longer covered by CloudNet sections

#### Scenario: Server not yet known
- **WHEN** a player connects to a server that is not in the proxy's resolution
- **THEN** exactly one lookup by service name is made before the server is treated as unknown

### Requirement: Optional CloudNet integration
The CloudNet integration SHALL be optional. With `cloudnet.integration` set to `auto` (the default) the plugin SHALL use CloudNet only if the driver classes are present and the CloudNet injection layer provides a service provider; with `off` it MUST NOT probe. Without CloudNet the plugin MUST start and work as before, CloudNet sections MUST NOT cover any server, and the plugin SHALL log one warning at startup naming the number of CloudNet sections it cannot enforce.

#### Scenario: Plugin runs outside CloudNet
- **WHEN** the plugin starts on a proxy that is not a CloudNet service and a `CLOUDNET_TASK` section exists
- **THEN** the plugin starts, a warning is logged once, and a player with an active ban in that section can connect to any server

#### Scenario: Integration switched off
- **WHEN** `cloudnet.integration` is `off` on a CloudNet service
- **THEN** no CloudNet class is loaded and CloudNet sections cover no server

#### Scenario: Running under CloudNet
- **WHEN** the plugin starts as a CloudNet service
- **THEN** the integration is active and no warning is logged

### Requirement: Driver is not bundled
The CloudNet driver SHALL be a compile-only dependency of the plugin and MUST NOT be shaded into it, and only one adapter class SHALL reference CloudNet types.

#### Scenario: Plugin jar contents
- **WHEN** the plugin jar is built
- **THEN** it contains no `eu/cloudnetservice` classes

### Requirement: Unknown server is not covered
When the proxy cannot determine the CloudNet task, groups or service name of a server (it is not a CloudNet service, or CloudNet has no data for it even after one lookup), a CLOUDNET section SHALL NOT cover it, and the proxy SHALL log a warning once per server name. A `PATTERN` section MUST still be evaluated by the server name.

#### Scenario: Server registered outside CloudNet
- **WHEN** a player with an active `CLOUDNET_TASK` ban connects to a server that CloudNet does not know
- **THEN** the connect is allowed and a warning naming the server is logged once

#### Scenario: Warning is not repeated
- **WHEN** the same unknown server is connected to again
- **THEN** no further warning is logged for that server name

### Requirement: Single-service targets follow the name
A `CLOUDNET_SERVICE` target SHALL be bound to the service name, not to the CloudNet instance. A restart that gives the same name to a new instance MUST keep the section covering it, and a target naming a service that does not currently exist SHALL stay stored and inert. The admin UI and the documentation MUST warn that a service name can belong to a different instance after a restart.

#### Scenario: Service restarts under the same name
- **WHEN** service `BedWars-3` stops and a new instance starts as `BedWars-3` while a player has an active ban in a section targeting `BedWars-3`
- **THEN** the connect to the new instance is denied

#### Scenario: Service no longer exists
- **WHEN** the section targets `BedWars-9` and no such service is running
- **THEN** the section stays stored and covers no server

#### Scenario: Service stops during an active ban
- **WHEN** the targeted service stops
- **THEN** the punishment and the section are unchanged

### Requirement: Catalogue report
The proxy SHALL report a full catalogue snapshot (tasks with their groups, groups, services with task, groups and running state) with `PUT /cloudnet/catalogue`, identified by a `proxyId`. It SHALL send a snapshot at startup, after CloudNet task, group or service events, and as a heartbeat at least every five minutes.

#### Scenario: Snapshot at startup
- **WHEN** the plugin starts under CloudNet
- **THEN** one snapshot with all tasks, groups and services is sent

#### Scenario: Heartbeat without changes
- **WHEN** no event occurs for the heartbeat interval
- **THEN** a full snapshot is sent anyway

### Requirement: Catalogue report debounce and bounds
The proxy SHALL debounce event-triggered reports so that at most one is sent per debounce window (default five seconds) and MUST NOT let a failed report affect enforcement. The backend MUST NOT rate limit the route; it SHALL only bound the size (at most 500 tasks, 500 groups, 5000 services, `proxyId` of at most 64 characters).

#### Scenario: Burst of service events
- **WHEN** ten services change state within the debounce window
- **THEN** exactly one snapshot is sent after the window

#### Scenario: Backend unreachable
- **WHEN** a report fails
- **THEN** it is retried on the next tick and punishments continue to be enforced

#### Scenario: Oversized snapshot
- **WHEN** a snapshot lists more than 5000 services
- **THEN** the backend answers HTTP 400 and stores nothing

### Requirement: Catalogue storage and read
The backend SHALL store each proxy's latest snapshot in the shared cache with a time to live of 15 minutes and SHALL answer `GET /cloudnet/catalogue` with the merged view of all live snapshots (tasks, groups and services by name; a service is running if any proxy reports it running; the reporting proxy ids). With no live snapshot, or with the cache unavailable, the answer SHALL have `available` false and empty lists. A write while the cache is unavailable SHALL fail with HTTP 503.

#### Scenario: Two proxies report
- **WHEN** `Proxy-1` and `Proxy-2` each report task `BedWars`
- **THEN** `GET /cloudnet/catalogue` lists task `BedWars` once and both proxy ids

#### Scenario: Proxy goes away
- **WHEN** a proxy has not reported for 15 minutes
- **THEN** its entries are no longer part of the merged view

#### Scenario: Newer snapshot replaces the old one
- **WHEN** `Proxy-1` reports again without service `BedWars-4`
- **THEN** the merged view no longer lists `BedWars-4` unless another proxy reports it

#### Scenario: No catalogue known
- **WHEN** no proxy has reported
- **THEN** the response has `available` false and empty lists

#### Scenario: Cache unavailable
- **WHEN** the shared cache is down
- **THEN** `PUT` answers HTTP 503 and `GET` answers `available` false

### Requirement: Catalogue is not a source of enforcement
Enforcement SHALL NOT depend on the catalogue. The backend MUST NOT use it to validate, enable or disable sections, and proxies MUST NOT read it.

#### Scenario: Catalogue empty, section enforced
- **WHEN** the catalogue is empty and a player with an active `CLOUDNET_TASK` ban connects to a server of that task
- **THEN** the connect is denied by the proxy from its own CloudNet view
