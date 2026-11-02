## ADDED Requirements

### Requirement: One header for every entity page
Case, appeal, report and player pages SHALL show the same header parts in the same order: the type label, the short id, the state pill, the owner, the assignee, the primary action, the overflow menu and the breadcrumb.

#### Scenario: Case header
- **WHEN** a moderator opens a case
- **THEN** the header shows CASE, the short reference, the case status as a text pill, the player, the assignee and the primary action

#### Scenario: Report header
- **WHEN** a moderator opens a report
- **THEN** the header shows REPORT, the report id and its status, without an assignee part

### Requirement: State is shown in words
A state pill SHALL show the state as text, not only as a colour. Case states SHALL be OPEN, IN_REVIEW, WAITING and CLOSED. Appeal states SHALL show the pending or decided state as text, and report states SHALL be OPEN, UNDER_REVIEW, ACTIONED or DISMISSED.

#### Scenario: Closed case
- **WHEN** a case is CLOSED
- **THEN** the pill reads "Closed" and the primary action is Reopen

#### Scenario: Colour blindness
- **WHEN** the pill is shown in greyscale
- **THEN** the state can still be read from its text

### Requirement: Disabled actions say why
A primary or overflow action that is disabled SHALL show its reason as visible text under the header, and the button SHALL refer to that text with `aria-describedby`.

#### Scenario: Viewer on a case
- **WHEN** a VIEWER opens a case
- **THEN** the Close action is not offered and the header shows "A viewer cannot change a case."

#### Scenario: Decided appeal
- **WHEN** an appeal is already decided
- **THEN** the Decide action is disabled and the text says "This appeal is already decided."

### Requirement: Breadcrumb to the list
The breadcrumb SHALL show the workspace, the list and the short id, and each part SHALL be a link. The current list item SHALL be marked by route match, not by prefix.

#### Scenario: Case breadcrumb
- **WHEN** a moderator opens `/cases/A1B2C3D4`
- **THEN** the breadcrumb reads Moderation, Cases, A1B2C3D4 and Cases links to `/cases`

### Requirement: Undo after closing or reopening a case
Closing and reopening a case SHALL take effect at once and SHALL show a snackbar with an Undo action for ten seconds. Undo SHALL send the case update with the version read after the change. Reopen SHALL restore the status the case had before it was closed, read from the case timeline, and SHALL set OPEN when no previous status is found.

#### Scenario: Close and undo
- **WHEN** a moderator closes an IN_REVIEW case and presses Undo within ten seconds
- **THEN** the case status is IN_REVIEW again

#### Scenario: Reopen restores the previous status
- **WHEN** a case that was WAITING before it was closed is reopened
- **THEN** its status becomes WAITING

#### Scenario: Undo after another change
- **WHEN** another moderator changed the case after the close and the moderator presses Undo
- **THEN** nothing is overwritten and the snackbar says "The case changed since. Reload to see it."

#### Scenario: Appeal decision has no undo
- **WHEN** a moderator decides an appeal
- **THEN** the decision dialog is shown as before and no snackbar Undo is offered

### Requirement: Activity per entity
Each entity page SHALL show the history that exists for it: the case timeline for a case, the conversation and decision for an appeal, the resolution dates for a report, and the cases, appeals and reports of a player for a player.

#### Scenario: Player activity
- **WHEN** a moderator opens a player
- **THEN** the page lists the player's cases, appeals and reports in date order, each linked to its own URL

#### Scenario: Report without history
- **WHEN** a moderator opens a report
- **THEN** the page shows "created" and "resolved" with their times and no timeline is invented

### Requirement: Cross-links between related entities
Each entity page SHALL link to the entities it belongs to: a case to its appeal, report and player; an appeal to its case, punishment and player; a report to its player and, when it has one, its case; a player to filtered lists of its cases, appeals and reports.

#### Scenario: Case to appeal
- **WHEN** a case has an appeal
- **THEN** the case overview links to `/appeals/:id` of that appeal

#### Scenario: Player to filtered cases
- **WHEN** a moderator opens a player's cases link
- **THEN** the case list opens with `owner` set to the player's owner hash in the query

### Requirement: Copy link on every entity header
Every entity header SHALL offer a copy-link action that copies the absolute URL of the current view, including the query
and the hash. The action SHALL confirm the copy in text. It SHALL NOT copy a Ban ID, a name, an IP or a raw UUID, because
none of these is in a canonical address.

#### Scenario: Copy a case link with an open note
- **WHEN** a moderator copies the link while the note `#event-7f3c2a10` is open
- **THEN** the clipboard holds the case address with `?tab=notes-audit#event-7f3c2a10`

#### Scenario: Copy in a read-only view
- **WHEN** a VIEWER copies the link of a report
- **THEN** the link is copied, because reading the entity is allowed for every role
