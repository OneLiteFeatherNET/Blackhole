# Spec Delta

## Purpose
A leaked Ban ID ends the appeal path of its punishment and closes the case with a predefined comment.

## ADDED Requirements

### Requirement: Leaked Ban ID forfeits an undecided appeal
When a Ban ID is marked leaked, every undecided appeal of the punishment SHALL move to the terminal status `FORFEITED_BAN_ID_LEAKED`. A forfeited appeal has no merits decision, starts no repeat-appeal cooldown, MUST NOT change the punishment and MUST NOT be counted as a granted or denied appeal. The status is appended to the appeal status enum with the next free ordinal.

#### Scenario: Filed appeal
- **WHEN** a Ban ID is marked leaked while an appeal is `IN_REVIEW`
- **THEN** the appeal is `FORFEITED_BAN_ID_LEAKED`, the punishment is unchanged and no cooldown is recorded

#### Scenario: Decided appeal
- **WHEN** a Ban ID is marked leaked after the appeal was decided
- **THEN** the decided appeal keeps its status

### Requirement: Leak closes the case with a predefined comment
Marking a Ban ID leaked SHALL close the punishment's case with the closing reason `BAN_ID_LEAKED`, and the case SHALL carry one player-visible closing comment whose text is the predefined `BAN_ID_LEAKED` closing reason. A leaked case MUST NOT be reopened by a filed appeal.

#### Scenario: Filing after a leak
- **WHEN** a player files an appeal for a leaked punishment
- **THEN** the answer is HTTP 409 `FORFEITED` and the case stays `CLOSED`

### Requirement: Closing comment for a limited session
A `LIMITED` session of a leaked Ban ID SHALL receive, on the public punishment answer, a `closing` object with the code `BAN_ID_LEAKED`, the stored closing text and the close time, and nothing else from the case. This field is additive; the conversation and appeal reads stay refused (HTTP 403).

#### Scenario: Limited login after a leak
- **WHEN** a leaked Ban ID logs in
- **THEN** the public punishment answer includes `closing` with the stored text and the conversation read answers HTTP 403

### Requirement: Reissue reopens only a leak-closed case
A Ban ID reissue SHALL reopen a case whose closing reason is `BAN_ID_LEAKED` to `OPEN` and clear the closing reason. A forfeited appeal MUST stay forfeited by a reissue.

#### Scenario: Reissue
- **WHEN** an administrator reissues the Ban ID of a leak-closed case
- **THEN** the case is `OPEN`, the closing reason is cleared and the forfeited appeal is unchanged
