# Spec Delta

## Purpose

Records evidence status changes on the case timeline without copying content.

## ADDED Requirements

### Requirement: Evidence changes are recorded on the case
Attaching evidence to a punishment (for pending evidence, when it is claimed) SHALL record `evidence.attached`; marking it `REVOKED` or `LIFTED` SHALL record `evidence.revoked` or `evidence.lifted`. The payload MUST contain only the evidence id, kind and source system (and the appeal id for lifted) and MUST NOT contain the payload, reference or file name. The recording goes through `PunishmentEvidenceGateway`/`EvidenceService` only, and a failure MUST NOT fail the evidence change.

#### Scenario: Attach
- **WHEN** a moderator attaches a chat excerpt to a punishment
- **THEN** the case has an `evidence.attached` event with the evidence id and kind and no content

#### Scenario: Pending evidence claimed
- **WHEN** pending evidence is claimed by a new punishment
- **THEN** the new case has an `evidence.attached` event at the claim time

#### Scenario: Lifted by appeal
- **WHEN** a granted full-lift appeal marks evidence `LIFTED`
- **THEN** the case has an `evidence.lifted` event referencing the appeal

#### Scenario: Admin deletion removes no timeline entry
- **WHEN** an admin deletes an evidence item
- **THEN** the earlier `evidence.attached` event stays and carries no content
