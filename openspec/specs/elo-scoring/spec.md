# elo-scoring Specification

## Purpose
Maintain a dual-ELO behavior standing (chat and gameplay) for every player, fed by chat toxicity scoring, reports and applied punishments, so that crossing configured thresholds automatically punishes a player without requiring a moderator to be online.

## Requirements

### Requirement: Independent chat and gameplay tracks
The system SHALL maintain, per player profile keyed by the SHA-512-hashed owner, two independent scores: a CHAT track and a GAMEPLAY track. A delta applied to one track MUST NOT change the score of the other track.

#### Scenario: Chat delta leaves gameplay untouched
- **WHEN** a negative delta is applied to a player's CHAT track
- **THEN** the CHAT score changes by that delta
- **AND** the GAMEPLAY score remains unchanged

### Requirement: Baseline score for new profiles
The system SHALL create an ELO profile lazily on the first delta for an owner, initializing both tracks to the configured baseline (`blackhole.elo.baseline`, default 1000) before applying that delta.

#### Scenario: First delta for an unknown player
- **WHEN** a delta of -50 is applied to the CHAT track of an owner that has no ELO profile
- **THEN** a profile is created with both tracks starting at the baseline
- **AND** the resulting CHAT score is baseline minus 50 while GAMEPLAY stays at baseline

### Requirement: Audited score changes
Every change to a track score, including decay recovery, SHALL be recorded as an immutable history event containing the owner, track, signed delta, reason code, optional evidence reference, resulting score, timestamp and free-form metadata. Reason codes MUST distinguish toxicity flags, reports actioned, reporter rewards, applied punishments, decay recovery and other signal sources.

#### Scenario: Delta produces a history event
- **WHEN** any delta is applied to a track
- **THEN** a history event is persisted with the delta, reason code and resulting score

#### Scenario: Decay produces a history event
- **WHEN** decay recovery raises a track score
- **THEN** a history event with reason code DECAY_RECOVERY and the number of elapsed intervals is persisted

### Requirement: Soft threshold triggers automatic temporary punishment
When a track score crosses below `blackhole.elo.soft-threshold` (default 700) but not below the perma-ban threshold, the system SHALL automatically apply a temporary punishment attributed to the fixed ELO system source: a CHAT mute for the chat track (duration `auto-ban.chat-soft-duration`, default PT24H) or a NETWORK ban for the gameplay track (duration `auto-ban.gameplay-soft-duration`, default P3D).

#### Scenario: Chat score drops below the soft threshold
- **WHEN** a player's CHAT score moves from at or above the soft threshold to below it
- **THEN** a temporary CHAT punishment is applied using the automatic chat timeout template
- **AND** the punishment metadata records the reason code of the triggering signal

#### Scenario: Automatic template is created on demand
- **WHEN** a soft-threshold crossing occurs and no automatic template for that track exists yet
- **THEN** the system creates one with the configured soft duration and an ELO delta of zero, and reuses it for later crossings

### Requirement: Perma-ban threshold triggers permanent ban
When a track score crosses below `blackhole.elo.perma-ban-threshold` (default 300), the system SHALL apply the operator-configured perma-ban template for that track (`perma-ban-template.chat-id` / `perma-ban-template.gameplay-id`), attributed to the ELO system source. If no template is configured or the configured template no longer exists, the system MUST skip the ban, log a warning and never invent a template.

#### Scenario: Configured perma-ban template is applied
- **WHEN** a player's GAMEPLAY score crosses below the perma-ban threshold and a gameplay perma-ban template is configured
- **THEN** that template is applied to the player
- **AND** the soft-threshold punishment is not additionally applied for the same delta

#### Scenario: No perma-ban template configured
- **WHEN** a player's CHAT score crosses below the perma-ban threshold and no chat perma-ban template is configured
- **THEN** no punishment is applied and a warning is logged
- **AND** the threshold-crossed event reports `templateConfigured` as false

### Requirement: Thresholds act only on genuine downward crossings
The system SHALL trigger an automatic punishment only when the score before the delta was at or above a threshold and the score after the delta is below it. Further violations while already below a threshold MUST NOT re-trigger that threshold's punishment.

#### Scenario: Player already below the soft threshold
- **WHEN** a player whose CHAT score is already below the soft threshold (but above the perma-ban threshold) receives another negative delta that keeps the score above the perma-ban threshold
- **THEN** no new automatic punishment is applied

### Requirement: Threshold crossing event
Every soft or hard threshold crossing SHALL publish an `elo.threshold_crossed` domain event containing the owner, track, tier (SOFT or HARD) and resulting score; HARD events MUST additionally include whether a perma-ban was actually applied (`templateConfigured`).

#### Scenario: Soft crossing publishes event
- **WHEN** a GAMEPLAY score crosses below the soft threshold
- **THEN** an `elo.threshold_crossed` event with tier SOFT and the resulting score is published

### Requirement: Decay recovery toward baseline
A track score below baseline SHALL recover by `blackhole.elo.decay.recovery-per-day` (default 10) for every full elapsed `decay.interval-ms` (default one day) since the track was last updated. Recovery MUST be capped at the baseline and MUST never raise a score that is already at or above baseline.

#### Scenario: Recovery capped at baseline
- **WHEN** a CHAT score of 995 with baseline 1000 has 3 full decay intervals elapsed
- **THEN** the score recovers to exactly 1000, not 1025

#### Scenario: Score above baseline does not decay
- **WHEN** a track score is above baseline because of rewards
- **THEN** decay leaves it unchanged

### Requirement: Decay is reconciled lazily and by a scheduled sweep
The system SHALL reconcile pending decay on a track before applying any new delta to it, and SHALL also run a scheduled sweep (`blackhole.elo.decay.sweep-cron`, default daily at 03:00) over all ELO profiles so inactive players recover without any new signal.

#### Scenario: Inactive player recovers via sweep
- **WHEN** the scheduled decay sweep runs and a profile has a below-baseline track with elapsed decay intervals
- **THEN** the recovery is applied and persisted without any new delta for that player

#### Scenario: Pending decay applied before a new delta
- **WHEN** a delta is applied to a track that has elapsed decay intervals pending
- **THEN** decay is applied first and the new delta is applied on top of the recovered score

### Requirement: Chat toxicity scoring
The system SHALL expose `POST /elo/chat` accepting a SHA-512-hashed owner and a message of at most 512 characters, score the message with a replaceable toxicity scorer returning a value between 0 and 1, and return whether it was flagged and the score. A message scoring at or above `blackhole.elo.chat.flag-threshold` (default 0.3) MUST apply a CHAT delta of `flag-delta` (default -50) multiplied by the score, rounded, with reason code TOXICITY_FLAG.

#### Scenario: Toxic message is flagged
- **WHEN** a chat message scores 0.8 against the default flag threshold and flag delta
- **THEN** the response reports flagged true with score 0.8
- **AND** a CHAT delta of -40 is applied to the owner

#### Scenario: Clean message is not flagged
- **WHEN** a chat message scores below the flag threshold
- **THEN** the response reports flagged false and no delta or evidence is recorded

### Requirement: Default rule-based toxicity scorer
The default scorer SHALL count case-insensitive substring matches against the configured pattern list (`blackhole.elo.chat.toxic-patterns`) and return the match count multiplied by `severity-per-match` (default 0.4), capped at 1.0; a blank message MUST score 0.

#### Scenario: Two pattern matches
- **WHEN** a message contains two configured patterns with default severity
- **THEN** the score is 0.8

### Requirement: Chat evidence stores only a hash
When a chat message is flagged, the system SHALL persist a CHAT_MESSAGE evidence record containing only a hash of the message, with an expiry `evidence-retention-days` (default 60) in the future, and reference it from the ELO history event. The raw message text MUST NOT be persisted anywhere.

#### Scenario: Flagged message evidence
- **WHEN** a chat message is flagged
- **THEN** the stored evidence contains the message hash and no raw text
- **AND** the resulting TOXICITY_FLAG history event references that evidence

### Requirement: Report outcome deltas
When a report is resolved as ACTIONED, the system SHALL apply `blackhole.elo.report.actioned-delta` (default -100) to the reported player's track derived from the report category (CHAT_ABUSE to CHAT; CHEATING and GRIEFING to GAMEPLAY; OTHER to none). If the resolution also applied a punishment, the reporter MUST receive `report.reward-delta` (default 50) on the same track.

#### Scenario: Actioned cheating report with punishment
- **WHEN** a CHEATING report is resolved ACTIONED with a punishment template applied
- **THEN** the reported player's GAMEPLAY track receives -100 with reason REPORT_ACTIONED
- **AND** the reporter's GAMEPLAY track receives +50 with reason REPORT_REWARDED

#### Scenario: Actioned report in category OTHER
- **WHEN** a report in category OTHER is resolved ACTIONED
- **THEN** no ELO delta is applied to either party

### Requirement: ELO profile and history read endpoints
The system SHALL expose `GET /elo/{owner}` returning the owner's current chat and gameplay scores, or 404 if no ELO profile exists, and `GET /elo/{owner}/history` returning that owner's ELO history events paginated.

#### Scenario: Profile not found
- **WHEN** `GET /elo/{owner}` is requested for an owner with no ELO profile
- **THEN** the response is 404

#### Scenario: Paginated history
- **WHEN** `GET /elo/{owner}/history` is requested for an owner with recorded events
- **THEN** a page of that owner's history events is returned
