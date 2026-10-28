## ADDED Requirements

### Requirement: Amend action on the player and case pages

The panel SHALL offer "Amend" on each active punishment of the player page and in the case page actions. The dialog SHALL show the state before and after for each change, require a justification of at least 10 characters, read the current `ETag` before submit and send it as `If-Match`. The panel SHALL offer ADMIN-only changes only to ADMIN users; the server enforces the role regardless.

#### Scenario: Before and after

- **WHEN** a moderator changes the reason of a temporary ban
- **THEN** the dialog shows the old and the new reason before submit

#### Scenario: Justification required

- **WHEN** the justification is shorter than 10 characters
- **THEN** submit is disabled

#### Scenario: Moderator sees no extension

- **WHEN** a MODERATOR opens the amend dialog of a temporary ban
- **THEN** the extension and permanence options are not shown

### Requirement: Version conflict in the panel

A version conflict (412) SHALL show a message that the punishment changed meanwhile, and SHALL reload the punishment before the user can amend it again.

#### Scenario: Conflict

- **WHEN** a submit is answered 412
- **THEN** the dialog shows the conflict message and reloads the punishment

### Requirement: Amendment history on the case

The case page SHALL list the amendments of a punishment with the actor, the time, the justification and the state before and after, newest first, with the cursor pagination of the API.

#### Scenario: History

- **WHEN** a case has two amendments
- **THEN** both are listed newest first with their justifications
