## ADDED Requirements

### Requirement: Selecting a staff actor's punishments

An `ADMIN` SHALL be able to request a rollback for one staff actor and a time window. The selection MUST match the punishments whose attributed human actor is the selected `oid`, where the attributed actor is the `actor_oid` of a linked in-game write or the `recorded_by_oid` of a web write. An unlinked in-game account MUST be selectable only by its Minecraft UUID. The selector MUST NOT be a service principal, and automatic punishments issued by the ELO system MUST NOT be selected.

#### Scenario: Linked in-game and web punishments are both selected

- **WHEN** an admin previews a rollback for a staff actor whose bans were applied in game through a linked account and whose mutes were applied in the panel
- **THEN** both kinds appear in the preview, each once

#### Scenario: Proxy writes are not selected by the proxy

- **WHEN** an admin tries to preview a rollback with the proxy's `oid` as the selector
- **THEN** the response is 400 `ROLLBACK_SELECTOR_INVALID` and no job is created

#### Scenario: Window bounds

- **WHEN** a punishment was created one second before the window start
- **THEN** it is not part of the preview

#### Scenario: Automatic punishments are excluded

- **WHEN** the ELO system applied a temporary mute to a player in the window
- **THEN** that mute does not appear in any rollback preview

### Requirement: Preview is read-only and freezes the set

The preview SHALL report the counts of the selected punishments per kind and per section, the players affected, and the punishments already inactive. A preview MUST NOT change any punishment, profile, ELO score or case. Starting the rollback MUST run over the frozen set only, and a preview MUST expire after its configured time-to-live.

#### Scenario: Preview changes nothing

- **WHEN** a preview is created
- **THEN** every punishment, profile slot and ELO score is unchanged

#### Scenario: Punishment after the preview is not included

- **WHEN** a new punishment by the same actor is applied after the preview and the rollback is started
- **THEN** the new punishment is not revoked by that rollback

#### Scenario: Expired preview

- **WHEN** a preview older than its time-to-live is started
- **THEN** the response is 409 and nothing is revoked

#### Scenario: Too many punishments

- **WHEN** the selection exceeds the configured maximum
- **THEN** the preview is refused with `ROLLBACK_TOO_LARGE` and the admin is told to narrow the window

### Requirement: Justification and idempotent start

Starting a rollback SHALL require a justification of 1 to 500 characters that passes the PII guard, and an `Idempotency-Key` header. A repeated start with the same key MUST return the same job and MUST NOT start a second run. A start on a job that is not in state `PREVIEW` MUST be refused with 409.

#### Scenario: Missing justification

- **WHEN** an admin starts a rollback with an empty reason
- **THEN** the response is 400 and the job stays in `PREVIEW`

#### Scenario: Missing idempotency key

- **WHEN** a rollback is started without an `Idempotency-Key` header
- **THEN** the response is 400

#### Scenario: Double submit

- **WHEN** the same start request is sent twice with the same key
- **THEN** both answers name the same job and each frozen item is revoked once

### Requirement: Each punishment is revoked through the manual revoke path

Every frozen item that is still active SHALL be revoked through the same internal revoke used for a manual revoke, with the admin's verified `oid` as the revoker. The case event MUST read "rolled back by <admin display name>, reason: <reason>" and carry the job identifier. The ELO effect MUST be the manual revoke effect: no refund for a temporary punishment, and a reset to the pardon score for a permanent one. The proxies MUST receive `punishment.revoked` for each revoked item.

#### Scenario: Revoked item in the case

- **WHEN** a frozen ban is still active at its turn
- **THEN** the ban moves to history, its case records the rolled-back event with the reason, and `punishment.revoked` is published with the admin as `revokedBy`

#### Scenario: Temporary punishment is not refunded

- **WHEN** a rolled-back punishment was temporary
- **THEN** the player's score does not change because of the rollback

#### Scenario: Permanent punishment resets the score

- **WHEN** a rolled-back punishment was permanent
- **THEN** the section score is reset to the pardon score, as a manual revoke does

### Requirement: Items that changed are skipped, not failed

An item whose slot no longer holds its punishment at the time of the revoke MUST be marked `SKIPPED` with reason `ALREADY_INACTIVE`. Skipping MUST NOT fail the job.

#### Scenario: Concurrent manual revoke

- **WHEN** a moderator revokes a frozen punishment while the rollback runs
- **THEN** the item is `SKIPPED` with `ALREADY_INACTIVE` and the job continues

### Requirement: Resumable after a restart

Execution SHALL take a lease on the job with an expiry from the injected clock, so that a job whose runner stopped is taken over by another runner after the lease expires. A resumed run MUST continue at the first `PENDING` item and MUST NOT process an item already `REVOKED`. A failed item MUST be marked `FAILED` with an error code and no message text, and the run MUST continue with the next item.

#### Scenario: Runner dies mid-job

- **WHEN** the runner stops after revoking 40 of 100 items and the lease expires
- **THEN** another runner revokes the remaining 60 and the 40 are not revoked again

#### Scenario: Failed item

- **WHEN** the revoke of one item fails with a database error
- **THEN** that item is `FAILED`, the others continue, and an execute call retries only the failed items

### Requirement: Cancellation

An admin MAY cancel a job in `PREVIEW` or `RUNNING`. A running job MUST stop before its next item, and items already revoked MUST stay revoked. A cancelled job MUST NOT be restarted.

#### Scenario: Cancel during the run

- **WHEN** an admin cancels a running job after 30 items
- **THEN** the job is `CANCELLED`, the 30 stay revoked and the rest stay `PENDING`

### Requirement: Optional link suspension

When the admin chooses to suspend the staff link, the rollback SHALL remove the link of the selected staff actor with the removal reason `ROLLBACK` before the first item runs. Stored `actor_oid` values MUST NOT change.

#### Scenario: Link suspended

- **WHEN** a rollback with link suspension runs
- **THEN** the link is removed, `staff-link.removed` is published with reason `ROLLBACK`, and existing `actor_oid` values are unchanged

### Requirement: History marks without enforcement

When the admin chooses history marks, kicks and warnings in the window SHALL be marked as rolled back. A mark MUST NOT change any enforcement, ELO score, profile slot or proxy state, and MUST NOT publish `punishment.revoked`. A marked kick MUST still count for escalation.

#### Scenario: Marked kick

- **WHEN** a kick in the window is marked
- **THEN** its metadata records the job and the rollback, and no ELO event or proxy event is produced

### Requirement: Audit and events

The job SHALL record the admin's `oid`, the selector, the window, the options, the reason, and the counts per state. The job SHALL publish `staff.rollback.started` and `staff.rollback.completed` with the job identifier and counts only. Neither event MUST contain an owner hash, a player name, or the reason text. A failed publish MUST NOT fail the job or roll back a revoke.

#### Scenario: Completion event

- **WHEN** a job completes with 95 revoked and 5 skipped
- **THEN** `staff.rollback.completed` carries those counts and the job identifier and no owner hash

### Requirement: Access

Every `/admin/rollback` route SHALL require the `ADMIN` role. A `MODERATOR` or `VIEWER` caller MUST receive 403 and nothing is written.

#### Scenario: Moderator tries a preview

- **WHEN** a moderator calls `POST /admin/rollback/preview`
- **THEN** the response is 403 and no job is created
