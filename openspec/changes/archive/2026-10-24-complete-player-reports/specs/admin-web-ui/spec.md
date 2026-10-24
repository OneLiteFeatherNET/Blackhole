# Spec Delta

## Purpose

Adds the report detail page to the admin web UI.

## ADDED Requirements

### Requirement: Report detail page
The UI SHALL provide a page `/reports/:id`, linked from every row of the reports list and reachable through the global search with `report:` and the report id. It SHALL show the reported player (linked, by hash or known name), the reporter by hash only and marked as pseudonymous, the category, the text, the time and, once resolved, the resolution note. A raw player UUID MUST NOT be shown.

#### Scenario: Open from the list
- **WHEN** a user follows "Details" in the reports list
- **THEN** the page of that report opens

#### Scenario: Open from the search
- **WHEN** a user enters `report:` and a report id in the global search
- **THEN** the page of that report opens without a backend call

### Requirement: Context of a report
The page SHALL show the evidence the report references with the existing evidence components, so the chat excerpt can be expanded, the other reports against the same player as links to their pages, and the reported player's active punishments with their evidence and case links. A section that cannot be loaded SHALL say so without hiding the report, and evidence that no longer exists SHALL be counted in a message.

#### Scenario: Chat excerpt
- **WHEN** a report references a `CHAT_LOG` item
- **THEN** the page lists it and expanding it shows the redacted lines

#### Scenario: A section fails
- **WHEN** the player's profile cannot be loaded
- **THEN** the report is still shown and the punishments section says it could not be loaded

### Requirement: Resolving from the report page
For an open report, a `MODERATOR` or `ADMIN` SHALL see "Apply template" and "Dismiss"; other roles and resolved reports MUST NOT. "Dismiss" SHALL require a note and resolve the report as `DISMISSED` through the resolve route. An error of the backend SHALL be shown in the dialog.

#### Scenario: Dismiss needs a note
- **WHEN** a moderator opens "Dismiss" without a note
- **THEN** the confirm button is disabled

#### Scenario: Viewer
- **WHEN** a viewer opens an open report
- **THEN** no action buttons are shown

### Requirement: Apply a template to a report
"Apply template" SHALL use the apply dialog and offer first the templates whose section kind fits the category (mute sections for chat abuse, ban and server sections for cheating and griefing, all for other), with a switch to show all. It SHALL preview the step and resolve the report as `ACTIONED` with the template through the resolve route in one call; it MUST NOT apply the template separately.

#### Scenario: Apply template
- **WHEN** a moderator picks a template and confirms
- **THEN** one resolve call with status `ACTIONED` and the template is made and no separate apply call
