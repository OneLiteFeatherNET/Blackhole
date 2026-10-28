## ADDED Requirements

### Requirement: Denial screen by reason
The proxy SHALL refuse a login whose backend verdict is `DENY` with the screen chosen by `denialReason`: `AREA_BAN` shows the area ban screen, `GUARD_STRICT` the strict screen, `GUARD_LOCKDOWN` the lockdown screen, and a `DENY` without a reason (a `VPN_POLICY` denial) the existing VPN screen.

#### Scenario: Lockdown screen
- **WHEN** a player is refused with `GUARD_LOCKDOWN`
- **THEN** the screen says the network is currently only open to known players

#### Scenario: Old verdict without reason
- **WHEN** the backend returns `DENY` without a `denialReason`
- **THEN** the player sees the VPN screen

### Requirement: Denial screen content
The denial screens SHALL NOT show an IP address, a classification source, an owner hash, a player name or the reason text of an area ban. Each screen MUST be localized with the English text as fallback. The area ban screen SHALL show an expiry only when the ban has one.

#### Scenario: Area ban screen has no reason text
- **WHEN** a player is refused by an area ban with the reason "botnet wave from provider X"
- **THEN** the screen shows the generic area ban text and an expiry only when the ban has one

### Requirement: Staff presence heartbeat
The proxy SHALL send `POST /guard/presence` every `blackhole.guard.heartbeat-interval` (default 30 seconds) with its proxy id and the number of online players with the permission node `blackhole.staff`. The proxy SHALL store the effective level from the response and use it as the level for the fail mode. A failed heartbeat SHALL increment `blackhole.guard.heartbeat.failures` and SHALL NOT affect logins.

#### Scenario: Heartbeat stores the level
- **WHEN** the heartbeat response carries `STRICT`
- **THEN** the proxy uses `STRICT` as its cached level

#### Scenario: Heartbeat fails
- **WHEN** the heartbeat request fails
- **THEN** the failure counter increases and players can still log in

### Requirement: Guard fail mode on timeout
When the login check times out, errors or returns an unknown verdict, the proxy SHALL decide with its cached level as set out in the `join-guard` capability: allow at `OFF` and `STRICT` and count `blackhole.guard.failopen`, refuse non-staff players at `LOCKDOWN` with the lockdown screen and count `blackhole.guard.failclosed`, and allow players with the `blackhole.staff` permission at `LOCKDOWN`. The timeout SHALL be the existing login check timeout.

#### Scenario: Timeout at strict
- **WHEN** the login check times out and the cached level is `STRICT`
- **THEN** the login is allowed and `blackhole.guard.failopen` increases

#### Scenario: Timeout at lockdown for a player
- **WHEN** the login check times out, the cached level is `LOCKDOWN` and the player lacks the staff permission
- **THEN** the login is refused with the lockdown screen

### Requirement: Guard command
The proxy SHALL offer `/guard` (status), and `/guard off|strict|lockdown` to set the manual level, guarded by the permission node `blackhole.guard`. The command SHALL call `POST /guard/level` once, attribute the change to a linked staff member through `onBehalfOf` when the sender is linked, and report the resulting effective level to the sender. An unknown level SHALL show usage text and call nothing.

#### Scenario: Set strict
- **WHEN** a staff member with `blackhole.guard` runs `/guard strict`
- **THEN** the manual level is set to `STRICT` and the sender sees the effective level

#### Scenario: Unknown level
- **WHEN** a staff member runs `/guard other`
- **THEN** the command shows its usage text and no backend call is made
