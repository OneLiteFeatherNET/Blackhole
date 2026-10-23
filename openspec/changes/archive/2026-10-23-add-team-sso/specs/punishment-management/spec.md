# Spec Delta

## MODIFIED Requirements

### Requirement: Applying a template creates a punishment
`POST /punishment/active/{owner}/{templateId}` SHALL, for a caller with at least `MODERATOR` or the `PROXY` role, create a new punishment from the template for the hashed owner, recording the source identity taken from the verified token (or the proxy's `onBehalfOf`) and creation/update timestamps, creating the punishment profile if none exists, and returning the updated profile. The request MUST NOT carry a client-supplied source. An unknown template MUST respond 404 without any change.

#### Scenario: Apply to a first-time offender
- **WHEN** a template is applied to an owner that has no punishment profile
- **THEN** a profile is created holding the new punishment in its matching active slot
- **AND** a `profile.created` event and a `punishment.created` event are published

#### Scenario: Source is the verified caller
- **WHEN** a moderator applies a template
- **THEN** the punishment's source and `recorded_by_oid` equal the moderator's `oid`

#### Scenario: Unknown template
- **WHEN** a punishment is applied with a template identifier that does not exist
- **THEN** the response is 404 and no punishment or profile is created

### Requirement: Manual revocation
The system SHALL let callers with at least `MODERATOR` or the `PROXY` role revoke an owner's active ban via `POST /punishment/active/{owner}/ban/revoke` and active chat punishment via `POST /punishment/active/{owner}/mute/revoke`, recording the revoking identity from the verified token (or the proxy's `onBehalfOf`) in the punishment metadata as `revokedBy` together with `recordedByOid`, moving it to history and publishing `punishment.revoked`. If the profile or the targeted active slot is empty, the response MUST be 404.

#### Scenario: Revoke an active mute
- **WHEN** the mute revoke endpoint is called for an owner with an active chat punishment
- **THEN** the punishment moves to history with `revokedBy` recorded from the token and the chat slot is cleared
- **AND** `punishment.revoked` is published with owner, punishment identifier, type and revokedBy

#### Scenario: Nothing to revoke
- **WHEN** the ban revoke endpoint is called for an owner without an active ban
- **THEN** the response is 404
