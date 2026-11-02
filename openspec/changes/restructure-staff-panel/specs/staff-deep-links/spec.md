## ADDED Requirements

### Requirement: One canonical URL per entity
Every case, appeal, report and player SHALL have one canonical URL: `/cases/:reference`, `/appeals/:id`, `/reports/:id` and `/players/:owner`. Opening that URL SHALL show the same entity for every staff member with access.

#### Scenario: Shared case link
- **WHEN** a moderator sends `/cases/A1B2C3D4` to a colleague with access
- **THEN** the colleague sees the same case on that URL

#### Scenario: Appeal detail page
- **WHEN** a moderator opens `/appeals/:id` for an appeal
- **THEN** the page shows the appeal, its punishment, its case link, its conversation and its decision panel

### Requirement: No name, Ban ID or punishment identifier in a URL
No route SHALL take a player name, a Ban ID or a punishment identifier as a path or query value. The name lookup SHALL call the resolve endpoint from the page and replace the address with the owner URL.

#### Scenario: Name lookup
- **WHEN** a moderator types a player name in the lookup and presses Enter
- **THEN** the address changes to `/players/:owner` and the name is not in the address bar

#### Scenario: Unknown name
- **WHEN** the resolve endpoint answers NOT_FOUND
- **THEN** the address stays on `/players` and the page says the player was not found

### Requirement: View state in the query
Tabs, filters and the queue SHALL be in the query string, so that a link and a reload show the same view. The parameters SHALL be `tab` on case, appeal and player pages, `status`, `assignee`, `priority`, `section`, `tag` and `q` on lists, `view` on the Inbox, and `owner` on the report and case lists.

#### Scenario: Reload keeps the tab
- **WHEN** a moderator reloads `/cases/A1B2C3D4?tab=conversation`
- **THEN** the Conversation tab is active

#### Scenario: Filter in the URL
- **WHEN** a moderator sets the case status filter to IN_REVIEW
- **THEN** the address contains `status=IN_REVIEW`

### Requirement: Invalid query values fall back
An unknown or missing query value SHALL fall back to its default. The page SHALL remove the invalid value from the address with a replacement, not a new history entry.

#### Scenario: Unknown tab
- **WHEN** a moderator opens `/cases/A1B2C3D4?tab=unknown`
- **THEN** the Overview tab is active and the address no longer has `tab=unknown`

### Requirement: Old URLs keep working
Every URL that existed before this change SHALL keep working. A changed URL SHALL redirect with its query string. Old `show=all` on appeals SHALL redirect to `status=all`.

#### Scenario: Old appeal filter
- **WHEN** a moderator opens `/appeals?show=all`
- **THEN** the browser shows `/appeals?status=all` with the same list

#### Scenario: Old admin URL
- **WHEN** a user opens `/templates?x=1`
- **THEN** the browser shows `/admin/templates?x=1`

#### Scenario: Retired case sub-path
- **WHEN** a user opens `/cases/A1B2C3D4/timeline`
- **THEN** the browser shows `/cases/A1B2C3D4?tab=notes-audit`

### Requirement: Filters are not stored on the device
The panel SHALL NOT store list filters in local storage, session storage or on the server. A navigation to a list without a query SHALL show the default filters.

#### Scenario: Return to a list
- **WHEN** a moderator sets a filter, opens an entity and returns through the navigation to the list
- **THEN** the list shows the default filters
