# punishment-management Specification

## Purpose
Define reusable punishment templates, apply them to player profiles keyed by hashed UUIDs, track the active ban and chat slots with full history, expire and revoke punishments, and propagate every change to proxies through domain events and Redis.

## Requirements

### Requirement: Punishment templates
The system SHALL let callers create, update, list, fetch and delete punishment templates under `/template`. A template MUST carry a reason, a type (SERVER, NETWORK or CHAT), an integer `eloDelta` and a metadata map; creation MUST reject a request that supplies an identifier, and update MUST require the identifier of an existing template.

#### Scenario: Create a template
- **WHEN** `POST /template/` is called with reason, type, eloDelta and metadata and no identifier
- **THEN** the template is stored and returned with a generated identifier

#### Scenario: Create with identifier rejected
- **WHEN** `POST /template/` is called with an identifier set
- **THEN** the request is rejected and no template is created

#### Scenario: Update a missing template
- **WHEN** `POST /template/update` references an identifier that does not exist
- **THEN** the response is 404

#### Scenario: Update without identifier
- **WHEN** `POST /template/update` is called without an identifier
- **THEN** the response is 400

### Requirement: Template duration determines expiry
When a template's metadata contains an ISO-8601 duration, a punishment applied from it SHALL receive an expiration date equal to the application time plus that duration. When absent, the resulting punishment MUST be permanent and carry no expiration date.

#### Scenario: Temporary template
- **WHEN** a template with duration P3D is applied
- **THEN** the created punishment expires three days after application

#### Scenario: Permanent template
- **WHEN** a template without a duration is applied
- **THEN** the created punishment has no expiration date and never expires on its own

### Requirement: Template deletion blocked while referenced
The system SHALL refuse to delete a template that is referenced by any punishment, responding with 409 Conflict and leaving the template intact. Deleting an unknown template MUST respond 404.

#### Scenario: Delete a referenced template
- **WHEN** `DELETE /template/delete/{identifier}` targets a template used by at least one punishment
- **THEN** the response is 409 and the template still exists

#### Scenario: Delete an unused template
- **WHEN** `DELETE /template/delete/{identifier}` targets a template with no punishments
- **THEN** the template is removed and returned

### Requirement: Applying a template creates a punishment
`POST /punishment/active/{owner}/{templateId}/{source}` SHALL create a new punishment from the template for the hashed owner, recording the source identity and creation/update timestamps, creating the punishment profile if none exists, and returning the updated profile. An unknown template MUST respond 404 without any change.

#### Scenario: Apply to a first-time offender
- **WHEN** a template is applied to an owner that has no punishment profile
- **THEN** a profile is created holding the new punishment in its matching active slot
- **AND** a `profile.created` event and a `punishment.created` event are published

#### Scenario: Unknown template
- **WHEN** a punishment is applied with a template identifier that does not exist
- **THEN** the response is 404 and no punishment or profile is created

### Requirement: Active punishment slots with history rotation
Each profile SHALL hold at most one active ban (SERVER or NETWORK) and at most one active chat punishment (CHAT). Applying any template MUST first move every currently active punishment of that profile, in both slots, into its history, then place the new punishment in the slot matching its type; history entries MUST be retained.

#### Scenario: New ban replaces an active ban
- **WHEN** a NETWORK template is applied to a profile with an active SERVER ban
- **THEN** the previous ban is moved to history and the new punishment becomes the active ban

#### Scenario: Chat punishment slot
- **WHEN** a CHAT template is applied
- **THEN** the new punishment occupies the active chat slot

### Requirement: Template ELO delta
When a template with a non-zero `eloDelta` is applied by any source other than the ELO system itself, the system SHALL apply that delta to the owner's matching ELO track (CHAT for CHAT templates, GAMEPLAY for SERVER and NETWORK templates) with reason code PUNISHMENT_APPLIED, referencing the punishment and template.

#### Scenario: Manual ban with ELO delta
- **WHEN** a staff source applies a NETWORK template with eloDelta -200
- **THEN** the owner's GAMEPLAY score decreases by 200 with reason PUNISHMENT_APPLIED

#### Scenario: ELO-triggered punishment
- **WHEN** the ELO system itself applies a template with a non-zero eloDelta
- **THEN** no additional ELO delta is applied for that punishment

### Requirement: Scheduled expiry sweep
The system SHALL periodically (`blackhole.punishment.expiry.sweep-interval`, default 1 minute) scan profiles with an active punishment and move every punishment whose expiration date has passed into history, clearing its slot and publishing `punishment.expired` with owner, punishment identifier and type.

#### Scenario: Temporary ban expires
- **WHEN** the sweep runs after an active ban's expiration date has passed
- **THEN** the ban moves to history, the ban slot is cleared and `punishment.expired` is published

#### Scenario: Permanent punishment is never swept
- **WHEN** the sweep runs over a profile whose active punishment has no expiration date
- **THEN** the punishment stays active

### Requirement: Expired punishments are cleared on profile read
When a profile is read via `GET /profile/{owner}`, any active punishment whose expiration date has passed SHALL be moved to history and `punishment.expired` published before the profile is returned, so a read never reports an expired punishment as active.

#### Scenario: Read after expiry before the sweep
- **WHEN** a profile is read whose active chat punishment expired since the last sweep
- **THEN** the returned profile has an empty chat slot and the punishment appears in history

### Requirement: Manual revocation
The system SHALL let callers revoke an owner's active ban via `POST /punishment/active/{owner}/ban/revoke/{source}` and active chat punishment via `POST /punishment/active/{owner}/mute/revoke/{source}`, recording the revoking source in the punishment metadata, moving it to history and publishing `punishment.revoked`. If the profile or the targeted active slot is empty, the response MUST be 404.

#### Scenario: Revoke an active mute
- **WHEN** the mute revoke endpoint is called for an owner with an active chat punishment
- **THEN** the punishment moves to history with `revokedBy` recorded and the chat slot is cleared
- **AND** `punishment.revoked` is published with owner, punishment identifier, type and revokedBy

#### Scenario: Nothing to revoke
- **WHEN** the ban revoke endpoint is called for an owner without an active ban
- **THEN** the response is 404

### Requirement: Punishment listing
The system SHALL expose `GET /punishment` returning all punishments, active and historical, paginated.

#### Scenario: List punishments
- **WHEN** `GET /punishment` is requested
- **THEN** a page of punishment entries is returned

### Requirement: Profile management and read
The system SHALL expose profile endpoints under `/profile` to create, update, delete, list and fetch punishment profiles by hashed owner, returning 404 when fetching an unknown owner. Every owner path parameter MUST be validated as a 128-character hexadecimal SHA-512 hash, and update MUST reject a body whose owner differs from the path owner.

#### Scenario: Fetch unknown profile
- **WHEN** `GET /profile/{owner}` is requested for an owner without a profile
- **THEN** the response is 404

#### Scenario: Owner mismatch on update
- **WHEN** `POST /profile/update/{owner}` is called with a body owner different from the path owner
- **THEN** the response is 400 and nothing is changed

### Requirement: Owners are hashed identifiers
The system MUST identify players in punishment profiles and punishments only by the SHA-512 hash of their UUID and MUST NOT store raw player UUIDs, so stored and published punishment data contains no directly identifying player key.

#### Scenario: Raw UUID rejected as owner
- **WHEN** a profile request uses a raw UUID instead of a SHA-512 hash as owner
- **THEN** the request fails validation

### Requirement: Punishment domain events
The system SHALL publish domain events for punishment lifecycle changes: `punishment.created` (owner, punishment identifier, template identifier, type, and expiresAt when temporary), `punishment.expired`, `punishment.revoked`, and `profile.created` when a profile is first created, plus `profile.updated` and `profile.deleted` for direct profile writes.

#### Scenario: Temporary punishment event payload
- **WHEN** a temporary punishment is created
- **THEN** the `punishment.created` event includes its expiresAt timestamp

### Requirement: Redis mirroring for proxies
The system SHALL mirror active punishment state into Redis from domain events: `punishment.created` MUST set `blackhole:punish:ban:{owner}` or `blackhole:punish:chatban:{owner}` with a TTL equal to the remaining duration (none if permanent); `punishment.expired`, `punishment.revoked` or a full-lift appeal grant MUST delete it. Each change MUST be published on `blackhole.punishment.sync`, and Redis failures MUST NOT fail the originating write.

#### Scenario: Temporary ban mirrored with TTL
- **WHEN** a `punishment.created` event for a temporary NETWORK ban is processed
- **THEN** the ban key is set with a TTL equal to the remaining duration and a SET message is published on the sync channel

#### Scenario: Revocation clears Redis
- **WHEN** a `punishment.revoked` event for a chat punishment is processed
- **THEN** the chat ban key is deleted and a CLEARED message is published on the sync channel

### Requirement: Cache invalidation on punishment writes
Every write that changes a profile's punishment state (apply, revoke, expiry sweep, direct profile create/update/delete) SHALL invalidate the cached profile for that owner locally and across replicas.

#### Scenario: Revocation invalidates cache
- **WHEN** an active ban is revoked
- **THEN** the cached profile for that owner is invalidated on all replicas
