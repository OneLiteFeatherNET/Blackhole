# Spec Delta

## Purpose

Adds the CloudNet target picker to the section editor of the admin web UI.

## ADDED Requirements

### Requirement: CloudNet target picker
For a SERVER section the section editor SHALL offer a target selector (pattern, CloudNet task, CloudNet group, CloudNet service). For `PATTERN` it SHALL keep the free-text pattern input. For a CloudNet kind it SHALL offer a multi-select fed by `GET /cloudnet/catalogue` (tasks, groups or services according to the kind) and SHALL mark chosen names that are not in the catalogue. The section list SHALL show the target kind next to the targets.

#### Scenario: Pick a task
- **WHEN** a user chooses target CloudNet task and the catalogue lists tasks `BedWars` and `SkyWars`
- **THEN** both are offered and choosing `BedWars` saves the section with `targetKind` `CLOUDNET_TASK` and servers `["BedWars"]`

#### Scenario: Name not in the catalogue
- **WHEN** the saved section names a task that the catalogue does not list
- **THEN** the editor marks it as not in the catalogue and still allows saving

#### Scenario: Switching to patterns
- **WHEN** a user switches the target back to pattern
- **THEN** the pattern input is shown and wildcard characters are accepted again

### Requirement: Free-text fallback
For a CloudNet kind the editor SHALL always allow adding a name as free text. When the catalogue is not available, or its request fails, the editor SHALL work in free-text mode, say that no CloudNet data was reported, and MUST NOT block the form.

#### Scenario: No catalogue
- **WHEN** `GET /cloudnet/catalogue` answers `available` false and the user chooses CloudNet group
- **THEN** a free-text input is shown with a hint that no CloudNet data was reported, and typed names are saved

#### Scenario: Catalogue request fails
- **WHEN** `GET /cloudnet/catalogue` fails
- **THEN** the editor falls back to free text without blocking the form

### Requirement: Service target warning
When the target kind is CloudNet service the editor SHALL show a warning that a service name can belong to a different instance after a restart and that a task or group should be used for lasting bans.

#### Scenario: Warning shown
- **WHEN** a user selects CloudNet service
- **THEN** the warning is visible before saving

#### Scenario: Warning hidden for tasks
- **WHEN** a user selects CloudNet task
- **THEN** the warning is not shown
