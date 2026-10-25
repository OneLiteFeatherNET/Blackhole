## ADDED Requirements

### Requirement: Creating POST routes replay on Idempotency-Key
Every creating `POST` route listed in `design.md` D18 (I1 to I8) SHALL accept the `Idempotency-Key`
request header. The backend SHALL store, for 24 hours, the key, the caller, the route, a SHA-256
fingerprint of the canonical request, and the response of a successful request. A repeat with the same
key, caller, route and fingerprint SHALL receive the stored response with the same status and body and
the header `Idempotent-Replayed: true`. The key SHALL be scoped to the caller and the route.

#### Scenario: Retry after a dropped response
- **WHEN** a staff client sends `POST /report` with `Idempotency-Key: k-123` and the same body twice
- **THEN** exactly one report exists and the second response has the same status and body as the first and `Idempotent-Replayed: true`

#### Scenario: Same key, different body
- **WHEN** a client sends `POST /report` with `Idempotency-Key: k-123` and then a different body with the same key
- **THEN** the second response is HTTP 409 with `code` `IDEMPOTENCY_KEY_REUSED` and nothing is stored

#### Scenario: Same key on another route or from another caller
- **WHEN** the same key is used on `POST /appeal` or by another caller
- **THEN** the request is handled as new and does not replay the earlier response

#### Scenario: Malformed key
- **WHEN** a client sends an `Idempotency-Key` that is empty, longer than 128 characters, or contains characters outside the allowed set
- **THEN** the response is HTTP 422 with `code` `IDEMPOTENCY_KEY_INVALID`

#### Scenario: Failed request is not stored
- **WHEN** a request with a key fails validation with HTTP 400
- **THEN** the corrected request with the same key is processed as new

#### Scenario: Key expires after 24 hours
- **WHEN** a request repeats a key more than 24 hours after its first success
- **THEN** the request is processed as new

#### Scenario: Portal message keeps its behaviour
- **WHEN** the portal posts a message through the BFF with a well-formed `Idempotency-Key`
- **THEN** the key reaches the backend unchanged and a repeat replays the first message

### Requirement: Idempotency storage holds no raw identifiers
The idempotency store SHALL key the caller by an HMAC of the caller identity, SHALL NOT store a raw
player UUID, IP address or Ban ID, and SHALL delete rows at expiry.

#### Scenario: Stored row has no raw identifier
- **WHEN** a public session posts a message with a key
- **THEN** the stored row contains the HMAC of the session token hash and no Ban ID or raw UUID
