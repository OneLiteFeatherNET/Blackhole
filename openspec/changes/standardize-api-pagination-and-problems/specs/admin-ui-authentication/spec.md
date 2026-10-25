## MODIFIED Requirements

### Requirement: Authenticated API calls
Every API request SHALL carry `Authorization: Bearer`. On HTTP 401 the UI MUST renew once and retry once, then sign in again; on HTTP 403 it MUST show a "not permitted" message and MUST NOT retry. The UI SHALL read the error from the problem body (`application/problem+json`) through the shared `ApiError` type, using the `code` and `detail` members. A response whose body is not a problem SHALL be handled by its status alone.

#### Scenario: Expired token during a request
- **WHEN** a request is answered 401 and renewal succeeds
- **THEN** the request is repeated once with the new token

#### Scenario: Forbidden action
- **WHEN** a request is answered 403
- **THEN** a not-permitted message is shown and no retry is made

#### Scenario: Forbidden problem
- **WHEN** a request is answered 403 with a problem body
- **THEN** the UI shows the "not permitted" message from its own translations, not the server's `detail`, and does not retry
