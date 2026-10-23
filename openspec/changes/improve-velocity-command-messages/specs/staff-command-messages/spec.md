# Spec Delta

## Purpose

Defines how the Velocity plugin presents results, errors and usage of its staff commands in chat: a consistent look, readable blocks, hover details, clickable actions and next-step guidance, for players and the console alike.

## ADDED Requirements

### Requirement: Consistent staff message style
Every chat message the plugin sends to a staff member as feedback of a staff command SHALL start with the prefix `Blackhole »` and use one shared set of semantic styles: success green with `✔`, error red with `✖`, warning yellow with `⚠`, info grey. Glyphs MUST be limited to characters the default Minecraft font renders. The English and German texts MUST use the same styles for the same meaning.

#### Scenario: Success message
- **WHEN** a staff member lifts a ban successfully
- **THEN** the message starts with the prefix, then a green `✔`

#### Scenario: Languages agree
- **WHEN** the same result is shown to an English and a German staff member
- **THEN** both texts carry the same prefix, glyphs and colours for the same parts

### Requirement: Highlighted values
Player names SHALL be rendered white and bold, section names aqua and durations yellow. A score change MUST show both scores and the signed delta, coloured by direction (drop red, rise green).

#### Scenario: Score change direction
- **WHEN** a punishment lowers a score from 1000 to 900
- **THEN** the score line shows `Score: 1000 → 900 (−100)` with the delta in red, and a score that rises shows the delta in green

#### Scenario: Player name
- **WHEN** a message names a player
- **THEN** the name is white and bold

### Requirement: Block layout for multi-part results
The punish result, the ELO view, and the profile and history views SHALL be sent as one message of several lines: a header line with the prefix, then indented detail lines. A punish result MUST show target, section, escalation step, duration or end, and score change; an escalation to a permanent punishment MUST be an extra warning line.

#### Scenario: Temporary punishment
- **WHEN** a staff member applies a template that yields step 1 of 2 and ends in 6 hours
- **THEN** one message arrives with a header naming target and section and detail lines for step and duration and for the score change

#### Scenario: Escalation to permanent
- **WHEN** applying a template makes the backend escalate to a permanent punishment
- **THEN** the same message contains an additional warning line saying the player is now permanently punished in that section with the resulting score

### Requirement: Hover details
The player name and the punishment in a punish result, and each entry in the info and history views, SHALL carry hover text. The punishment hover MUST show the reason, the template key, the short punishment id and the absolute end date and time in the configured `timeZone` (or "permanent"). The ELO view MUST show a section's soft and permanent thresholds on hover. Hover MUST NOT contain raw IP addresses, owner hashes or raw UUIDs.

#### Scenario: Punishment hover
- **WHEN** a staff member hovers a punishment in a punish result
- **THEN** reason, template key, short id and the end as an absolute time in the configured zone are shown

### Requirement: Clickable actions
Clicking a short punishment id MUST copy it to the clipboard. Clicking a player name MUST put `/elo <player>` into the chat input without sending it. A punish result MUST offer an undo button (`[Undo]` / `[Rückgängig]`) that puts the matching `/unban <player> <section>` or `/unmute <player> <section>` into the chat input. No click action MUST run a command directly.

#### Scenario: Undo suggested, not run
- **WHEN** a staff member with `blackhole.unban` clicks `[Undo]` on a ban result
- **THEN** `/unban <player> <section>` appears in the chat input and nothing is executed

#### Scenario: Button hidden without permission
- **WHEN** a staff member without `blackhole.unmute` receives a mute result
- **THEN** the message contains no undo button

### Requirement: Undo button permissions
The undo button MUST only be shown to a sender who has the permission of that action: `blackhole.unban` for ban and server sections, `blackhole.unmute` for mute sections. For a permanent punishment it additionally requires `blackhole.pardon.permanent`.

#### Scenario: Permanent punishment
- **WHEN** a sender with `blackhole.unban` but without `blackhole.pardon.permanent` receives a result with an escalation to a permanent ban
- **THEN** the message contains no undo button

### Requirement: Actionable errors
An error message MUST state what to do next. An unknown template key MUST list up to five template keys of the right section kind that match the typed text, or say that none match. A template of the wrong section kind MUST name the command that fits it. A target that is not found MUST say the name is neither online nor known to the backend. Messages MUST NOT disclose backend error details such as status codes.

#### Scenario: Unknown template
- **WHEN** a staff member runs `/ban Steve spamm` and no template `spamm` exists
- **THEN** the message says the template was not found and lists the matching ban or server template keys, or that none match

#### Scenario: Wrong section kind
- **WHEN** a staff member runs `/ban Steve chat-spam` and `chat-spam` is a mute template
- **THEN** the message says it is a mute template and shows `/mute Steve chat-spam`

#### Scenario: Target not found
- **WHEN** a staff member targets a name that is neither online nor known to the backend
- **THEN** the message says so and tells the staff member to tab-complete online names

### Requirement: Usage line
A staff command called with wrong syntax SHALL answer with a usage line showing the command and its arguments.

#### Scenario: Wrong syntax
- **WHEN** a staff member runs `/ban Steve` without a template
- **THEN** the message shows a usage line `/ban <player> <template>`

### Requirement: Console output stays readable
When the receiver is not a player, the plugin SHALL send the same content with colours but without hover and click events and without buttons. Details that exist only in hover for players (short id, template key, absolute end time) MUST be written inline in the message.

#### Scenario: Console result
- **WHEN** a result is rendered for the console
- **THEN** the short id, template key and absolute end time appear in the text and no component carries a hover or click event

### Requirement: Complete localisation
Every staff-facing text of the staff commands, including the info and history views, SHALL exist in the English and German bundles with the same keys and the same argument placeholders.

#### Scenario: Key parity
- **WHEN** a key exists in one bundle
- **THEN** it exists in the other with the same placeholders
