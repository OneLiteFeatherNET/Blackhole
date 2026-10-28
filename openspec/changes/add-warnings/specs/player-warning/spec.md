## ADDED Requirements

### Requirement: Warning is a punishment kind that is never active

A warning SHALL be recorded as a punishment of kind `WARN` with a reason, a staff actor, a creation time, a Ban ID and a case event, and it SHALL have no duration. A warning MUST NOT be placed in a player's active punishment slots, MUST NOT be enforced on login, and MUST NOT be published to the proxy mirror.

#### Scenario: Warning does not block a later login

- **WHEN** a player with a warning logs in
- **THEN** the login is allowed and no active punishment is reported for the player

#### Scenario: Warning is not in active lists

- **WHEN** the active punishments of a player are listed after a warning
- **THEN** the warning does not appear in the list and does not count in the active evidence count

#### Scenario: Warning is in the history

- **WHEN** the player's punishment history is read
- **THEN** the warning appears with its reason, actor, time and kind `WARN`

### Requirement: Warnings are shown in game and acknowledged by the player

A warning for an online player SHALL be shown in game at once, with a line the player can click to acknowledge it. A warning for an offline player SHALL be shown on the player's next login. A warning SHALL stay pending until the player acknowledges it, and a pending warning SHALL be shown again on each login.

#### Scenario: Online player receives a warning

- **WHEN** staff warn a player who is connected to a proxy
- **THEN** that proxy shows the warning in chat with the acknowledge line, and the warning stays pending

#### Scenario: Offline player sees the warning on login

- **WHEN** a player with a pending warning logs in
- **THEN** the proxy shows the warning once for this login

#### Scenario: Acknowledgement clears the pending state

- **WHEN** the player clicks the acknowledge line
- **THEN** the warning is marked acknowledged and is not shown on later logins

#### Scenario: Warning never blocks play

- **WHEN** a warning is pending
- **THEN** the player can join, chat and play as usual

### Requirement: Acknowledgement is recorded once and only by the proxy

`POST /warn/{id}/acknowledge` SHALL be accepted only from the `PROXY` role, with the owner of the warning in the body. The first acknowledgement SHALL set the acknowledged time and record `punishment.acknowledged` on the case; a repeat SHALL change nothing and answer 204. An acknowledgement for an owner that does not own the warning SHALL answer 404 and change nothing.

#### Scenario: Repeated acknowledgement

- **WHEN** the same warning is acknowledged twice
- **THEN** the acknowledged time is the first one, one case event exists, and both answers are 204

#### Scenario: Acknowledgement for another owner

- **WHEN** the proxy acknowledges warning `W` with the owner hash of a different player
- **THEN** the response is 404 and nothing changes

#### Scenario: Staff cannot acknowledge

- **WHEN** a MODERATOR posts an acknowledgement
- **THEN** the request is refused

### Requirement: Pending warnings are read on login

`GET /warn/pending` SHALL list the warnings of one owner that are not acknowledged, oldest first, with their reasons, in the cursor page envelope with a defaulted `limit`. It SHALL be accepted only from the `PROXY` role. A failed read SHALL NOT refuse the login.

#### Scenario: Pending list

- **WHEN** an owner has two warnings and one is acknowledged
- **THEN** the pending read returns the other warning only

#### Scenario: Read fails at login

- **WHEN** the pending read fails while the player logs in
- **THEN** the login proceeds and no warning is shown; the warning stays pending

### Requirement: Warning events carry no personal data

`punishment.warned` SHALL carry the warning identifier, the owner hash and the time. It MUST NOT carry the reason text, a player name, a raw UUID or an IP address. The proxy SHALL read the reason over HTTP with `GET /warn/{id}`.

#### Scenario: Event payload scan

- **WHEN** a warning is published
- **THEN** the payload contains the owner hash and no reason text

### Requirement: Warnings can be appealed

A warning SHALL be appealable through the appeal workflow with the minimum wait of a manual punishment. A warning MUST NOT be granted a duration reduction, because it has no duration.

#### Scenario: Warning appeal after the manual wait

- **WHEN** a player appeals a warning 15 days after it was issued, with default configuration
- **THEN** the minimum-time criterion passes and the appeal is awaiting review

#### Scenario: Duration reduction of a warning

- **WHEN** a reviewer grants a duration reduction on a warning
- **THEN** the response is HTTP 400 and the warning is unchanged

### Requirement: Warning window for escalation

A template MAY set `warnWindow`, an ISO-8601 duration. When it is set, earlier warnings of the template's escalation group SHALL count towards escalation only if they fall within it. When it is not set, they SHALL count within the template's escalation window like any other punishment. Warnings SHALL stay in the history in all cases.

#### Scenario: Warning outside the window

- **WHEN** a template has `warnWindow` P30D and a player's only warning is 31 days old
- **THEN** the warning does not count and the next application selects the first step

#### Scenario: Warning without a window

- **WHEN** a template has no `warnWindow` and a player's warning is 31 days old, inside a 365-day escalation window
- **THEN** the warning counts and the next application selects the second step
