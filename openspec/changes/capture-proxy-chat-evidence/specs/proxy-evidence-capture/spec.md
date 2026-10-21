# Spec Delta

## Purpose

Lets the Velocity proxy optionally attach recent chat as evidence when staff punish a player and show players how much evidence exists without revealing it.

## ADDED Requirements

### Requirement: Opt-in transient chat buffer
When `evidence.chat-capture.enabled` is true, the plugin SHALL keep the last `lines` public chat lines per player in memory for at most `max-age`, keyed by owner hash, and MUST NOT persist or log buffered text. When disabled (the default) nothing is buffered. Buffers MUST be cleared when the player disconnects, and commands MUST NOT be buffered.

#### Scenario: Disabled by default
- **WHEN** the plugin runs with default configuration
- **THEN** chat lines are not stored

#### Scenario: Bounded buffer
- **WHEN** a player sends more than `lines` messages
- **THEN** only the most recent `lines` are retained

#### Scenario: Age eviction
- **WHEN** a buffered line is older than `max-age`
- **THEN** it is no longer included in a snapshot

#### Scenario: Disconnect clears
- **WHEN** a player disconnects
- **THEN** their buffer is removed

### Requirement: Attach chat evidence on staff punishment
When capture is enabled and staff punish a target with `/ban`, `/mute` or `/punish`, the plugin SHALL send the target's buffered lines (plus up to `context-lines` preceding lines of other players) as `CHAT_LOG` evidence with source system `velocity`, every speaker replaced by a stable per-evidence alias (for example "Spieler A") and never a name or UUID, addressed by a fresh `correlationId` that the punishment call also carries. A failure to attach MUST NOT prevent or undo the punishment.

#### Scenario: Evidence attached with the ban
- **WHEN** staff ban a player whose buffer has 12 lines
- **THEN** a CHAT_LOG item with those lines is posted before the punishment call, which carries the same correlation id

#### Scenario: Other speakers pseudonymised
- **WHEN** the snapshot contains lines of two other players
- **THEN** they appear as "Spieler B" and "Spieler C" with no name or UUID, and a URL in a line is replaced by `[url]`

#### Scenario: Evidence service unavailable
- **WHEN** the evidence call times out
- **THEN** the punishment is still applied and staff see a warning without evidence content

### Requirement: Captured chat is pseudonymised and redacted
The plugin MUST replace URLs and IP addresses in captured text with `[url]` and `[ip]` before sending, and MUST NOT send player names or UUIDs.

#### Scenario: IP redacted
- **WHEN** a captured line contains `203.0.113.5`
- **THEN** the sent text contains `[ip]`

### Requirement: Evidence count on the punishment screen
The plugin SHALL append the localised evidence count (for example "Beweise: 3") to the login and chat denial screens when the punishment has at least one item, fetched asynchronously from the count endpoint, and MUST omit the line when the count is zero or unavailable. It MUST NOT display any evidence content.

#### Scenario: Count shown
- **WHEN** a banned player with 3 evidence items tries to join
- **THEN** the denial screen contains "Beweise: 3" in German

#### Scenario: Backend slow
- **WHEN** the count call exceeds the timeout
- **THEN** the denial screen is shown without the evidence line
