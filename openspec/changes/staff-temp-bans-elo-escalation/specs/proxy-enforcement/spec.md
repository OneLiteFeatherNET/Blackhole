# Spec Delta

## MODIFIED Requirements

### Requirement: Staff ban command
The proxy SHALL provide `/ban <user> <template>` and `/blackhole <user> ban <template>`, requiring permission `blackhole.ban` and accepting only templates of sections with enforcement BAN. It MUST apply the template via the backend with the sender's UUID as source, disconnect a connected target with the rendered punishment message, and reply with target, section, duration and the ELO change; if the backend escalated, the reply MUST also state the permanent ban.

#### Scenario: Ban an online player
- **WHEN** a staff member with `blackhole.ban` runs `/ban Steve hacking-1` and Steve is connected
- **THEN** the backend records a temporary `network` ban, Steve is disconnected with the rendered message, and the sender sees the duration and the `network` score change

#### Scenario: Ban escalates to permanent
- **WHEN** the backend reports that the ban's ELO cost triggered a permanent `network` ban
- **THEN** the sender is told the player is now permanently banned from `network` and the target is disconnected with the permanent ban message

#### Scenario: Ban an offline player
- **WHEN** a staff member with `blackhole.ban` bans a player who is not connected
- **THEN** the backend records the punishment, no disconnect is attempted, and the sender receives the ban result

#### Scenario: Server flag with a non-server template
- **WHEN** a staff member runs `/ban Steve spam-1` where `spam-1` belongs to a MUTE section (the former `--server` flag no longer exists)
- **THEN** nothing is applied and the sender receives `punishment.error.template.wrong_section`

#### Scenario: Backend rejects the ban
- **WHEN** the backend call to apply the ban fails
- **THEN** the sender receives `punishment.error.ban` and the target is not disconnected

#### Scenario: Missing permission
- **WHEN** a sender without `blackhole.ban` runs the ban command
- **THEN** the command is not executed

### Requirement: Staff mute command
The proxy SHALL provide `/mute <user> <template>` and `/blackhole <user> mute <template>`, requiring permission `blackhole.mute` and accepting only templates of sections with enforcement MUTE. It MUST apply the template via the backend with the sender's UUID as source, inform a connected target, and reply with section, duration, ELO change and any escalation. On backend failure the sender MUST receive `punishment.error.mute`.

#### Scenario: Mute a player
- **WHEN** a staff member with `blackhole.mute` runs `/mute Steve spam-1`
- **THEN** the backend records a temporary `chat` mute, Steve is told he is muted, and the sender sees duration and `chat` score change

#### Scenario: Backend rejects the mute
- **WHEN** the backend call to apply the mute fails
- **THEN** the sender receives `punishment.error.mute`

#### Scenario: Missing permission
- **WHEN** a sender without `blackhole.mute` runs the mute command
- **THEN** the command is not executed

### Requirement: Punishment template argument
The template argument SHALL be given as a template key. The proxy MUST select the template with exactly that key from the templates eligible for the command; an unknown key MUST fail the command with `punishment.error.template.not_found` and the entered key, never with an internal error.

#### Scenario: Template selected by reason
- **WHEN** a staff member enters `hacking-1` and a template with that key exists
- **THEN** that template is used for the punishment

#### Scenario: Server flag restricts candidates
- **WHEN** a staff member enters a key no template has
- **THEN** the command fails with `punishment.error.template.not_found` naming the key and nothing is punished

### Requirement: Template tab completion
For the template argument the proxy SHALL suggest the keys of templates eligible for the command (BAN sections for ban, MUTE sections for mute, all for punish) that contain the typed text, compared case-insensitively. When templates cannot be loaded, the proxy MUST suggest nothing instead of failing.

#### Scenario: Suggest matching template reasons
- **WHEN** a staff member tab-completes `/mute Steve sp`
- **THEN** only keys of templates in MUTE sections containing `sp` are suggested

#### Scenario: Backend unavailable during completion
- **WHEN** templates cannot be loaded during tab completion
- **THEN** the proxy returns an empty suggestion list

### Requirement: Staff unban command
The proxy SHALL provide `/unban <user> [section]` and `/blackhole <user> unban [section]`, requiring `blackhole.unban`; the section defaults to `network` and must have enforcement BAN. Targets resolve like ban targets, including offline players. Lifting a permanent punishment MUST additionally require `blackhole.pardon.permanent`. The sender receives `punishment.success.unban`, `punishment.error.unban.none_active` or `punishment.error.unban`.

#### Scenario: Revoke an active ban by UUID
- **WHEN** a staff member with `blackhole.unban` runs `/unban Steve` while Steve is offline with an active temp `network` ban
- **THEN** the backend revokes the ban and the sender receives the unban success message

#### Scenario: Permanent ban without pardon permission
- **WHEN** a staff member without `blackhole.pardon.permanent` tries to unban a player whose `network` ban is permanent
- **THEN** nothing is revoked and the sender receives `punishment.error.pardon.permanent_permission`

#### Scenario: No active ban
- **WHEN** the target has no active punishment in the section
- **THEN** nothing is revoked and the sender receives `punishment.error.unban.none_active`

#### Scenario: Offline name that is not a UUID
- **WHEN** the target argument is neither a connected player, a resolvable player name nor a UUID
- **THEN** the command fails with an argument error and nothing is revoked

#### Scenario: Backend rejects the unban
- **WHEN** the backend revoke call fails
- **THEN** the sender receives `punishment.error.unban`

### Requirement: Staff unmute command
The proxy SHALL provide `/unmute <user> [section]` and `/blackhole <user> unmute [section]`, requiring `blackhole.unmute`; the section defaults to `chat` and must have enforcement MUTE. Target rules and the permanent-pardon permission are the same as for unban. The sender receives `punishment.success.unmute`, `punishment.error.unmute.none_active` or `punishment.error.unmute`.

#### Scenario: Revoke an active mute
- **WHEN** a staff member with `blackhole.unmute` runs `/unmute Steve` while Steve is muted in `chat`
- **THEN** the backend revokes the mute and the sender receives "You have unmuted Steve successfully."

#### Scenario: No active mute
- **WHEN** the target has no active punishment in the section
- **THEN** nothing is revoked and the sender receives `punishment.error.unmute.none_active`

### Requirement: Login enforcement
On every login the proxy SHALL first report an evasion sighting (owner hash and IP) to the backend, then check the player's active punishments in all sections with enforcement BAN using the Redis mirror, falling back to the backend profile when Redis is unavailable. A player with any active punishment in a BAN section MUST be disconnected with its rendered message. Allowed logins record session info.

#### Scenario: Network-banned player connects
- **WHEN** a player with an active `network` punishment logs in
- **THEN** the player is disconnected with the rendered message of that punishment

#### Scenario: Server-scoped ban does not block the proxy
- **WHEN** a player whose only active punishment is in section `chat` logs in
- **THEN** the login is allowed and session info is recorded

#### Scenario: Redis unavailable
- **WHEN** a player logs in while Redis is not connected
- **THEN** the proxy fetches the punishment profile over HTTP and applies the same decision

#### Scenario: Evasion sighting recorded for every login
- **WHEN** any player logs in, banned or not
- **THEN** the proxy submits an evasion sighting with the player's owner hash and IP address to the backend

#### Scenario: Backend unreachable on fallback
- **WHEN** Redis is unavailable and the backend profile request fails
- **THEN** the login is allowed

### Requirement: Chat enforcement
On every chat message the proxy SHALL check the player's active punishments in sections with enforcement MUTE from the local mirror, or once per session from the backend profile when unknown (then cached). A muted player's message MUST be denied with `punishment.error.muted`. Other messages MUST be delivered and forwarded with the owner hash for toxicity scoring; scoring failures MUST NOT affect delivery.

#### Scenario: Muted player chats
- **WHEN** a player with an active `chat` punishment sends a chat message
- **THEN** the message is not delivered and the player receives "You are currently muted and cannot send chat messages."

#### Scenario: Non-muted player chats
- **WHEN** a player without an active MUTE-section punishment sends a chat message
- **THEN** the message is delivered and submitted to the backend as a chat signal for ELO scoring

#### Scenario: Mute state unknown this session
- **WHEN** a player chats and their mute state was not loaded at login
- **THEN** the proxy fetches the profile from the backend once and caches the result for later messages

#### Scenario: Backend unreachable for unknown mute state
- **WHEN** the mute state is unknown and the backend profile request fails
- **THEN** the message is delivered without being scored

### Requirement: Proxy-side sync cache
The proxy SHALL subscribe to `blackhole.punishment.sync` and keep a per-player local cache of active punishments per section, seeded at login by reading the key of every known section and updated from received messages; entries MUST be removed on disconnect. The proxy MUST reload the section list when sections change. Without Redis at startup it MUST keep working via HTTP checks only.

#### Scenario: Login seeds both slots
- **WHEN** a player logs in while Redis is connected
- **THEN** the keys of all known sections are read and cached so the following chat check needs no network call

#### Scenario: Mute applied elsewhere
- **WHEN** a SET message for a MUTE section arrives for a cached player
- **THEN** that player's next chat message is denied without contacting the backend

#### Scenario: Redis down at startup
- **WHEN** the proxy cannot connect to Redis on startup
- **THEN** it logs the error and enforcement continues via backend HTTP calls

### Requirement: Cross-proxy ban kick
When a SET message for a section with enforcement BAN is received and the owner hash matches a player connected to this proxy, the proxy SHALL disconnect that player with the rendered punishment message, regardless of which proxy issued the ban.

#### Scenario: Ban issued on another proxy
- **WHEN** a `network` ban is applied for a player connected to a different proxy than the one where staff issued it
- **THEN** the proxy hosting the player disconnects them immediately

## ADDED Requirements

### Requirement: Generic punish command
The proxy SHALL provide `/punish <user> <template>` requiring `blackhole.punish`, accepting a template of any section and behaving like the ban or mute command matching that section's enforcement kind.

#### Scenario: Punish with a chat template
- **WHEN** a staff member with `blackhole.punish` runs `/punish Steve spam-1`
- **THEN** the result is the same as `/mute Steve spam-1`

### Requirement: ELO info command
The proxy SHALL provide `/elo <user>` requiring `blackhole.elo.view`, showing the target's score in every section together with the section's soft and permanent thresholds and whether a permanent punishment is active.

#### Scenario: Show scores
- **WHEN** a staff member runs `/elo Steve`
- **THEN** the sender sees Steve's `chat` and `network` scores with their thresholds

### Requirement: Staff cannot issue permanent punishments
The proxy SHALL offer no command or argument that creates a permanent punishment; permanent punishments MUST only result from the backend's section escalation.

#### Scenario: No permanent option
- **WHEN** a staff member lists the ban, mute and punish command syntax
- **THEN** none accepts a duration or permanent flag, and every selectable template is temporary
