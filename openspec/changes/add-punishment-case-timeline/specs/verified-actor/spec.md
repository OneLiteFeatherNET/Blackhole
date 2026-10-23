# Spec Delta

## Purpose

Extends the verified actor to case events and states how staff anonymization covers them.

## ADDED Requirements

### Requirement: Case event actors
Every case event SHALL store the actor as the verified `oid` (and the proxy's `oid` as `recorded_by_oid` for in-game actions), an attributed source UUID only when no `oid` exists, and a trust level; it MUST NOT store a display name, email or user principal name. Actors of events written before this change MUST be `UNVERIFIED` unless the source row has a `recorded_by_oid`.

#### Scenario: Name comes from the snapshot
- **WHEN** a staff member's name changes in `staff_actor`
- **THEN** their earlier timeline events show the new name

#### Scenario: Legacy events are unverified
- **WHEN** a punishment from before SSO is backfilled
- **THEN** its events have trust `UNVERIFIED`

### Requirement: Anonymization covers the case timeline
Anonymizing a `staff_actor` SHALL leave every case event unchanged and show that actor without a name on all of them, keeping the `oid`.

#### Scenario: Former moderator in a timeline
- **WHEN** an admin anonymizes a moderator who changed the status of a case
- **THEN** the event still references the `oid` and the timeline shows no name
