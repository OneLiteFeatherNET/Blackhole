# Spec Delta

## ADDED Requirements

### Requirement: Appeals expose only the evidence count
Appeal responses SHALL include `evidenceCount`, the number of evidence items attached to the appealed punishment, and MUST NOT include evidence content, references, source systems or file metadata.

#### Scenario: Count in appeal response
- **WHEN** an appeal is read for a punishment with 3 evidence items
- **THEN** the response contains `evidenceCount` 3 and no evidence content

### Requirement: Granted full lift erases evidence
When an appeal is granted as full lift, the system SHALL delete all evidence of the lifted punishment with reason `APPEAL_LIFTED`, including stored files. Reductions and denials MUST NOT delete evidence.

#### Scenario: Full lift deletes evidence
- **WHEN** an appeal is granted as full lift for a punishment with a screenshot and an anticheat record
- **THEN** both items and the stored file are deleted and `evidence.deleted` events with reason `APPEAL_LIFTED` are published

#### Scenario: Denial keeps evidence
- **WHEN** an appeal is denied
- **THEN** the punishment's evidence is unchanged

### Requirement: Open appeal holds evidence retention
Evidence of a punishment with an appeal that is not yet decided MUST NOT be deleted by the retention sweep.

#### Scenario: Retention elapsed during open appeal
- **WHEN** the retention period of a punishment has elapsed but its appeal is still awaiting review
- **THEN** the sweep keeps the evidence
