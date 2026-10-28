## MODIFIED Requirements

### Requirement: Chat toxicity scoring

The system SHALL expose `POST /elo/chat` accepting a SHA-512-hashed owner and a message of at most 512 characters, score the message with the replaceable toxicity scorer, and return whether it was flagged, the overall score and the four category scores (`INSULT`, `THREAT`, `SEXUAL`, `HATE`). The overall score and the category scores SHALL each be a value between 0 and 1. A message SHALL be flagged when any category score reaches that category's threshold for the message's section, as defined in "Per-category thresholds per section". A flagged message in a section in `ENFORCE` mode MUST apply a CHAT delta of `flag-delta` (default -50) multiplied by the highest category score, rounded, with reason code TOXICITY_FLAG. The response SHALL include the section's mode.

#### Scenario: Toxic message is flagged

- **WHEN** a chat message in a section in `ENFORCE` mode scores 0.8 in category `INSULT` against an `INSULT` threshold of 0.5 and the default flag delta
- **THEN** the response reports flagged true, the score 0.8 and the mode `ENFORCE`
- **AND** a CHAT delta of -40 is applied to the owner

#### Scenario: Clean message is not flagged

- **WHEN** every category score of a message is below that category's threshold
- **THEN** the response reports flagged false and no delta or evidence is recorded

#### Scenario: Threat threshold is lower than insult

- **WHEN** a message scores 0.35 in category `THREAT` with a `THREAT` threshold of 0.3 and 0.35 in category `INSULT` with an `INSULT` threshold of 0.5
- **THEN** the message is flagged because of `THREAT`
- **AND** the flag delta is multiplied by 0.35

### Requirement: Chat evidence stores only a hash

When the automatic toxicity flow flags a chat message in `ENFORCE` mode, the system SHALL persist a CHAT_MESSAGE evidence record containing only a hash of the message, with an expiry `evidence-retention-days` (default 60) in the future, and reference it from the ELO history event. The automatic flow MUST NOT persist the raw message text in the evidence record or the ELO history. The only exception is the labelled sample of "Labelled samples for tuning", which is stored in its own table with its own retention and access rules.

#### Scenario: Flagged message evidence

- **WHEN** a chat message is flagged in `ENFORCE` mode
- **THEN** the stored evidence contains the message hash and no raw text
- **AND** the resulting TOXICITY_FLAG history event references that evidence

#### Scenario: Attached chat log is separate

- **WHEN** a proxy attaches a chat excerpt to a punishment as CHAT_LOG evidence
- **THEN** it is stored as a punishment evidence item under the `punishment-evidence` capability, with its own retention and access rules
- **AND** the toxicity flow's hash-only records are unchanged

#### Scenario: Shadow flag stores no evidence record

- **WHEN** a chat message is flagged in `SHADOW` mode
- **THEN** no CHAT_MESSAGE evidence record is created and no ELO history event is written

## REMOVED Requirements

### Requirement: Default rule-based toxicity scorer

**Reason**: The substring-matching placeholder is replaced by a multilingual model scorer. It misses German abuse and obfuscated spelling, and flags harmless game talk.

**Migration**: The rule-based scorer class, its configuration keys `blackhole.elo.chat.toxic-patterns` and `severity-per-match`, and its tests are removed in the same release that adds the scorer. The default behaviour is governed by the new requirements below. Existing `TOXICITY_FLAG` history rows are not changed.

## ADDED Requirements

### Requirement: Multilingual scorer behind the toxicity seam

The default `ToxicityScorer` SHALL call a self-hosted inference service over HTTP at the fixed URL in `blackhole.elo.chat.scorer.url`, using a classifier that covers German and English. The backend MUST NOT accept a caller-supplied scorer URL. The score MUST be recorded with the model version the service reports.

#### Scenario: German message is scored

- **WHEN** a German chat message containing an insult is submitted in a section in `ENFORCE` mode
- **THEN** the scorer is called once with the normalised model view of the message
- **AND** the stored score records the model version reported by the service

#### Scenario: Fixed scorer URL

- **WHEN** a request to `POST /elo/chat` carries any field that names a scorer URL
- **THEN** the field is ignored and the configured URL is used

### Requirement: Minecraft normalisation before tagging

The system SHALL normalise each message in two views before scoring: a model view for the classifier and a tagger view for term matching. A section's allow-list of game phrases SHALL suppress tagger matches only. The normalised text MUST NOT be persisted.

#### Scenario: Obfuscated insult is tagged

- **WHEN** a message contains `1d10t` and the section allow-list does not contain it
- **THEN** the tagger assigns the message to the `INSULT` category

#### Scenario: Spaced insult is tagged

- **WHEN** a message contains `i.d.i.o.t`
- **THEN** the tagger assigns the message to the `INSULT` category

#### Scenario: Allow-listed game phrase

- **WHEN** a message contains an allow-listed phrase such as `kill the dragon`
- **THEN** no tagger match is produced for that phrase
- **AND** the model score is unchanged

#### Scenario: Colour codes do not hide a word

- **WHEN** a message contains `§aidiot`
- **THEN** the model view is `idiot` and the tagger finds the term

#### Scenario: Model view does not decode leet

- **WHEN** a message contains `1d10t`
- **THEN** the model view keeps `1d10t` unchanged
- **AND** the tagger view decodes it to `idiot`

### Requirement: Per-category thresholds per section

The system SHALL store a threshold in `[0, 1]` for each category (`INSULT`, `THREAT`, `SEXUAL`, `HATE`) and each section. A category without a stored value SHALL use the default from `blackhole.elo.chat.thresholds.*`. The system MUST refuse a threshold outside `[0, 1]` and MUST record who changed it and when.

#### Scenario: Default threshold

- **WHEN** a section has no stored threshold for `HATE`
- **THEN** the default `HATE` threshold from configuration is used

#### Scenario: Refused threshold

- **WHEN** a threshold of 1.4 is set for `THREAT`
- **THEN** the change is refused with an expected error and nothing is stored

### Requirement: Shadow and enforce modes per section

Each section SHALL have one mode: `OFF`, `SHADOW` or `ENFORCE`. In `OFF` the scorer MUST NOT be called. In `SHADOW` the scorer is called and a flagged message is recorded with its scores and the delta it would have applied, but no ELO delta, evidence record or player-facing effect is produced. In `ENFORCE` the flag applies as in "Chat toxicity scoring". Only an ADMIN may change a section's mode. A mode change SHALL take effect within the configured cache TTL, without a redeploy.

#### Scenario: Off makes no call

- **WHEN** a section is in `OFF` and a chat message is submitted
- **THEN** the scorer is not called and no flag is recorded

#### Scenario: Shadow records without effect

- **WHEN** a message is flagged in a section in `SHADOW`
- **THEN** one flag row is recorded with mode `SHADOW` and the would-be delta
- **AND** the owner's ELO is unchanged

#### Scenario: Rollback to off

- **WHEN** an ADMIN changes an `ENFORCE` section to `OFF`
- **THEN** later messages in that section are not scored and no ELO is applied

#### Scenario: Moderator cannot change the mode

- **WHEN** a MODERATOR attempts to change a section's mode
- **THEN** the change is refused as forbidden and the mode is unchanged

### Requirement: Labelled samples for tuning

A moderator SHALL be able to label a flagged or shadow-flagged message as `FALSE_POSITIVE` or `CONFIRMED`. The label records the actor's Entra ID `oid` from the token and the time. For each flagged or shadow-flagged message the system SHALL store the scores, the model version, the label and the message text, and SHALL delete the text and the row when the retention `sample-retention-days` (default 30) has passed. Clean messages MUST NOT be stored. A label MUST NOT change any ELO.

#### Scenario: Labelling a flag

- **WHEN** a MODERATOR labels a shadow-flagged message `FALSE_POSITIVE`
- **THEN** the label, the actor's `oid` and the time are stored
- **AND** the owner's ELO is unchanged

#### Scenario: Retention deletes the text

- **WHEN** a flagged message is older than `sample-retention-days`
- **THEN** its message text and its row are deleted by the sweeper

#### Scenario: Clean message is not stored

- **WHEN** a message scores below every threshold
- **THEN** no row and no text are stored for it

#### Scenario: Export carries no identity

- **WHEN** an ADMIN exports the labelled samples as CSV
- **THEN** the file is produced and contains no name and no UUID
- **AND** a MODERATOR requesting the export is refused

### Requirement: Fail-open scoring

A scorer failure (timeout, error or open breaker) SHALL record no delta, no flag and no evidence for that message. The scorer MUST NOT block, delay or cancel chat delivery. Each scorer call is bounded by `blackhole.elo.chat.scorer.timeout`, and a circuit breaker opens after repeated failures.

#### Scenario: Scorer timeout

- **WHEN** the scorer does not answer within the timeout
- **THEN** the outcome is `timeout`, no delta is applied and the chat signal request still answers

#### Scenario: Open breaker makes no call

- **WHEN** the breaker is open
- **THEN** no scorer call is made and the outcome is `breaker_open`

#### Scenario: Log line carries no text

- **WHEN** a scorer call fails for a message
- **THEN** the log line contains the outcome, section and model version, and does not contain the message text

### Requirement: Batching of scorer calls

The backend SHALL collect concurrent scoring requests and send them to the scorer as one batch of at most `blackhole.elo.chat.scorer.batch.max-size` (default 16) messages, or after `blackhole.elo.chat.scorer.batch.linger` (default 5 ms), whichever comes first. A lone request SHALL be sent after the linger.

#### Scenario: Concurrent requests are batched

- **WHEN** 16 scoring requests arrive within the linger window
- **THEN** one scorer call carries all 16 messages

#### Scenario: Lone request after linger

- **WHEN** one scoring request arrives and no other request arrives within the linger
- **THEN** it is sent after the linger elapses

### Requirement: Chat toxicity metrics

The system SHALL expose Micrometer metrics for chat toxicity: request outcomes per section and mode, scorer latency, flags per section and category, staff labels per section and label, and the circuit-breaker state. No metric label SHALL carry an owner hash, a player name or message text.

#### Scenario: Outcome counter

- **WHEN** a scorer call times out for a section in `ENFORCE`
- **THEN** the counter `blackhole.chat.toxicity.requests` with outcome `timeout` and that section is incremented

#### Scenario: No identifying labels

- **WHEN** any chat toxicity metric is recorded
- **THEN** none of its tags contains an owner hash, a player name or message text
