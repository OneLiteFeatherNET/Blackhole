# Spec Delta

## ADDED Requirements

### Requirement: Granted appeals restore ELO
When an appeal is granted, the system SHALL adjust the appellant's score in the punishment's section: a full lift or reduction of a permanent punishment MUST set the score to the section's pardon score (reason PARDON_RESET); a full lift of a temporary punishment MUST add back the ELO cost recorded on that punishment (reason APPEAL_REFUND). Denials MUST NOT change scores.

#### Scenario: Permanent ban lifted on appeal
- **WHEN** an appeal against a permanent `network` ban is granted as full lift and the pardon score is 700
- **THEN** the ban is lifted and the appellant's `network` score is set to 700

#### Scenario: Staff temp ban lifted on appeal
- **WHEN** an appeal against a temporary `chat` mute that cost 80 ELO is granted as full lift
- **THEN** the mute is lifted and 80 is added back to the appellant's `chat` score

#### Scenario: Appeal denied
- **WHEN** an appeal is denied
- **THEN** the appellant's scores are unchanged
