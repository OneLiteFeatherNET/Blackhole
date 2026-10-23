# Spec Delta

## MODIFIED Requirements

### Requirement: Independent chat and gameplay tracks
The system SHALL maintain, per player keyed by the SHA-512-hashed owner, one independent score per punishment section (by default `chat` and `network`). A player without a recorded score in a section MUST be treated as having that section's base score. A delta applied to one section MUST NOT change the score of any other section.

#### Scenario: Chat delta leaves gameplay untouched
- **WHEN** a negative delta is applied to a player's `chat` score
- **THEN** the `chat` score changes by that delta
- **AND** the `network` score remains unchanged

#### Scenario: Unscored section reads as base
- **WHEN** a player who was never scored in section `network` is read
- **THEN** their `network` score is reported as the section's base score

### Requirement: Soft threshold triggers automatic temporary punishment
When a section defines a soft threshold and a score crosses from at or above it to below it, without falling below the permanent threshold, the system SHALL apply a temporary punishment in that section for the section's soft duration, attributed to the ELO system source and without an additional ELO cost.

#### Scenario: Chat score drops below the soft threshold
- **WHEN** a player's `chat` score moves from 720 to 680 and the soft threshold is 700
- **THEN** a temporary mute in section `chat` is applied for the soft duration
- **AND** the punishment metadata records the reason code of the triggering signal

#### Scenario: Automatic template is created on demand
- **WHEN** a soft-threshold crossing occurs
- **THEN** the temporary punishment is created directly in the section without a template, since automatic punishments no longer use templates

#### Scenario: Section without soft threshold
- **WHEN** a section has no soft threshold and a score decreases
- **THEN** no soft automatic punishment is applied

### Requirement: Perma-ban threshold triggers permanent ban
A score below a section's permanent threshold SHALL trigger the section's automatic permanent punishment as defined by the `punishment-sections` capability; no operator-configured perma-ban template is required, and the escalation MUST NOT be skipped for lack of configuration.

#### Scenario: Configured perma-ban template is applied
- **WHEN** a player's `network` score drops from 320 to 250 with permanent threshold 300
- **THEN** the player is permanently banned from section `network` using the section's permanent reason
- **AND** the soft-threshold punishment is not additionally applied for the same delta

#### Scenario: No perma-ban template configured
- **WHEN** a player's `chat` score falls below the permanent threshold and no perma-ban template is configured anywhere
- **THEN** the permanent `chat` punishment is still applied, because escalation no longer depends on a template

### Requirement: Thresholds act only on genuine downward crossings
The soft threshold SHALL trigger only when the score before the delta was at or above it and the score after the delta is below it; further deltas while already below the soft threshold MUST NOT re-trigger it. The permanent threshold follows the escalation rule of `punishment-sections` instead.

#### Scenario: Player already below the soft threshold
- **WHEN** a player whose `chat` score is already below the soft threshold (but above the permanent threshold) receives another negative delta that keeps the score above the permanent threshold
- **THEN** no new soft automatic punishment is applied

### Requirement: Report outcome deltas
A report SHALL be resolved at most once: resolving a report that is already ACTIONED or DISMISSED MUST be refused with 409 and apply nothing. When a report is resolved as ACTIONED **without** a punishment template, the system SHALL apply `blackhole.elo.report.actioned-delta` (default -100) to the reported player's score in the section mapped from the report category by `blackhole.elo.report.category-sections` (default CHAT_ABUSE to `chat`; CHEATING and GRIEFING to `network`; OTHER to none). When it is resolved as ACTIONED **with** a template, only the template's step cost is deducted (through the normal apply path) and the actioned delta MUST NOT be applied in addition; the reporter then receives `report.reward-delta` (default 50) in the mapped section. A template MUST only be accepted together with status ACTIONED (otherwise 400), and a template or section that does not exist MUST be a 400 with an error body, distinguishable from a missing report (404).

#### Scenario: Actioned cheating report without punishment
- **WHEN** a CHEATING report is resolved ACTIONED without a template
- **THEN** the reported player's `network` score receives -100 with reason REPORT_ACTIONED
- **AND** the reporter receives no reward

#### Scenario: Actioned cheating report with punishment
- **WHEN** a CHEATING report is resolved ACTIONED with a template whose step costs 100
- **THEN** the reported player's `network` score is reduced by 100 once, by the template's cost
- **AND** no REPORT_ACTIONED delta is applied
- **AND** the reporter's `network` score receives +50 with reason REPORT_REWARDED

#### Scenario: Template on a dismissed report
- **WHEN** a report is resolved DISMISSED with a template
- **THEN** the response is 400 and nothing is applied

#### Scenario: Resolving twice
- **WHEN** an already ACTIONED report is resolved again
- **THEN** the response is 409 and neither the template nor any ELO delta is applied again

#### Scenario: Unknown template
- **WHEN** a report is resolved with a template identifier that does not exist
- **THEN** the response is 400 with an error body and the report stays unresolved

#### Scenario: Actioned report in category OTHER
- **WHEN** a report in category OTHER is resolved ACTIONED without a template
- **THEN** no ELO delta is applied to either party

### Requirement: ELO profile and history read endpoints
The system SHALL expose `GET /elo/{owner}` returning the owner's score per section together with each section's thresholds, and `GET /elo/{owner}/history` returning that owner's ELO events paginated and filterable by section. An owner without any recorded event MUST be reported with base scores, not 404.

#### Scenario: Profile not found
- **WHEN** `GET /elo/{owner}` is requested for an owner with no recorded ELO events
- **THEN** the response is 200 and every enabled section is returned with its base score

#### Scenario: Paginated history
- **WHEN** `GET /elo/{owner}/history?section=chat` is requested
- **THEN** a page of that owner's `chat` events is returned

## ADDED Requirements

### Requirement: Manual score adjustment
The system SHALL expose `POST /elo/{owner}/adjust` with section, signed delta and note, recorded with reason MANUAL_ADJUSTMENT and subject to the same soft and permanent threshold rules as any other delta.

#### Scenario: Adjustment escalates
- **WHEN** an admin adjusts a player's `network` score by -400 from 600
- **THEN** the score becomes 200, the event is recorded with the note and the verified caller, and the player is permanently banned from `network`

#### Scenario: Unknown section
- **WHEN** an adjustment names a section that does not exist
- **THEN** the response is an error and no score changes

### Requirement: Chat toxicity maps to a section
Chat toxicity flags SHALL be applied to the section configured by `blackhole.elo.chat.section` (default `chat`).

#### Scenario: Toxic message
- **WHEN** a chat message is flagged as toxic
- **THEN** the computed delta is applied to the player's `chat` score
