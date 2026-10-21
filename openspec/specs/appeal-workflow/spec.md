# appeal-workflow Specification

## Purpose
Lets a punished player contest an automatic or manual punishment through a structured appeal.
A fixed, versioned eligibility checklist gates whether an appeal is reviewable, and a human
reviewer then decides within the bounds that checklist allows.

## Requirements

### Requirement: Appeal submission
The system SHALL accept appeals via `POST /appeal` carrying a `punishmentIdentifier`, an `appellantHash` (a 128-character hex SHA-512 hash) and a `statement` of at most 2000 characters. The system MUST evaluate the eligibility checklist at submission time and record the appeal with status `ELIGIBLE_PENDING_REVIEW` or `INELIGIBLE`, returning the recorded appeal.

#### Scenario: Eligible appeal is recorded for review
- **WHEN** a player submits an appeal against an existing punishment that passes the eligibility checklist
- **THEN** the appeal is recorded with status `ELIGIBLE_PENDING_REVIEW`
- **AND** the response contains the appeal including its eligibility checklist snapshot

#### Scenario: Ineligible appeal is still recorded
- **WHEN** a player submits an appeal that fails the eligibility checklist
- **THEN** the appeal is recorded with status `INELIGIBLE`

#### Scenario: Malformed appellant hash is rejected
- **WHEN** an appeal is submitted with an `appellantHash` that is not a 128-character hex string
- **THEN** the request is rejected with a validation error and nothing is recorded

### Requirement: Unknown punishment rejection
The system MUST reject an appeal whose `punishmentIdentifier` does not match an existing punishment with HTTP 404, and MUST NOT record anything for it.

#### Scenario: Appeal against a non-existent punishment
- **WHEN** an appeal is submitted with a `punishmentIdentifier` that matches no punishment
- **THEN** the response is HTTP 404
- **AND** no appeal is recorded

### Requirement: Minimum wait period
The eligibility checklist SHALL require a minimum number of days since the punishment was created: `blackhole.appeal.min-days-auto` (default 3) for punishments issued automatically by the ELO system, and `blackhole.appeal.min-days-manual` (default 14) for all other punishments. An appeal submitted before that period has elapsed MUST be marked `INELIGIBLE`.

#### Scenario: Auto-punishment appealed after its shorter wait
- **WHEN** an appeal is submitted 4 days after an ELO-triggered punishment with default configuration
- **THEN** the minimum-time criterion passes

#### Scenario: Manual punishment appealed too early
- **WHEN** an appeal is submitted 4 days after a staff-issued punishment with default configuration
- **THEN** the appeal is recorded as `INELIGIBLE`
- **AND** the checklist snapshot records `minTimeElapsed` as false and `minDaysRequired` as 14

### Requirement: Repeat-appeal cooldown
The eligibility checklist SHALL treat an appeal as a repeat appeal when a prior appeal on the same punishment with status `DENIED` or `INELIGIBLE` was created within `blackhole.appeal.repeat-appeal-cooldown-days` (default 7). A repeat appeal MUST be marked `INELIGIBLE`.

#### Scenario: Re-appeal shortly after a denial
- **WHEN** an appeal is submitted on a punishment whose previous appeal was denied 2 days earlier
- **THEN** the new appeal is recorded as `INELIGIBLE` with `isRepeatAppeal` true

#### Scenario: Re-appeal after the cooldown
- **WHEN** an appeal is submitted on a punishment whose only prior denied appeal was created more than 7 days earlier and the wait period has elapsed
- **THEN** the repeat-appeal criterion does not block eligibility

### Requirement: Versioned eligibility checklist snapshot
Every appeal SHALL store an immutable checklist snapshot including the checklist version, required and elapsed days, repeat-appeal flag, whether the punishment was auto-triggered, its ELO trigger reason code, a severity tier (`SEVERE` or `STANDARD`), the supporting ELO track with its score and recovery flag, and the overall eligibility result.

#### Scenario: Snapshot is returned with the appeal
- **WHEN** an appeal is submitted
- **THEN** the returned appeal's `eligibilityCheckResult` contains `checklistVersion`, `severityTier`, `supportingEloTrack`, `supportingEloScore` and `eligible`

### Requirement: Supporting ELO signal
The checklist SHALL report the ELO track that did not trigger the punishment as a supporting signal: gameplay for toxicity-triggered punishments, chat for anticheat-triggered ones, otherwise gameplay for chat punishments and chat for all others. It MUST be marked recovered when that score is at or above `blackhole.elo.baseline`, and MUST NOT gate eligibility.

#### Scenario: Toxicity-triggered chat punishment
- **WHEN** an appeal is submitted against a punishment triggered by a toxicity flag
- **THEN** the checklist reports the gameplay track score as the supporting signal
- **AND** eligibility is decided independently of whether that score has recovered

### Requirement: Appeal listing
The system SHALL return a paginated list of all appeals via `GET /appeal`, each including its status, checklist snapshot and decision fields.

#### Scenario: List appeals
- **WHEN** a caller requests `GET /appeal` with paging parameters
- **THEN** the response is a page of appeals

### Requirement: Review preconditions
The system SHALL accept decisions via `POST /appeal/{identifier}/review`. It MUST return 404 for an unknown appeal, 409 when the appeal is not `ELIGIBLE_PENDING_REVIEW` or `IN_REVIEW`, and 400 when the decision is not one of `GRANTED_FULL_LIFT`, `GRANTED_DURATION_REDUCTION` or `DENIED`.

#### Scenario: Reviewing an ineligible appeal
- **WHEN** a reviewer submits a decision for an appeal with status `INELIGIBLE`
- **THEN** the response is HTTP 409 and nothing changes

#### Scenario: Invalid decision value
- **WHEN** a reviewer submits the decision `SUBMITTED`
- **THEN** the response is HTTP 400

### Requirement: Self-review exclusion
The system MUST reject a review with HTTP 403 when the supplied `reviewerId` equals the source that issued the appealed punishment.

#### Scenario: Issuer reviews their own punishment
- **WHEN** the `reviewerId` matches the punishment's original source
- **THEN** the response is HTTP 403 and the appeal remains undecided

### Requirement: Severe punishments cannot be fully lifted
The checklist SHALL classify a permanent network ban (no expiration date) as `SEVERE` and any other punishment as `STANDARD`; the tier MUST NOT affect eligibility. The system MUST reject a `GRANTED_FULL_LIFT` decision with HTTP 400 when the tier is `SEVERE`; only a duration reduction or denial is permitted.

#### Scenario: Permanent network ban is classified severe
- **WHEN** an appeal is submitted against a permanent network ban
- **THEN** the checklist snapshot records `severityTier` as `SEVERE`

#### Scenario: Full lift on a permanent ban
- **WHEN** a reviewer grants a full lift on an appeal whose severity tier is `SEVERE`
- **THEN** the response is HTTP 400 and the punishment remains in force

### Requirement: Full lift decision
When a reviewer grants `GRANTED_FULL_LIFT`, the system SHALL move the punishment from the player's active ban or chat-ban slot into their history, record the reviewer as `revokedBy` on the punishment, invalidate cached state for the player, and mark the appeal decided with reviewer and optional note.

#### Scenario: Active ban is lifted
- **WHEN** a reviewer grants a full lift on an eligible appeal for a still-active standard ban
- **THEN** the punishment is no longer active and appears in the player's history
- **AND** the appeal status becomes `GRANTED_FULL_LIFT` with `decidedBy` set to the reviewer

### Requirement: Duration reduction decision
When a reviewer grants `GRANTED_DURATION_REDUCTION`, the system MUST require a `newExpirationAt` timestamp in the future (HTTP 400 otherwise) and SHALL set it as the punishment's new expiration while keeping it active.

#### Scenario: Reduction with a future expiry
- **WHEN** a reviewer grants a duration reduction with a `newExpirationAt` one day ahead
- **THEN** the punishment's expiration is updated to that timestamp
- **AND** the appeal status becomes `GRANTED_DURATION_REDUCTION`

#### Scenario: Reduction without an expiry
- **WHEN** a reviewer grants a duration reduction without `newExpirationAt`
- **THEN** the response is HTTP 400

### Requirement: Denial decision
When a reviewer submits `DENIED`, the system SHALL mark the appeal `DENIED` with reviewer and note and MUST leave the punishment unchanged.

#### Scenario: Appeal denied
- **WHEN** a reviewer denies an eligible appeal
- **THEN** the appeal status becomes `DENIED` and the punishment is unaffected

### Requirement: Inactive punishment conflict
The system MUST reject a full lift or duration reduction with HTTP 409 when the appealed punishment is no longer the player's active ban or chat ban, leaving the appeal undecided.

#### Scenario: Punishment expired before review
- **WHEN** a reviewer grants a full lift after the punishment has already expired
- **THEN** the response is HTTP 409 and the appeal stays awaiting review

### Requirement: Appeal domain events
The system SHALL publish `appeal.submitted` (appeal id, owner, punishment id, eligible flag) on every recorded submission and `appeal.resolved` (appeal id, owner, punishment id, decision, punishment type) on every successful decision. A `GRANTED_FULL_LIFT` resolution MUST clear the punishment from the proxy-facing active-punishment state.

#### Scenario: Full lift propagates to proxies
- **WHEN** an appeal is resolved with `GRANTED_FULL_LIFT`
- **THEN** an `appeal.resolved` event is published
- **AND** the lifted punishment is removed from the shared active-punishment state read by proxies
