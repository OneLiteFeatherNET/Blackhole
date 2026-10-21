# Spec Delta

## Purpose

Provides a browser-based management interface for admins and staff to configure sections and templates and to review players, reports and appeals without calling the API by hand.

## ADDED Requirements

### Requirement: Material Design 3 appearance
The UI SHALL use Material Design 3 color roles, type scale, shapes and elevation derived from a single seed color, and SHALL support light and dark schemes following the operating system preference with a manual override that is remembered.

#### Scenario: Dark mode
- **WHEN** the operating system prefers dark mode and no override is set
- **THEN** the UI renders with the dark MD3 scheme

#### Scenario: Manual override persists
- **WHEN** a user switches to light mode and reloads the page
- **THEN** the light scheme is still applied

### Requirement: Localised interface
The UI SHALL provide all texts in German and English, defaulting to German, with a language switch that is remembered.

#### Scenario: Switch to English
- **WHEN** a user selects English
- **THEN** all navigation, labels and messages are shown in English

### Requirement: Dashboard
The UI SHALL show a dashboard with active punishments per section, open reports, pending appeals and the most recent automatic permanent escalations.

#### Scenario: Open dashboard
- **WHEN** a user opens the UI start page
- **THEN** the counts and the latest escalations are displayed

### Requirement: Player lookup and detail
The UI SHALL let users look up a player by name and show the player's score per section against its soft and permanent thresholds, active punishments per section, punishment history and ELO history.

#### Scenario: Look up a player
- **WHEN** a user searches for `Steve`
- **THEN** the player detail shows Steve's section scores, active punishments and history

#### Scenario: Unknown player
- **WHEN** the name cannot be resolved
- **THEN** the UI shows a not-found message and no detail page

### Requirement: Staff actions from the player detail
The player detail SHALL allow applying a template, revoking an active punishment per section and adjusting a section score with a mandatory note, each after a confirmation that previews the resulting score and whether it will cause a permanent escalation.

#### Scenario: Apply template with escalation preview
- **WHEN** a user selects a template whose ELO cost would drop the score below the permanent threshold
- **THEN** the confirmation warns that the player will be permanently punished in that section before anything is applied

#### Scenario: Revoke permanent ban
- **WHEN** a user confirms revoking a permanent punishment
- **THEN** the punishment is lifted and the detail shows the score reset to the section's pardon score

### Requirement: Template management
The UI SHALL list, create, edit and delete templates with key, reason, section, duration and ELO cost, showing validation errors returned by the API next to the affected field. Deleting a template still referenced MUST show the conflict instead of failing silently.

#### Scenario: Create template
- **WHEN** a user submits a valid template form
- **THEN** the template appears in the list

#### Scenario: Invalid duration
- **WHEN** a user submits a template without a positive duration
- **THEN** the duration field shows the validation error and nothing is saved

### Requirement: Section configuration
The UI SHALL list sections and let users create, edit and disable them, editing enforcement kind, base score, soft threshold and duration, permanent threshold, pardon score and permanent reason.

#### Scenario: Change permanent threshold
- **WHEN** a user changes the `network` permanent threshold to 250 and saves
- **THEN** the section list shows the new threshold

### Requirement: Report and appeal queues
The UI SHALL list open reports and pending appeals and let users resolve reports and decide appeals with the decisions the API supports.

#### Scenario: Grant an appeal
- **WHEN** a user grants a full lift on a pending appeal
- **THEN** the appeal leaves the pending queue and the punishment is lifted

### Requirement: Single-origin deployment
The UI SHALL be served together with a reverse proxy that forwards API requests to the backend under the same origin, so the backend requires no cross-origin configuration.

#### Scenario: API call from the browser
- **WHEN** the UI requests player data
- **THEN** the request goes to the UI's own origin and is forwarded to the backend

### Requirement: No personal data retained by the UI
The UI MUST NOT persist player names, UUIDs or owner hashes in browser storage; only theme, language and the optional acting-as label MAY be stored.

#### Scenario: Reload after lookup
- **WHEN** a user looks up a player and reloads the browser
- **THEN** no player identifier is restored from browser storage
