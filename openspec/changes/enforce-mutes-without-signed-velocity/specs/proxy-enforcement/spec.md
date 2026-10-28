## MODIFIED Requirements

### Requirement: Chat enforcement
On every chat message the proxy SHALL check the player's mute state from the local mirror, or once per session from the backend profile when unknown (then cached). A muted player's message MUST be denied and the player receives `punishment.error.muted`. When SignedVelocity is not loaded on the proxy and the message is signed (Minecraft 1.19.1 and newer), the proxy MUST NOT deliver the message; it SHALL disconnect the player with the mute screen as the reason instead. A non-muted player's message MUST be delivered and forwarded with the owner hash to the backend chat-signal endpoint for toxicity scoring; scoring failures MUST NOT affect delivery.

#### Scenario: Muted player chats
- **WHEN** a player with an active mute sends a chat message and SignedVelocity is loaded on the proxy
- **THEN** the message is not delivered and the player receives "You are currently muted and cannot send chat messages."

#### Scenario: Muted player with a signed message and no SignedVelocity
- **WHEN** a muted player on a 1.19.1 or newer client sends a chat message and SignedVelocity is not loaded on the proxy
- **THEN** the message is not delivered, the player is disconnected with the mute screen as the reason, and the disconnect is logged once per player without the message text

#### Scenario: Muted player with an unsigned message and no SignedVelocity
- **WHEN** a muted player on a client older than 1.19.1 sends a chat message and SignedVelocity is not loaded on the proxy
- **THEN** the message is not delivered and the player receives the mute notice, without a disconnect

#### Scenario: Non-muted player chats
- **WHEN** a player without an active mute sends a chat message
- **THEN** the message is delivered and submitted to the backend as a chat signal for ELO scoring

#### Scenario: Mute state unknown this session
- **WHEN** a player chats and their mute state was not loaded at login
- **THEN** the proxy fetches the profile from the backend once and caches the result for later messages

#### Scenario: Backend unreachable for unknown mute state
- **WHEN** the mute state is unknown and the backend profile request fails
- **THEN** the message is delivered without being scored

## ADDED Requirements

### Requirement: Muted command enforcement
The proxy SHALL deny the commands listed in its `mute.commands` setting when the sender has an active mute. A denied command MUST NOT be run or forwarded, and the player receives `punishment.error.muted`. A command with signed arguments MUST follow the signed-chat rule of the chat enforcement requirement.

#### Scenario: Muted player sends a private message
- **WHEN** a muted player runs `/msg Alex hello` and SignedVelocity is loaded
- **THEN** the command is not run, Alex receives nothing, and the player receives the mute notice

#### Scenario: Muted player with an unsigned command and no SignedVelocity
- **WHEN** a muted player runs `/me waves` from a client that sends it unsigned, and SignedVelocity is not loaded
- **THEN** the command is denied and the player receives the mute notice without a disconnect

#### Scenario: Muted player with a signed command and no SignedVelocity
- **WHEN** a muted player runs `/msg Alex hello` from a 1.19.1 or newer client and SignedVelocity is not loaded
- **THEN** the command is not run and the player is disconnected with the mute screen as the reason

#### Scenario: Namespaced command
- **WHEN** a muted player runs `/minecraft:msg Alex hello` and SignedVelocity is loaded
- **THEN** the command is denied, because `namespace:label` is matched as `label` as well

#### Scenario: Command not in the list
- **WHEN** a muted player runs `/spawn`
- **THEN** the command is run as usual, because only the listed commands are denied for mutes

#### Scenario: Setting empty
- **WHEN** `mute.commands` is set to an empty list
- **THEN** no command is denied for mutes and chat enforcement is unchanged

### Requirement: SignedVelocity relay state
The proxy SHALL check at startup whether SignedVelocity is loaded and log an error line, not only a warning, when it is missing. The proxy SHALL report its relay state (present or absent, and the SignedVelocity version when known) to the backend under its machine identity, and the backend MUST show a banner on the admin panel and set a metric while any proxy reports the relay as absent. A proxy that has not reported is shown as unknown, not as present.

#### Scenario: Proxy starts without SignedVelocity
- **WHEN** the proxy starts and SignedVelocity is not loaded
- **THEN** the proxy logs an error that names the consequence for mutes and reports `signedVelocity: absent`

#### Scenario: Admin sees the banner
- **WHEN** a staff member with the ADMIN or MODERATOR role opens the admin panel while a proxy reports the relay as absent
- **THEN** the panel shows a banner naming the proxy and that signed mutes are blocked by disconnect

#### Scenario: Proxy has not reported
- **WHEN** no proxy has reported its relay state within the configured window
- **THEN** the panel shows the relay state as unknown and the metric is not set to present

### Requirement: Backend server coverage for SignedVelocity
The operator runbook SHALL list every backend server that players can reach through the proxy and state that each one needs the matching SignedVelocity backend plugin. The proxy plugin SHALL NOT be deployed without the backend plugin on every server it can forward a player to.

#### Scenario: New backend server
- **WHEN** an operator adds a backend server to the network
- **THEN** the runbook checklist requires the SignedVelocity backend plugin on it before players are sent there
