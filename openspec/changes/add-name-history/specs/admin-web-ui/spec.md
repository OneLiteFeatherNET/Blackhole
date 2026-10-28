## ADDED Requirements

### Requirement: Name history section on the player page
The player page SHALL show a "Name history" section for every role that may open the page, listing the names of the player's owner hash with first and last seen, the most recent name first. The section SHALL say from which date the history is complete, the date of the earliest session record.

#### Scenario: Two names
- **WHEN** a moderator opens a player who used two names
- **THEN** the section lists both names with their first and last seen times

#### Scenario: Short history
- **WHEN** the player's only known name was first seen at the start of the recorded history
- **THEN** the section notes that earlier names are not known

## MODIFIED Requirements

### Requirement: Player lookup and detail
The UI SHALL let users look up a player by name and show the player's score per section against its soft and permanent thresholds, active punishments per section, punishment history and ELO history. When the live name lookup finds nothing, the UI SHALL look the name up in the name history and open the player page of the single account that matches; when several accounts match, it SHALL list them for the user to choose.

#### Scenario: Look up a player
- **WHEN** a user searches for `Steve`
- **THEN** the player detail shows Steve's section scores, active punishments and history

#### Scenario: Unknown player
- **WHEN** the name cannot be resolved by the live lookup and the name history has no account for it
- **THEN** the UI shows a not-found message and no detail page

#### Scenario: Old name
- **WHEN** a user searches for a name the account no longer uses and the history has one account for it
- **THEN** the UI opens that account's player page

#### Scenario: Several accounts used the name
- **WHEN** the history has more than one account for the searched name
- **THEN** the UI lists those accounts and opens the one the user picks
