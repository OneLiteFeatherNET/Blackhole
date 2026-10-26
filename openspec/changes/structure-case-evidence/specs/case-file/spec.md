# Spec Delta

## Purpose
Lets a moderator choose existing case data in one picker, present the shareable part to the player as a case file for the
open appeal, and lets the player answer each presented item with linked counter-evidence that staff see as a threaded pair.

## ADDED Requirements

### Requirement: Only the shareable set is presentable
Only chat excerpts, image files, previous punishments (summary), reports against the player (summary) and kick records (summary) SHALL be presentable to a player. Any other data type MUST be refused for presentation (HTTP 422 `NOT_SHAREABLE`) and MUST carry a `lockReason` in the picker.

#### Scenario: Anticheat record
- **WHEN** a moderator presents an anticheat record
- **THEN** the answer is HTTP 422 `NOT_SHAREABLE` and no item is created

#### Scenario: Evasion hint in the picker
- **WHEN** the picker lists an evasion hint
- **THEN** the row has `shareability` `NEVER_OFFERED`, no count and no date, and no checkbox

#### Scenario: Shareable set matches the table
- **WHEN** the shareable set is compared with the table in the design
- **THEN** every entry of the set is shareable and no other data type is

### Requirement: Evasion hints never reach a player
Evasion hints SHALL NOT appear in any player-facing answer, even as a count, and the picker MUST show them to moderators without a count, a date or a preview.

#### Scenario: Evasion hint in a player answer
- **WHEN** any player route is called for a case that has evasion hints
- **THEN** the response contains no evasion data, count or owner hash

### Requirement: Summaries are snapshots
A presented summary (previous punishment, report, kick) SHALL be stored as a snapshot of whitelisted fields only, at presentation time. The whitelist for a previous punishment is kind, applied date and outcome; for a report it is category, date and outcome; for a kick it is applied date, scope and the canned reason text when one was used. Reporter identity, reporter hash, report description, resolution note, staff names and free-text kick reasons MUST NOT be copied.

#### Scenario: Report snapshot
- **WHEN** a moderator presents a report against the player
- **THEN** the stored item contains category, date and outcome only, and the player's view shows exactly those fields

#### Scenario: Later report change
- **WHEN** the report is resolved after the case file was presented
- **THEN** the player's view still shows the snapshot outcome until a new item is presented

### Requirement: Excerpts keep the minimisation rules
A presented chat excerpt SHALL keep the rules of the share feature: at most 20 selected lines, other speakers rendered as "Player N", the appellant rendered as "You", times as offsets, and `confirmedMinimised` true (HTTP 400 `NOT_CONFIRMED` otherwise). Each line MUST pass `PiiGuard` again on presentation (HTTP 422 `PII_REJECTED`, with the line index only).

#### Scenario: Too many lines
- **WHEN** a moderator selects 21 lines
- **THEN** the answer is HTTP 400 `SELECTION_TOO_LONG` and nothing is created

#### Scenario: Not confirmed
- **WHEN** a presentation contains an excerpt and `confirmedMinimised` is false
- **THEN** the answer is HTTP 400 `NOT_CONFIRMED`

### Requirement: Preview writes nothing
The preview of a case file draft SHALL render the player's view with the same renderer as the player route and MUST NOT write any row or event.

#### Scenario: Preview
- **WHEN** a moderator previews a draft
- **THEN** the answer contains the player rendering and the case file count is unchanged

### Requirement: Presenting a case file
Presenting a case file SHALL require an open appeal on the case (HTTP 409 `NO_APPEAL` otherwise), a case that is not closed (HTTP 409 `CASE_CLOSED`) and a staff display name (HTTP 409 `STAFF_PROFILE_REQUIRED`). Presentation MUST be idempotent on its `Idempotency-Key`, MUST create the items in one data-modifying statement and MUST append `case.file_presented` plus one `evidence.shared` per file or excerpt, without content.

#### Scenario: No appeal
- **WHEN** a moderator presents a case file on a case with no appeal
- **THEN** the answer is HTTP 409 `NO_APPEAL`

#### Scenario: Repeat with the same key
- **WHEN** the same presentation request is sent twice
- **THEN** one case file exists, the second answer carries `repeated: true`, and no second event is appended

### Requirement: Withdrawal and review
A moderator SHALL be able to withdraw one item (files and excerpts through the share revoke, summaries by a withdrawal mark) and to mark an item reviewed. A withdrawn item MUST stay visible to staff, MUST be marked withdrawn for the player and MUST take no new responses (HTTP 409 `ITEM_WITHDRAWN`).

#### Scenario: Withdrawn item
- **WHEN** a player answers a withdrawn item
- **THEN** the answer is HTTP 409 `ITEM_WITHDRAWN` and no message is stored

### Requirement: Counter-evidence is linked to one item
A player answer to a presented item SHALL be a `message.added` event with author `PLAYER`, visibility `PLAYER`, and the payload fields `caseFileItemId` and `stance` (`DISPUTE`, `ACCEPT` or `NO_COMMENT`). It MAY carry text and up to five staged files of the same appeal, which bind through the existing claim. The player MUST NOT be able to answer an item of another case or another appeal (HTTP 404, the same answer as an unknown id).

#### Scenario: Answer with files
- **WHEN** a player answers an item with a text and two staged images
- **THEN** one message exists with `caseFileItemId`, the two files are bound to it, and the answer is HTTP 201

#### Scenario: Foreign item
- **WHEN** a player answers an item id that belongs to another punishment
- **THEN** the answer is HTTP 404 and nothing is stored

#### Scenario: Leaked case
- **WHEN** a player answers an item after the Ban ID was marked leaked
- **THEN** the answer is HTTP 409 `CASE_CLOSED`

### Requirement: Threaded staff view
The staff case file view SHALL show each item as one card with its team side (preview, presenter display name, presentation time, staff text) and its player side (responses in order with stance, text and files, then staff replies), so that each card reads as a team evidence and player response pair. A card with no response MUST say "No response yet" and MUST NOT be marked answered.

#### Scenario: Pair
- **WHEN** a player answers an item
- **THEN** the card shows the presented item on the team side and the response with its stance on the player side

#### Scenario: Unanswered
- **WHEN** no response exists
- **THEN** the card says "No response yet" and its status is `PRESENTED`

### Requirement: Player view of presented items
The player's case file route SHALL return only presented, not withdrawn items of the player's own appeal, rendered by the presentation renderer, with the presenter shown as the staff display name or "Moderation team". It MUST NOT return items of another appeal, withdrawn items as content, or any internal field, and it MUST answer HTTP 403 for a `LIMITED` session.

#### Scenario: LIMITED session
- **WHEN** a leaked Ban ID logs in and requests the case file
- **THEN** the answer is HTTP 403

#### Scenario: Withdrawn item
- **WHEN** a moderator withdrew an item and the player reads the case file
- **THEN** the item appears with status `WITHDRAWN` and no content
