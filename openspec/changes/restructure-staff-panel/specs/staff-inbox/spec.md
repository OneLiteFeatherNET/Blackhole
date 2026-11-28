## ADDED Requirements

### Requirement: Inbox sections in priority order
The Inbox SHALL show these sections in this order: Assigned to me, Unassigned and urgent, Appeals waiting for a decision, Answers waiting, and Open reports. Each section SHALL show its full count and at most ten rows, and a "Show all" link to the same list filtered in the URL.

#### Scenario: Section order
- **WHEN** a moderator opens the Inbox
- **THEN** the sections appear in the order listed above

#### Scenario: Show all keeps the filter in the URL
- **WHEN** a moderator presses "Show all" under Unassigned and urgent
- **THEN** the case list opens with `priority` and an empty assignee filter in its query

### Requirement: Assigned to me uses the verified identity
The section Assigned to me SHALL list the open cases whose assignee is the caller. The caller's identity SHALL come from the verified token, read through `GET /auth/me`. A caller without an identity SHALL see a message that explains this and SHALL NOT see an empty list.

#### Scenario: Caller has an identity
- **WHEN** `GET /auth/me` returns an oid and the caller has two assigned cases
- **THEN** the section count is 2 and both cases are rows

#### Scenario: Caller has no identity
- **WHEN** `GET /auth/me` returns no oid
- **THEN** the section shows "Your account has no staff identity yet" and no count

### Requirement: Urgent cases without an assignee
The section Unassigned and urgent SHALL list cases with no assignee and with priority URGENT or HIGH, and SHALL NOT list closed cases.

#### Scenario: Urgent case without assignee
- **WHEN** a case with priority URGENT and no assignee is open
- **THEN** it appears in Unassigned and urgent

#### Scenario: Closed case
- **WHEN** a case with priority HIGH and no assignee is closed
- **THEN** it does not appear in Unassigned and urgent

### Requirement: Role decides what a row offers
A VIEWER SHALL see every section read-only and SHALL see no row action. A MODERATOR SHALL see the row actions Assign to me and Close, and the Open link. An ADMIN SHALL see what a MODERATOR sees.

#### Scenario: Viewer
- **WHEN** a VIEWER opens the Inbox
- **THEN** every row has an Open link only, and the page says that moderators act on cases and appeals

#### Scenario: Moderator takes a case
- **WHEN** a MODERATOR presses Assign to me on an unassigned row
- **THEN** the case is assigned to the caller and the row moves to Assigned to me

#### Scenario: Viewer cannot assign
- **WHEN** a VIEWER opens the Inbox
- **THEN** no Assign to me control is shown

### Requirement: Every row links to its entity
Every row SHALL be a link to the entity's own URL: `/cases/:reference` for a case, `/appeals/:id` for an appeal, and `/reports/:id` for a report. A row SHALL NOT open a dialog as its only target.

#### Scenario: Appeal row
- **WHEN** a moderator presses an appeal row
- **THEN** the browser goes to `/appeals/:id` for that appeal

#### Scenario: Middle click
- **WHEN** a moderator opens a row in a new tab
- **THEN** the new tab shows the same entity

### Requirement: Empty states name the next step
Each empty section SHALL say what the section is for and SHALL NOT offer an action that the caller's role cannot perform.

#### Scenario: Empty assigned section
- **WHEN** a moderator has no assigned case
- **THEN** the section says "No case is assigned to you. Cases you take appear here."

### Requirement: Counts agree with badges
The section counts and the navigation badges SHALL come from the same inbox read, so that they show the same number for the same item.

#### Scenario: Count and badge
- **WHEN** the Inbox shows 5 open reports
- **THEN** the Reports badge shows 5
