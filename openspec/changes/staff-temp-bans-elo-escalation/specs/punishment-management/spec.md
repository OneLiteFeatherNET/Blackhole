# Spec Delta

## MODIFIED Requirements

### Requirement: Punishment templates
The system SHALL let callers create, update, list, fetch and delete punishment templates under `/template`. A template MUST carry a unique kebab-case key, a reason, a section key of an existing section, an escalation group, an optional escalation window, one or more ordered steps each with a positive ISO-8601 duration and a non-negative `eloCost`, and a metadata map. Creation MUST reject a supplied identifier; update MUST require the identifier of an existing template.

#### Scenario: Create a template
- **WHEN** `POST /template/` is called with key `hacking`, reason, section `network`, steps P1D/100, P7D/150, P30D/250 and no identifier
- **THEN** the template is stored with its three steps in order and returned with a generated identifier
- **AND** its escalation group defaults to `hacking`

#### Scenario: Create with identifier rejected
- **WHEN** `POST /template/` is called with an identifier set
- **THEN** the request is rejected and no template is created

#### Scenario: Permanent template rejected
- **WHEN** a template is submitted without steps, or with a step that has no duration, a zero or negative duration, or a negative eloCost
- **THEN** the request is rejected with a validation error

#### Scenario: Unknown section rejected
- **WHEN** a template references a section key that does not exist
- **THEN** the request is rejected and no template is stored

#### Scenario: Update a missing template
- **WHEN** `POST /template/update` references an identifier that does not exist
- **THEN** the response is 404

#### Scenario: Update without identifier
- **WHEN** `POST /template/update` is called without an identifier
- **THEN** the response is 400

#### Scenario: Duplicate key rejected
- **WHEN** a template is created with a key another template already uses
- **THEN** the response is 409

### Requirement: Template duration determines expiry
A punishment applied from a template SHALL receive an expiration date equal to the application time plus the duration of the selected escalation step. Only punishments created by the system's permanent escalation MAY be permanent.

#### Scenario: Temporary template
- **WHEN** a template whose selected step has duration P3D is applied
- **THEN** the created punishment expires three days after application

#### Scenario: Permanent template
- **WHEN** the system escalates a player in a section
- **THEN** the created punishment has no expiration date, no template and is marked permanent

### Requirement: Applying a template creates a punishment
`POST /punishment/apply` with owner, template (key or identifier) and source SHALL create a temporary punishment from the template in its section, record the source and timestamps, create the profile if absent, and return the punishment, the section, the selected step and step count, the section score before and after the ELO cost, and any escalation it caused. Unknown templates, disabled sections or invalid owner hashes MUST produce an error result without any change.

#### Scenario: Apply to a first-time offender
- **WHEN** template `spam` (section `chat`, first step eloCost 50) is applied to an owner without a profile
- **THEN** a profile is created holding the new punishment as the active `chat` punishment
- **AND** the response reports step 1 of the template's steps and `chat` score 1000 before and 950 after
- **AND** `profile.created` and `punishment.created` events are published

#### Scenario: Unknown template
- **WHEN** a punishment is applied with a template key that does not exist
- **THEN** the response is 404 and no punishment, deduction or profile is created

#### Scenario: Application causes escalation
- **WHEN** the ELO cost drops the section score below its permanent threshold
- **THEN** the response includes the permanent punishment created by the escalation

### Requirement: Active punishment slots with history rotation
Each profile SHALL hold at most one active punishment per section. Applying a punishment MUST move only the currently active punishment of the same section into history and then occupy that section's slot; active punishments of other sections MUST remain untouched. History entries MUST be retained.

#### Scenario: New ban replaces an active ban
- **WHEN** a `network` template is applied to a profile with an active `network` ban
- **THEN** the previous ban is moved to history and the new punishment becomes the active `network` punishment

#### Scenario: Chat punishment slot
- **WHEN** a `chat` template is applied to a profile with an active `network` ban
- **THEN** the new punishment occupies the `chat` slot and the `network` ban stays active

### Requirement: Template ELO delta
When a template is applied by any source other than the ELO system itself, the system SHALL deduct the selected step's `eloCost` from the owner's score in the template's section with reason PUNISHMENT_APPLIED, referencing the punishment and template, and record the deducted cost on the punishment. A cost of 0 MUST NOT create an ELO event.

#### Scenario: Manual ban with ELO delta
- **WHEN** a staff source applies a `network` template whose selected step has eloCost 200
- **THEN** the owner's `network` score decreases by 200 with reason PUNISHMENT_APPLIED
- **AND** the punishment records an ELO cost of 200

#### Scenario: ELO-triggered punishment
- **WHEN** the ELO system itself creates a punishment
- **THEN** no ELO cost is deducted for that punishment

### Requirement: Manual revocation
The system SHALL let callers revoke an owner's active punishment in a section via `POST /punishment/revoke` with owner, section and source, recording the revoking source in the punishment metadata, moving it to history and publishing `punishment.revoked` with the section. Revoking a permanent punishment MUST set the owner's score in that section to the section's pardon score. A staff revoke of a temporary punishment MUST NOT refund the ELO cost it was charged; only a granted appeal that fully lifts it does (see `appeal-workflow`). If there is no active punishment in that section the response MUST be 404.

#### Scenario: Revoke an active mute
- **WHEN** the revoke endpoint is called for section `chat` of an owner with an active chat punishment
- **THEN** the punishment moves to history with `revokedBy` recorded and the `chat` slot is cleared
- **AND** `punishment.revoked` is published with owner, punishment identifier, section and revokedBy

#### Scenario: Revoking a temporary punishment does not refund its cost
- **WHEN** a staff temporary `chat` mute that cost 80 ELO is revoked via the revoke endpoint
- **THEN** the owner's `chat` score is unchanged by the revoke

#### Scenario: Revoke a permanent ban
- **WHEN** a permanent `network` ban is revoked for an owner with `network` score 150 and pardon score 700
- **THEN** the owner's `network` score becomes 700 with reason PARDON_RESET

#### Scenario: Nothing to revoke
- **WHEN** the revoke endpoint is called for a section without an active punishment
- **THEN** the response is 404

### Requirement: Redis mirroring for proxies
The system SHALL mirror active punishment state per section into Redis key `blackhole:punish:{section}:{owner}` with a TTL equal to the remaining duration (none if permanent) on `punishment.created`, and delete it on `punishment.expired`, `punishment.revoked` or a lifting appeal grant. Each change MUST be published on `blackhole.punishment.sync` including section and enforcement kind; Redis failures MUST NOT fail the originating write.

#### Scenario: Temporary ban mirrored with TTL
- **WHEN** a `punishment.created` event for a temporary `network` ban is processed
- **THEN** key `blackhole:punish:network:<owner>` is set with a TTL equal to the remaining duration and a SET message with enforcement BAN is published

#### Scenario: Revocation clears Redis
- **WHEN** a `punishment.revoked` event for a `chat` punishment is processed
- **THEN** key `blackhole:punish:chat:<owner>` is deleted and a CLEARED message is published on the sync channel

#### Scenario: Expiry of a mute clears the mute key
- **WHEN** a `chat` punishment expires, whether by the sweep or on profile read
- **THEN** key `blackhole:punish:chat:<owner>` is deleted and a CLEARED message for section `chat` is published

## ADDED Requirements

### Requirement: Escalation step selection
When a template is applied, the system SHALL select step `min(n, stepCount - 1)` (zero-based), where `n` is the number of the owner's earlier punishments in the template's escalation group created within the escalation window (template value or `blackhole.punishment.escalation.default-window`, default P365D) that were not revoked and not lifted by a granted appeal. Expired punishments MUST count.

#### Scenario: Second offence uses second step
- **WHEN** template `hacking` (steps P1D, P7D, P30D) is applied to a player who has one expired `hacking` punishment from last month
- **THEN** the new punishment lasts 7 days and is recorded as step 2 of 3

#### Scenario: Beyond the last step
- **WHEN** the player already has three counted punishments in the group
- **THEN** the last step (P30D) is applied again

#### Scenario: Old offences outside the window
- **WHEN** the player's only earlier `hacking` punishment is older than the escalation window
- **THEN** the first step is applied

#### Scenario: Lifted punishment does not count
- **WHEN** the player's earlier punishment in the group was revoked or lifted on appeal
- **THEN** it is not counted for step selection

### Requirement: Shared escalation groups
Templates SHALL be able to share an escalation group, so that punishments from any template of the group count towards each other's steps; each template uses its own steps.

#### Scenario: Variants of one offence escalate together
- **WHEN** templates `killaura` and `fly` both use escalation group `hacking` and a player with one counted `killaura` punishment is punished with `fly`
- **THEN** `fly`'s second step is applied

### Requirement: Escalation details are recorded
Every template-based punishment SHALL record its escalation group, selected step, step count and deducted ELO cost, so its history entry stays explainable after the template is edited or deleted.

#### Scenario: Template edited later
- **WHEN** a template's steps are changed after a player was punished with it
- **THEN** the player's history still shows the original step, duration and ELO cost
