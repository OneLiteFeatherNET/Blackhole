## MODIFIED Requirements

### Requirement: Dashboard
The UI SHALL open on an Inbox that lists what needs attention: appeal chats awaiting a team reply, open appeals, new reports and unassigned open cases, each with a count, its first ten items and a link to the filtered list. The Inbox SHALL also show the most recent automatic permanent escalations and the active punishments per section that the dashboard showed before. Each section the signed-in user cannot read SHALL be omitted, not shown as an error.

#### Scenario: Open dashboard
- **WHEN** a user opens the UI start page
- **THEN** the counts and the latest escalations are displayed

#### Scenario: Open the Inbox
- **WHEN** a user opens the UI start page
- **THEN** the four attention sections with their counts and the latest escalations are displayed

#### Scenario: Count matches the navigation badge
- **WHEN** the Inbox shows 3 open appeals
- **THEN** the navigation badge of Appeals shows 3

#### Scenario: Section not readable
- **WHEN** the user has no read access to reports
- **THEN** the "New reports" section is not shown and no error is displayed

### Requirement: Report and appeal queues
The UI SHALL list open reports and pending appeals with the server-side filters of the queue (status, assignee, search by player or reference) and SHALL page them with "Load more" from the cursor the API returns. It SHALL NOT filter one page on the client. Resolving a report and deciding an appeal SHALL use the decisions the API supports, and the queue SHALL remove the decided item without a full reload.

#### Scenario: Search finds an item on a later page
- **WHEN** a user searches for a player whose appeal is on the second page
- **THEN** the search is sent to the API and the appeal is shown

#### Scenario: Grant an appeal
- **WHEN** a user grants a full lift on a pending appeal
- **THEN** the appeal leaves the pending queue and the punishment is lifted

## ADDED Requirements

### Requirement: Navigation groups and names
The panel navigation SHALL have an ungrouped Inbox item and four headed groups, one of them for administrators only. Labels SHALL use the glossary terms in English and German. Staff links for the signed-in user SHALL be in the account menu. Renamed routes SHALL redirect.

#### Scenario: Group headings
- **WHEN** an administrator opens the panel
- **THEN** every group except the Inbox has a visible heading in the active language

#### Scenario: Account links
- **WHEN** a staff member opens the Sections page
- **THEN** no account-link panel is shown on it

#### Scenario: Renamed route
- **WHEN** a user opens an old route whose page moved
- **THEN** the browser lands on the new route

### Requirement: Breadcrumbs on detail pages
Every detail page (case, report, player, staff account) SHALL show breadcrumbs from the list it was reached from, as links, and the current page SHALL be the last breadcrumb with `aria-current="page"`.

#### Scenario: Case breadcrumb
- **WHEN** a user opens a case from the Cases list
- **THEN** the breadcrumb reads Cases, then the case reference, and "Cases" is a link to the list

### Requirement: One design system for the panel
The panel SHALL use one spacing scale, one type ramp, the button, chip and filter variants, one filter toolbar, one row-action pattern, one table component, one page template and the empty, loading and error states defined by the design, and SHALL NOT use spacing or type classes outside them.

#### Scenario: Filters on every list
- **WHEN** a list page is opened
- **THEN** its filters are in one toolbar row, and each applied filter is shown as a removable chip

#### Scenario: Retired classes
- **WHEN** the source of the panel is checked by the lint test
- **THEN** no retired spacing or type class is found

#### Scenario: One primary action per page
- **WHEN** a page section is rendered
- **THEN** it has at most one filled button

### Requirement: Row actions
Each table row SHALL have at most one primary action, which is a link or a button named by its verb, and secondary actions SHALL be in an overflow menu. Destructive actions SHALL be in the overflow menu and SHALL require confirmation in a dialog whose confirm button is the error variant.

#### Scenario: Destructive action
- **WHEN** a user chooses Delete from a row's overflow menu
- **THEN** a confirmation dialog opens and nothing is deleted until its error-variant button is pressed

### Requirement: Empty, loading and error states
Every list and detail page SHALL show an empty state with a next step when it has no items, skeleton placeholders on first load with `aria-busy` on the container, and an inline problem banner with a retry action when loading fails.

#### Scenario: Empty filtered list
- **WHEN** the filters match no items
- **THEN** the empty state offers "Clear filters" and does not claim there are no items at all

#### Scenario: First load
- **WHEN** a list page is loading for the first time
- **THEN** skeleton rows are shown instead of a blank area

### Requirement: Error display in the panel
The panel SHALL show every failed request as a translated message built from the Problem Details `code` when the code is known, and otherwise from the status class. The server's `title` and `detail` SHALL NOT be shown as text. The panel SHALL show the request's `traceId` as a reference when one is present. A failure SHALL never be silent: an empty `catch` is not allowed, and a failed read SHALL be shown in place.

#### Scenario: Known code
- **WHEN** a request fails with code `NAME_TAKEN`
- **THEN** the translated message for that code is shown in the dialog

#### Scenario: Unknown code
- **WHEN** a request fails with a code the panel does not know
- **THEN** the generic message for the status class is shown and the server text is not

#### Scenario: Failed read is visible
- **WHEN** the appeals read fails
- **THEN** the appeals page shows the problem banner with a retry button

### Requirement: Filter idiom and URL state
Filters, search text and sort SHALL be kept in the URL query, so that Back and a reload return to the same filtered list, and each filter SHALL use the one filter toolbar component.

#### Scenario: Reload keeps the filter
- **WHEN** a user filters the cases by tag and reloads the page
- **THEN** the same tag filter is applied and shown as a chip

### Requirement: Accessible structure
Every view SHALL have exactly one level-one heading, sections SHALL use level two and sub-sections level three, every table SHALL have a caption, every form field SHALL have a visible label, and the live announcements SHALL NOT be duplicated for one event.

#### Scenario: Report detail heading
- **WHEN** the report detail page is rendered
- **THEN** it has one level-one heading and no level-two heading with the same text

#### Scenario: One announcement per message
- **WHEN** a new message arrives in the open case chat
- **THEN** the message is announced once

### Requirement: Staff profile setting
The panel SHALL offer "My profile" from the account menu to every signed-in staff member, where the member sets the display name and the avatar mode, acknowledges the staff notice and sees the rule that the name is required before replies reach players. Writes to players SHALL show the `STAFF_PROFILE_REQUIRED` banner and disable the composer until the name is saved.

#### Scenario: Reply without a name
- **WHEN** a staff member without a display name opens an appeal chat
- **THEN** the composer is disabled and a banner links to My profile

#### Scenario: Name saved
- **WHEN** the staff member saves a valid name and acknowledges the notice
- **THEN** the composer is enabled and the next reply shows the name to the player

### Requirement: Administration area
The Administration area SHALL group the admin-only tools in tabs with a heading each: Staff accounts (staff actors, staff links, Discord links), Imports and CloudNet. The area SHALL be visible only to administrators.

#### Scenario: Viewer
- **WHEN** a viewer opens the navigation
- **THEN** Administration is not shown
