# Spec Delta

## Purpose

Records appeal filing and decisions on the case timeline.

## ADDED Requirements

### Requirement: Appeals are recorded on the case
Filing an appeal SHALL record `appeal.filed` (appeal id, eligible flag) and a decision `appeal.decided` (appeal id, decision, new expiry), with no statement or decision note text in the payload. A granted full lift SHALL additionally record `punishment.lifted`. Recording MUST NOT change whether the appeal is accepted or decided.

#### Scenario: Filing
- **WHEN** an appeal is filed for a punishment
- **THEN** the punishment's case has an `appeal.filed` event with the appeal id and no statement text

#### Scenario: Granted full lift
- **WHEN** a moderator grants a full lift
- **THEN** the case has `appeal.decided` and `punishment.lifted` events attributed to the reviewer

#### Scenario: Denial keeps the punishment event-free
- **WHEN** a moderator denies an appeal
- **THEN** the case has an `appeal.decided` event with the decision `DENIED` and no `punishment.lifted` event
