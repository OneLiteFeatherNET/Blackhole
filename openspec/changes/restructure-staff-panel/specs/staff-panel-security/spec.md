## ADDED Requirements

### Requirement: Redirects only to allow-listed routes
The panel SHALL follow a redirect target (an old URL or the return path after sign-in) only when it is a same-origin
relative path that resolves to a route name in the router's allow-list. Any other target SHALL be ignored, and the default
route SHALL be used instead. The check SHALL parse the target against the panel origin and SHALL reject backslashes and
control characters. Standard: CWE-601 (open redirect).

#### Scenario: Absolute target ignored
- **WHEN** the return path is `https://evil.example/cases`
- **THEN** it is ignored and the Inbox opens

#### Scenario: Protocol-relative target ignored
- **WHEN** the return path is `//evil.example` or `/\evil.example`
- **THEN** it is ignored and the Inbox opens

#### Scenario: Allow-listed route followed
- **WHEN** the return path is `/cases/A1B2C3D4?tab=conversation`
- **THEN** the case opens on that tab

#### Scenario: Route outside the allow-list ignored
- **WHEN** the return path names a route that is not in the allow-list, such as `/auth/callback`
- **THEN** it is ignored and the Inbox opens

### Requirement: Every read checks the role on each request
Every deep-link route SHALL load its data only through endpoints that check the caller's role on each request. The URL,
the route guard and the page SHALL NOT be the authorization. Every read-by-id endpoint added by the companion change SHALL
answer 401 without a valid token and 403 for a role below its minimum, and each SHALL have a test for both answers.
Standard: OWASP API Security Top 10 2023, API1 (Broken Object Level Authorization) and API5 (Broken Function Level Authorization).

#### Scenario: Viewer opens an admin address
- **WHEN** a VIEWER opens `/admin/staff/:oid`
- **THEN** the page shows the no-access view and sends no request for staff data

#### Scenario: No token
- **WHEN** a request to `GET /admin/staff-actor/{oid}` carries no token
- **THEN** the answer is 401

#### Scenario: Role below the minimum
- **WHEN** a MODERATOR sends `GET /admin/staff-actor/{oid}`
- **THEN** the answer is 403

#### Scenario: Object of another staff member
- **WHEN** a MODERATOR sends `GET /exemption/reviews/{id}` for a review that the role may not read
- **THEN** the answer is 403 and no body with the review is returned

### Requirement: Short ids only behind sign-in
Short ids (for example `#A1B2C3D4`, 32 bits) SHALL appear only in staff URLs that require sign-in. A short id is a lookup
key and SHALL NOT grant access: the route it opens SHALL pass the role check of the requirement above. Public and portal
URLs SHALL NOT carry a staff short id, a case reference or any other staff-only identifier.

#### Scenario: Signed-out visitor opens a case address
- **WHEN** a signed-out visitor opens `/cases/A1B2C3D4`
- **THEN** sign-in starts, and the case is shown only after sign-in and only if the role allows it

#### Scenario: Public ban list links
- **WHEN** the public ban list renders its links
- **THEN** no link contains `/cases/`, `/punished/` or any other staff route

### Requirement: No action runs on navigation
A route that changes state SHALL open a confirmation and SHALL NOT send a request when it is opened, reloaded or
navigated to. The confirmed request SHALL carry the version that was read, as `expectedVersion` or as `If-Match`. A
stale version SHALL be refused. Standard: CWE-352 and RFC 9110, section 13.1.

#### Scenario: Opening an action address changes nothing
- **WHEN** a moderator opens `/cases/A1B2C3D4/close`, `/reports/:id/resolve`, `/punished/:shortId/revoke`,
  `/area-bans/:id/lift`, `/admin/staff/:oid/anonymize` or `/admin/rollback/:id/cancel`
- **THEN** no request is sent and a confirmation is shown

#### Scenario: Confirmed close carries the version
- **WHEN** the moderator confirms the close
- **THEN** the update carries the `expectedVersion` read with the case

#### Scenario: Stale version refused
- **WHEN** the case changed after it was read and the confirmed update is sent
- **THEN** the server answers 409, the panel offers a reload, and the case is not overwritten

#### Scenario: Resource without a version
- **WHEN** an action has no version field on its endpoint
- **THEN** it still needs a confirmation, and the missing version is listed in `design.md` decision 8 as a known gap

### Requirement: No secret, Ban ID, name, raw UUID or IP in an address
No URL, query or fragment SHALL contain a secret, a Ban ID, a player name, a raw player UUID, a Minecraft UUID, an IP
address, a Discord user id or a feed token. Standard: CWE-598 (sensitive information in the query string), and GDPR principle 1
of `openspec/config.yaml`. The route table SHALL NOT have a parameter named for any of these. A test SHALL fail when a route
with such a parameter is added.

#### Scenario: Route table test
- **WHEN** the route table is tested
- **THEN** no parameter is named `uuid`, `name`, `ip`, `token`, `ban`, `banId` or `secret`

#### Scenario: Name typed in the lookup
- **WHEN** a moderator types a player name and presses Enter
- **THEN** the address holds the owner hash and not the name

#### Scenario: Copied link
- **WHEN** a moderator copies the link of a case
- **THEN** the copied address holds no Ban ID

### Requirement: Referrer policy on every panel response
Every panel response SHALL send `Referrer-Policy: no-referrer`, as `web/server/lib/headers.ts` already does. The security
header test SHALL cover every route that the router defines, so that a new route cannot leave the header out.
Standard: OWASP ASVS Level 2, V14 (configuration).

#### Scenario: Every route sends the header
- **WHEN** the security header test requests each route of the router
- **THEN** every response carries `referrer-policy: no-referrer`

### Requirement: ASVS Level 2 review for every phase
Every phase pull request SHALL state in its description that it was checked against OWASP ASVS Level 2 for the items it
touches: input validation (V5), access control (V4), session and authentication (V2, V3), error handling and logging
without personal data (V7). The review SHALL be recorded as one line in `tasks.md` for that phase.

#### Scenario: Phase PR without the review
- **WHEN** a phase pull request is opened without the ASVS review in its description
- **THEN** it is not merged
