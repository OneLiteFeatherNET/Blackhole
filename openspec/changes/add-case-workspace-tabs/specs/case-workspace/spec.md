## ADDED Requirements

### Requirement: Case page tabs
The case page SHALL group its content into the tabs Overview, Conversation and Notes & audit, SHALL show one tab at a time, and SHALL keep the active tab in the URL.

#### Scenario: Opening a link to a tab
- **WHEN** a staff member opens a case with `?tab=conversation`
- **THEN** the Conversation tab is active

#### Scenario: Unknown tab
- **WHEN** the `tab` value is missing or unknown
- **THEN** the Overview tab is active

#### Scenario: Switching does not add history
- **WHEN** a staff member switches tabs and goes back in the browser
- **THEN** the browser leaves the case page

### Requirement: One-click close
The case page SHALL show the case status on every tab and SHALL offer a moderator one action that closes an open case and one that reopens a closed case, with the same version check as any case update. A viewer SHALL NOT get either action.

#### Scenario: Closing
- **WHEN** a moderator presses Close on a case that is not closed
- **THEN** the case status becomes CLOSED and the action becomes Reopen

#### Scenario: Someone else changed the case first
- **WHEN** the update answers 409
- **THEN** nothing is overwritten and the moderator is offered a reload

### Requirement: Messenger conversation
The Conversation tab SHALL show the thread in a scrolling area of its own with the reply composer always below it, SHALL separate days with a divider, and SHALL keep the newest message in view unless the reader scrolled up.

#### Scenario: A message arrives at the end of the thread
- **WHEN** a message arrives while the reader is at the end of the thread
- **THEN** the thread shows the new message

#### Scenario: The reader scrolled up
- **WHEN** a message arrives while the reader is further up
- **THEN** the scroll position does not change

#### Scenario: Sending with the keyboard
- **WHEN** a moderator presses Enter in the reply composer
- **THEN** the reply is sent, and Shift+Enter adds a line instead

### Requirement: Unseen messages on another tab
The Conversation tab SHALL show how many messages arrived since the tab was last open, counted only on the open page.

#### Scenario: Message while on Overview
- **WHEN** a message arrives while another tab is active
- **THEN** the Conversation tab shows the count, and the count clears when the tab is opened
