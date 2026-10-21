# Spec Delta

## MODIFIED Requirements

### Requirement: Chat evidence stores only a hash
When the automatic toxicity flow flags a chat message, the system SHALL persist a CHAT_MESSAGE evidence record containing only a hash of the message, with an expiry `evidence-retention-days` (default 60) in the future, and reference it from the ELO history event. The automatic flow MUST NOT persist the raw message text. This requirement does not apply to evidence that staff or integrated systems explicitly attach to a punishment under the `punishment-evidence` capability, which has its own retention and access rules.

#### Scenario: Flagged message evidence
- **WHEN** a chat message is flagged
- **THEN** the stored evidence contains the message hash and no raw text
- **AND** the resulting TOXICITY_FLAG history event references that evidence

#### Scenario: Attached chat log is separate
- **WHEN** a proxy attaches a chat excerpt to a punishment as CHAT_LOG evidence
- **THEN** it is stored as a punishment evidence item and the toxicity flow's hash-only records are unchanged
