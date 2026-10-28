## ADDED Requirements

### Requirement: Exempt players

A player SHALL be exempt when the Minecraft account is linked to a staff member (a `staff_link` exists for the player's UUID), or when the proxy reports the permission `blackhole.exempt` or `blackhole.exempt.<section>` for an online session of that player. The backend MUST store the exemption as a projection keyed by the hashed owner and MUST NOT store a raw UUID in it.

#### Scenario: Linked staff member is exempt

- **WHEN** a staff member links their Minecraft account
- **THEN** the projection holds a `LINKED_STAFF` row for the player's owner and the link's removal deletes it

#### Scenario: Permission reported at login

- **WHEN** a player with `blackhole.exempt.survival` logs in and the proxy reports the login
- **THEN** the projection holds a `PERMISSION` row for that owner with the section `survival`

#### Scenario: Permission removed

- **WHEN** the proxy reports a later session of the same player without the permission
- **THEN** the `PERMISSION` row is deleted

### Requirement: Rank rule for punishment writes

A punishment, a kick or a template application against an exempt target SHALL be refused with 409 `TARGET_EXEMPT` when the actor's rank is equal to or lower than the target's protection rank. A refused request MUST write nothing: no punishment, no case event, no ELO event and no NATS event.

#### Scenario: Refused request writes nothing

- **WHEN** a kick of an exempt player is refused with `TARGET_EXEMPT`
- **THEN** no kick row, case event, ELO event or NATS event is written

### Requirement: Ranks of actors and exempt targets

Ranks SHALL be `VIEWER` (1), `MODERATOR` (2) and `ADMIN` (3). The target's rank is the last recorded role of its linked staff `oid` (`staff_actor.last_role`), MODERATOR when no role is recorded, and MODERATOR for a permission-exempt target. The actor's rank is the highest role of the current token, or for an in-game write with `onBehalfOf` the last recorded role of the linked `oid`. An unlinked in-game actor has no rank.

#### Scenario: Unlinked in-game actor has no rank

- **WHEN** an unlinked in-game actor is ranked against an exempt target
- **THEN** the actor is refused, because no rank can be established

#### Scenario: Linked staff without a recorded role

- **WHEN** a player is linked to an `oid` whose role was never recorded
- **THEN** the player's rank is MODERATOR

#### Scenario: Moderator punishes a linked moderator

- **WHEN** a MODERATOR applies a mute to a player linked to another MODERATOR
- **THEN** the response is 409 `TARGET_EXEMPT` and no punishment is written

#### Scenario: Admin punishes a permission-exempt player

- **WHEN** an ADMIN applies a ban to a player with `blackhole.exempt` (rank MODERATOR)
- **THEN** the ban is applied without an override

#### Scenario: Admin punishes a linked admin

- **WHEN** an ADMIN applies a ban to a player linked to an ADMIN
- **THEN** the response is 409 `TARGET_EXEMPT` unless an override is supplied

#### Scenario: Unlinked in-game actor

- **WHEN** an unlinked in-game staff account applies a punishment to an exempt player
- **THEN** the response is 409 `TARGET_EXEMPT` with a message that the actor is not linked

#### Scenario: Non-exempt target

- **WHEN** any actor punishes a player who is not exempt
- **THEN** the write proceeds as before, with no exemption query changing the result

### Requirement: ADMIN override with justification

An ADMIN MAY override a `TARGET_EXEMPT` refusal in the panel by sending `exemptionOverride` with a justification of 1 to 500 characters that passes the PII guard. The override SHALL be stored on the punishment (justification, the target's rank and the source of the exemption) and SHALL publish `punishment.exemption_overridden` with the admin's verified `oid`. An override MUST NOT be accepted on a proxy-delegated write, and a non-ADMIN sending an override MUST receive 403.

#### Scenario: Override recorded

- **WHEN** an ADMIN applies a ban to a linked admin with a justification
- **THEN** the ban is written, its metadata holds the override, and `punishment.exemption_overridden` is published

#### Scenario: Override without justification

- **WHEN** an ADMIN sends an override with an empty justification
- **THEN** the response is 400 and nothing is written

#### Scenario: Override through the proxy

- **WHEN** the proxy sends an override on a write with `onBehalfOf`
- **THEN** the response is 400 and nothing is written

### Requirement: Exemption report from the proxy

The backend SHALL accept exemption facts for a player only from the `PROXY` role, at login and with each enforcement request for an online target. A report replaces the `PERMISSION` rows of that owner, and a report without the flags removes them. A report MUST NOT change `LINKED_STAFF` rows. A read of an owner's exemption MUST expose only the source, the sections and the staff rank, never a UUID or a name.

#### Scenario: Report from a non-proxy caller

- **WHEN** a human staff token posts an exemption report
- **THEN** the response is 403

#### Scenario: Read exposes no identity

- **WHEN** a moderator reads the exemption of an owner
- **THEN** the response contains the source and the rank, and no UUID, name or IP

### Requirement: Staleness of permission facts

The protection of a `PERMISSION` exemption SHALL last until the next report for that owner. A removed permission on an offline player MUST keep protecting until the next login, and this MUST be documented on the panel badge tooltip.

#### Scenario: Offline player keeps protection

- **WHEN** a permission was removed while the player was offline
- **THEN** the player stays protected until their next login report

### Requirement: Review of automatic punishments against exempt players

When a threshold crossing would apply an automatic punishment to an exempt player, the system SHALL NOT apply the punishment. It SHALL keep the score change and the `elo.threshold_crossed` event with `exempt: true`, and SHALL create an `OPEN` review item with the owner, section, track, the template that would have applied and the crossing time.

#### Scenario: Review item instead of punishment

- **WHEN** an exempt player's score crosses the perma-ban threshold
- **THEN** no ban is written and one `OPEN` review item exists with the perma-ban template as proposed action

### Requirement: Deciding a review of an exempt player

An ADMIN MAY decide an `OPEN` review item by applying its template with a justification, which goes through the normal apply path with the override recorded, or by dismissing it with a justification. A decided review MUST NOT be decided again.

#### Scenario: Soft crossing of an exempt player

- **WHEN** a permission-exempt player's gameplay score crosses the soft threshold
- **THEN** no punishment is applied, the score change is kept, and one `OPEN` review item exists

#### Scenario: Admin applies a review

- **WHEN** an ADMIN applies the review's template with a justification
- **THEN** the punishment is applied through the normal path with the override recorded and the review is `APPLIED`

#### Scenario: Dismissed review

- **WHEN** an ADMIN dismisses a review with a justification
- **THEN** the review is `DISMISSED` and no punishment is written

#### Scenario: Second decision

- **WHEN** a review that is `APPLIED` or `DISMISSED` is decided again
- **THEN** the response is 409

### Requirement: Exemption domain events

The backend SHALL publish `punishment.exemption_overridden` for each override, after the write, with the owner hash, the section, the admin's `oid` and the target's rank. A failed publish MUST NOT fail the write.

#### Scenario: Publish failure after an override

- **WHEN** the NATS publish fails after an override is written
- **THEN** the override is kept, the failure is logged without the justification text, and the request still answers with the write's status
