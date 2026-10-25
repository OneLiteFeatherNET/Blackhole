## ADDED Requirements

### Requirement: Every error response is an RFC 9457 problem
Every error response of every backend route SHALL be `application/problem+json` with the members `type`, `title`, `status`, `detail` and `instance`, where `status` equals the response status. `type` SHALL be `https://blackhole.onelitefeather.net/problems/<slug>`, the slug being the kebab-case machine code or else the status kind. `title` SHALL be fixed per type.

#### Scenario: Not found is a problem
- **WHEN** a client requests a single resource that does not exist
- **THEN** the response is HTTP 404 with content type `application/problem+json` and a `type` ending in `/not-found`

#### Scenario: Conflict keeps its code
- **WHEN** a client submits an appeal whose eligibility window has not opened
- **THEN** the response is HTTP 409 with `type` ending in `/not-eligible` and the extension member `code` equal to `NOT_ELIGIBLE`

#### Scenario: Plain-string and empty bodies become problems
- **WHEN** a route that previously answered with a plain string or an empty body fails
- **THEN** the body is a problem with the same status as before

### Requirement: Machine codes and extension members keep their names
The machine code SHALL be carried in the extension member `code` with its existing value (for example `NOT_ELIGIBLE`, `TRANSCRIPT_MISMATCH`). Existing extension data SHALL keep its name and type: `availableFrom`, `backendCount` and `backendLastMessageId`. The members `errorMessage`, `kind`, `reason` and `_embedded` SHALL NOT appear in any error body.

#### Scenario: Discord transcript mismatch keeps its tallies
- **WHEN** a Discord ticket relay reports a transcript mismatch
- **THEN** the problem has `code` `TRANSCRIPT_MISMATCH`, `backendCount` as an integer and `backendLastMessageId` as a decimal string

#### Scenario: Old member names are absent
- **WHEN** any error body is read
- **THEN** it contains no `errorMessage`, `kind` or `reason` member

### Requirement: Validation errors list their fields
A request that fails bean validation or framework argument conversion SHALL be answered with HTTP 400 and a problem whose extension member `errors` lists each failing field as an object with `field` and `message`. A `message` SHALL be a fixed template and SHALL NOT contain the rejected value.

#### Scenario: Two invalid fields
- **WHEN** a submission has two fields outside their constraints
- **THEN** the problem's `errors` array has one entry per field, and no entry contains the submitted value

### Requirement: Security rejections are problems
Authentication failures (HTTP 401) and authorization failures (HTTP 403), including the groups-overage rejection that previously answered `{"reason":"groups-overage"}`, SHALL be problems. The groups-overage problem SHALL carry the extension member `code` `GROUPS_OVERAGE`. The status of each rejection SHALL be unchanged.

#### Scenario: Groups overage
- **WHEN** a caller whose token has a groups overage requests a role-protected route
- **THEN** the response is HTTP 403 with `code` `GROUPS_OVERAGE` and no `reason` member

### Requirement: Uniform public login error
`POST /public/session` SHALL answer every failed login with the same HTTP 401 problem (same `type`, `title`, `detail`, `code` `INVALID_BAN_ID`, `instance` and `Cache-Control: no-store`), whatever the reason for failure. A leaked Ban ID SHALL keep succeeding with scope `LIMITED`. The public 401 on a missing or expired session SHALL keep its status so that SSE clients stop reconnecting.

#### Scenario: Unknown and malformed Ban IDs are indistinguishable
- **WHEN** two login attempts fail, one with an unknown Ban ID and one with a malformed one
- **THEN** both responses are identical problem bodies with HTTP 401

#### Scenario: Live stream stops reconnecting
- **WHEN** a public live stream is opened with an expired session
- **THEN** the response is HTTP 401 with a problem body, and the client does not retry

### Requirement: No identifiers in problem detail or instance
No problem `detail`, `instance`, `errors` entry or `nextCursor` SHALL contain a Ban ID, a raw player UUID or an IP address. The `instance` SHALL be the request path. A path that already contains such an identifier SHALL NOT be echoed in `instance`.

#### Scenario: Leak check does not echo the candidate
- **WHEN** a staff member submits a Ban ID to `POST /punishment/lookup` and no punishment matches
- **THEN** the problem's `detail` and `instance` do not contain the submitted candidate
