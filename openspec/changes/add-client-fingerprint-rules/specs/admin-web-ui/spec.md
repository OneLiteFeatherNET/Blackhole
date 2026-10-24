# Spec Delta

## Purpose
Gives staff a view to manage client rules and shows the weak fingerprint hint on the player page.

## ADDED Requirements

### Requirement: Client rules view
The UI SHALL list client rules with kind, scope, action, enabled state and hit count. Users below `ADMIN` SHALL see the view read-only with every edit action disabled.

#### Scenario: List
- **WHEN** a user opens the view
- **THEN** each rule shows kind, scope, action, enabled state and hit count

#### Scenario: Create a notify rule
- **WHEN** an admin submits a valid channel rule
- **THEN** it appears in the list with the action `NOTIFY` and zero hits

#### Scenario: Read-only for a moderator
- **WHEN** a `MODERATOR` opens the view
- **THEN** rules and hits are listed and every edit action is disabled

#### Scenario: Template needs acknowledgement
- **WHEN** an admin chooses the action `TEMPLATE` for a brand rule
- **THEN** the form shows a warning that the client controls this value and requires the acknowledgement checkbox before saving

### Requirement: Client rule editor
An `ADMIN` SHALL be able to create, edit, enable, disable and delete rules with the match fields of the chosen kind, a scope picker (global, section keys, target kind and names, reusing the section target picker and the CloudNet catalogue), the action with the deny message or the template picker, and the hide option for commands. Server validation errors SHALL show next to the affected field. The message field SHALL state that player-facing text is English.

#### Scenario: Validation error
- **WHEN** the server rejects a pattern
- **THEN** the error shows next to the pattern field and nothing is saved

### Requirement: Catalogue pickers
The rule editor SHALL offer the catalogue (brands, channels, commands, protocol versions with their names) as suggestions with the number of players, and MUST allow free-text entry when the catalogue is unavailable.

#### Scenario: Catalogue unavailable
- **WHEN** the catalogue answers `available: false`
- **THEN** the editor still accepts typed patterns and says suggestions are unavailable

### Requirement: Hits list
The view SHALL list the hits of a rule (player link, time, action, enforced or bypassed, server) and the player page SHALL list the player's recent hits. Neither SHALL show a brand, channel, mod or command text.

#### Scenario: Hit with a template
- **WHEN** a hit applied a template
- **THEN** the entry links to the resulting case

### Requirement: Client hint on the player page
The evasion hints on the player page SHALL show the origin of each linked account (IP, client or both), labelling a client-only link as a weak hint that can be faked or shared, and MUST NOT show a fingerprint.

#### Scenario: Client-only link
- **WHEN** the sightings contain a link with origin `CLIENT`
- **THEN** the hint is labelled "weak: same client setup"
