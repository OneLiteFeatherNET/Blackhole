## ADDED Requirements

### Requirement: Read by id carries the role of the list of its entity
Each of the seven read-by-id endpoints (`GET /appeal/{identifier}`, `GET /exemption/reviews/{id}`, `GET /area-bans/{id}`,
`GET /intel/allow-list/{owner}`, `GET /admin/audit/{auditId}`, `GET /admin/staff-actor/{oid}`,
`GET /appeal-conditions/versions/{version}`) SHALL be `@Version(V1)` and SHALL be `@Secured` with exactly the roles of the
list endpoint of the same entity. No endpoint SHALL accept a wider set of roles. The check SHALL run on each request.

#### Scenario: Viewer reads an appeal
- **WHEN** a VIEWER sends `GET /appeal/{identifier}` for an existing appeal
- **THEN** the answer is 200 with the appeal

#### Scenario: Moderator reads an audit entry
- **WHEN** a MODERATOR sends `GET /admin/audit/{auditId}` for an existing entry
- **THEN** the answer is 403 and no entry is returned

#### Scenario: Proxy reads an area ban
- **WHEN** a PROXY sends `GET /area-bans/{id}` for an existing area ban
- **THEN** the answer is 200, the same as its list

### Requirement: No token and too low a role are refused
A request without a valid token SHALL be answered 401. A request whose token carries no role at or above the minimum of the
endpoint SHALL be answered 403. A VIEWER-minimum endpoint has no role below its minimum except a token without any
Blackhole role; the tests use such a token for the 403 case.

#### Scenario: No token
- **WHEN** a request to `GET /admin/staff-actor/{oid}` carries no token
- **THEN** the answer is 401

#### Scenario: Role below the minimum
- **WHEN** a MODERATOR sends `GET /exemption/reviews/{id}`
- **THEN** the answer is 403

#### Scenario: Token without a Blackhole role
- **WHEN** a valid token with no Blackhole role sends `GET /appeal/{identifier}`
- **THEN** the answer is 403

### Requirement: Admin-only entities answer 403 before any lookup
For an entity whose list is `ADMIN`-only, a caller below `ADMIN` SHALL get 403 whether or not the id exists, so that the
existence of an id is not revealed to a role that may not see the entity.

#### Scenario: Viewer asks for an unknown review
- **WHEN** a VIEWER sends `GET /exemption/reviews/{id}` with an id that does not exist
- **THEN** the answer is 403, not 404

#### Scenario: Viewer asks for an existing review
- **WHEN** a VIEWER sends `GET /exemption/reviews/{id}` with an id that exists
- **THEN** the answer is 403 and the review is not in the body

### Requirement: Unknown id answers 404
For a role that may read the entity, an id that does not exist SHALL be answered 404. The answer SHALL carry no entity data.
The endpoints whose DTO has an `Error` variant SHALL answer it with kind `NOT_FOUND`; `GET /appeal/{identifier}` answers 404
with no body, as its existing review endpoint does.

#### Scenario: Unknown area ban
- **WHEN** an ADMIN or a VIEWER sends `GET /area-bans/{id}` for an id that does not exist
- **THEN** the answer is 404 with an error body of kind `NOT_FOUND`

#### Scenario: Unknown appeal
- **WHEN** a VIEWER sends `GET /appeal/{identifier}` for a UUID that does not exist
- **THEN** the answer is 404 with no body

### Requirement: Path identifiers are the ones staff already see
A path value SHALL be a UUID for appeals, reviews, audit entries and staff objects, an integer of 1 or more for an
appeal-terms version, and an opaque area ban id as the list returns it. `GET /intel/allow-list/{owner}` SHALL accept only
a 128-character lowercase hexadecimal owner hash. Any other value SHALL be answered 400 `VALIDATION` before a lookup. A
raw player UUID, a player name, an IP address, a Discord user id and a Ban ID SHALL NOT be accepted as a path value.

#### Scenario: Raw UUID as owner
- **WHEN** a request sends `GET /intel/allow-list/<raw player UUID>`
- **THEN** the answer is 400 `VALIDATION` and no lookup runs

#### Scenario: Name as owner
- **WHEN** a request sends `GET /intel/allow-list/Steve`
- **THEN** the answer is 400 `VALIDATION`

#### Scenario: Unknown owner hash
- **WHEN** a request sends `GET /intel/allow-list/<well-formed hash with no entry>`
- **THEN** the answer is 404 with kind `NOT_FOUND`

#### Scenario: Non-integer version
- **WHEN** a request sends `GET /appeal-conditions/versions/latest`
- **THEN** the answer is 400

### Requirement: No Ban ID, raw UUID, name or IP in any response
None of the seven responses SHALL contain a Ban ID, a raw player UUID, a player name, an IP address or a Discord user id.
The tests seed each of these in the fixture and assert that the JSON body does not contain its value.

#### Scenario: Appeal response
- **WHEN** a VIEWER reads an appeal whose punishment has a Ban ID and a raw player UUID in the database
- **THEN** the JSON body contains neither value and the identifier and the owner hash only

#### Scenario: Allow-list entry
- **WHEN** a VIEWER reads an allow-list entry
- **THEN** the body names the owner by its hash, and the staff object id that added it, and nothing else about the player

### Requirement: Responses reuse the existing DTO of the entity
Each endpoint SHALL answer the existing record of its entity (the list item or the detail record). Where that record is not
yet a variant of the entity's sealed marker, it SHALL implement the marker; no field SHALL be added, removed or renamed, so
that the JSON of the list is unchanged.

#### Scenario: Audit entry matches the list item
- **WHEN** an ADMIN reads an audit entry through `GET /admin/audit/{auditId}` and through the list
- **THEN** the two JSON objects for that entry are equal

### Requirement: Version and ETag only where the entity has a version
A response SHALL carry an `ETag` header and a version field only for an entity that has a version column today. None of the
seven new reads has one. Appeal, exemption review, area ban, allow-list entry and staff actor have no version and their
responses carry neither; the change records this as a known gap for the restructured panel's confirmed actions.

#### Scenario: Area ban has no ETag
- **WHEN** an ADMIN reads `GET /area-bans/{id}`
- **THEN** the response carries no `ETag` header

#### Scenario: Case keeps its version
- **WHEN** a VIEWER reads the existing `GET /case/{ref}`
- **THEN** the body keeps its `version` field, unchanged by this change

### Requirement: Route coverage
Every route of this change SHALL appear in `RouteCoverageIntegrationTest` with its `@Secured` roles and version, and each SHALL
appear in `PermissionMatrixIntegrationTest` with the answers of the requirements above.

#### Scenario: New route missing from the matrix
- **WHEN** a seventh read is added without a row in `PermissionMatrixIntegrationTest`
- **THEN** the build fails
