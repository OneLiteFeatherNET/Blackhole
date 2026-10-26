# Spec Delta

## Purpose
Gives every escalation step of a punishment template its own translatable reason, and lets a punishment keep its reason as a key with arguments plus a history snapshot.

## ADDED Requirements

### Requirement: Reason translation key per escalation step
The system SHALL give every escalation step of a punishment template a reason translation key of the form `template.<key>.step.<stepId>.reason` in the catalog. The `<stepId>` MUST be assigned when the step is created, MUST be unique within the template, and MUST NOT be reused after the step is removed. The template's own reason SHALL be the `en` text of `template.<key>.name`. A template step without a reason text MUST NOT be saved; the `en` text is required.

#### Scenario: Step gets its own reason
- **WHEN** a template with three escalation steps is saved with an `en` reason for each step
- **THEN** each step stores a reason key of its own, and the three keys are different

#### Scenario: Missing English reason
- **WHEN** a template step is saved without an `en` reason
- **THEN** the save is rejected with a Problem Details error that names the step

#### Scenario: Reordering keeps the reason
- **WHEN** the steps of a template are reordered
- **THEN** each step keeps its reason key and its reason text

### Requirement: Punishment stores its reason key and snapshot
The system SHALL store the reason translation key and its arguments for every punishment created from a template step or a catalog text, together with a snapshot of the `en` text rendered at creation. The snapshot MUST NOT change when the catalog text changes later.

#### Scenario: Keyed punishment stores key, arguments and snapshot
- **WHEN** a punishment is created from a template step with a reason key and arguments
- **THEN** the punishment stores the key, the arguments and the rendered `en` text as the snapshot

#### Scenario: Player screens render the current text
- **WHEN** a player's screen is built for a keyed punishment
- **THEN** the screen renders the current catalog text of the key with the stored arguments, and the moderator history shows the snapshot

#### Scenario: Later edit does not rewrite history
- **WHEN** the `en` text of a step reason is changed after a punishment was created from that step
- **THEN** the moderator history of that punishment still shows the snapshot text and the player screen shows the new text

#### Scenario: Free-text reason
- **WHEN** a staff member punishes a player with a free-text reason
- **THEN** the punishment stores the text and no reason key

#### Scenario: Arguments are stored, not rendered
- **WHEN** a punishment is created with reason arguments such as the name of a template
- **THEN** the arguments are stored as values and the snapshot holds the rendered text

### Requirement: Snapshot is free of personal data
The reason snapshot SHALL NOT contain any player name, raw UUID, IP address or owner hash, and the reason arguments MUST be limited to the declared argument types of the key. The snapshot MUST be checked against the existing PII guard before it is stored.

#### Scenario: Argument with a player name is rejected
- **WHEN** a reason argument would contain a player name
- **THEN** the punishment is not created with that argument and the request fails with a Problem Details error
