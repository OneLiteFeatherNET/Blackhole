## ADDED Requirements

### Requirement: Join guard settings section
The join guard page SHALL show a settings section with the unattended level, the absence threshold, the night window (enabled flag, start and end) and the time zone. Only an admin SHALL be able to edit and save the section. Viewers and moderators SHALL see the same values read-only, and SHALL NOT see any input control for them.

#### Scenario: An admin edits the settings
- **WHEN** an admin opens the join guard page
- **THEN** the settings section shows editable controls for the five settings, and a save action

#### Scenario: A moderator sees the settings read-only
- **WHEN** a moderator opens the join guard page
- **THEN** the settings section shows the values and no input control for them

#### Scenario: A viewer sees the settings read-only
- **WHEN** a viewer opens the join guard page
- **THEN** the settings section shows the values and no input control for them

### Requirement: Next night window in the viewer's time zone
The join guard page SHALL show the next night window as a start and an end time in the viewer's own time zone, and SHALL name the configured time zone next to it. When the window is disabled, the page SHALL say so and SHALL show no times.

#### Scenario: A viewer in another zone
- **WHEN** the configured zone is `Europe/Berlin`, the window is 01:00-07:00, and the viewer's browser is in `UTC`
- **THEN** the page shows the window in UTC with `Europe/Berlin` named as the configured zone

#### Scenario: A disabled window
- **WHEN** the night window is disabled
- **THEN** the page states that no night window applies and shows no start or end time

### Requirement: Confirmation for LOCKDOWN as the unattended level
Saving the unattended level `LOCKDOWN` SHALL ask for confirmation in a dialog that names the level. Saving any other change SHALL NOT ask again.

#### Scenario: Confirming LOCKDOWN
- **WHEN** an admin selects `LOCKDOWN` as the unattended level and saves
- **THEN** a confirmation dialog names `LOCKDOWN` before the request is sent

#### Scenario: Changing the absence threshold
- **WHEN** an admin changes only the absence threshold and saves
- **THEN** no confirmation dialog is shown

### Requirement: Bounds in the form and German and English texts
The form SHALL refuse a value outside the bounds of the join guard requirements before sending it, using the same numbers as the server. A server refusal SHALL be shown in the form with its message. Every text of the section SHALL exist in German and in English.

#### Scenario: An absence threshold outside the bounds
- **WHEN** an admin enters 300 minutes
- **THEN** the form shows a message with the allowed range and the save action is disabled

#### Scenario: A texts check
- **WHEN** the locale files are compared
- **THEN** every key of the settings section exists in `de.json` and in `en.json`
