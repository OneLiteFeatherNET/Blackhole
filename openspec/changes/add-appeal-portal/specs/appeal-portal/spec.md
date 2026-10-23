# Spec Delta

## Purpose
Lets a punished player sign in with the Ban ID alone, see the facts of their punishment, file one appeal, talk to the team and read the decision, through a separate public portal app.

## ADDED Requirements

### Requirement: Login with the Ban ID only
`POST /public/session` SHALL accept a Ban ID in the request body and, when it matches a punishment, create a player session and return its token once. A wrong, unknown, malformed or not-yet-issued Ban ID MUST all answer HTTP 401 `INVALID_BAN_ID` with an identical body and no detail, and the Ban ID MUST NOT appear in a URL.

#### Scenario: Good login
- **WHEN** a player posts the Ban ID of an existing punishment
- **THEN** the response is HTTP 200 with a session token, a CSRF value, the expiry and the scope

#### Scenario: Uniform failure
- **WHEN** a Ban ID is unknown, and another has the wrong length
- **THEN** both answers are HTTP 401 with byte-identical bodies

#### Scenario: Failures are counted for alerting only
- **WHEN** a login fails
- **THEN** the counter `blackhole.public.login{result=failure}` increases and the request is not delayed or blocked by the application

### Requirement: Player session
A player session SHALL be an opaque random token of at least 256 bits whose SHA-256 is stored with the punishment, a scope (`FULL` or `LIMITED`), a CSRF value and times. It MUST expire after 30 minutes idle (sliding) and 4 hours absolute (`blackhole.public.session.*`), measured on the injected clock, and expired rows MUST be removed by a sweeper. A leak mark or a reissue MUST delete all sessions of the punishment.

#### Scenario: Idle expiry
- **WHEN** 31 minutes pass with no request
- **THEN** the next request is answered 401

#### Scenario: Activity extends the session
- **WHEN** a request arrives after 20 minutes and another after 40 minutes from login
- **THEN** both succeed until the absolute limit

#### Scenario: Absolute expiry
- **WHEN** 4 hours have passed since login
- **THEN** the session is refused even if it was used a minute ago

#### Scenario: Logout
- **WHEN** the player calls `DELETE /public/session`
- **THEN** the session row is deleted and the token no longer works

### Requirement: A session only reaches its own punishment
Every `/public/**` route except login SHALL take the punishment from the session and MUST NOT accept a punishment, appeal or case identifier from the request. A player session MUST NOT authorize any route outside `/public/**`, and a staff token MUST NOT authorize `/public/**` routes that need a session.

#### Scenario: Staff route with a player session
- **WHEN** a player token is sent to `GET /case`
- **THEN** the response is HTTP 401 or 403

#### Scenario: Another punishment's id in a parameter
- **WHEN** a player passes another appeal id in a query string
- **THEN** it is ignored and only the session's own data is returned

### Requirement: Public facts only
`GET /public/punishment` SHALL return an allow-listed view: type, reason, section name, creation time, permanent flag, expiry, state, the valid evidence count and the appeal summary. It MUST NOT return the owner hash, any staff name or oid, ELO values, notes, evidence content or any other punishment.

#### Scenario: Facts of the own ban
- **WHEN** a logged-in player reads their punishment
- **THEN** the response contains the listed fields and none of the excluded ones

### Requirement: Filing the appeal
`POST /public/appeal` SHALL file the appeal of the session's punishment with a statement of 1 to 2000 characters through the existing eligibility checklist, but MUST NOT record an attempt that is not eligible: it answers HTTP 409 with the reason and `availableFrom`. At most one appeal per punishment MAY be open, and after a `DENIED` decision a new appeal is possible only after `repeat-appeal-cooldown-days`.

#### Scenario: Eligible appeal
- **WHEN** a player files after the minimum waiting period
- **THEN** an appeal in status `ELIGIBLE_PENDING_REVIEW` exists and the case records `appeal.filed`

#### Scenario: Too early
- **WHEN** a player files before the minimum days have passed
- **THEN** the response is HTTP 409 with the date it becomes available and nothing is recorded

#### Scenario: Second appeal while one is open
- **WHEN** a player files again while an appeal is open
- **THEN** the response is HTTP 409 and no second appeal exists

#### Scenario: After a denial
- **WHEN** an appeal was denied 8 days ago with a 7 day cooldown
- **THEN** a new appeal can be filed and the conversation continues in the same thread

### Requirement: Messages
`POST /public/messages` SHALL add a `PLAYER`-visible message of 1 to 2000 characters to the case conversation while an appeal is open and the conversation is not frozen, rejecting a UUID, IP or Ban ID lookalike with HTTP 422. `GET /public/conversation` SHALL return only `PLAYER`-visible entries of the allow-listed types, paged by the `seq` cursor.

#### Scenario: Post and read back
- **WHEN** a player posts a message and then reads the conversation
- **THEN** the message appears once, in order, after the team's earlier replies

#### Scenario: Too long
- **WHEN** a message has 2001 characters
- **THEN** the response is HTTP 400 and nothing is stored

#### Scenario: PII lookalike
- **WHEN** a message contains an IP address
- **THEN** the response is HTTP 422 and nothing is stored

#### Scenario: No appeal yet
- **WHEN** a player posts a message before an appeal exists
- **THEN** the response is HTTP 409

#### Scenario: Staff-only rows stay hidden
- **WHEN** the case holds `STAFF`-visibility messages and notes
- **THEN** none appears in the player's conversation

#### Scenario: Double submit
- **WHEN** the same `Idempotency-Key` is sent twice
- **THEN** one message is stored

### Requirement: Decision visible to the player
`GET /public/appeal` SHALL return the appeal status, the decision and, for a grant, the new expiry. The team's message to the player SHALL be shown as a `PLAYER`-visible staff message; the internal `decisionNote` MUST never be returned.

#### Scenario: Denied with a message
- **WHEN** a moderator denies the appeal with a `playerMessage` and an internal note
- **THEN** the player sees `DENIED` and the message, and not the internal note

### Requirement: Leak rule in the portal
After a Ban ID is marked leaked, the portal SHALL show the appeal right as forfeited (no appeal yet) or the ticket as frozen (appeal filed), and MUST refuse the matching player writes with HTTP 409. A login with a leaked Ban ID SHALL give a `LIMITED` session that exposes the punishment facts and the notice only, never the conversation or the decision text.

#### Scenario: Forfeited before filing
- **WHEN** the Ban ID was marked leaked before any appeal
- **THEN** `POST /public/appeal` answers HTTP 409 `FORFEITED`

#### Scenario: Frozen after filing
- **WHEN** the Ban ID is marked leaked after an appeal was filed
- **THEN** `POST /public/messages` answers HTTP 409 `FROZEN` and staff can still reply and decide

#### Scenario: Leaked holder sees no statements
- **WHEN** someone logs in with a leaked Ban ID
- **THEN** the conversation route answers HTTP 403 and the facts route works

#### Scenario: Reissue restores the right
- **WHEN** an admin reissues the Ban ID after a leak that was not the player's fault
- **THEN** the new ID gives a `FULL` session and a frozen ticket accepts player messages again

### Requirement: Portal host
The portal SHALL be served at `appeal.onelitefeather.net`, which is also the value of `BLACKHOLE_PORTAL_ORIGIN`, the origin checked for state-changing requests and the host of the `__Host-` cookie; startup MUST fail when the origin is not a valid `https` origin outside development.

#### Scenario: Origin check
- **WHEN** a state-changing request carries an `Origin` other than the configured portal origin
- **THEN** the portal answers 403

#### Scenario: Invalid origin configuration
- **WHEN** the portal starts outside development with a non-https origin
- **THEN** it refuses to start

### Requirement: Player copy of the protocol
A player session with scope `FULL` SHALL be able to download the protocol of its decided appeal through `GET /public/appeal/protocol?format=text|json`, rendered on demand from the conversation the portal already shows. The response MUST be `no-store` and an attachment, and a `LIMITED` session MUST be refused.

#### Scenario: Player downloads
- **WHEN** a player with a decided appeal requests the text copy
- **THEN** it lists exactly the entries the portal conversation shows

#### Scenario: Not yet decided
- **WHEN** the appeal is still open
- **THEN** the route answers HTTP 409

#### Scenario: Leaked Ban ID
- **WHEN** a `LIMITED` session requests the copy
- **THEN** the response is HTTP 403

### Requirement: The player copy is player-visible only
The player copy SHALL use the same visibility filter and type allow-list as the portal conversation, label the player "You" and staff "Moderation team" without names, show attachments as type and size, and show redacted and deleted entries as removed. The stored staff protocol, its versions and `STAFF`-visibility entries MUST NOT be reachable from a player session.

#### Scenario: Staff-only entry
- **WHEN** the conversation contains a `STAFF`-visibility bot message
- **THEN** the player copy does not contain it

#### Scenario: No staff names
- **WHEN** a verified staff member replied
- **THEN** the player copy shows "Moderation team" and no name

### Requirement: Separate public portal application
The portal SHALL be a separate Nitro application `portal/` with its own origin, container and cookie, serving the player UI and a backend-for-frontend with an allow-list of exactly the player routes. It MUST NOT forward any other path to the backend, MUST hold the session token only in a cookie named `__Host-bh_portal` with `HttpOnly`, `Secure`, `SameSite=Lax` and `Path=/`, and MUST NOT log request bodies.

#### Scenario: Unknown path
- **WHEN** a browser requests `/api/case` on the portal
- **THEN** the portal answers 404 and sends nothing to the backend

#### Scenario: Cookie flags
- **WHEN** a login succeeds
- **THEN** the response sets the cookie with the required flags and the body contains no token

#### Scenario: CSRF
- **WHEN** a state-changing request lacks the CSRF header or has a foreign `Origin`
- **THEN** the portal answers 403 before calling the backend

#### Scenario: Session expired
- **WHEN** the backend answers 401 for a session
- **THEN** the portal clears the cookie and the UI shows the expiry page

### Requirement: Portal interface
The portal SHALL be available in German and English, keyboard operable and screen-reader accessible (landmarks, labelled fields, live-region errors, visible focus), SHALL render all user text as plain text, and MUST NOT accept attachments in this version.

#### Scenario: Language parity
- **WHEN** the i18n test runs
- **THEN** both languages contain the same keys

#### Scenario: Markup in a message
- **WHEN** a message contains `<b>` or a Markdown link
- **THEN** it is shown literally and nothing is linked

### Requirement: Rate limiting is an infrastructure requirement
The application MUST NOT implement rate limiting for the public routes. The deployment documentation SHALL state the required edge rules for the login and write routes.

#### Scenario: Documented
- **WHEN** the documentation is reviewed
- **THEN** it names the portal host, the login route and a starting limit
