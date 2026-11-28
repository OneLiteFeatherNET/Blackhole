## ADDED Requirements

### Requirement: List and detail side by side on expanded widths
Cases, appeals and reports SHALL show their list and the open item side by side at 840dp and wider. The list SHALL keep its width between 360dp and 400dp, and the detail SHALL use the rest of the width. Appeals join this layout once their detail page exists (phase 6 of `design.md`).

#### Scenario: Opening a case from the list
- **WHEN** a moderator selects a case row at 1000dp
- **THEN** the case detail appears on the right and the list stays visible on the left

#### Scenario: Nothing selected
- **WHEN** the list opens without an item
- **THEN** the detail area shows "Select a case to see it here." and no detail request is made

### Requirement: The URL names the open item
Opening an item SHALL change the address to that item's canonical URL and SHALL keep the list query. Browser Back SHALL return to the list without the item.

#### Scenario: Open and back
- **WHEN** a moderator opens case `A1B2C3D4` from `/cases?status=IN_REVIEW` and presses Back
- **THEN** the address is `/cases?status=IN_REVIEW` and no item is open

#### Scenario: Shared open item
- **WHEN** a moderator sends the address of an open item
- **THEN** the colleague sees the list with that item open

### Requirement: The list is not reloaded for the open item
Changing the open item SHALL load only that item's detail. The list SHALL NOT be requested again for that change, except after a change that affects the list's filters.

#### Scenario: Next item
- **WHEN** a moderator moves to the next row with `j`
- **THEN** only the detail of the new row is requested

### Requirement: Behaviour on medium and compact widths
Between 600dp and 839dp the detail SHALL replace the list in the same column, with a link "Cases" back to the list. Below 600dp the list and the detail SHALL be separate pages, `/cases` and `/cases/:reference`.

#### Scenario: Medium width
- **WHEN** a moderator opens a case at 700dp
- **THEN** the list is no longer visible and the header offers a link back to the list

#### Scenario: Compact width
- **WHEN** a moderator opens a case at 400dp
- **THEN** the detail is a page of its own with a Back link to the list

### Requirement: Keyboard movement in lists
In a list, `j` SHALL select the next row, `k` the previous row, `Enter` SHALL open the selected row, and `Esc` SHALL close the detail and return the focus to the row. These keys SHALL NOT act while focus is in a text field.

#### Scenario: Keyboard only
- **WHEN** a moderator presses `j`, `j` and `Enter`
- **THEN** the third row is opened and its address is shown

#### Scenario: Escape returns focus
- **WHEN** a moderator presses `Esc` with a detail open
- **THEN** the detail closes, the address is the list address, and the previously open row has focus

### Requirement: Selection is visible
The open row SHALL show a selected state and `aria-current="true"`.

#### Scenario: Screen reader
- **WHEN** a row is open
- **THEN** that row has `aria-current="true"`
