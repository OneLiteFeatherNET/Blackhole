# Spec Delta

## Purpose

Lets staff and appeal reviewers view, add and delete punishment evidence in the admin web UI.

## ADDED Requirements

### Requirement: Evidence panel on punishments and appeals
The UI SHALL show an evidence panel for a punishment on the player detail (active punishments and history) and in the appeal decision dialog, listing each item with a kind icon, source system, captured time and a summary, with paging and an empty state.

#### Scenario: Punishment with evidence
- **WHEN** a user opens a punishment that has an anticheat record and a screenshot
- **THEN** the panel lists both items with their kind icons

#### Scenario: Appeal review
- **WHEN** a reviewer opens an appeal
- **THEN** the appealed punishment's evidence is listed in the decision dialog

### Requirement: Safe external links
The UI SHALL open stored links in a new tab with `rel="noopener noreferrer"`, MUST render only `http` and `https` values as links (anything else as plain text), and MUST NOT request, preview or unfurl the link target.

#### Scenario: Link opens safely
- **WHEN** a user activates a LINK item
- **THEN** the URL opens in a new tab with `rel="noopener noreferrer"`

#### Scenario: Unsafe stored value
- **WHEN** a stored reference starts with `javascript:`
- **THEN** it is shown as plain text and no anchor is rendered

### Requirement: Inline previews from the backend
The UI SHALL preview uploaded images and videos inline using the backend content endpoint, with video preloading metadata only, and SHALL show anticheat details as a table and chat logs as a read-only transcript with speakers resolved to names for display only.

#### Scenario: Screenshot preview
- **WHEN** a user expands an image item
- **THEN** the image is displayed from the backend content endpoint

#### Scenario: Chat transcript names
- **WHEN** a chat log item is expanded
- **THEN** speakers are shown by resolved name and no name is persisted by the UI

### Requirement: Upload with progress
The UI SHALL let staff upload evidence files by drag-and-drop or file picker, show per-file progress with cancel, apply advisory type and size checks, and show the server's rejection reason per file.

#### Scenario: Upload succeeds
- **WHEN** a user drops a PNG within the size limit
- **THEN** a progress bar is shown and the item appears in the list on completion

#### Scenario: Server rejects the type
- **WHEN** the server answers HTTP 415
- **THEN** the file shows a "type not allowed" message and the list is unchanged

### Requirement: Add links and delete evidence
The UI SHALL let staff add LINK and REPLAY evidence with scheme validation, and delete an item only after an explicit confirmation.

#### Scenario: Delete confirmed
- **WHEN** a user confirms deletion of an item
- **THEN** the item is removed from the list and a confirmation is shown

#### Scenario: Delete cancelled
- **WHEN** a user cancels the confirmation
- **THEN** no request is sent

### Requirement: Localised and accessible evidence UI
All evidence strings SHALL exist in German and English, and the drop zone, previews and dialogs MUST be keyboard operable with visible focus.

#### Scenario: Locale parity
- **WHEN** the locale key test runs
- **THEN** every evidence key exists in both languages
