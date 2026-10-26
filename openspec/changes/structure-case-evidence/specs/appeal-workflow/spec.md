# Spec Delta

## Purpose
A leaked Ban ID freezes an open appeal, closes the case once no appeal is open or the frozen appeal is decided, and a
reissue resets the leak as if it had not happened.

## ADDED Requirements

### Requirement: Leak freezes an open appeal
When a Ban ID is marked leaked and the punishment has an undecided appeal, that appeal SHALL be `FROZEN`: the team MAY still decide it, the player MUST NOT post, file evidence or respond (HTTP 409 `FROZEN`), and no appeal status is added or changed. A leak with no undecided appeal and no earlier filing SHALL forfeit the right to appeal (filing answers HTTP 409 `FORFEITED`). The punishment itself MUST NOT change in either case.

#### Scenario: Frozen appeal
- **WHEN** a Ban ID is marked leaked while an appeal is `IN_REVIEW`
- **THEN** the appeal is shown as `FROZEN`, the team can decide it, the player's post is refused with `FROZEN`, and the punishment is unchanged

#### Scenario: Forfeited right
- **WHEN** a Ban ID is marked leaked before any appeal was filed
- **THEN** filing answers HTTP 409 `FORFEITED` and no appeal row is created

### Requirement: Case closes when no appeal is open or the frozen appeal is decided
A leaked case SHALL be closed with closing reason `BAN_ID_LEAKED` and one player-visible closing comment when it has no undecided appeal, at once on the leak, or when its frozen appeal is decided. The closing comment's text is the predefined `BAN_ID_LEAKED` closing reason at that moment. A repeat leak, or a repeat decision, MUST append nothing. A leaked case MUST NOT be reopened by a filed appeal.

#### Scenario: Leak with no open appeal
- **WHEN** a Ban ID is marked leaked and the case has no undecided appeal
- **THEN** the case is `CLOSED` with closing reason `BAN_ID_LEAKED` and the closing comment exists once

#### Scenario: Decision on the frozen appeal
- **WHEN** the team decides a frozen appeal
- **THEN** the case is `CLOSED` with closing reason `BAN_ID_LEAKED` and the closing comment exists once, and a second decision appends nothing

#### Scenario: Filing after a close
- **WHEN** a player tries to file for a closed leaked case
- **THEN** the answer is HTTP 409 `FORFEITED` and the case stays `CLOSED`

### Requirement: Closing comment for a limited session
A `LIMITED` session of a leaked Ban ID SHALL receive a `closing` object (code `BAN_ID_LEAKED`, the stored closing text, the close time) on the public punishment answer once the case is closed, and nothing else from the case. While the appeal is frozen the `closing` object is absent. The conversation and appeal reads stay refused (HTTP 403).

#### Scenario: Limited login while frozen
- **WHEN** a leaked Ban ID with a frozen appeal logs in
- **THEN** the public punishment answer has no `closing` object and the conversation read answers HTTP 403

#### Scenario: Limited login after close
- **WHEN** the same Ban ID logs in after the case closed
- **THEN** the answer includes `closing` with the stored text

### Requirement: Reissue resets the leak
A Ban ID reissue SHALL reset the leak as if it had not happened: the leak state is cleared; a frozen appeal is open again; a right forfeited by the leak is available again for filing; a case closed by the leak is reopened to `OPEN` with its closing reason cleared; and the closing comment is withdrawn for the player with `message.deleted`. A decided appeal keeps its decision. The leak and the reissue MUST remain in the timeline.

#### Scenario: Reissue of a frozen appeal
- **WHEN** an administrator reissues the Ban ID of a case whose appeal is frozen
- **THEN** the appeal is open again and the team and the player can continue it

#### Scenario: Reissue of a closed case
- **WHEN** an administrator reissues the Ban ID of a leak-closed case
- **THEN** the case is `OPEN`, the closing comment is withdrawn for the player, and the timeline still shows `ban_id.leak_marked`, the closing comment and `ban_id.reissued`

#### Scenario: Decided appeal
- **WHEN** an administrator reissues a Ban ID whose appeal was decided after the leak
- **THEN** the decision is unchanged and the case is reopened to `OPEN` for follow-up
