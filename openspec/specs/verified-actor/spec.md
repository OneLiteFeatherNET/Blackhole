# verified-actor Specification

## Purpose
Defines how every write records the identity verified from the access token instead of a client-supplied value, what staff data is stored, and how it is retained and anonymized.

## Requirements

### Requirement: Actor comes from the token
For every write the backend SHALL derive the acting identity from the validated token and MUST NOT read an actor from the request body or query. The request fields `source`, `resolvedBy`, `reviewerId`, `punishmentSource`, evidence `source` and `createdBy`, and the ELO adjustment `source` MUST NOT exist in the API contract, and a client that still sends them MUST have the values ignored.

#### Scenario: Spoofed actor is ignored
- **WHEN** a moderator resolves a report and the body contains a `resolvedBy` of another person
- **THEN** the report records the moderator's own `oid`

#### Scenario: Contract has no actor fields
- **WHEN** the OpenAPI specification is generated
- **THEN** no request schema contains `source`, `resolvedBy`, `reviewerId` or `punishmentSource`

### Requirement: Recorded verified principal
Every record written by a caller SHALL store the token's `oid` in a `recorded_by_oid` column (or the `recordedByOid` metadata key) in addition to its attribution value. Records written before this change MUST keep their value and have no `recorded_by_oid`, and the API SHALL report them as unverified.

#### Scenario: New punishment
- **WHEN** a moderator applies a punishment
- **THEN** the punishment's attributed actor and `recorded_by_oid` both equal the moderator's `oid`

#### Scenario: Legacy record
- **WHEN** a punishment created before SSO is read
- **THEN** its attributed actor is unchanged and its verified principal is absent

### Requirement: Staff actor snapshot
The backend SHALL keep a `staff_actor` row per principal with `oid`, kind (`HUMAN` or `SERVICE`), display name and first and last seen times, created or updated on a write. It MUST read only the `oid` and `name` claims and MUST NOT store email, user principal name or tokens.

#### Scenario: First write by a new moderator
- **WHEN** a moderator with no `staff_actor` row performs a write
- **THEN** a row is created with their `oid`, name claim and kind `HUMAN`

#### Scenario: Name change
- **WHEN** the same `oid` later arrives with a different `name`
- **THEN** the row's display name is updated and its `oid` stays

### Requirement: Delegated attribution by the proxy
A `PROXY` caller MAY send `onBehalfOf` (an in-game staff Minecraft UUID) on punishment, revocation and evidence writes; when present it SHALL be the attributed actor and `recorded_by_oid` MUST still be the proxy's `oid`. Any other role sending `onBehalfOf` MUST receive HTTP 400.

#### Scenario: In-game ban
- **WHEN** the proxy applies a punishment with `onBehalfOf` set to a staff member's UUID
- **THEN** the punishment is attributed to that UUID and `recorded_by_oid` is the proxy's `oid`

#### Scenario: Moderator sends onBehalfOf
- **WHEN** a moderator's request contains `onBehalfOf`
- **THEN** the response is HTTP 400 and nothing is written

### Requirement: Staff data retention and anonymization
Staff identity data SHALL be retained for as long as records referencing it exist. An `ADMIN` MUST be able to call `POST /admin/staff-actor/{oid}/anonymize`, which clears the display name and keeps the `oid` and all history; the operation MUST be audited with the admin's own verified actor.

#### Scenario: Anonymize a former moderator
- **WHEN** an admin anonymizes a `staff_actor`
- **THEN** its display name is empty, existing records still reference the `oid`, and the action is recorded with the admin's `oid`

#### Scenario: Moderator cannot anonymize
- **WHEN** a moderator calls the anonymize route
- **THEN** the response is HTTP 403
