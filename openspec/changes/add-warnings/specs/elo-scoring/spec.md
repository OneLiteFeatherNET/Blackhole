## ADDED Requirements

### Requirement: Warning template step costs ELO

A `WARN` template step SHALL apply its `eloCost` to the player's score, with the same history event and dedupe as any template step. The default `eloCost` of a warning step SHALL be 0, which changes no score and records no ELO event. A warning appealed and fully lifted SHALL refund its recorded cost once, with reason `APPEAL_REFUND`.

#### Scenario: Warning step with cost

- **WHEN** a template warning step with `eloCost` 5 is applied to a player
- **THEN** the player's score drops by 5 and one audited ELO event is recorded

#### Scenario: Warning step without cost

- **WHEN** a template warning step with `eloCost` 0 is applied
- **THEN** no score changes and no ELO event is recorded

#### Scenario: Lifted warning refunds its cost

- **WHEN** a warning with recorded cost 5 is fully lifted on appeal
- **THEN** the score rises by 5 once with reason `APPEAL_REFUND`
