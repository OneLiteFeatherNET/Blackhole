## MODIFIED Requirements

### Requirement: Chat toxicity scoring

The system SHALL expose `POST /elo/chat` accepting a SHA-512-hashed owner and a message of at most 512 characters, score the message with the replaceable toxicity scorer returning a value between 0 and 1, and return whether it was flagged and the score. A message SHALL be flagged when its score reaches `blackhole.elo.chat.toxicity.flag-threshold` (default 0.5). A flagged message in mode `ENFORCE` MUST apply a CHAT delta of `flag-delta` (default -50) multiplied by the score, rounded, with reason code TOXICITY_FLAG.

#### Scenario: Toxic message is flagged

- **WHEN** a chat message scores 0.6 in category `INSULT` against the default flag threshold, in mode `ENFORCE` with the default flag delta
- **THEN** the response reports flagged true with score 0.6
- **AND** a CHAT delta of -30 is applied to the owner

#### Scenario: Clean message is not flagged

- **WHEN** a chat message scores below the flag threshold
- **THEN** the response reports flagged false and no delta or evidence is recorded

### Requirement: Chat evidence stores only a hash

When the automatic toxicity flow flags a chat message in `ENFORCE` mode, the system SHALL persist a CHAT_MESSAGE evidence record containing only a hash of the message, with an expiry `evidence-retention-days` (default 60) in the future, and reference it from the ELO history event. The automatic flow MUST NOT persist the raw message text in the evidence record or the ELO history.

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

**Reason**: The substring-matching placeholder misses German abuse and obfuscated spelling, and flags harmless game talk. It is replaced by a term-list scorer that normalises the text first.

**Migration**: The class `RuleBasedToxicityScorer` and the configuration keys `blackhole.elo.chat.toxic-patterns` and `severity-per-match` are removed in the same release that adds the term-list scorer. Existing `TOXICITY_FLAG` history rows are not changed.

## ADDED Requirements

### Requirement: Term-list toxicity scorer

The default `ToxicityScorer` SHALL score a message in-process from configured word lists, one list per category (`INSULT`, `THREAT`, `SEXUAL`, `HATE`, `SPAM`). It SHALL normalise the message before matching, match terms on word boundaries, and remove allow-listed phrases before matching. The score SHALL be the highest weight among the matched categories, or 0 when none matched. A blank message SHALL score 0. The scorer MUST NOT make any network call.

#### Scenario: Obfuscated insult is matched

- **WHEN** a message contains `1d10t` and the list of category `INSULT` contains `idiot`
- **THEN** the category `INSULT` matches

#### Scenario: Spaced insult is matched

- **WHEN** a message contains `i.d.i.o.t`
- **THEN** the category `INSULT` matches

#### Scenario: Word boundaries

- **WHEN** a message contains `class` and the list of category `INSULT` contains `ass`
- **THEN** the category `INSULT` does not match

#### Scenario: Allow-listed phrase is not matched

- **WHEN** a message contains the allow-listed phrase `kill the dragon` and the list of category `THREAT` contains `kill`
- **THEN** the category `THREAT` does not match for that phrase

#### Scenario: Highest weight wins

- **WHEN** a message matches category `INSULT` with weight 0.6 and category `THREAT` with weight 1.0
- **THEN** the score is 1.0

### Requirement: Category weights and flag rule

Each category SHALL have a weight between 0 and 1 in configuration (`blackhole.elo.chat.toxicity.weights.<category>`). A matched category SHALL contribute its weight as the score of the message. A category whose weight is below the flag threshold MUST be reported without being flagged.

#### Scenario: Category below threshold is not flagged

- **WHEN** a message matches only category `SPAM` with weight 0.4 against a flag threshold of 0.5
- **THEN** the message is not flagged and the score is 0.4

#### Scenario: Per-category ELO strength

- **WHEN** a message matches only category `THREAT` with weight 1.0 in mode `ENFORCE` with the default flag delta
- **THEN** a CHAT delta of -50 is applied to the owner

### Requirement: Chat toxicity modes

The system SHALL read one chat toxicity mode from `blackhole.elo.chat.toxicity.mode`, one of `OFF`, `SHADOW` or `ENFORCE`, defaulting to `OFF`. In `OFF` the scorer MUST NOT be called. In `SHADOW` a flagged message MUST NOT change any ELO or create any evidence record. In `ENFORCE` the flag applies as in "Chat toxicity scoring".

#### Scenario: Default mode is off

- **WHEN** `blackhole.elo.chat.toxicity.mode` is not set
- **THEN** the mode is `OFF`

#### Scenario: Mode change takes effect at restart

- **WHEN** the mode is changed in configuration and the backend restarts
- **THEN** the new mode applies to later messages, with no code change

#### Scenario: Off makes no call

- **WHEN** the mode is `OFF` and a chat message is submitted
- **THEN** the scorer is not called and no flag is recorded

#### Scenario: Shadow records without effect

- **WHEN** a message is flagged in mode `SHADOW`
- **THEN** one metric is incremented and one log line is written with the category and the would-be delta
- **AND** the owner's ELO is unchanged

#### Scenario: Shadow log carries no text

- **WHEN** a message is flagged in mode `SHADOW`
- **THEN** the log line does not contain the message text or the owner hash

### Requirement: Fail-open toxicity scoring

A scorer failure SHALL record no delta, no flag and no evidence for that message. The failure MUST NOT block, delay or cancel the response to the chat signal request. The log line for a failure SHALL carry the outcome and the mode, and MUST NOT contain the message text.

#### Scenario: Scorer throws

- **WHEN** the scorer throws an exception for a message in mode `ENFORCE`
- **THEN** no delta and no evidence are recorded for that message
- **AND** the request still answers with flagged false
