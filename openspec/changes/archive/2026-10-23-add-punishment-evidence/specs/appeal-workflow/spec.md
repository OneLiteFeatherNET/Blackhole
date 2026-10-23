# Spec Delta

## ADDED Requirements

### Requirement: Appeals expose only the evidence count
Appeal responses SHALL include `evidenceCount`, the number of valid evidence items attached to the appealed punishment, and MUST NOT include evidence content, references, source systems or file metadata.

#### Scenario: Count in appeal response
- **WHEN** an appeal is read for a punishment with 3 valid evidence items
- **THEN** the response contains `evidenceCount` 3 and no evidence content

### Requirement: Granted full lift keeps evidence marked as lifted
When an appeal is granted as full lift, the system SHALL keep all evidence of the lifted punishment and mark it `LIFTED` with the lift time and the appeal id; it MUST NOT delete it. Reductions and denials MUST NOT change evidence.

#### Scenario: Full lift marks evidence
- **WHEN** an appeal is granted as full lift for a punishment with a screenshot and an anticheat record
- **THEN** both items and the stored file are kept with status `LIFTED`, liftedAt and the appeal id

#### Scenario: Denial keeps evidence unchanged
- **WHEN** an appeal is denied
- **THEN** the punishment's evidence is unchanged

### Requirement: Evidence of an undecided appeal is kept
Evidence of a punishment with an undecided appeal MUST remain available to the reviewer in the appeal review.

#### Scenario: Reviewer sees evidence
- **WHEN** an appeal awaits review
- **THEN** the valid evidence of the appealed punishment can be listed
