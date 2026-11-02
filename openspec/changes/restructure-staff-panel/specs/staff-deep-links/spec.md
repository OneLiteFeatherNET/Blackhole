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

### Requirement: URL-only state for lists
Filters, sort, page, cursor and tab of a list SHALL live only in the address. The panel SHALL NOT write them to local
storage, session storage or a server preference. A navigation to a list without a query SHALL show the default filters.

#### Scenario: Return to a list
- **WHEN** a moderator sets a filter, opens an entity and returns through the navigation to the list
- **THEN** the list shows the default filters and nothing was written to storage

### Requirement: Every entity and view state has one canonical URL
Every entity and every view of the panel SHALL have one canonical URL, as listed in `design.md` decision 7. A view state
SHALL be addressed by its path and query, so that the same address shows the same view for any staff member with access.

#### Scenario: Punishment has its own address
- **WHEN** a moderator opens a punishment from a player's history
- **THEN** the address is `/punished/:shortId` and the same page opens when the address is pasted in a new tab

#### Scenario: Admin entry has its own address
- **WHEN** an administrator opens an exemption review
- **THEN** the address is `/admin/exemption-reviews/:id`

#### Scenario: Unknown entity
- **WHEN** a staff member opens `/admin/templates/<unknown id>`
- **THEN** the panel shows its not-found view with a link back to the list, and no blank page

### Requirement: Reload, Back and Forward restore the exact view
A reload, a Back and a Forward SHALL restore the tab, the filters, the sort, the page, the open item and the open dialog
of the address. A view SHALL NOT depend on state that exists only in memory.

#### Scenario: Reload restores tab and filter
- **WHEN** a moderator reloads `/cases/A1B2C3D4?tab=conversation`
- **THEN** the Conversation tab is active and the case is the same

#### Scenario: Back and Forward restore the list
- **WHEN** a moderator changes the case status filter, opens a case and presses Back
- **THEN** the list shows the filter it had before the case was opened

### Requirement: Create, edit and action states are routes
A create, edit, confirm or action state SHALL be a child route of its page, for example `/admin/templates/new`,
`/admin/templates/:id/edit` or `/reports/:id/resolve`. The parent page SHALL stay rendered and the dialog SHALL render
over it while the route is active. Back SHALL close the dialog, and a reload SHALL reopen it.

#### Scenario: Back closes a dialog
- **WHEN** a moderator opens `/reports/:id/resolve` from the report page and presses Back
- **THEN** the resolve dialog closes, the address is `/reports/:id`, and no resolution is sent

#### Scenario: A shared dialog link
- **WHEN** a moderator sends `/admin/sections/:key/edit` to a colleague with administrator access
- **THEN** the colleague sees the section page with the edit dialog open

#### Scenario: Dialog for a missing item
- **WHEN** a child route refers to an id that does not exist
- **THEN** the panel shows not-found and no empty dialog

### Requirement: Items inside a page are addressed by a hash
A note, a message, a protocol, a timeline event or an evidence share inside a page SHALL be addressed by `#event-<id>`.
Opening such an address SHALL scroll to the item and highlight it. The highlight SHALL stay until the reader clicks
elsewhere or navigates, and SHALL NOT depend on a timer.

#### Scenario: Deep link to a note highlights it
- **WHEN** a moderator opens `/cases/A1B2C3D4?tab=notes-audit#event-7f3c2a10`
- **THEN** the Notes and audit tab is active, the note with that id is scrolled into view and highlighted

#### Scenario: Hash stays out of the server
- **WHEN** a moderator opens an address with a hash
- **THEN** the request to the backend does not contain the hash

#### Scenario: Missing item in the hash
- **WHEN** the address names an item id that is not on the page
- **THEN** the page opens at its top and shows no highlight

### Requirement: A Ban ID never appears in a URL
No address, query, hash, log line or event of the panel SHALL contain a Ban ID. The Ban ID is revealed as an action on the
case, and the revealed value is not stored in the address.

#### Scenario: Revealing a Ban ID
- **WHEN** a moderator reveals the Ban ID of a case
- **THEN** the address does not change and no route with the Ban ID is created

#### Scenario: Copy link of a case with a revealed Ban ID
- **WHEN** a moderator copies the link of a case whose Ban ID is shown
- **THEN** the copied link is the canonical case address without the Ban ID

### Requirement: No name, IP, raw UUID or secret in a URL
No address SHALL contain a player name, an IP address, a player's raw UUID, a Minecraft UUID, a Discord user id,
a feed token or any other secret. Identifiers in addresses SHALL be those staff already see: short ids, ids, the owner
hash, the staff oid, keys and version numbers. This follows GDPR-by-design (`openspec/config.yaml`, principle 1):
addresses carry no personal data they do not need.

#### Scenario: Name lookup
- **WHEN** a moderator types a player name and presses Enter
- **THEN** the address becomes `/players/:owner` and the name appears nowhere in the address

#### Scenario: Staff links without a Minecraft UUID
- **WHEN** a moderator opens the links of a staff member
- **THEN** the address is `/admin/staff/:oid?tab=links` and no Minecraft UUID or Discord id is in it

### Requirement: No 404 for a changed or old address
Every address that existed before this change SHALL redirect to its new address, keeping the query string. A changed
address SHALL NOT answer with a blank page or a bare error.

#### Scenario: Old appeal filter
- **WHEN** a user opens `/appeals?show=all`
- **THEN** the browser shows `/appeals?status=all` with the same list
