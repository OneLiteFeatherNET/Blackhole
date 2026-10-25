## ADDED Requirements

### Requirement: Kick is a punishment kind that is never active

A kick SHALL be recorded as a punishment of kind `KICK` with a reason, a staff actor, a creation time and a case event, and it SHALL have no duration. A kick MUST NOT be placed in a player's active punishment slots and MUST NOT be enforced on a later login.

#### Scenario: Kick does not block a later login

- **WHEN** a player is kicked from the network and reconnects a moment later
- **THEN** the login is allowed and no active punishment is reported for the player

#### Scenario: Kick is not in active lists

- **WHEN** the active punishments of a player are listed after a kick
- **THEN** the kick does not appear in the list and does not count in the active evidence count

#### Scenario: Kick is in the history

- **WHEN** the player's punishment history is read
- **THEN** the kick appears with its reason, actor, time and kind `KICK`

#### Scenario: Kick of a permanently banned player

- **WHEN** staff kick a player who is already permanently banned in the target section
- **THEN** the kick is recorded and accepted with a warning in the response, and the ban is unchanged

### Requirement: Network kick disconnects with a kick screen

A network kick SHALL disconnect the player from the proxy with a kick screen that shows the reason. The player SHALL be able to reconnect immediately.

#### Scenario: Network kick of an online player

- **WHEN** staff kick a player connected to any proxy from the network
- **THEN** that proxy disconnects the player with the kick screen and the player can connect again at once

#### Scenario: Kick screen language

- **WHEN** a kick screen is shown to a player whose client is set to German
- **THEN** the kick screen is still English

### Requirement: Server or section kick moves the player to the fallback

A server or section kick SHALL move the player off the current server to the configured fallback server when the current server is covered by the target. If the fallback is missing, banned for the player, unreachable or is the target itself, the proxy SHALL disconnect the player with the kick screen instead.

#### Scenario: Player on a covered server

- **WHEN** staff kick a player from section `lobby-section` and the player is on a server of that section
- **THEN** the player is moved to the fallback server and the kick is acknowledged as moved

#### Scenario: Player on another server

- **WHEN** staff kick a player from server `survival` and the player is on `creative`
- **THEN** nothing moves, the player stays on `creative`, and the status is `NOT_ON_TARGET`

#### Scenario: Fallback is the target

- **WHEN** the target server is the fallback server and the player is on it
- **THEN** the proxy disconnects the player with the kick screen

### Requirement: Kick outcome is reported honestly

A kick SHALL report one of `PENDING`, `DELIVERED`, `NOT_ON_TARGET` or `NOT_ONLINE`. `NOT_ONLINE` MUST NOT be reported before the acknowledgement deadline has passed, and MUST be reported after it when no proxy found the player.

#### Scenario: Player found and kicked

- **WHEN** a proxy acknowledges that it found and moved or disconnected the player before the deadline
- **THEN** the status is `DELIVERED`

#### Scenario: Player offline

- **WHEN** no proxy acknowledges the kick and the deadline passes
- **THEN** the status is `NOT_ONLINE` and the kick remains recorded

#### Scenario: Status read before the deadline

- **WHEN** the status is read before the deadline with no acknowledgement
- **THEN** the status is `PENDING`

### Requirement: Kick requests are idempotent and validated

`POST /kick` and `POST /kick/mass` SHALL require an `Idempotency-Key` header. A repeat with the same key SHALL write nothing and answer the same kick. A reason SHALL be 1 to 200 characters and MUST be refused when it contains a UUID, an IP address or a Ban ID. A server or section scope MUST have a target that exists.

#### Scenario: Double submit

- **WHEN** the same `Idempotency-Key` is sent twice
- **THEN** one kick is recorded and the second answer names the same kick with `repeated: true`

#### Scenario: Missing key

- **WHEN** a kick is posted without `Idempotency-Key`
- **THEN** the request is refused with 400 and nothing is recorded

#### Scenario: Reason contains an IP address

- **WHEN** the reason contains an IP address
- **THEN** the request is refused with 400 and the reason is not stored

#### Scenario: Unknown server target

- **WHEN** a server kick names a server that does not exist
- **THEN** the request is refused with 422 `KICK_TARGET_INVALID`

### Requirement: Roles for kick routes

A kick by a MODERATOR or above SHALL be allowed through `POST /kick`. A mass kick SHALL be allowed only for ADMIN. The `PROXY` role SHALL be allowed to read a kick and to post an acknowledgement, and SHALL NOT be allowed to create a kick.

#### Scenario: Moderator mass kick

- **WHEN** a MODERATOR posts a mass kick
- **THEN** the request is refused and no action is recorded

#### Scenario: Proxy creates a kick

- **WHEN** the `PROXY` role posts to `POST /kick`
- **THEN** the request is refused

### Requirement: Mass kick is one audited action without punishments

A mass kick SHALL move every player on the target server or section, through the proxies, to the fallback. It SHALL write one `kick_action` record and one `kick.mass` domain event, and it MUST NOT write a punishment, a case event or ELO for any player.

#### Scenario: Maintenance mass kick

- **WHEN** an ADMIN mass-kicks server `survival` for maintenance
- **THEN** every proxy moves its players on `survival` to the fallback, the player histories are unchanged, and the admin audit view shows one action with the count of players moved

#### Scenario: No player identifiers in the mass record

- **WHEN** the mass kick record or its event is read
- **THEN** it contains no owner hash and no player name

### Requirement: Kick events carry no personal data

The `punishment.kick` and `kick.mass` events SHALL carry only identifiers, the owner hash, the scope and the target. They MUST NOT carry the reason text, a player name, a raw UUID or an IP address. A proxy SHALL read the reason over HTTP.

#### Scenario: Event payload scan

- **WHEN** a kick is published
- **THEN** the payload contains the owner hash and no reason text

### Requirement: Kick in a template

A template step of kind `KICK` SHALL apply as a kick with the scope named by the apply request, defaulting to `NETWORK` when the request names no scope. Its `eloCost` SHALL apply as for any template step, and it SHALL count towards escalation of its escalation group.

#### Scenario: Escalation from kick to ban

- **WHEN** a player is kicked by a template whose first step is a kick and whose second step is a one-day ban, and the same template is applied again within the escalation window
- **THEN** the second application uses the one-day ban step
