## ADDED Requirements

### Requirement: Warn action on the player and case pages

The panel SHALL offer "Warn" from the player row menu, the player page header and the case page actions. The dialog SHALL ask for a template (required) and show the step the server applied, as a chip: `Warning`, `Kick`, or the ban or mute label. It SHALL call the template apply route; there is no separate warn route in the panel.

#### Scenario: Warn with a warning step

- **WHEN** a moderator warns a player with a template whose current step is `WARN`
- **THEN** the dialog shows the chip `Warning` and the history gains a warning row

#### Scenario: Template required

- **WHEN** no template is selected
- **THEN** submit is disabled

### Requirement: Warning rows in history

The player history SHALL show warning rows with a "Warning" badge and the acknowledged time when acknowledged, or "Pending" when not. Warning rows MUST NOT be counted in "active" counters.

#### Scenario: Pending warning

- **WHEN** a player has one unacknowledged warning and no active punishment
- **THEN** the history shows the warning as "Pending" and the active counter shows zero
