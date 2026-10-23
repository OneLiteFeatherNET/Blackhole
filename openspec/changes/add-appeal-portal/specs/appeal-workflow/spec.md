# Spec Delta

## Purpose
Adds the portal filing path, the one-open-appeal rule and the player-visible decision message.

## ADDED Requirements

### Requirement: One open appeal per punishment
The system SHALL keep at most one appeal in an open status (`ELIGIBLE_PENDING_REVIEW`, `IN_REVIEW`) per punishment, enforced by a unique partial index, and a second filing while one is open MUST be refused with HTTP 409.

#### Scenario: Concurrent filing
- **WHEN** two filings for the same punishment arrive at once
- **THEN** exactly one appeal is created and the other is refused with HTTP 409

### Requirement: Portal filing records only eligible appeals
Filing from the player portal SHALL evaluate the eligibility checklist without recording and MUST record an appeal only when it is eligible; an ineligible attempt is answered with the reason and the date it becomes possible. `POST /appeal` for staff and tools MUST keep recording `INELIGIBLE` appeals as before.

#### Scenario: Portal attempt in the cooldown
- **WHEN** a player files within the repeat-appeal cooldown
- **THEN** nothing is recorded and the response carries `availableFrom`

#### Scenario: Staff route unchanged
- **WHEN** staff file an ineligible appeal through `POST /appeal`
- **THEN** it is recorded as `INELIGIBLE`

### Requirement: Player message with the decision
The review request SHALL accept an optional `playerMessage` of 1 to 2000 characters that passes the PII guard and is recorded as a `PLAYER`-visible staff message on the case after the decision; the internal `decisionNote` MUST stay staff-only.

#### Scenario: Decision with a message
- **WHEN** a moderator denies an appeal with a `playerMessage`
- **THEN** the case has `appeal.decided` and a `PLAYER`-visible message from that moderator

#### Scenario: Message with an IP
- **WHEN** the `playerMessage` contains an IP address
- **THEN** the review is rejected with HTTP 422 and the appeal stays undecided
