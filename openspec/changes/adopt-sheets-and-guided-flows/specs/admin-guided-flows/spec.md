# Spec Delta

## Purpose

Defines the step-by-step flow that multi-step staff tasks use inside a side sheet: which tasks use it,
how each step explains itself, how it validates, and how it ends with a review of what will happen.

## ADDED Requirements

### Requirement: Multi-step tasks use a guided flow
The tasks creating a client rule, creating or editing a template with escalation steps, punishing a
player, kicking a player, sharing evidence, presenting a case file and deciding an appeal SHALL be
guided flows. A task with one or two fields and no consequence beyond the form MAY stay a plain side sheet.

#### Scenario: Client rule is a guided flow
- **WHEN** an administrator opens "New client rule"
- **THEN** the sheet shows the steps of the guided flow with the first step active

#### Scenario: Plain edit stays a sheet
- **WHEN** an administrator edits the description of a server section
- **THEN** a plain side sheet opens without steps

### Requirement: Progress is shown and announced
A guided flow SHALL show its steps with the current, completed and upcoming steps distinguishable by more than colour, and the number of the current step out of the total. When the step changes, the new step title SHALL be announced once through a polite live region.

#### Scenario: Step change is announced
- **WHEN** a moderator presses "Next" on step 1 of 4 that is valid
- **THEN** the heading of step 2 receives focus and "Step 2 of 4: <title>" is announced once

#### Scenario: Progress without colour
- **WHEN** a step is completed
- **THEN** the completed state is shown by a check icon and text, not by colour alone

### Requirement: Each step explains itself
Each step SHALL show a one-sentence purpose, the fields of that step and the help of each field. A step SHALL NOT show fields of a later step.

#### Scenario: Purpose of a step
- **WHEN** the step "What to match" of the client rule flow is active
- **THEN** the step shows its purpose sentence and only the match fields

### Requirement: Validation happens per step
"Next" SHALL validate only the current step. Errors SHALL be shown next to the field and listed at the top of the step with links to the fields. A later step MUST NOT be reached while the current step has errors, and values entered in earlier steps SHALL be kept when going back.

#### Scenario: Invalid step blocks Next
- **WHEN** a moderator presses "Next" on a step with an empty required field
- **THEN** the step stays open, the field shows its error and the error list links to that field

#### Scenario: Back keeps input
- **WHEN** a moderator presses "Back" from step 3 to step 2
- **THEN** the values of step 2 are unchanged

### Requirement: Every guided flow ends with a review
The last step of a guided flow SHALL be a review that lists every choice in plain language, names the consequence of the final action (for example "A hit applies the punishment template X and the player can appeal"), and offers the final action as its only primary control.

#### Scenario: Review names the consequence
- **WHEN** a moderator reaches the review of a rule whose action is TEMPLATE
- **THEN** the review states that a hit applies the template and that the player can appeal it

#### Scenario: Review without edits
- **WHEN** the review is shown
- **THEN** no field is editable there and each line links back to the step that sets it

### Requirement: Final action reports the result in place
When the final action is submitted, the sheet SHALL stay open until the server answers. On success the sheet closes and the list shows the change. On a server error the error SHALL be shown in the review without losing any input.

#### Scenario: Server refuses
- **WHEN** the final action is refused with a validation problem for one field
- **THEN** the flow returns to that field's step, shows the error there and keeps every value
