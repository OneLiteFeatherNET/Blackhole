## ADDED Requirements

### Requirement: Authenticated read-only list of listed punishments

The backend SHALL expose `GET /public/bans` as a read-only route that lists the punishments that are on the ban list. It SHALL answer only a request that passes the Basic Auth check of the named ban-list client requirement, SHALL NOT answer any request without it, and SHALL use the cursor envelope `{items, nextCursor}` with the query parameters `limit` and `cursor` of the pagination change.

#### Scenario: Authenticated client gets the list

- **WHEN** a configured ban-list client sends valid Basic credentials to `GET /public/bans`
- **THEN** the response is HTTP 200 with `items` and `nextCursor`

#### Scenario: Next page follows the cursor

- **WHEN** more listed entries exist than the limit and the client follows `nextCursor`
- **THEN** the next page starts after the last entry of the previous page, with no entry repeated or skipped

#### Scenario: Limit out of range

- **WHEN** an authenticated client requests `limit=0` or `limit=500`
- **THEN** the response is HTTP 400 with an `errors` entry for `limit`

#### Scenario: Tampered cursor

- **WHEN** an authenticated client changes one character of a cursor
- **THEN** the response is HTTP 400 with code `INVALID_CURSOR`

### Requirement: Named ban-list clients with Basic Auth

The backend SHALL authenticate the ban-list routes with HTTP Basic Auth (RFC 7617) in a chain separate from the Entra JWT chain. A client SHALL be a name listed in `blackhole.ban-list.clients`, and its password SHALL be checked against an Argon2id PHC hash in the file `<blackhole.ban-list.secrets-dir>/<name>`. Passwords and hashes SHALL NOT appear in configuration, environment variables, images or logs.

#### Scenario: Valid credentials

- **WHEN** a configured client sends its correct password
- **THEN** the request is authenticated with that client name as its principal

#### Scenario: Client without a hash file outside dev

- **WHEN** the backend starts outside `dev` and `test` and a configured client has no hash file
- **THEN** startup fails and the message names the client and the file path without printing any hash

#### Scenario: Hash file in dev is skipped with a warning

- **WHEN** the backend starts in `dev` and a configured client has no hash file
- **THEN** that client is not authenticated and a warning names the client

#### Scenario: Password rotation by restart

- **WHEN** the hash file of a client is replaced with a new hash and the backend restarts
- **THEN** the old password is refused and the new password is accepted

### Requirement: Uniform 401 for every failed authentication

A request to the ban-list routes that is not authenticated SHALL be answered HTTP 401 with `WWW-Authenticate: Basic realm="blackhole-ban-list", charset="UTF-8"` and an RFC 9457 body with code `UNAUTHORIZED`. Every failure cause SHALL produce the same status, headers and body, and the body SHALL NOT echo the client name or the password.

#### Scenario: Missing Authorization header

- **WHEN** a request to `GET /public/bans` carries no `Authorization` header
- **THEN** the response is HTTP 401 with the `WWW-Authenticate` header above

#### Scenario: Unknown client and wrong password look the same

- **WHEN** one request uses an unknown client name and another uses a known name with a wrong password
- **THEN** both responses are HTTP 401 with identical headers and identical bodies

#### Scenario: Bearer token is not accepted

- **WHEN** a request to a ban-list route carries a valid Entra access token and no Basic credentials
- **THEN** the response is HTTP 401 with the Basic challenge

#### Scenario: Unknown hash without credentials

- **WHEN** a request for `GET /public/bans/{hash}` of an unknown hash carries no credentials
- **THEN** the response is HTTP 401, not 404, so the endpoint does not reveal which hashes exist

#### Scenario: Unknown name still runs the password check

- **WHEN** a request uses an unknown client name
- **THEN** the password verifier is invoked once against a fixed dummy hash, and the derived value is compared in constant time

### Requirement: Basic Auth over HTTPS only

Basic credentials SHALL NOT be evaluated over plain HTTP. A request to a ban-list route that arrives over plain HTTP SHALL be answered HTTP 403 with code `HTTPS_REQUIRED` and no `WWW-Authenticate` header, whatever credentials it carries. The scheme SHALL be read from `X-Forwarded-Proto` only when the request comes from the configured ingress.

#### Scenario: Plain HTTP with valid credentials

- **WHEN** a configured client sends its correct password over plain HTTP
- **THEN** the response is HTTP 403 with code `HTTPS_REQUIRED` and the password is not checked

#### Scenario: HTTPS behind the ingress

- **WHEN** the ingress forwards the request with `X-Forwarded-Proto: https`
- **THEN** the credentials are evaluated normally

### Requirement: Client attribution without credential leaks

Every request to a ban-list route SHALL be attributed to its client in the access log and in the metric `blackhole_ban_list_requests_total{client, route, status}`. A failed attempt SHALL NOT log the submitted client name. The `Authorization` header and any password SHALL NOT appear in any log line or metric label at any level.

#### Scenario: Successful request is attributed

- **WHEN** the client `website` reads `GET /public/bans`
- **THEN** the access log line contains `client=website` and the metric counts one request for `client="website"`

#### Scenario: Failed attempt does not log the username

- **WHEN** a request with an unknown client name fails
- **THEN** the log line reports `client=unauthenticated` with reason `unknown-client` and does not contain the submitted name

#### Scenario: No credential in logs

- **WHEN** a request with valid credentials is logged at debug level
- **THEN** no log line contains the `Authorization` header or the password

### Requirement: Entries carry no player reference

Every entry SHALL carry only the fields `hash`, `reason`, `kind`, `status`, `issuedOn`, `permanent`, `expiresOn` and `endedOn`. No entry, list, summary or error SHALL contain a player name, a player UUID, a hash of a UUID, a Ban ID, a punishment identifier or reference, a staff or actor identifier, an owner hash, an IP address, a section key, a scope, an ELO value, evidence, the name of a client, or an exact instant.

#### Scenario: Entry has only the allowed fields

- **WHEN** any entry is read through the list or the single read
- **THEN** its JSON object has exactly the fields `hash`, `reason`, `kind`, `status`, `issuedOn`, `permanent`, `expiresOn` and `endedOn`

#### Scenario: No identifier leaks in the page

- **WHEN** the list of a database that contains a punishment with a known Ban ID, reference and owner hash is read
- **THEN** none of those values appears in the body, in the `Link` header or in the cursor (the cursor decodes to a day and a hash only)

### Requirement: Listing is opt-in per template and excludes kicks

A punishment SHALL be listed only when it is of kind `PUNISHMENT`, its template has a non-blank `publicReason`, and its section resolves to a section kind. The public reason and the section kind SHALL be copied onto the punishment when it is applied. Kicks, manual punishments without a template, and punishments whose template has no public reason SHALL NOT be listed.

#### Scenario: Template with a public reason

- **WHEN** a template with `publicReason` "Cheating in competitive modes" is applied to a player in the network section
- **THEN** the punishment is listed with reason "Cheating in competitive modes" and kind `NETWORK_BAN`

#### Scenario: Template without a public reason

- **WHEN** a template without `publicReason` is applied
- **THEN** the punishment is not in the list and its hash is not computed

#### Scenario: Kick is never listed

- **WHEN** a kick of kind `KICK` is recorded, even from a template with a public reason
- **THEN** no entry is created for it

#### Scenario: Later template edit does not change a listed entry

- **WHEN** the public reason of the template is changed after the punishment was applied
- **THEN** the listed entry keeps the reason copied at apply time

### Requirement: Stable entry hash that cannot be linked to a Ban ID

Each listed punishment SHALL have an entry hash computed once as the first 16 bytes of HMAC-SHA256 under a key derived from `BLACKHOLE_PUBLIC_BAN_KEY` with a label of its own, over the internal punishment identifier, encoded as 22 base64url characters. The key SHALL be separate from `BLACKHOLE_BANID_KEY` and from `BLACKHOLE_CURSOR_KEY`. The backend SHALL refuse to start without `BLACKHOLE_PUBLIC_BAN_KEY` outside the `dev` and `test` environments.

#### Scenario: Hash is stable

- **WHEN** the same entry is read twice, including after a restart
- **THEN** its `hash` is the same both times

#### Scenario: Hash does not depend on the Ban ID key

- **WHEN** `BLACKHOLE_BANID_KEY` changes and `BLACKHOLE_PUBLIC_BAN_KEY` does not
- **THEN** every entry hash is unchanged

#### Scenario: Missing key outside dev

- **WHEN** the backend starts in production without `BLACKHOLE_PUBLIC_BAN_KEY`
- **THEN** it refuses to start and names the missing variable without printing a key

### Requirement: Dates are rounded to the UTC day

Every date in an entry and in the summary SHALL be a calendar day in UTC (`YYYY-MM-DD`). No response, `Link` header or cursor SHALL contain an exact instant of a punishment, and the list SHALL sort entries by day and then by hash.

#### Scenario: Entry issued late in the day

- **WHEN** a punishment is applied at 23:59:59 UTC on 2026-11-02
- **THEN** its `issuedOn` is `2026-11-02` and no time of day is returned

#### Scenario: Stable order within a day

- **WHEN** two entries were issued on the same day
- **THEN** they appear in the order of their hash, descending, on every request

### Requirement: Entry status derived on read

Each entry SHALL have a status derived on read from the stored listing fields and the clock: `REVOKED` when the punishment was revoked, `EXPIRED` when it was not revoked and its expiry instant has passed, and `ACTIVE` otherwise. A permanent entry SHALL be `ACTIVE` until it is revoked. The status SHALL NOT depend on whether an expiry sweep has run.

#### Scenario: Expired entry before the sweep

- **WHEN** a punishment expired an hour ago and no profile read has cleared it yet
- **THEN** its entry has status `EXPIRED` and an `endedOn` equal to the day of the expiry

#### Scenario: Revoked entry shows no reason

- **WHEN** a punishment is revoked before its expiry
- **THEN** its entry has status `REVOKED`, and nothing in the entry says why it was revoked

### Requirement: Retention window for ended entries

An entry SHALL stay in the list while it is `ACTIVE`, and after it ends for `blackhole.public-bans.retention` (`BLACKHOLE_PUBLIC_BANS_RETENTION`, default `P90D`), counted from `public_revoked_at`, or from `public_expires_at` when not revoked. After that it SHALL NOT be listed, counted, or answered by its hash.

#### Scenario: Entry leaves the list after the window

- **WHEN** an entry ended more than the retention window ago
- **THEN** it is not in the list, not in the summary, and its hash answers HTTP 404 to an authenticated client

### Requirement: Single entry read and uniform not-found

`GET /public/bans/{hash}` SHALL return one entry to an authenticated client. A malformed hash, an unknown hash and a hash of an entry outside the retention window SHALL all answer HTTP 404 with the same problem body.

#### Scenario: Known entry

- **WHEN** an authenticated client requests the hash of a listed entry
- **THEN** the response is HTTP 200 with that entry

#### Scenario: Unknown and malformed hashes look the same

- **WHEN** an authenticated client requests a hash that is unknown, and another that is not 22 base64url characters
- **THEN** both responses are HTTP 404 with identical bodies

### Requirement: Summary per public reason with suppression

`GET /public/bans/summary` SHALL return, to an authenticated client, for each public reason and kind that has at least one listed entry in the retention window, the number of ACTIVE entries and the total number of listed entries. A row whose total is below `blackhole.public-bans.summary-min-count` (default 5) SHALL return `active` and `total` as `null` with `suppressed` true.

#### Scenario: Count above the threshold

- **WHEN** eight entries share a reason and kind and five of them are ACTIVE
- **THEN** that row returns `active` 5 and `total` 8

#### Scenario: Count below the threshold

- **WHEN** two entries share a reason and kind
- **THEN** that row returns `active` and `total` as `null` and `suppressed` true, and its reason is still listed

#### Scenario: Summary matches the list

- **WHEN** the summary and the full list are read in the same window
- **THEN** the totals of the summary equal the number of listed entries for the same reason and kind

### Requirement: No-store on every answer

Every answer of `GET /public/bans`, `GET /public/bans/{hash}` and `GET /public/bans/summary`, success or error, SHALL carry `Cache-Control: private, no-store`. The answers SHALL carry no `ETag`.

#### Scenario: Successful list is not stored

- **WHEN** an authenticated client requests `GET /public/bans`
- **THEN** the response carries `Cache-Control: private, no-store`

#### Scenario: Unauthenticated answer is not stored

- **WHEN** a request without credentials is answered 401
- **THEN** the response carries `Cache-Control: private, no-store`

### Requirement: Erasure removes the entry

When the punishments of an owner are erased on a valid erasure request, the erasure SHALL clear the listing columns of those punishments, so that their entries leave the list at once and their hashes answer 404.

#### Scenario: Erased punishment disappears

- **WHEN** the owner of a listed punishment is erased
- **THEN** the punishment is no longer in the list, the summary no longer counts it, and its former hash answers 404

### Requirement: Listed entries carry no personal identifiers

Entries SHALL NOT carry an IP address, a raw UUID or a name, as `amend-data-protection-principle` requires for anything published. A public reason that contains an IP address, a UUID or a Ban ID SHALL be refused when the template is saved, with a validation error that does not echo the value.

#### Scenario: Reason with an IP address is refused

- **WHEN** a template is saved with a `publicReason` that contains an IPv4 address
- **THEN** the save is refused with a validation error and the address is not echoed in the response
