## ADDED Requirements

### Requirement: The inbox lists changes by other staff in the last 24 hours
`GET /inbox` SHALL carry a `changedByOthers` section after its four existing sections. The section SHALL list, for each
punishment whose latest case event falls inside the window, one row, when that event is by a verified staff actor other than
the caller. The window SHALL be the configured `blackhole.inbox.changed-window` (default `PT24H`), measured from the read.
The section SHALL carry the full `count`, and at most 10 items, newest first. The existing four sections SHALL be unchanged.

#### Scenario: Change by another moderator
- **WHEN** a moderator B changed a case 2 hours ago and that change is the case's latest event
- **THEN** moderator A's inbox lists the case in `changedByOthers` with `change` and `byName` of B

#### Scenario: Caller's own change
- **WHEN** the caller made the latest change of a case
- **THEN** the case is not in `changedByOthers`

#### Scenario: Latest change by the proxy or a system job
- **WHEN** the latest event of a case has an actor trust other than `VERIFIED`, or no actor
- **THEN** the case is not in `changedByOthers`

#### Scenario: Change older than the window
- **WHEN** the latest event of a case is 25 hours old
- **THEN** the case is not in `changedByOthers`

#### Scenario: Count is not capped
- **WHEN** 14 cases were changed by others inside the window
- **THEN** `count` is 14 and `items` holds the 10 newest

### Requirement: A changed row is a case or an appeal
A row SHALL have kind `APPEAL` and the appeal identifier as `id` when its latest event is an appeal filing or decision;
otherwise it SHALL have kind `CASE`.

#### Scenario: Appeal decided by another moderator
- **WHEN** a moderator decided an appeal, and that is the latest event of its punishment
- **THEN** the row has kind `APPEAL`, `change` `APPEAL_DECIDED` and the appeal identifier

#### Scenario: Case changed by another moderator
- **WHEN** a moderator changed the status of a case and that is its latest event
- **THEN** the row has kind `CASE` and `change` `STATUS`

### Requirement: The change is a fixed value and the staff name is display only
`change` SHALL be one value of a fixed enum (`STATUS`, `ASSIGNEE`, `PRIORITY`, `TAGS`, `NOTE`, `MESSAGE`, `APPEAL_FILED`,
`APPEAL_DECIDED`, `OTHER`). `byName` SHALL be the staff display name, or `null` once that staff member has been anonymized. A
row SHALL NOT carry a staff object id, a Ban ID, a player name, an IP address or any note or message text.

#### Scenario: Anonymized staff member
- **WHEN** the staff member who made the latest change has been anonymized
- **THEN** `byName` is `null` and the row is still listed

#### Scenario: Free text is never a change
- **WHEN** a note was added by another moderator
- **THEN** `change` is `NOTE` and the note text is not in the row

### Requirement: Role and the other sections are unchanged
The section SHALL be readable by the role of the inbox (`VIEWER`), and it SHALL NOT add to the navigation badges, which keep
the counts of the four existing sections. The `GET /inbox` request SHALL NOT change.

#### Scenario: Viewer reads the inbox
- **WHEN** a VIEWER sends `GET /inbox`
- **THEN** the answer is 200 and includes `changedByOthers`

#### Scenario: Unauthenticated read
- **WHEN** a request to `GET /inbox` carries no token
- **THEN** the answer is 401
