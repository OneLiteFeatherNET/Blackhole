# Spec Delta

## Purpose
Gives a case one stated structure: a fixed set of sections, every evidence item attributed to who brought it, labelled by
provenance and verification, an internal allegation per case, and a leak close that ends the case with a predefined comment.

## ADDED Requirements

### Requirement: Fixed case sections
The staff case page SHALL render, in this order, the sections Summary, Allegation, Evidence, Conversation, Case file,
Decision, Timeline and Protocol, each as an `h2`, with team notes in the Summary column. A section without data MUST still
render its heading and an empty state that names the next step. The page MUST NOT render a Ban ID value, a Minecraft UUID,
an IP address, a Discord id or a staff `oid`.

#### Scenario: Section order
- **WHEN** a moderator opens a case that has an open appeal, evidence and a presented case file
- **THEN** the sections appear in the order Summary, Allegation, Evidence, Conversation, Case file, Decision, Timeline, Protocol

#### Scenario: Empty evidence
- **WHEN** a case has no evidence and no appeal
- **THEN** the Evidence section shows its heading and the text "No evidence yet" with the next step

### Requirement: Evidence bundles
Evidence of a case SHALL be listed in two bundles, `TEAM` and `PLAYER`. An item MUST carry `broughtBy` (the staff display name, "You" for the viewing staff member, "Unverified staff", "Player" or "System"), `provenance` (`TEAM`, `PLAYER` or `SYSTEM`) and `verification` (`VERIFIED`, `UNVERIFIED`, `PLAYER` or `SYSTEM`). These values are derived on read from existing rows; the feature MUST NOT store them.

#### Scenario: Staff item from a verified actor
- **WHEN** a verified moderator with a display name attached an image
- **THEN** the item is in `TEAM`, has `verification` `VERIFIED` and `broughtBy` equal to that display name for every other viewer, and "You" for that moderator

#### Scenario: Legacy staff item
- **WHEN** an item was attached by an actor without a verified `oid`
- **THEN** `verification` is `UNVERIFIED` and `broughtBy` is "Unverified staff"

#### Scenario: Player upload
- **WHEN** a player attaches a file to a message
- **THEN** the item is in `PLAYER`, `provenance` is `PLAYER`, `verification` is `PLAYER` and `broughtBy` is "Player"

#### Scenario: Automatic record
- **WHEN** a template step or the proxy writes a record that no moderator attached
- **THEN** `provenance` is `SYSTEM`, `verification` is `SYSTEM` and `broughtBy` is "System"

### Requirement: Player-facing labels never expose staff identity
Any view a player can see SHALL show a staff item's display name, or "Moderation team" when no display name is set, and MUST NOT show an `oid`, a trust level or the word "Unverified".

#### Scenario: Staff without a display name
- **WHEN** a player sees an item brought by a verified staff member who has no display name
- **THEN** the label is "Moderation team"

### Requirement: Allegation per case
The case SHALL hold one current allegation, written by `MODERATOR` as an append-only `allegation.set` event with a version number. The allegation is internal. Its text MUST pass `PiiGuard` and `MessageRules` and is at most 4000 characters. Writing an allegation with an `Idempotency-Key` that was already used MUST insert nothing and answer `repeated: true`. The event payload MUST NOT contain the text.

#### Scenario: New version
- **WHEN** a moderator writes a second allegation
- **THEN** the first event remains, the current version is 2 and the event carries no text

#### Scenario: Repeat
- **WHEN** the same `Idempotency-Key` is sent twice
- **THEN** the second answer is the first answer with `repeated: true` and only one event exists

#### Scenario: Player never reads it
- **WHEN** a player reads the conversation, the case file or the public punishment
- **THEN** the allegation text is absent from every answer

### Requirement: Status is locked after a leak close
While a case carries the closing reason `BAN_ID_LEAKED`, a status change MUST answer HTTP 409 `CASE_LEAKED`. Assignee, tags and priority MUST remain changeable. Only a Ban ID reissue MAY change the status again.

#### Scenario: Status change refused
- **WHEN** a moderator sets a leaked case to `IN_REVIEW`
- **THEN** the answer is HTTP 409 `CASE_LEAKED` and the status is unchanged

#### Scenario: Assignee still changes
- **WHEN** a moderator assigns a leaked case to another staff member
- **THEN** the assignee changes and one event is appended

### Requirement: Leak close is one statement
Marking a Ban ID leaked SHALL set the case to `CLOSED` with closing reason `BAN_ID_LEAKED`, forfeit an undecided appeal, delete the punishment's player sessions and append its events, all in one data-modifying statement.

#### Scenario: One statement
- **WHEN** the leak close runs against a case with an open appeal
- **THEN** the status, the closing reason, the appeal status, the session deletion and the events are written by one statement, and a failure of any part leaves all of them unwritten

### Requirement: Closing comment is a player-visible system message
The leak close SHALL append exactly one closing comment: a `message.added` with author `SYSTEM` and visibility `PLAYER`, whose text is the predefined `BAN_ID_LEAKED` closing reason as it is at that moment. A repeat mark MUST change nothing and append nothing.

#### Scenario: First mark
- **WHEN** a moderator marks an open case with a filed appeal as leaked
- **THEN** the case is `CLOSED` with closing reason `BAN_ID_LEAKED`, the appeal is `FORFEITED_BAN_ID_LEAKED`, and the closing comment exists once

#### Scenario: Repeated mark
- **WHEN** the same Ban ID is marked leaked again
- **THEN** no row changes, no event is appended and the answer is `changed: false`

#### Scenario: No Ban ID in the record
- **WHEN** the leak close has run
- **THEN** no event, closing comment, log line or metric label contains the Ban ID value

### Requirement: Case page is reached from the workspace
The case page SHALL show its breadcrumb trail with the workspace name when the switcher is shown, and its heading structure SHALL be one `h1` (the case title) and `h2` sections.

#### Scenario: Administrator trail
- **WHEN** an administrator opens a case
- **THEN** the trail begins with Moderation, then Cases, then the case
