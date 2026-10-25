## ADDED Requirements

### Requirement: Instants are RFC 3339 strings in UTC
Every instant in a request body, a response body and a domain event payload SHALL be an RFC 3339
string in UTC with the `Z` suffix (for example `2026-11-01T10:00:00Z`). Epoch milliseconds SHALL NOT
appear in any HTTP body or NATS payload. Field names SHALL NOT change; only the type changes.
A nullable instant SHALL stay nullable (`null` when absent).

#### Scenario: Discord not-yet error
- **WHEN** a Discord ticket is refused with 409 `NOT_YET`
- **THEN** `availableFrom` is an RFC 3339 string ending in `Z`, not a number

#### Scenario: Epoch number in a request is refused
- **WHEN** a client submits `capturedAt` as a number (for example `1761388800000`) to `POST /evidence`
- **THEN** the response is HTTP 400 with an `errors` entry for `capturedAt` and no evidence is stored

#### Scenario: Existing RFC 3339 fields keep their format
- **WHEN** a client reads `ClientRuleResponse.createdAt` or a public `availableFrom`
- **THEN** the value is already an RFC 3339 string and stays byte-compatible with the previous format

#### Scenario: Domain event carries an RFC 3339 occurredAt
- **WHEN** a domain event is published on `blackhole.events.<type>`
- **THEN** its `occurredAt` is an RFC 3339 string in UTC and `payloadVersion` is unchanged

#### Scenario: Durations stay numbers
- **WHEN** a share line reports `offsetSeconds`
- **THEN** the value remains a number of seconds, because it is a duration and not an instant
