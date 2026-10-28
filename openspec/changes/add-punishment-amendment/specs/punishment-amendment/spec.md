## ADDED Requirements

### Requirement: Amendment changes an active punishment

A staff member SHALL be able to amend an active punishment of kind `PUNISHMENT` through `POST /punishment/{identifier}/amendments`, with one or more of: a new expiry, `permanent`, a new `reason` and a new `templateKey`. An amendment SHALL carry a justification of 10 to 500 characters. An amendment with no change, or with a justification outside the bounds, SHALL be refused with 422 or 400 and change nothing.

#### Scenario: Shorten a temporary ban

- **WHEN** a MODERATOR shortens an active temporary ban to an expiry later than now and earlier than the current expiry, with a justification
- **THEN** the ban has the new expiry, the amendment is recorded, and the response is 201

#### Scenario: Empty amendment

- **WHEN** a request carries a justification and no change
- **THEN** the response is 422 `AMENDMENT_INVALID` and nothing changes

#### Scenario: Short justification

- **WHEN** a request carries a justification of 5 characters
- **THEN** the response is 400 and nothing changes

#### Scenario: Expiry in the past

- **WHEN** a request sets an expiry before now
- **THEN** the response is 422 `AMENDMENT_INVALID`; ending a punishment is a revoke

### Requirement: Amendments are limited by role

A MODERATOR SHALL be allowed to shorten a temporary punishment, and to change its reason or template. An ADMIN SHALL be allowed every change, including extending a punishment, making it permanent and making a permanent punishment temporary. The server SHALL enforce this regardless of the panel or the command.

#### Scenario: Moderator extends a ban

- **WHEN** a MODERATOR sends an extension
- **THEN** the response is 403 and nothing changes

#### Scenario: Moderator changes a reason

- **WHEN** a MODERATOR changes the reason of a temporary ban with a justification
- **THEN** the response is 201 and the reason is changed

#### Scenario: Admin makes a ban permanent

- **WHEN** an ADMIN makes an active temporary ban permanent with a justification
- **THEN** the response is 201 and the punishment is permanent

### Requirement: Amendment requires the current version

`GET /punishment/{identifier}` SHALL return the punishment with `ETag: "<version>"`. An amendment SHALL require `If-Match` naming that version. A stale `If-Match` SHALL be answered 412 `PRECONDITION_FAILED` with the current `ETag`, a missing or `*` `If-Match` SHALL be answered 428 `PRECONDITION_REQUIRED`, and neither SHALL change anything.

#### Scenario: Amend with the current version

- **WHEN** a client amends a punishment with `If-Match` equal to its current version
- **THEN** the amendment is stored and the response carries the new `ETag`

#### Scenario: Stale version

- **WHEN** two moderators amend the same punishment from the same version and the second one is sent later
- **THEN** the second is answered 412 `PRECONDITION_FAILED` and nothing is stored

#### Scenario: Missing version

- **WHEN** an amendment is sent without `If-Match`
- **THEN** the response is 428 `PRECONDITION_REQUIRED`

### Requirement: Amendment is idempotent

The amendment POST SHALL require an `Idempotency-Key`. A repeat with the same key and the same body SHALL write nothing and answer the original 201. A repeat with the same key and a different body SHALL be answered 422 `IDEMPOTENCY_KEY_INVALID`.

#### Scenario: Double submit

- **WHEN** the same amendment is sent twice with one `Idempotency-Key`
- **THEN** one amendment is recorded and the second answer is the same 201

### Requirement: Every amendment is recorded with both states

Every amendment SHALL be stored as an append-only record with the actor (the verified `oid`, or the proxy's `onBehalfOf` for an in-game amendment), the justification, the state before and the state after (expiry, permanence, reason, template reference, version). It SHALL record a case event `punishment.amended` with both states after the write. A recording failure SHALL NOT fail the amendment.

#### Scenario: Case shows both states

- **WHEN** an ADMIN extends a ban
- **THEN** the case has one `punishment.amended` event naming the admin, the justification, the old and the new expiry

#### Scenario: Amendment history

- **WHEN** the amendment list of a punishment is read
- **THEN** the amendments are returned newest first in the cursor envelope with a `Link` header when there is a next page

### Requirement: Amendment changes no escalation

An amendment SHALL NOT change the escalation group, the step index or the step count recorded when the punishment was applied, and SHALL NOT change which punishments count towards escalation.

#### Scenario: Template changed

- **WHEN** an amendment changes the template reference of a punishment
- **THEN** the escalation group, step index and step count are unchanged

### Requirement: Amendment publishes the new state

After an amendment is written, the backend SHALL publish `punishment.updated` with the full state of the punishment, in the shape of `punishment.created`, and SHALL evict the cached profile. A failed publish SHALL NOT fail the amendment, and the event payload SHALL carry no justification text, no IP address and no raw UUID.

#### Scenario: Expiry changed

- **WHEN** an extension is stored
- **THEN** `punishment.updated` carries the new expiry and the proxy mirror replaces its entry

#### Scenario: Publish failure

- **WHEN** the publish fails after the write
- **THEN** the amendment is kept and the failure is logged without the justification text

### Requirement: Amendment of a warning or kick is refused

An amendment SHALL be refused with 409 when the punishment is of kind `WARN` or `KICK`, is expired, or is lifted.

#### Scenario: Amend a kick

- **WHEN** an amendment targets a kick
- **THEN** the response is 409 and nothing changes
