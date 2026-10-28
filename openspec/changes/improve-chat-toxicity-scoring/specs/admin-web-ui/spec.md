## ADDED Requirements

### Requirement: Chat toxicity thresholds and mode panel

The UI SHALL show a chat toxicity panel per section listing the mode (`OFF`, `SHADOW`, `ENFORCE`) and the four category thresholds (`INSULT`, `THREAT`, `SEXUAL`, `HATE`). An ADMIN SHALL be able to change the mode and each threshold; a value outside `[0, 1]` MUST be refused in the form before saving and by the API. A MODERATOR and VIEWER SHALL see the values read-only. Saving a mode change SHALL show a confirmation that names the section and the new mode.

#### Scenario: Admin changes a threshold

- **WHEN** an ADMIN sets the `THREAT` threshold of the `chat` section to 0.25 and saves
- **THEN** the panel shows 0.25 for `THREAT` in the `chat` section
- **AND** the change is recorded with the admin's name and time

#### Scenario: Out-of-range threshold

- **WHEN** an ADMIN enters 1.4 for a threshold
- **THEN** the form shows a validation message and does not save

#### Scenario: Moderator sees read-only values

- **WHEN** a MODERATOR opens the chat toxicity panel
- **THEN** the mode and thresholds are shown and no edit control is enabled

#### Scenario: Confirmed mode change

- **WHEN** an ADMIN changes the `chat` section from `SHADOW` to `ENFORCE`
- **THEN** a confirmation names the section and `ENFORCE` before the change is saved

### Requirement: Feedback on flagged and shadow-flagged messages

The player history and the chat toxicity list SHALL offer `False positive` and `Confirmed` actions on each flagged or shadow-flagged entry for a MODERATOR or ADMIN. A saved label SHALL be shown on the entry with the label, the staff name and the time. A label action MUST NOT show or change any ELO value.

#### Scenario: Marking a false positive

- **WHEN** a MODERATOR marks a shadow-flagged entry `False positive`
- **THEN** the entry shows the label with the moderator's name and the time
- **AND** the player's ELO displayed on the page is unchanged

#### Scenario: Label is shown after reload

- **WHEN** a labelled entry is opened again
- **THEN** the saved label is shown

### Requirement: Labelled sample list and export

The UI SHALL list the labelled samples of a section, filterable by label and date, with the score per category and the model version. An ADMIN SHALL be able to export the labelled samples as CSV. The list and the export SHALL show the message text only to ADMIN and MODERATOR, and SHALL NOT show names or UUIDs. The UI SHALL state the retention period on the page.

#### Scenario: Filter by label

- **WHEN** a user filters the sample list by `FALSE_POSITIVE`
- **THEN** only labelled false positives are listed

#### Scenario: Viewer cannot see text

- **WHEN** a VIEWER opens the sample list
- **THEN** the message text is not shown and the export control is not shown

#### Scenario: Export is admin only

- **WHEN** a MODERATOR opens the sample list
- **THEN** no CSV export control is shown

#### Scenario: Retention note

- **WHEN** the sample list is shown
- **THEN** the page states the retention period from the server configuration
