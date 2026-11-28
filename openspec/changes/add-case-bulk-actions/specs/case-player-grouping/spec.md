# Spec Delta

## Purpose

Defines how the case list shows the player behind each case and how staff group the list by player: a
Player column with the name from the name history, server-side groups with open and total counts that
stay correct across pages, expandable groups, filters that apply within groups, and a view choice that
the viewer's browser remembers.

## ADDED Requirements

### Requirement: Player column in the case list
The case list items SHALL carry the player behind the case as `playerName` (the newest name of the owner in the name history, or `null`) and `ownerShort` (the first 12 characters of the owner hash). The list MUST resolve the name in the same statement that reads the page, with one statement per page and no per-row lookup. A response MUST NOT contain a raw player UUID.

#### Scenario: Known name
- **WHEN** a staff member lists cases and the owner of a case has a name in the name history
- **THEN** the item carries that name as `playerName` and the short owner hash as `ownerShort`

#### Scenario: Several names
- **WHEN** an owner has several names in the name history
- **THEN** `playerName` is the name with the latest `last_seen`

#### Scenario: Name removed by retention or erasure
- **WHEN** the owner has no name in the name history
- **THEN** `playerName` is `null` and the web shows the short owner hash

#### Scenario: One statement per page
- **WHEN** a page of twenty cases is read
- **THEN** the list issues one statement for the rows and the names together

### Requirement: Server-side groups by player
The system SHALL offer `GET /case/groups` (API version 1, `VIEWER`) returning one group per owner that has at least one case matching the filters. A group SHALL carry `owner`, `ownerShort`, `playerName`, `openCount` (cases whose status is not `CLOSED`), `totalCount`, `lastActivityAt` and the page metadata. Groups MUST be ordered by `lastActivityAt` descending, then by `owner`. Paging MUST be over groups, so the cases of one owner never span two pages.

#### Scenario: Counts across pages
- **WHEN** a player has twelve cases and the page size is ten groups
- **THEN** the player's group is on exactly one page and its counts cover all twelve cases

#### Scenario: Open count
- **WHEN** a player has two `OPEN`, one `WAITING` and one `CLOSED` case
- **THEN** the group has `openCount` 3 and `totalCount` 4

#### Scenario: Filters apply within groups
- **WHEN** the filter is status `CLOSED` and a player has no closed case
- **THEN** the player has no group in the response

#### Scenario: Counts follow the filter
- **WHEN** the filter is tag `appeal` and a player has three cases, one tagged
- **THEN** the group has `totalCount` 1 and `openCount` as for that one case

#### Scenario: Invalid filter
- **WHEN** a filter value is malformed, for example an unknown priority
- **THEN** the response is HTTP 400 with the same error as the case list

### Requirement: Expanding a group
The case list SHALL accept an `owner` filter. With `owner` set, the list returns that owner's cases matching the other filters, in the same order as the list, paged within the group. The owner value MUST be validated like the owner in the player routes; a malformed owner is HTTP 400. Without `owner` the list is unchanged.

#### Scenario: Expand a group
- **WHEN** a staff member expands a group with `openCount` 3 and `totalCount` 4 under the same filters
- **THEN** the expansion lists the four cases of that owner that match the filters

#### Scenario: Malformed owner
- **WHEN** the owner value is not a valid owner hash
- **THEN** the response is HTTP 400 and no case is read

### Requirement: Viewer's view choice is remembered in the browser
The Cases page SHALL offer a view toggle with two values: the flat case list and the list grouped by player. The chosen value MUST be stored under one key in the browser's local storage, holding only the value `cases` or `player`. The page MUST NOT store a name, an owner or a case in browser storage. A storage that is unavailable or throws MUST fall back to the flat list without an error message.

#### Scenario: Choice is kept
- **WHEN** a viewer chooses the grouped view and reloads the page
- **THEN** the grouped view is shown

#### Scenario: Storage blocked
- **WHEN** the browser refuses access to local storage
- **THEN** the page shows the flat list and no error

#### Scenario: Nothing personal in storage
- **WHEN** the viewer uses the grouped view with several players expanded
- **THEN** local storage holds only the view value and no name or owner hash

### Requirement: Grouped view in the Cases page
The grouped view SHALL show, for each group, the player's name (or the short owner hash when the name is `null`), the open count and the total count, and a control that expands the group to its cases. Each case in the expansion SHALL link to the case page as it does in the flat view. The view MUST be keyboard operable, and the expansion state MUST be announced to assistive technology.

#### Scenario: Keyboard expansion
- **WHEN** a keyboard user activates a group's expansion control
- **THEN** the cases of that group appear, the control reports itself as expanded, and focus stays on the control

#### Scenario: Link to a case
- **WHEN** the user activates a case in an expanded group
- **THEN** the case page opens as it does from the flat list

### Requirement: Player column in the flat view
The flat case list SHALL show a Player column between Case and Section. The column shows `playerName` when present and `ownerShort` otherwise, and never a raw UUID. The other columns keep their order.

#### Scenario: Column order
- **WHEN** the flat list is shown
- **THEN** the columns are Case, Player, Section, Punishment, Status, Assignee, Priority, Tags and Last activity
