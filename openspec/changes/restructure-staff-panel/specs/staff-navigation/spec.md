## ADDED Requirements

### Requirement: Destinations grouped by job
The Moderation rail SHALL group its destinations under the headings Today, Work, Players and Protection. Each destination SHALL have its own icon and label, and no two destinations SHALL share an icon.

#### Scenario: Moderator opens the panel
- **WHEN** a moderator signs in
- **THEN** the Inbox is the first destination under Today, followed by Work with Cases, Appeals and Reports

#### Scenario: Icons are distinct
- **WHEN** the Administration System group is shown to an administrator
- **THEN** each of its six items shows a different icon

### Requirement: Navigation by window width
The panel SHALL use a navigation bar with at most five destinations below 600dp, a collapsed navigation rail from 600dp to 839dp, and an expanded navigation rail from 840dp. The panel SHALL NOT use a navigation drawer at any width.

#### Scenario: Phone width
- **WHEN** the window is narrower than 600dp
- **THEN** the navigation bar shows Inbox, Cases, Appeals, Reports and More, and no rail is shown

#### Scenario: Medium width reaches the other groups
- **WHEN** the window is between 600dp and 839dp and the moderator presses More
- **THEN** a modal expanded rail opens with all Moderation groups and closes on Escape

#### Scenario: Wide window
- **WHEN** the window is 840dp or wider
- **THEN** the expanded rail is shown with its group headings, and no drawer is opened

### Requirement: Badges show open work
Each Work destination and the Inbox SHALL show a count badge with the open work of that destination. A count of zero SHALL show no badge. A count above 99 SHALL show 99+. A failed read SHALL keep the previous count.

#### Scenario: Count of open appeals
- **WHEN** the inbox read reports three appeals waiting for a decision
- **THEN** the Appeals destination shows the badge 3

#### Scenario: Failed read
- **WHEN** a badge read fails after a successful read showed 4
- **THEN** the badge still shows 4 and no error is shown in the rail

### Requirement: Keyboard shortcuts for top destinations
The panel SHALL open the Inbox, Cases, Appeals, Reports and Player lookup from the keyboard with the chords `g i`, `g c`, `g a`, `g r` and `g p`. The shortcuts SHALL NOT fire while focus is in a text field, a text area or an open dialog. The `?` key SHALL open the list of shortcuts.

#### Scenario: Chord moves to cases
- **WHEN** a moderator presses `g` and then `c` with focus on a page heading
- **THEN** the Cases page opens

#### Scenario: Typing a chord in a field
- **WHEN** a moderator types `g` then `c` in the reply composer
- **THEN** the text is typed and the page does not change

### Requirement: Workspace switcher for administrators only
The workspace switcher SHALL be a dropdown at the top right of the app bar. It SHALL be shown to ADMIN only. A moderator SHALL see no switcher and no link to Administration.

#### Scenario: Moderator
- **WHEN** a MODERATOR opens any page
- **THEN** no workspace switcher and no Administration entry are shown

#### Scenario: Administrator switches workspace
- **WHEN** an ADMIN chooses Administration in the switcher
- **THEN** the Administration Configuration group is shown and the URL changes to the first Administration page

### Requirement: Search scoped by type
The global search SHALL classify its input as a player name, a case short reference, an appeal or a report identifier, and SHALL name the type of every hit. Suggestions and the typed text SHALL be cleared when the page changes.

#### Scenario: Case reference
- **WHEN** a staff member types `#A1B2C3D4` in the search
- **THEN** the first hit reads "Case A1B2C3D4" and opens `/cases/A1B2C3D4`

#### Scenario: Navigating away clears the search
- **WHEN** a staff member opens a result and the page changes
- **THEN** the search field is empty and no suggestion list is shown
