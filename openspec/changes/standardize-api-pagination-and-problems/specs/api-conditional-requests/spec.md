## ADDED Requirements

### Requirement: Versioned resources return an ETag
A resource that has a version counter SHALL return an `ETag` response header on its read, set to the
quoted decimal version (`"<version>"`). In 0.0.41 the only such resource is the case (`GET /case/{reference}`);
the others checked are listed in `design.md` D17.

#### Scenario: Case read returns its ETag
- **WHEN** a client reads `GET /case/{reference}` for a case at version 7
- **THEN** the response has `ETag: "7"` and the body `version` is `7`

### Requirement: Updates of versioned resources require If-Match
An update of a versioned resource SHALL require an `If-Match` request header naming the version the
caller read. The request body SHALL NOT carry an `expectedVersion` field.

#### Scenario: Update with the current version succeeds
- **WHEN** a client sends `PATCH /case/{reference}` with `If-Match: "7"` and the case is still at version 7
- **THEN** the update is applied and the response carries `ETag: "8"`

#### Scenario: Body version is not a lock
- **WHEN** a client sends `PATCH /case/{reference}` with `expectedVersion` in the body and no `If-Match` header
- **THEN** the response is HTTP 428 with `code` `PRECONDITION_REQUIRED` and no change is stored

### Requirement: Stale or missing preconditions are refused as problems
A stale `If-Match` SHALL be answered with HTTP 412 Precondition Failed and the problem code
`PRECONDITION_FAILED`. A missing `If-Match`, or `If-Match: *`, SHALL be answered with HTTP 428
Precondition Required (RFC 6585) and the problem code `PRECONDITION_REQUIRED`. Both responses SHALL carry
the current `ETag` header and store no change.

#### Scenario: Stale version is a precondition failure
- **WHEN** a client sends `PATCH /case/{reference}` with `If-Match: "7"` and the case is at version 8
- **THEN** the response is HTTP 412 with `code` `PRECONDITION_FAILED`, the current `ETag: "8"` header, and no change is stored

#### Scenario: Missing If-Match is refused
- **WHEN** a client sends `PATCH /case/{reference}` without an `If-Match` header
- **THEN** the response is HTTP 428 with `code` `PRECONDITION_REQUIRED` and no change is stored

#### Scenario: Wildcard If-Match is refused
- **WHEN** a client sends `PATCH /case/{reference}` with `If-Match: *`
- **THEN** the response is HTTP 428 with `code` `PRECONDITION_REQUIRED`
