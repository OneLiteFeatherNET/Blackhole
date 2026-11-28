## ADDED Requirements

### Requirement: Guard levels
The network SHALL run at one guard level: `OFF`, `STRICT` or `LOCKDOWN`, ordered in that sequence. At `OFF` the guard refuses nothing. At `STRICT` the guard SHALL refuse a login from an address classified `VPN`, `HOSTING` or `PROXY` for every player who is not exempt. At `LOCKDOWN` the guard SHALL additionally refuse every owner who is not known, and an owner is known when the recorded playtime is at least `blackhole.guard.lockdown.min-playtime`.

#### Scenario: Off lets everyone through the guard
- **WHEN** the effective level is `OFF` and a player logs in from a hosting address
- **THEN** the guard refuses nothing, and the VPN policy alone decides

#### Scenario: Strict refuses a hosting address
- **WHEN** the effective level is `STRICT`, the player is not exempt, and the address is classified `HOSTING`
- **THEN** the login is refused with the `GUARD_STRICT` reason, whatever the VPN policy for hosting is

#### Scenario: Strict admits a clean address
- **WHEN** the effective level is `STRICT` and the address is classified `CLEAN`
- **THEN** the guard admits the login

#### Scenario: Lockdown refuses an unknown owner
- **WHEN** the effective level is `LOCKDOWN` and the owner has 5 minutes of recorded playtime with `blackhole.guard.lockdown.min-playtime` at 60 minutes
- **THEN** the login is refused with the `GUARD_LOCKDOWN` reason

#### Scenario: Lockdown admits a known owner from a clean address
- **WHEN** the effective level is `LOCKDOWN` and the owner has 90 minutes of recorded playtime from a `CLEAN` address
- **THEN** the login is admitted

### Requirement: Effective level from manual and automatic sources
The effective level SHALL be the higher of the manual level and the automatic level. The manual level SHALL change only when a staff member sets it. The automatic level SHALL be written only by the unattended evaluator and SHALL be either `OFF` or the configured unattended level. A manual level SHALL stay in force until a staff member changes it.

#### Scenario: Manual lockdown survives staff returning
- **WHEN** a moderator sets `LOCKDOWN` manually and a staff member then comes online
- **THEN** the effective level stays `LOCKDOWN`, because the manual level is unchanged

#### Scenario: Manual off does not override an unattended level
- **WHEN** the manual level is `OFF`, nobody has been on the proxy for 30 minutes, and the unattended level is `STRICT`
- **THEN** the effective level is `STRICT` and the panel shows that the source is unattended

#### Scenario: Setting manual off while staff are online
- **WHEN** the manual level is set to `OFF` while a staff member is on the proxy
- **THEN** the effective level becomes `OFF` at the next evaluation

### Requirement: Unattended automatic level
The backend SHALL evaluate the automatic level every 30 seconds using its injected clock. When staff are present, the automatic level SHALL be `OFF`. When staff are absent for at least `blackhole.guard.unattended.absence-threshold` (default 20 minutes), the automatic level SHALL be the configured unattended level.

#### Scenario: Absence below the threshold
- **WHEN** the last staff presence was 19 minutes ago and the threshold is 20 minutes
- **THEN** the automatic level is `OFF`

#### Scenario: Absence at the threshold
- **WHEN** the last staff presence was exactly 20 minutes ago and the threshold is 20 minutes
- **THEN** the automatic level is `STRICT`

#### Scenario: Staff return clears the automatic level
- **WHEN** the automatic level is `STRICT` and a staff member comes online
- **THEN** the automatic level is `OFF` at the next evaluation

### Requirement: Night window trigger
The local time in `blackhole.guard.unattended.zone` SHALL be checked against `blackhole.guard.unattended.night-window` (default `01:00-07:00`). Inside the window, staff absence alone SHALL set the automatic level to the unattended level, without waiting for the absence threshold. The unattended level SHALL NOT be `OFF`, and `LOCKDOWN` SHALL be used only when the operator configures it.

#### Scenario: Night window with nobody online
- **WHEN** the local time is 01:00 in the configured zone, the night window is `01:00-07:00`, and staff were last seen 2 minutes ago
- **THEN** the automatic level is `STRICT`, because the night window is an independent trigger

#### Scenario: Night window with staff online
- **WHEN** the local time is 03:00, the night window is `01:00-07:00`, and a staff member is on the proxy
- **THEN** the automatic level is `OFF`

#### Scenario: Night window end is exclusive
- **WHEN** the local time is exactly 07:00 and the night window is `01:00-07:00`, with nobody online
- **THEN** the night window does not trigger, and the absence threshold alone decides

#### Scenario: Window across midnight
- **WHEN** the night window is `23:00-02:00` and the local time is 00:30 with nobody online
- **THEN** the night window triggers

#### Scenario: Unattended level set to lockdown by configuration
- **WHEN** the operator configures the unattended level as `LOCKDOWN` and staff have been absent past the threshold
- **THEN** the automatic level is `LOCKDOWN`

#### Scenario: Unattended level off is refused
- **WHEN** the operator configures the unattended level as `OFF`
- **THEN** the configuration is refused at startup

### Requirement: Staff presence from proxy heartbeats
Each proxy SHALL send a presence heartbeat every 30 seconds with its proxy id and the number of online players with the permission node `blackhole.staff`. The backend SHALL treat a proxy's report as current for `blackhole.guard.presence.ttl` (default 5 minutes) and SHALL ignore older reports. Staff SHALL be present while any current report has a count above zero. The heartbeat response SHALL carry the effective level.

#### Scenario: Staff online on one proxy
- **WHEN** proxy A reports a staff count of 1 within the last five minutes
- **THEN** staff are present

#### Scenario: Stale report from a stopped proxy
- **WHEN** the only report with a count above zero is 6 minutes old
- **THEN** staff are absent

#### Scenario: Staff on the other proxy
- **WHEN** proxy A reports 0 and proxy B reports 2
- **THEN** staff are present

#### Scenario: Heartbeat returns the level
- **WHEN** a proxy sends a heartbeat while the effective level is `STRICT`
- **THEN** the response carries `STRICT`

### Requirement: Level changes happen once
A change of the effective level SHALL be written with one conditional update of the state row that matches the level read before the change. Only the replica whose update succeeds SHALL write the history row and publish the change event. Replicas that run the same evaluation at the same time SHALL NOT produce a second change or a second event.

#### Scenario: Two replicas see the same transition
- **WHEN** two backend replicas evaluate the same transition from `OFF` to `STRICT` at the same time
- **THEN** exactly one history row and exactly one `guard.level_changed` event are written

### Requirement: Manual level changes by staff
Panel users with the MODERATOR or ADMIN role, in-game staff through `/guard`, and Discord staff through the `/guard` command SHALL be able to set the manual level. Every manual change SHALL write a history row with the previous and new manual level, the source, the reason when given, and the actor's `oid` for panel and in-game actions (NULL for Discord). Lowering a level SHALL NOT require a higher role.

#### Scenario: Moderator raises to lockdown
- **WHEN** a MODERATOR sets the manual level to `LOCKDOWN` in the panel
- **THEN** the change is written to the history with the MODERATOR's `oid` as actor, and the effective level becomes `LOCKDOWN`

#### Scenario: Discord user without the staff role
- **WHEN** a Discord user without the staff role runs `/guard strict`
- **THEN** the command is refused and no backend call is made

### Requirement: Exemptions from the guard
Linked staff SHALL never be refused by the guard, whatever the level. A player on the VPN allow-list SHALL never be refused by a guard level. Neither exemption SHALL apply to area bans (see `area-bans`).

#### Scenario: Linked staff under lockdown
- **WHEN** the effective level is `LOCKDOWN` and a linked staff member logs in from a hosting address
- **THEN** the login is admitted by the guard

#### Scenario: Allow-listed player under strict
- **WHEN** the effective level is `STRICT` and an allow-listed owner logs in from a VPN address
- **THEN** the guard does not refuse the login, and the VPN policy result decides

### Requirement: Fail mode per level
When the login check times out, errors or returns an unknown verdict, the proxy SHALL decide from the last effective level it received. At `OFF` or `STRICT` (or with no level received yet) the login SHALL be allowed and a fail-open counter SHALL increase. At `LOCKDOWN` a player without the `blackhole.staff` permission SHALL be refused with the `GUARD_LOCKDOWN` screen and a fail-closed counter SHALL increase, and a player with the permission SHALL be allowed.

#### Scenario: Backend down during strict
- **WHEN** the last level received was `STRICT` and the login check times out
- **THEN** the login is allowed and the fail-open counter increases

#### Scenario: Backend down during lockdown
- **WHEN** the last level received was `LOCKDOWN` and the login check times out for a player without the staff permission
- **THEN** the login is refused with the `GUARD_LOCKDOWN` screen and the fail-closed counter increases

#### Scenario: Backend down during lockdown for staff
- **WHEN** the last level received was `LOCKDOWN` and the login check times out for a player with the staff permission
- **THEN** the login is allowed

### Requirement: Level change alerts
Every effective or manual level change SHALL publish `guard.level_changed` on the domain event bus, and the staff Discord channel SHALL receive it through the existing notification path. The payload SHALL carry the previous and new level, the manual and automatic level, the source (`MANUAL`, `UNATTENDED` or `CLEARED`) and the time.

#### Scenario: Unattended escalation is announced
- **WHEN** staff have been absent past the threshold and the automatic level becomes `STRICT`
- **THEN** one `guard.level_changed` event with source `UNATTENDED` is published, and staff Discord receives a message for it

#### Scenario: Staff returning is announced
- **WHEN** a staff member comes online and the automatic level clears
- **THEN** one `guard.level_changed` event with source `CLEARED` is published

### Requirement: Refusal summaries per ASN
Refused logins SHALL NOT produce one message each. The backend SHALL summarise refusals every `blackhole.guard.summary-interval` (default 10 minutes) as one `guard.denials.summary` event with the window, the total and the top ten ASNs with their count, organisation and country. A window with no refusals SHALL publish nothing.

#### Scenario: Wave of refusals
- **WHEN** 300 logins from ASN 16276 are refused within one 10-minute window
- **THEN** one summary event is published with ASN 16276 and a count of 300, and no per-login event is published

#### Scenario: Quiet window
- **WHEN** no login is refused within a window
- **THEN** no summary event is published

### Requirement: No identity data in alerts or logs
The payloads of guard events and the Discord messages built from them SHALL NOT contain an IP address, an owner hash, a player name or a UUID. Log lines written while a refusal, a level change or a burst ban is processed SHALL NOT contain an IP address or an owner hash.

#### Scenario: Refusal does not leak the address
- **WHEN** a login is refused by the guard from the address 203.0.113.7
- **THEN** no published guard payload, no Discord message and no log line contains `203.0.113.7`

### Requirement: Level history
Every manual and automatic change SHALL be kept in the history with its previous level, new level, source, actor, reason and time. The history SHALL be readable in the panel and SHALL NOT be deleted by a retention job within the change.

#### Scenario: Overnight history
- **WHEN** the automatic level changed to `STRICT` at 01:00 and back to `OFF` at 07:30
- **THEN** the history shows both changes in order with source `UNATTENDED` and `CLEARED`
