## ADDED Requirements

### Requirement: IP ban login denial
The proxy SHALL deny a login whose address lies inside an active IP ban, using the disconnect screen with the ban's reason. The check MUST run from the local list only and MUST NOT make a backend call per login. It SHALL run after the player punishment check, and an IP ban denial MUST NOT change the player's punishment state.

#### Scenario: Banned address connects
- **WHEN** a player connects from an address inside an active IP ban
- **THEN** the login is denied and the player sees the ban reason

#### Scenario: Address outside every ban
- **WHEN** a player connects from an address that matches no active IP ban
- **THEN** the login proceeds to the existing checks

#### Scenario: No backend call per login
- **WHEN** a login is checked against the IP ban list
- **THEN** no HTTP request to the backend is made for that check

### Requirement: Proxy enforces the address it sees
The proxy SHALL match IP bans against the address of the player's connection as Velocity sees it. The proxy MUST NOT attempt to resolve a different address, and the firewall layer is documented as valid only where the proxy sees the player's address.

#### Scenario: Direct connection
- **WHEN** a player connects directly to the proxy
- **THEN** the address matched is the player's own address

### Requirement: IP ban list not yet loaded
When the proxy has never loaded the IP ban list, it SHALL allow the login, log a warning and increment `blackhole.ipban.proxy.unloaded`. When a refresh fails after a successful load, the proxy SHALL keep the last known list.

#### Scenario: Backend unreachable at start-up
- **WHEN** a player logs in before the first successful load
- **THEN** the login is allowed and the unloaded counter increases

#### Scenario: Refresh fails later
- **WHEN** a refresh fails after the list was loaded
- **THEN** logins are still checked against the last known list

### Requirement: Refresh runs off the login path
The proxy SHALL refresh the IP ban list on the `blackhole.events.ipban.changed` event and every `blackhole.ipban.proxy.poll` (default `PT60S`), on a background executor and never on the login thread.

#### Scenario: Ban created while the proxy runs
- **WHEN** an IP ban is created
- **THEN** the proxy refreshes its list without a restart

### Requirement: The /ipban command
The proxy SHALL provide `/ipban <player|ip> <template>` to players with the permission `blackhole.ipban`. For a player, the address is the last address on record for that player; if none is on record the command is refused with a message. The command MUST run its backend calls asynchronously and report the created range.

#### Scenario: Ban by player
- **WHEN** a staff member runs `/ipban Steve <template>` for a player with a last address on record
- **THEN** the IP ban is created for that address and the staff member sees the range

#### Scenario: Ban by player without an address
- **WHEN** the player has no address on record
- **THEN** the command reports that no address is on record and creates nothing

#### Scenario: Ban by address
- **WHEN** a staff member runs `/ipban 203.0.113.7 <template>`
- **THEN** the IP ban is created for `203.0.113.7/32`

#### Scenario: Missing permission
- **WHEN** a player without `blackhole.ipban` runs the command
- **THEN** the command is not available
