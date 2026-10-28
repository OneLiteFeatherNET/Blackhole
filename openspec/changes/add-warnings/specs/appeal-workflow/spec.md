## ADDED Requirements

### Requirement: Warning appeal

A warning SHALL be appealable like any manual punishment. A full lift of a warning SHALL set it lifted without any slot change, record the lift on the case, mark its evidence `LIFTED`, and refund its recorded ELO cost once through `APPEAL_REFUND`. A denial SHALL change nothing.

#### Scenario: Full lift of a warning

- **WHEN** a reviewer grants a full lift on a warning whose cost was 5
- **THEN** the warning is lifted, is no longer counted for escalation, the score rises by 5 once, and the appeal is `GRANTED_FULL_LIFT`

#### Scenario: Denial of a warning

- **WHEN** a reviewer denies an appeal against a warning
- **THEN** the warning and the score are unchanged

## MODIFIED Requirements

### Requirement: Inactive punishment conflict

The system MUST reject a full lift or duration reduction with HTTP 409 when the appealed punishment is no longer the player's active ban or chat ban, leaving the appeal undecided. A warning has no slot and is not subject to this rule; its full lift follows the warning appeal requirement.

#### Scenario: Punishment expired before review

- **WHEN** a reviewer grants a full lift after the punishment has already expired
- **THEN** the response is HTTP 409 and the appeal stays awaiting review

#### Scenario: Warning full lift is not a conflict

- **WHEN** a reviewer grants a full lift on a warning that is still pending in the case
- **THEN** the lift is applied and the response is not 409
