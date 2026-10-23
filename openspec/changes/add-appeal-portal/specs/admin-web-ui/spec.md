# Spec Delta

## Purpose
Brings the appeal conversation, the leak rule and the transcript export into the admin UI.

## ADDED Requirements

### Requirement: Conversation on the case page
The case page SHALL show the conversation as a thread with each author labelled (staff by name, the player as "Player", redacted and deleted entries marked), a composer for `MODERATOR` and above (2000 character counter, Ctrl+Enter, PII-guard errors shown inline), and banners for a forfeited right and a frozen ticket. `VIEWER` MUST see the thread without a composer.

#### Scenario: Reply
- **WHEN** a moderator sends a reply
- **THEN** it appears in the thread as staff-authored and the composer is cleared

#### Scenario: Viewer
- **WHEN** a viewer opens the case
- **THEN** the thread is shown and no composer is rendered

#### Scenario: Frozen
- **WHEN** the Ban ID was marked leaked after filing
- **THEN** a banner explains the freeze and the composer remains available to staff

### Requirement: Leak, reissue, reveal and export actions
The case page SHALL offer a masked Ban ID with a reveal button (`MODERATOR`+), a dialog to mark the Ban ID leaked with a reason code (`MODERATOR`+), a dialog to reissue it with a mandatory reason (`ADMIN`), and an export of the transcript as JSON or text (`ADMIN`). Controls a role may not use MUST be absent or disabled with an explanation, and the revealed Ban ID MUST NOT persist in browser storage.

#### Scenario: Reveal
- **WHEN** a moderator presses reveal
- **THEN** the Ban ID is shown until the page is left and is not written to browser storage

#### Scenario: Mark leaked
- **WHEN** a moderator confirms the leak dialog with a reason
- **THEN** the timeline shows the leak event and the banner appears

#### Scenario: Export as moderator
- **WHEN** a moderator opens the case
- **THEN** no export control is enabled

### Requirement: Queue shows unanswered player messages
The appeals queue SHALL mark appeals whose latest conversation entry is a player message and link to the case conversation.

#### Scenario: Unanswered
- **WHEN** the newest entry of an open appeal is a player message
- **THEN** the queue row is marked unanswered
