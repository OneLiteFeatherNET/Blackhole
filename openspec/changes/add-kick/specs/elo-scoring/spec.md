## ADDED Requirements

### Requirement: Kick template step costs ELO

A `KICK` template step SHALL apply its `eloCost` to the player's score, with the same history event and dedupe as any template step. A kick applied without a template SHALL cost no ELO. A mass kick SHALL cost no ELO.

#### Scenario: Kick step with cost

- **WHEN** a template kick step with `eloCost` 10 is applied to a player
- **THEN** the player's score drops by 10 and one audited ELO event is recorded

#### Scenario: Kick without template

- **WHEN** staff kick a player with no template
- **THEN** no ELO change and no ELO event is recorded

#### Scenario: Mass kick

- **WHEN** an ADMIN mass-kicks a server
- **THEN** no player's score changes
