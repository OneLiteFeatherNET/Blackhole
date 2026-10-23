# Spec Delta

## MODIFIED Requirements

### Requirement: Self-review exclusion
The system MUST reject a review with HTTP 403 when the verified `oid` of the reviewing caller equals the source that issued the appealed punishment or that punishment's `recorded_by_oid`. The reviewer SHALL be taken from the token; the request MUST NOT carry a `reviewerId`.

#### Scenario: Issuer reviews their own punishment
- **WHEN** the caller's `oid` matches the punishment's original source
- **THEN** the response is HTTP 403 and the appeal remains undecided

#### Scenario: Spoofed reviewer is ignored
- **WHEN** a request body contains a `reviewerId` different from the caller
- **THEN** the review is evaluated and recorded for the caller's `oid`

#### Scenario: Another moderator reviews
- **WHEN** a moderator who did not issue the punishment decides the appeal
- **THEN** the decision is recorded with that moderator's `oid` as `decidedBy`
