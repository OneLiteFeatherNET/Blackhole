## ADDED Requirements

### Requirement: Amendments change no score except the pardon reset

An amendment SHALL NOT change any ELO score and SHALL NOT write an ELO event, except when a permanent punishment is made temporary: then the section score SHALL be reset to the section's pardon score with reason `PARDON_RESET`, with the same metadata as an appeal duration reduction. A shortening of a temporary punishment SHALL NOT refund its cost.

#### Scenario: Shortening a temporary ban

- **WHEN** a temporary ban that cost 10 is shortened
- **THEN** the score is unchanged and no ELO event is recorded

#### Scenario: Permanent ban made temporary

- **WHEN** an ADMIN makes a permanent ban temporary
- **THEN** the score is set to the section's pardon score once, with reason `PARDON_RESET`

#### Scenario: Extension

- **WHEN** a temporary ban is extended
- **THEN** the score is unchanged and no ELO event is recorded
