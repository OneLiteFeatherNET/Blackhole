# Spec Delta

## Purpose

Records punishment lifecycle changes on the case timeline.

## ADDED Requirements

### Requirement: Punishment lifecycle is recorded on the case
Applying a punishment (including system escalation) SHALL record `punishment.applied`, a revocation `punishment.revoked`, a granted full-lift appeal `punishment.lifted` and the expiry of a punishment `punishment.expired`, each after the primary write with a deterministic dedupe key. A recording failure MUST NOT fail or roll back the change, and the expiry sweeper MUST record `punishment.expired` once per punishment.

#### Scenario: Apply records the step
- **WHEN** a moderator applies a template that selects step 2 of 3
- **THEN** the case has a `punishment.applied` event with step 2 of 3, the ELO cost and the moderator as verified actor

#### Scenario: Revoke
- **WHEN** a moderator revokes an active punishment
- **THEN** the case has a `punishment.revoked` event with the moderator as actor

#### Scenario: Expiry recorded once
- **WHEN** the expiry sweeper runs twice over the same expired punishment
- **THEN** the case has exactly one `punishment.expired` event, with trust `SYSTEM`

#### Scenario: Recording failure does not block enforcement
- **WHEN** the case recording throws while a ban is applied
- **THEN** the punishment is active, the profile is updated and the failure is counted
