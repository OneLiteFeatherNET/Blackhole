# Spec Delta

## Purpose

A proof-of-work challenge in front of the two anonymous or costly actions of the appeal portal: signing in with a
Ban ID and filing an appeal. Each accepted solution works once, and the check is run by the backend.

## ADDED Requirements

### Requirement: The backend issues the challenge
`GET /public/challenge` SHALL answer, for the purpose `sign-in` or `appeal`, a challenge signed with the configured
HMAC key, of algorithm `PBKDF2/SHA-256`, carrying the purpose in its signed `data`. The route SHALL be anonymous,
answer `Cache-Control: no-store`, and read no client address. An unknown purpose MUST be refused with HTTP 400.
When the challenge is disabled, it SHALL answer `{"enabled": false}`.

#### Scenario: Challenge for sign-in
- **WHEN** a client requests `GET /public/challenge?purpose=sign-in` with the challenge enabled
- **THEN** the response carries a signed challenge whose `data.purpose` is `sign-in`, and `Cache-Control` is `no-store`

#### Scenario: Unknown purpose
- **WHEN** a client requests a purpose other than `sign-in` or `appeal`
- **THEN** the response is HTTP 400 and no challenge is issued

#### Scenario: Disabled in a dev environment
- **WHEN** the challenge is disabled in the `dev` environment and a client requests a challenge
- **THEN** the response is `{"enabled": false}`

### Requirement: Sign-in requires a solved challenge
`POST /public/session` SHALL verify the `altcha` field before it looks up the Ban ID. A request without a valid
solved challenge for purpose `sign-in` MUST be refused with HTTP 403 `CHALLENGE_FAILED` and MUST NOT create a
session or read the Ban ID. A valid challenge with a wrong Ban ID keeps the uniform HTTP 401 `INVALID_BAN_ID`.

#### Scenario: No challenge
- **WHEN** a sign-in request has no `altcha` field and the challenge is enabled
- **THEN** the response is HTTP 403 `CHALLENGE_FAILED` and no session row is created

#### Scenario: Challenge solved, Ban ID wrong
- **WHEN** a sign-in request carries a valid solved challenge and an unknown Ban ID
- **THEN** the response is HTTP 401 `INVALID_BAN_ID` with the same body as any other wrong Ban ID

#### Scenario: Challenge for another purpose
- **WHEN** a sign-in request carries a valid challenge whose `data.purpose` is `appeal`
- **THEN** the response is HTTP 403 `CHALLENGE_FAILED`

### Requirement: Appeal filing requires a solved challenge before any eligibility check
`POST /public/appeal` SHALL verify the `altcha` field for purpose `appeal` before the eligibility checks. A request
that fails the challenge MUST NOT record an appeal attempt, MUST NOT change the appeal, and MUST be refused with
HTTP 403 `CHALLENGE_FAILED`.

#### Scenario: Filing without a challenge
- **WHEN** a filing request has no valid solved challenge
- **THEN** the response is HTTP 403 `CHALLENGE_FAILED` and no appeal row or attempt is written

#### Scenario: Filing with a challenge on an ineligible punishment
- **WHEN** a filing request carries a valid solved challenge but the appeal is too early
- **THEN** the response is the existing HTTP 409 `NOT_ELIGIBLE` with `availableFrom`, and the challenge is used up

### Requirement: Other routes need no challenge
Messages, file uploads, file downloads and every read route of `/public/**` SHALL NOT require a challenge. Their
protection is the player session and the existing appeal rules.

#### Scenario: A message with a session
- **WHEN** a player with a valid session posts a message to an open appeal without any challenge field
- **THEN** the message is accepted by the existing rules

### Requirement: Each solution is accepted once
The backend SHALL reserve the nonce of every verified solution in Dragonfly with an atomic set-if-absent that expires
after the challenge's remaining lifetime plus a 60 second margin. A solution whose nonce is already reserved MUST be
refused with HTTP 403 `CHALLENGE_FAILED` and counted as `replayed`. The reserve SHALL happen only after the signature,
expiry, key prefix and purpose checks have passed.

#### Scenario: Replay within the lifetime
- **WHEN** the same verified solution is submitted a second time before it expires
- **THEN** the second request is refused with HTTP 403 `CHALLENGE_FAILED`

#### Scenario: Two concurrent requests
- **WHEN** two requests with the same valid solution arrive at the same time on two replicas
- **THEN** exactly one reserves the nonce and the other is refused

#### Scenario: Wrong Ban ID uses the challenge up
- **WHEN** a valid solution is used for a sign-in with an unknown Ban ID
- **THEN** the nonce stays reserved, and a second sign-in with the same solution is refused

#### Scenario: Garbage does not reserve
- **WHEN** a payload fails the signature check
- **THEN** no key is written to Dragonfly

### Requirement: Fail closed without the reserve
When Dragonfly cannot answer within `blackhole.public.challenge.store-timeout`, sign-in and filing MUST answer HTTP 503
`UNAVAILABLE` and MUST NOT create a session or an appeal. Issuing a challenge MUST keep working.

#### Scenario: Dragonfly down during sign-in
- **WHEN** Dragonfly is stopped and a sign-in carries a valid solution
- **THEN** the response is HTTP 503 `UNAVAILABLE` and no session row is written

#### Scenario: Challenge issued while Dragonfly is down
- **WHEN** Dragonfly is stopped and a client requests a challenge
- **THEN** the challenge is issued

### Requirement: The HMAC key is required and secret
The backend SHALL read the HMAC key from `BLACKHOLE_PUBLIC_CHALLENGE_KEY`, which MUST be base64 of at least 32 bytes.
Outside the `dev` and `test` environments an unset or shorter key MUST stop the application from starting. The key MUST
NOT appear in any log, error message, response, metric or committed file.

#### Scenario: Missing key outside local environments
- **WHEN** the backend starts without the `dev` or `test` environment and no challenge key
- **THEN** startup fails with a message that does not contain a key

#### Scenario: Short key outside local environments
- **WHEN** the key decodes to fewer than 32 bytes outside the local environments
- **THEN** startup fails

#### Scenario: Local development without a key
- **WHEN** the backend starts in the `dev` environment without a key
- **THEN** startup succeeds and the challenge uses a key generated for that run

### Requirement: Dev-only switch
`BLACKHOLE_PUBLIC_CHALLENGE_ENABLED=false` SHALL disable the check only in the `dev` and `test` environments. Outside them
a disabled challenge MUST stop the application from starting.

#### Scenario: Disabled outside local environments
- **WHEN** the backend starts without the `dev` or `test` environment with the challenge disabled
- **THEN** startup fails

#### Scenario: Disabled in tests
- **WHEN** a test runs with the challenge disabled
- **THEN** a sign-in without `altcha` is accepted by the existing rules

### Requirement: Tunables with defaults
Difficulty, expiry and the store timeout SHALL be read from the environment variables
`BLACKHOLE_PUBLIC_CHALLENGE_COST` (default `5000`), `BLACKHOLE_PUBLIC_CHALLENGE_KEY_PREFIX` (default `00`, an even
number of hex characters), `BLACKHOLE_PUBLIC_CHALLENGE_EXPIRY` (default `PT5M`) and
`BLACKHOLE_PUBLIC_CHALLENGE_STORE_TIMEOUT` (default `PT0.5S`), each documented by a comment in `application.yml`. A
value outside its allowed range MUST stop the application from starting.

#### Scenario: Default values
- **WHEN** no challenge variable is set outside the local environments
- **THEN** the defaults above apply

#### Scenario: Odd-length prefix
- **WHEN** the key prefix has an odd number of characters
- **THEN** startup fails

### Requirement: Nothing about the player is stored for the challenge
The challenge mechanism SHALL store nothing per player. Its only state is the reserved nonce in Dragonfly, which expires.
Logs, metrics, events and responses of the challenge MUST NOT contain a challenge payload, a solution, a nonce, a Ban ID
or a client address.

#### Scenario: Log output of a full sign-in
- **WHEN** a sign-in with a valid solution runs under a captured log appender
- **THEN** no captured line contains the payload, the solution or the nonce

#### Scenario: Reserved key expires
- **WHEN** a reserved nonce's lifetime passes
- **THEN** Dragonfly no longer holds the key

### Requirement: Outcome metrics
The backend SHALL count challenge outcomes in `blackhole.public.challenge` with one tag `outcome`, one of `issued`,
`solved`, `rejected`, `replayed` or `unavailable`.

#### Scenario: Replay is counted
- **WHEN** a solution is replayed
- **THEN** the counter `blackhole.public.challenge{outcome=replayed}` increases by one

### Requirement: Widget without a visual puzzle
The portal SHALL use the ALTCHA widget with the proof-of-work challenge only. The control MUST be a labelled checkbox,
operable with the keyboard, with its status in a polite live region. A failed solve MUST show a retry control that works
with Enter and Space. The portal MUST NOT show a code challenge (image or audio).

#### Scenario: Keyboard only
- **WHEN** a player uses only the keyboard on the sign-in form
- **THEN** the challenge control is reachable in tab order before the submit button, and the retry control works with Enter

#### Scenario: Failed solve
- **WHEN** the solve fails or times out
- **THEN** the form shows a retry control and keeps the typed Ban ID or statement

### Requirement: Fallback when workers are blocked
When the widget's worker cannot start, the portal MUST show a message that the check could not run in this browser and
that a current browser with JavaScript is needed. The message MUST exist in English and German.

#### Scenario: Worker blocked
- **WHEN** the browser refuses to start the widget's worker
- **THEN** the message is shown and the submit button stays disabled until a check succeeds

### Requirement: Portal content security policy
The portal's `Content-Security-Policy` SHALL add `worker-src 'self'`. It MUST NOT add `blob:`, `data:`, `unsafe-eval` or
any external origin for the widget. The widget SHALL be imported from the `altcha/external` build with its workers
loaded from the portal's own origin. The widget's human interaction signature collector MUST be disabled, and the
widget's `test` and `mockError` options MUST NOT be set outside tests.

#### Scenario: Workers from the own origin
- **WHEN** the portal runs the widget under its CSP
- **THEN** the worker loads from the portal's origin and no CSP violation is reported

#### Scenario: No interaction collector
- **WHEN** the widget is configured in the portal
- **THEN** `humanInteractionSignature` is `false`

### Requirement: Rate limiting stays with the infrastructure
The challenge MUST NOT count requests per address or per player and MUST NOT add an application rate limiter. The edge
limits of sign-in and the write routes stay the operator's responsibility.

#### Scenario: Many requests from one address
- **WHEN** one address sends many challenge requests
- **THEN** every request is answered by the challenge rules alone, with no application-level limit
