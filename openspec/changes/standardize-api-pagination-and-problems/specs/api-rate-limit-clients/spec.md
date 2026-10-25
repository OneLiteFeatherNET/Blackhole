## ADDED Requirements

### Requirement: Clients honour 429 and Retry-After
Rate limits SHALL be enforced by the infrastructure; the application SHALL NOT add a limiter, except the
existing report-submission limiter. When a backend response is HTTP 429, a client SHALL wait for the
`Retry-After` delay (RFC 9110, seconds or HTTP-date) before it retries, capped at 60 seconds where the
client retries itself.

#### Scenario: Discord retries after the delay
- **WHEN** the backend answers a Discord request with 429 and `Retry-After: 2`
- **THEN** the bot waits at most 2 seconds and retries once

#### Scenario: Web retries a read once
- **WHEN** the web client receives 429 for a GET with `Retry-After: 1`
- **THEN** it waits and retries that GET once, then shows the wait time from `ApiError`

### Requirement: POST retries require an idempotency key
A client SHALL retry a `POST` after 429 or a network failure only when the request carries an
`Idempotency-Key`.

#### Scenario: POST without a key is not retried
- **WHEN** the backend answers a POST without an `Idempotency-Key` with 429
- **THEN** the client reports the wait and does not send the request again

### Requirement: The portal relays rate limits
The portal BFF SHALL relay `429` and `Retry-After` to the browser unchanged and SHALL NOT treat a 429
problem as an outage.

#### Scenario: Portal relays the wait
- **WHEN** the backend answers a portal request with 429 and `Retry-After: 5`
- **THEN** the BFF answers the browser with 429 and `Retry-After: 5` and does not answer 503

### Requirement: Velocity keeps its last known section on 429
Velocity SHALL treat a 429 on a section or rule read as a deferred read and SHALL keep the last known value.

#### Scenario: Velocity keeps the last section
- **WHEN** Velocity receives 429 while reading a section
- **THEN** the previously loaded section stays in effect until a later read succeeds
