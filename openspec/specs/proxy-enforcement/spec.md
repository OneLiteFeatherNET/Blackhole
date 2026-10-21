# proxy-enforcement Specification

## Purpose
Describes how the Velocity proxy plugin lets staff issue and revoke punishments, enforces active bans and mutes on players connected to the proxy, feeds chat and session data to the backend, and stays in sync with backend punishment state through a shared Redis mirror.

## Requirements

### Requirement: Plugin configuration
The proxy plugin SHALL read its settings from `config.json` in its data directory, containing `baseUrl` (backend API base URL, default `http://localhost:8080`) and `redisUri` (Redis connection URI, default `redis://localhost:6379`). When the file does not exist, the plugin MUST create it with these defaults and use them.

#### Scenario: First start without a config file
- **WHEN** the proxy starts and `config.json` does not exist in the plugin data directory
- **THEN** the plugin writes `config.json` with `baseUrl` `http://localhost:8080` and `redisUri` `redis://localhost:6379` and uses those values

#### Scenario: Existing config file
- **WHEN** the proxy starts and `config.json` exists
- **THEN** the plugin sends backend API calls to the configured `baseUrl` and connects to Redis at the configured `redisUri`

### Requirement: Player identity hashing
The proxy SHALL identify players to the backend and in Redis only by a SHA hash of their Minecraft UUID (the owner hash), never by the raw UUID, for punishment profile lookups, punishments, chat signals, session info, and Redis keys.

#### Scenario: Profile lookup uses the owner hash
- **WHEN** the proxy fetches a player's punishment profile from the backend
- **THEN** the request identifies the player by the SHA hash of their UUID

### Requirement: Staff target resolution
Commands that target a player by name SHALL resolve the name to a connected player first; when no connected player has that name, the proxy MUST resolve it through the backend player lookup endpoint so offline players can be targeted. If neither yields a player, the command MUST fail with an argument error and no punishment is applied.

#### Scenario: Online target
- **WHEN** a staff member targets a name of a player connected to the proxy
- **THEN** the proxy uses that player's UUID and current username as the target

#### Scenario: Offline target resolved by the backend
- **WHEN** a staff member targets a name that is not connected but the backend player lookup resolves it
- **THEN** the proxy uses the resolved UUID and name as the target and loads that player's punishment profile

#### Scenario: Unknown target
- **WHEN** a staff member targets a name that is neither connected nor resolvable by the backend
- **THEN** the command fails with a "player not found" argument error and nothing is punished

### Requirement: Player name tab completion
For the player argument of the ban, mute and info commands the proxy SHALL suggest the usernames of currently connected players whose name starts with the typed prefix, compared case-insensitively.

#### Scenario: Suggest connected players
- **WHEN** a staff member tab-completes the player argument with prefix `no`
- **THEN** the proxy suggests every connected player whose username starts with `no` ignoring case, and no offline players

### Requirement: Punishment template argument
The template argument of the ban and mute commands SHALL be given as a template reason; the proxy MUST load all templates from the backend and select the first whose reason equals the input exactly. With the `--server` flag, only templates of type SERVER are considered. If the backend returns no eligible templates, the command MUST fail with "No templates found".

#### Scenario: Template selected by reason
- **WHEN** a staff member enters a template argument equal to an existing template's reason
- **THEN** that template is used for the punishment

#### Scenario: Server flag restricts candidates
- **WHEN** a staff member runs a ban with `--server` and no SERVER-type template exists
- **THEN** the command fails with "No templates found" and nothing is punished

### Requirement: Template tab completion
For the template argument the proxy SHALL suggest the reasons of templates loaded from the backend that contain the typed text, compared case-insensitively. When the backend cannot be reached, the proxy MUST suggest nothing instead of failing.

#### Scenario: Suggest matching template reasons
- **WHEN** a staff member tab-completes the template argument with `hack`
- **THEN** the proxy suggests every template reason containing `hack` ignoring case

#### Scenario: Backend unavailable during completion
- **WHEN** the backend template request fails during tab completion
- **THEN** the proxy returns an empty suggestion list

### Requirement: Staff ban command
The proxy SHALL provide `/ban <user> <template>` and `/blackhole <user> ban <template>` with optional `--server` flag, requiring permission `blackhole.ban`. It MUST apply the template to the target via the backend with the sender's UUID as source, disconnect the target if connected using the rendered template message, and reply `punishment.success.ban` with the target name. With `--server`, a non-SERVER template MUST be rejected.

#### Scenario: Ban an online player
- **WHEN** a staff member with `blackhole.ban` runs `/ban Steve Hacking` and Steve is connected
- **THEN** the backend records the punishment, Steve is disconnected with the rendered template message, and the sender receives "You have banned Steve successfully."

#### Scenario: Ban an offline player
- **WHEN** a staff member with `blackhole.ban` bans a player who is not connected
- **THEN** the backend records the punishment, no disconnect is attempted, and the sender receives the ban success message

#### Scenario: Server flag with a non-server template
- **WHEN** a staff member runs the ban command with `--server` and a template whose type is not SERVER
- **THEN** no punishment is applied and the sender receives `punishment.error.template.only_server`

#### Scenario: Backend rejects the ban
- **WHEN** the backend call to apply the ban fails
- **THEN** the sender receives `punishment.error.ban` and the target is not disconnected

#### Scenario: Missing permission
- **WHEN** a sender without `blackhole.ban` runs the ban command
- **THEN** the command is not executed

### Requirement: Staff mute command
The proxy SHALL provide `/mute <user> <template>` and `/blackhole <user> mute <template>`, requiring permission `blackhole.mute`. It MUST apply the template to the target via the backend with the sender's UUID as source and send the sender the rendered template message for the updated profile. On backend failure the sender MUST receive `punishment.error.mute`.

#### Scenario: Mute a player
- **WHEN** a staff member with `blackhole.mute` runs `/mute Steve Spam`
- **THEN** the backend records the punishment and the sender receives the rendered template message for Steve's updated profile

#### Scenario: Backend rejects the mute
- **WHEN** the backend call to apply the mute fails
- **THEN** the sender receives `punishment.error.mute`

#### Scenario: Missing permission
- **WHEN** a sender without `blackhole.mute` runs the mute command
- **THEN** the command is not executed

### Requirement: Staff unban command
The proxy SHALL provide `/unban <user>` and `/blackhole <user> unban`, requiring permission `blackhole.unban`. The target MUST be a connected player name or a raw UUID. If the target has no active ban the sender receives `punishment.error.unban.none_active`; otherwise the ban is revoked via the backend with the sender's UUID and the sender receives `punishment.success.unban`.

#### Scenario: Revoke an active ban by UUID
- **WHEN** a staff member with `blackhole.unban` runs `/unban <uuid>` for a player with an active ban
- **THEN** the backend revokes the ban and the sender receives "You have unbanned <uuid> successfully."

#### Scenario: No active ban
- **WHEN** the target has no active ban
- **THEN** nothing is revoked and the sender receives `punishment.error.unban.none_active`

#### Scenario: Offline name that is not a UUID
- **WHEN** the target argument is not a connected player and not a valid UUID
- **THEN** the command fails with an argument error and nothing is revoked

#### Scenario: Backend rejects the unban
- **WHEN** the backend revoke call fails
- **THEN** the sender receives `punishment.error.unban`

### Requirement: Staff unmute command
The proxy SHALL provide `/unmute <user>` and `/blackhole <user> unmute`, requiring permission `blackhole.unmute`, with the same target rules as unban. If the target has no active mute the sender receives `punishment.error.unmute.none_active`; otherwise the mute is revoked via the backend with the sender's UUID and the sender receives `punishment.success.unmute`, or `punishment.error.unmute` on failure.

#### Scenario: Revoke an active mute
- **WHEN** a staff member with `blackhole.unmute` runs `/unmute Steve` while Steve is connected and muted
- **THEN** the backend revokes the mute and the sender receives "You have unmuted Steve successfully."

#### Scenario: No active mute
- **WHEN** the target has no active mute
- **THEN** nothing is revoked and the sender receives `punishment.error.unmute.none_active`

### Requirement: Punishment info command
The proxy SHALL provide `/blackhole <user> info`, requiring permission `blackhole.user.info`, which sends the sender a summary of the target's punishment profile labelled with the target's resolved name.

#### Scenario: Show profile summary
- **WHEN** a staff member with `blackhole.user.info` runs `/blackhole Steve info`
- **THEN** the sender receives Steve's punishment profile summary

### Requirement: Punishment history command
The proxy SHALL provide `/blackhole <user> history <sort>`, requiring permission `blackhole.ban.history`, which sends the sender the target's punishment history labelled with the target's resolved name.

#### Scenario: Show history
- **WHEN** a staff member with `blackhole.ban.history` runs `/blackhole Steve history newest`
- **THEN** the sender receives Steve's punishment history

### Requirement: Login enforcement
On every login the proxy SHALL first report an evasion sighting (owner hash and IP) to the backend, then check for an active ban using the Redis mirror, falling back to the backend profile when Redis is unavailable. A player with an active ban of type NETWORK MUST be disconnected with the rendered template message; bans of other types MUST NOT block login. Allowed logins record session info (protocol version).

#### Scenario: Network-banned player connects
- **WHEN** a player whose active ban has type NETWORK logs in
- **THEN** the player is disconnected with the rendered message of the ban's template

#### Scenario: Server-scoped ban does not block the proxy
- **WHEN** a player whose active ban has type SERVER logs in
- **THEN** the login is allowed and session info is recorded

#### Scenario: Redis unavailable
- **WHEN** a player logs in while Redis is not connected
- **THEN** the proxy fetches the punishment profile over HTTP and applies the same NETWORK-ban decision

#### Scenario: Evasion sighting recorded for every login
- **WHEN** any player logs in, banned or not
- **THEN** the proxy submits an evasion sighting with the player's owner hash and IP address to the backend

#### Scenario: Backend unreachable on fallback
- **WHEN** Redis is unavailable and the backend profile request fails
- **THEN** the login is allowed

### Requirement: Chat enforcement
On every chat message the proxy SHALL check the player's mute state from the local mirror, or once per session from the backend profile when unknown (then cached). A muted player's message MUST be denied and the player receives `punishment.error.muted`. A non-muted player's message MUST be delivered and forwarded with the owner hash to the backend chat-signal endpoint for toxicity scoring; scoring failures MUST NOT affect delivery.

#### Scenario: Muted player chats
- **WHEN** a player with an active mute sends a chat message
- **THEN** the message is not delivered and the player receives "You are currently muted and cannot send chat messages."

#### Scenario: Non-muted player chats
- **WHEN** a player without an active mute sends a chat message
- **THEN** the message is delivered and submitted to the backend as a chat signal for ELO scoring

#### Scenario: Mute state unknown this session
- **WHEN** a player chats and their mute state was not loaded at login
- **THEN** the proxy fetches the profile from the backend once and caches the result for later messages

#### Scenario: Backend unreachable for unknown mute state
- **WHEN** the mute state is unknown and the backend profile request fails
- **THEN** the message is delivered without being scored

### Requirement: Client brand reporting
When a connected player's client brand becomes known, the proxy SHALL send it to the backend as session info for that player's owner hash; failures MUST be ignored.

#### Scenario: Brand reported
- **WHEN** a player's client reports the brand `vanilla`
- **THEN** the proxy updates that player's session info in the backend with client brand `vanilla`

### Requirement: Redis punishment mirror
The backend SHALL be the sole writer of active-punishment state to Redis keys `blackhole:punish:ban:<ownerHash>` and `blackhole:punish:chatban:<ownerHash>`, holding a JSON message (owner, slot BAN/CHAT_BAN, state SET/CLEARED, type, punishmentIdentifier, templateIdentifier, expiresAt), and SHALL publish each change on channel `blackhole.punishment.sync`. Proxies MUST only read.

#### Scenario: Punishment created
- **WHEN** the backend processes a `punishment.created` event of type CHAT
- **THEN** it writes the chat-ban key for the owner with state SET and publishes the same JSON on `blackhole.punishment.sync`

#### Scenario: Non-chat punishment created
- **WHEN** the backend processes a `punishment.created` event of type NETWORK or SERVER
- **THEN** it writes the ban key for the owner with state SET and that type, and publishes it

#### Scenario: Temporary punishment
- **WHEN** a created punishment has an expiry in the future
- **THEN** the Redis key is written with a TTL ending at that expiry, and a punishment already expired at processing time deletes the key instead

#### Scenario: Punishment lifted
- **WHEN** the backend processes `punishment.expired`, `punishment.revoked`, or `appeal.resolved` with decision `GRANTED_FULL_LIFT`
- **THEN** it deletes the matching key and publishes a CLEARED message on `blackhole.punishment.sync`

#### Scenario: Redis write failure
- **WHEN** a Redis write fails in the backend
- **THEN** the failure is logged, the event is acknowledged without retry, and the originating request is unaffected

### Requirement: Proxy-side sync cache
The proxy SHALL subscribe to `blackhole.punishment.sync` and keep a per-player local cache of ban and mute state, seeded at login by reading both Redis keys and updated from received messages; entries MUST be removed when the player disconnects. If Redis cannot be reached at startup the proxy MUST keep working using HTTP checks only.

#### Scenario: Login seeds both slots
- **WHEN** a player logs in while Redis is connected
- **THEN** both the ban and chat-ban keys are read and cached so the following chat check needs no network call

#### Scenario: Mute applied elsewhere
- **WHEN** a SET message for slot CHAT_BAN arrives for a cached player
- **THEN** that player's next chat message is denied without contacting the backend

#### Scenario: Redis down at startup
- **WHEN** the proxy cannot connect to Redis on startup
- **THEN** it logs the error and enforcement continues via backend HTTP calls

### Requirement: Cross-proxy ban kick
When a SET message for slot BAN is received and the affected owner hash matches a player connected to this proxy, the proxy SHALL fetch that player's profile and, if it has an active ban, disconnect the player with the rendered template message, regardless of which proxy issued the ban.

#### Scenario: Ban issued on another proxy
- **WHEN** a ban is applied for a player connected to a different proxy than the one where staff issued it
- **THEN** the proxy hosting the player disconnects them immediately with the ban's template message

### Requirement: Localized messages
The proxy SHALL render player- and staff-facing messages from translation keys with English as the default locale and German available, including `punishment.error.muted`, `punishment.success.ban`, `punishment.success.unban`, `punishment.success.unmute` and the related error keys.

#### Scenario: German client
- **WHEN** a muted player whose client locale is German sends a chat message
- **THEN** the denial message is shown in German
