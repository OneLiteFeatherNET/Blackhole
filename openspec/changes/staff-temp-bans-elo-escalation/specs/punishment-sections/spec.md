# Spec Delta

## Purpose

Defines punishment sections (e.g. chat, network) as configurable units that each own a player's ELO score, thresholds and enforcement, and the automatic permanent ban the system applies per section.

## ADDED Requirements

### Requirement: Server-scoped sections
A section with enforcement SERVER SHALL list one or more backend server name patterns (glob, e.g. `bedwars-*`); its punishments apply only to servers matching a pattern. Patterns MUST be required for SERVER sections, rejected for other kinds, and a pattern matching every server MUST be rejected.

#### Scenario: Create a server section
- **WHEN** a section `bedwars` with enforcement SERVER and pattern `bedwars-*` is created
- **THEN** it is stored with its own base score, thresholds and pardon score

#### Scenario: Missing patterns rejected
- **WHEN** a SERVER section is submitted without patterns, or with only `*`
- **THEN** the request is rejected with a validation error

### Requirement: Section registry
The system SHALL manage punishment sections via `/section` (create, update, list, get, disable). A section MUST have a unique kebab-case key, a display name, an enforcement kind (MUTE, BAN or SERVER), a base score, a permanent threshold, a pardon score and a permanent reason, and MAY have a soft threshold with soft duration.

#### Scenario: Create a section
- **WHEN** `POST /section` is called with key `pvp`, enforcement BAN, base 1000, permanent threshold 300, pardon 700 and a permanent reason
- **THEN** the section is stored and returned, and players start with score 1000 in it

#### Scenario: Invalid thresholds rejected
- **WHEN** a section is submitted whose permanent threshold is not lower than its base score, or whose pardon score is not above its permanent threshold
- **THEN** the request is rejected with a validation error and nothing is stored

### Requirement: Default sections
A new installation SHALL provide the sections `chat` (enforcement MUTE) and `network` (enforcement BAN, enforced at the proxy), each with base 1000, soft threshold 700, permanent threshold 300 and pardon score 700.

#### Scenario: Fresh installation
- **WHEN** the backend starts on an empty database
- **THEN** `GET /section` lists `chat` and `network` with those defaults

### Requirement: Sections are independent
A punishment, ELO deduction or escalation in one section MUST NOT change the score, active punishment or escalation state of any other section.

#### Scenario: Chat escalation leaves network untouched
- **WHEN** a player is permanently muted in section `chat`
- **THEN** the player can still join the network and their `network` score is unchanged

#### Scenario: Server ban leaves the network open
- **WHEN** a player is permanently banned from section `bedwars`
- **THEN** the player can still join the network and other servers, and their `network` score is unchanged

### Requirement: Automatic permanent ban per section
After any negative score change in a section, if the resulting score is below the section's permanent threshold and the player has no active permanent punishment in that section, the system SHALL apply a permanent punishment in that section, attributed to the ELO system source and carrying the section's permanent reason.

#### Scenario: Staff temp ban pushes score below threshold
- **WHEN** a player with `network` score 380 receives a staff temp ban costing 150
- **THEN** the score becomes 230 and the player is permanently banned from section `network`
- **AND** the staff temp ban is moved to history and the permanent ban becomes the active `network` punishment

#### Scenario: Already permanently punished
- **WHEN** a player with an active permanent `chat` punishment receives another chat deduction
- **THEN** no additional permanent punishment is created

#### Scenario: Pardoned player offends again below threshold
- **WHEN** a player whose permanent `network` ban was revoked still has a score below the threshold and receives a further deduction
- **THEN** a new permanent `network` ban is applied

### Requirement: Escalation is published
Every automatic permanent punishment SHALL publish a `section.escalated` domain event with owner, section, resulting score and punishment identifier.

#### Scenario: Event on escalation
- **WHEN** the system permanently bans a player from section `network`
- **THEN** a `section.escalated` event with section `network` and the resulting score is published

### Requirement: Disabled sections
A disabled section SHALL reject new template applications and score deltas with an error, while its existing active punishments remain enforced until they expire or are lifted.

#### Scenario: Apply to disabled section
- **WHEN** a template of a disabled section is applied
- **THEN** the request fails with a section-disabled error and no punishment or deduction is recorded
