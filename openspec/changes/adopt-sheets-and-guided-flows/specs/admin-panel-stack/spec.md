# Spec Delta

## Purpose

Defines how the staff panel presents create, edit and detail tasks as stacked side sheets over the
current page, how far they nest, how their state is kept in the URL, and when a dialog is used instead.

## ADDED Requirements

### Requirement: Create, edit and detail tasks open a side sheet
The panel SHALL open a create, edit or detail task in a side sheet that slides in from the right over
the current page. The page behind the sheet SHALL stay visible and keep its scroll position. Destructive
confirmations and single-question decisions MUST NOT use a side sheet.

#### Scenario: Open a rule editor
- **WHEN** an administrator chooses "New client rule" on the client rules page
- **THEN** a side sheet opens on the right, the client rules list stays visible behind it, and the sheet's first control has focus

#### Scenario: Confirmation stays a dialog
- **WHEN** an administrator chooses "Delete" on a client rule
- **THEN** a confirmation dialog opens and the side sheet is not used

### Requirement: A sheet opens at most one child sheet
A side sheet MAY open one child side sheet on top of it, so the maximum depth is two. A child sheet MUST NOT
open a further child. The parent sheet SHALL remain in the stack and return when the child closes.

#### Scenario: Child sheet from a parent
- **WHEN** a moderator opens "Add link" from the evidence sheet of a case
- **THEN** the link sheet opens on top, the evidence sheet is kept in the stack, and closing the link sheet shows the evidence sheet again

#### Scenario: Depth is capped
- **WHEN** a child sheet offers a further action that would open another sheet
- **THEN** that action is not offered and the child sheet shows the required step inside itself or as a dialog

### Requirement: Each sheet header shows its place
Each sheet SHALL have a header with its title, a back control when a parent exists and a close control, and a breadcrumb naming the stack (for example "Client rules > New rule"). The close control SHALL close the top sheet only.

#### Scenario: Back from a child
- **WHEN** a moderator activates the back control of a child sheet
- **THEN** the child closes and the parent sheet is shown with its previous content and input

#### Scenario: Close from a child
- **WHEN** a moderator activates the close control of a child sheet
- **THEN** only the child closes and the parent remains open

### Requirement: Sheet state lives in the URL
The state of the stack SHALL be held in a `panel` query parameter of the current route, listing the open sheets from parent to child, for example `?panel=client-rule:new` or `?panel=evidence:CASE-12,link:new`. Opening a sheet SHALL push a history entry and closing it SHALL replace the entry. Other query parameters, including filters, SHALL be kept.

#### Scenario: Browser back closes the top sheet
- **WHEN** a moderator opens a child sheet and presses the browser back button
- **THEN** the child sheet closes and the URL no longer names it

#### Scenario: Shared link opens the sheet
- **WHEN** a second moderator opens the URL `?panel=client-rule:new` of the client rules page
- **THEN** the same sheet opens, subject to that moderator's role

#### Scenario: Unknown or forbidden sheet is ignored
- **WHEN** the `panel` parameter names an unknown kind, an id that does not exist, or a sheet the user's role may not use
- **THEN** the parameter is removed from the URL, no sheet opens and no request is made for the unknown id

#### Scenario: Filters are kept
- **WHEN** a moderator opens a sheet while a list filter is set
- **THEN** the filter parameter is still present in the URL

### Requirement: Width follows the window size class
Sheets SHALL follow the window size classes of Material Design 3: compact (below 600 px) full screen; medium (600 px to 839 px) a standard side sheet of at most 400 px; expanded (840 px and wider) a standard side sheet of 480 px. On expanded widths the page behind a sheet SHALL remain interactive.

#### Scenario: Compact width
- **WHEN** the window is 480 px wide and a sheet opens
- **THEN** the sheet covers the full viewport with a close control and no horizontal page scroll

#### Scenario: Expanded width keeps the list usable
- **WHEN** the window is 1280 px wide and a sheet opens on the player page
- **THEN** the player page remains visible and clickable to the left of the sheet

### Requirement: A covered parent sheet stays reachable
On expanded widths a child sheet MAY cover its parent. The parent SHALL then show as a narrow strip with its title, and activating the strip SHALL bring the parent back as the top sheet and close the child.

#### Scenario: Return by strip
- **WHEN** a child sheet covers its parent and a moderator activates the parent's strip
- **THEN** the parent becomes the top sheet and the child is closed

### Requirement: Focus and dismissal
When a sheet opens, focus SHALL move to its first focusable control. When it closes, focus SHALL return to the control that opened it, or to the parent sheet if the opener is gone. Escape SHALL close the top sheet only. A compact-width sheet and a sheet with an unsaved change SHALL ask before it discards input.

#### Scenario: Focus returns to the opener
- **WHEN** a moderator closes a sheet opened by the "Edit" button of a table row
- **THEN** focus returns to that "Edit" button

#### Scenario: Escape closes the top sheet only
- **WHEN** a child sheet is open and a moderator presses Escape
- **THEN** only the child sheet closes

#### Scenario: Unsaved input is confirmed
- **WHEN** a moderator with changed input closes a sheet by Escape
- **THEN** a confirmation dialog asks whether to discard the input before the sheet closes

### Requirement: Modal variant on compact widths
On compact widths a side sheet SHALL be a modal: it sets `aria-modal="true"`, keeps keyboard focus inside itself while open and makes the page behind it inert. On medium and expanded widths the standard sheet SHALL NOT set `aria-modal` and SHALL NOT trap focus.

#### Scenario: Focus stays inside on compact
- **WHEN** a compact-width sheet is open and a moderator presses Tab on its last control
- **THEN** focus moves to its first control and never to the page behind

#### Scenario: Standard sheet on expanded
- **WHEN** an expanded-width sheet is open
- **THEN** the sheet does not carry `aria-modal` and Tab can move to the page behind it
