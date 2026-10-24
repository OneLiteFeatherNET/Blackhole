# Spec Delta

## Purpose
Turns the case page's conversation into a chat with files, a share dialog and live updates.

## ADDED Requirements

### Requirement: Conversation as a chat
The conversation section SHALL show messages as bubbles (staff on one side, the player on the other, Discord, bot and external participants as today) with grouping, timestamps, day separators, a "Seen" marker from `playerSeenSeq`, Markdown drawn from the tree, image thumbnails with a lightbox, file cards, and a composer with attach button, drag and drop, paste, per-file state and a counter. Strings MUST exist in German and English. External links MUST open through the interstitial.

#### Scenario: Markdown
- **WHEN** a message contains `**bold**` and a link
- **THEN** the bubble shows bold text and the link, which opens the interstitial

#### Scenario: Seen
- **WHEN** `playerSeenSeq` reaches the last staff message
- **THEN** "Seen" appears under it

#### Scenario: Warning before send
- **WHEN** a moderator attaches a file and presses Send
- **THEN** the panel states that the player will see the file

### Requirement: Provenance label
Every place that shows evidence SHALL label player-supplied items "Player-supplied, unverified" and the case header SHALL show the total with the player-supplied part.

#### Scenario: Evidence list
- **WHEN** the evidence list contains a player-supplied image
- **THEN** it carries the label

### Requirement: Share dialog
A moderator SHALL be able to share an evidence item from the evidence list or the conversation. For a chat capture the dialog MUST let the moderator choose up to 20 lines and the appellant's speaker, show the exact preview the player will see, and require a checked confirmation. For a screenshot it MUST show the image and a stronger confirmation text. Revoking MUST ask for a reason code and warn that it cannot recall what the player saw. Users below `MODERATOR` MUST NOT see these actions.

#### Scenario: Confirmation required
- **WHEN** the confirmation is not checked
- **THEN** the share action is disabled

#### Scenario: Preview
- **WHEN** the moderator selects lines
- **THEN** the dialog shows them as "You" and "Player N"

#### Scenario: Viewer
- **WHEN** a viewer opens the evidence list
- **THEN** no share action is rendered

### Requirement: Live updates in the panel
The conversation section SHALL update from the staff stream, fall back to polling, and show shares, revokes and the "Seen" marker without a reload.

#### Scenario: Player writes
- **WHEN** the player sends a message while the case page is open
- **THEN** the message appears within seconds without a reload
