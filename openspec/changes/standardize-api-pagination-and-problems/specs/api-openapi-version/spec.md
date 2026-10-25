## ADDED Requirements

### Requirement: The client spec declares its OpenAPI version and dialect
The client spec for 0.0.41 SHALL declare the OpenAPI version it uses. It SHALL be `3.1.x` with the JSON
Schema 2020-12 dialect when the spike in task 5.4 passes for the Java client generator and for
`openapi-typescript`. Otherwise it SHALL be `3.0.3`, and the move to 3.1 SHALL be recorded as deferred in
`design.md` D20 with the generator error as the reason.

#### Scenario: Spike passes
- **WHEN** the 3.1 copy validates and both generators accept it
- **THEN** `client/specs/blackhole-api-0.0.41.yml` declares `openapi: 3.1.x` and uses `type: [..., "null"]` for nullable fields

#### Scenario: Spike fails
- **WHEN** the Java generator rejects the 3.1 copy
- **THEN** the 0.0.41 spec declares `openapi: 3.0.3` and `design.md` D20 names the generator error and the follow-up change `openapi-31-client`
