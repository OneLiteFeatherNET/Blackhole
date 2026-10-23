# Spec Delta

## Purpose

Adds the case page and case list to the admin web UI.

## ADDED Requirements

### Requirement: Case page
The UI SHALL provide a case page per punishment, reachable from the punishment on the player detail and from a case list. The header SHALL show the player (short hash), section, punishment state, status, assignee, tags and priority and let a `MODERATOR` or `ADMIN` change status, assignee, tags and priority; a `VIEWER` SHALL see them read-only with an explanation. A conflict (HTTP 409) SHALL tell the user the case changed and reload it.

#### Scenario: Moderator changes status
- **WHEN** a moderator sets the status to `WAITING`
- **THEN** the UI sends the change with the case version and the timeline shows the new status event

#### Scenario: Viewer is read-only
- **WHEN** a viewer opens a case
- **THEN** the metadata controls and the notes editor are disabled with a hint

#### Scenario: Conflict
- **WHEN** another moderator changed the case first and the API answers 409
- **THEN** the UI shows that the case changed and shows the reloaded values

### Requirement: Timeline view
The case page SHALL show the timeline as a vertical ordered list with an icon and label per event type (a generic one for unknown types), the actor with a verified, unverified or system badge that has text and an icon and does not rely on colour alone, a marker for backfilled or reconstructed events, filters for type group, actor trust and date range, and "load more" by cursor. An edited note SHALL show the latest text with an expandable history; a redacted note SHALL be shown as removed.

#### Scenario: Unverified actor
- **WHEN** an event has trust `UNVERIFIED`
- **THEN** the actor shows an "unverified" badge with text and icon, not the verified badge

#### Scenario: Filter by type group
- **WHEN** the user selects the group "appeal"
- **THEN** only appeal events are listed and the request carries the type filter

#### Scenario: Unknown event type
- **WHEN** the API returns an event type the UI does not know
- **THEN** it is rendered with the generic icon and its type name

#### Scenario: Keyboard use
- **WHEN** the user operates the filters, "load more" and the editor with the keyboard only
- **THEN** every control is reachable, focus is visible and the focus does not jump when events load

### Requirement: Notes editor and inline evidence
The case page SHALL provide a notes editor (1 to 4000 characters, counter, Ctrl+Enter submits), editing of the user's own notes, and for an `ADMIN` redaction with a mandatory reason. Evidence events SHALL show the evidence inline through the existing evidence components, and the timeline MUST NOT hold a copy of its content. The page SHALL list the player's other cases as links.

#### Scenario: Add a note
- **WHEN** a moderator submits a note
- **THEN** it appears at the top of the timeline and the editor is cleared, and a failure restores the text with an error

#### Scenario: Redact as admin
- **WHEN** an admin redacts a note and enters a reason
- **THEN** the note is shown as removed

#### Scenario: Evidence inline
- **WHEN** the timeline has an `evidence.attached` event
- **THEN** the evidence item is shown inline and revealing its content uses the audited reveal

#### Scenario: Other cases
- **WHEN** the player has two other punishments
- **THEN** the page links to both cases

### Requirement: Case page language and accessibility
All case page strings SHALL exist in German and English with the same keys, use the Material Design 3 tokens of the UI, announce save results in a live region and use semantic list and time markup.

#### Scenario: Locale parity
- **WHEN** the i18n key test runs
- **THEN** every case key exists in both locales

#### Scenario: Save result announced
- **WHEN** a note is saved
- **THEN** a live region announces the result
