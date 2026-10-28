## ADDED Requirements

### Requirement: Anonymous read-only list of published punishments

The backend SHALL expose `GET /public/bans` as an anonymous, read-only route that lists published punishments. It SHALL be answered without a token or session, SHALL NOT be reachable with a staff, proxy or player session token to change anything, and SHALL use the cursor envelope `{items, nextCursor}` with the query parameters `limit` and `cursor` of the pagination change.

#### Scenario: Anonymous reader gets the list

- **WHEN** a caller without any token requests `GET /public/bans`
- **THEN** the response is HTTP 200 with `items` and `nextCursor`

#### Scenario: Next page follows the cursor

- **WHEN** more published entries exist than the limit and the caller follows `nextCursor`
- **THEN** the next page starts after the last entry of the previous page, with no entry repeated or skipped

#### Scenario: Limit out of range

- **WHEN** a caller requests `limit=0` or `limit=500`
- **THEN** the response is HTTP 400 with an `errors` entry for `limit`

#### Scenario: Tampered cursor

- **WHEN** a caller changes one character of a cursor
- **THEN** the response is HTTP 400 with code `INVALID_CURSOR`

### Requirement: Entries carry no player reference

Every entry SHALL carry only the fields `hash`, `reason`, `kind`, `status`, `issuedOn`, `permanent`, `expiresOn` and `endedOn`. No entry, list, summary or error SHALL contain a player name, a player UUID, a hash of a UUID, a Ban ID, a punishment identifier or reference, a staff or actor identifier, an owner hash, an IP address, a section key, a scope, an ELO value, evidence, or an exact instant.

#### Scenario: Entry has only the allowed fields

- **WHEN** any entry is read through the list or the single read
- **THEN** its JSON object has exactly the fields `hash`, `reason`, `kind`, `status`, `issuedOn`, `permanent`, `expiresOn` and `endedOn`

#### Scenario: No identifier leaks in the page

- **WHEN** the list of a database that contains a punishment with a known Ban ID, reference and owner hash is read
- **THEN** none of those values appears in the body, in the `Link` header or in the cursor (the cursor decodes to a day and a hash only)

### Requirement: Publication is opt-in per template and excludes kicks

A punishment SHALL be published only when it is of kind `PUNISHMENT`, its template has a non-blank `publicReason`, and its section resolves to a section kind. The public reason and the section kind SHALL be copied onto the punishment when it is applied. Kicks, manual punishments without a template, and punishments whose template has no public reason SHALL NOT be published.

#### Scenario: Template with a public reason

- **WHEN** a template with `publicReason` "Cheating in competitive modes" is applied to a player in the network section
- **THEN** the punishment is published with reason "Cheating in competitive modes" and kind `NETWORK_BAN`

#### Scenario: Template without a public reason

- **WHEN** a template without `publicReason` is applied
- **THEN** the punishment is not in the list and its hash is not computed

#### Scenario: Kick is never published

- **WHEN** a kick of kind `KICK` is recorded, even from a template with a public reason
- **THEN** no entry is created for it

#### Scenario: Later template edit does not change a published entry

- **WHEN** the public reason of the template is changed after the punishment was applied
- **THEN** the published entry keeps the reason copied at apply time

### Requirement: Stable entry hash that cannot be linked to a Ban ID

Each published punishment SHALL have an entry hash computed once as the first 16 bytes of HMAC-SHA256 under a key derived from `BLACKHOLE_PUBLIC_BAN_KEY` with a label of its own, over the internal punishment identifier, encoded as 22 base64url characters. The key SHALL be separate from `BLACKHOLE_BANID_KEY` and from `BLACKHOLE_CURSOR_KEY`. The backend SHALL refuse to start without `BLACKHOLE_PUBLIC_BAN_KEY` outside the `dev` and `test` environments.

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

Each entry SHALL have a status derived on read from the stored publication fields and the clock: `REVOKED` when the punishment was revoked, `EXPIRED` when it was not revoked and its expiry instant has passed, and `ACTIVE` otherwise. A permanent entry SHALL be `ACTIVE` until it is revoked. The status SHALL NOT depend on whether an expiry sweep has run.

#### Scenario: Expired entry before the sweep

- **WHEN** a punishment expired an hour ago and no profile read has cleared it yet
- **THEN** its entry has status `EXPIRED` and an `endedOn` equal to the day of the expiry

#### Scenario: Revoked entry shows no reason

- **WHEN** a punishment is revoked before its expiry
- **THEN** its entry has status `REVOKED`, and nothing in the entry says why it was revoked

### Requirement: Retention window for ended entries

An entry SHALL be listed while it is `ACTIVE`, or until the retention window `blackhole.public-bans.retention` has passed since it ended. The end is the revoke instant, or the expiry instant when the entry was not revoked. After the window the entry SHALL NOT be listed, and its hash SHALL answer 404.

#### Scenario: Entry leaves the list after the window

- **WHEN** an entry ended more than the retention window ago
- **THEN** it is absent from the list, and `GET /public/bans/{hash}` answers 404

### Requirement: Single entry read and uniform not-found

`GET /public/bans/{hash}` SHALL return one entry. A malformed hash, an unknown hash and a hash of an entry outside the retention window SHALL all answer HTTP 404 with the same problem body.

#### Scenario: Known entry

- **WHEN** a caller requests the hash of a listed entry
- **THEN** the response is HTTP 200 with that entry

#### Scenario: Unknown and malformed hashes look the same

- **WHEN** a caller requests a hash that is unknown, and another that is not 22 base64url characters
- **THEN** both responses are HTTP 404 with identical bodies

### Requirement: Summary per public reason with suppression

`GET /public/bans/summary` SHALL return, for each public reason and kind that has at least one published entry in the retention window, the number of ACTIVE entries and the total number of listed entries. A row whose total is below `blackhole.public-bans.summary-min-count` (default 5) SHALL return `active` and `total` as `null` with `suppressed` true.

#### Scenario: Count above the threshold

- **WHEN** eight entries share a reason and kind and five of them are ACTIVE
- **THEN** that row returns `active` 5 and `total` 8

#### Scenario: Count below the threshold

- **WHEN** two entries share a reason and kind
- **THEN** that row returns `active` and `total` as `null` and `suppressed` true, and its reason is still listed

#### Scenario: Summary matches the list

- **WHEN** the summary and the full list are read in the same window
- **THEN** the totals of the summary equal the number of listed entries for the same reason and kind

### Requirement: Cache headers on public ban answers

The list, the single read and the summary SHALL answer `Cache-Control: public, max-age=<blackhole.public-bans.max-age>, stale-while-revalidate=300` with `Vary: Accept` and a strong `ETag` over the body. A request with a matching `If-None-Match` SHALL be answered HTTP 304 with no body. Error answers SHALL keep `Cache-Control: no-store`. The list SHALL be the only public route family that does not answer `no-store`.

#### Scenario: Cacheable list

- **WHEN** a caller requests `GET /public/bans`
- **THEN** the response carries `Cache-Control` with `public` and a `max-age` of 60 seconds by default

#### Scenario: Conditional request

- **WHEN** a caller repeats the request with the `ETag` it received and nothing changed
- **THEN** the response is HTTP 304 with no body

#### Scenario: Error stays uncached

- **WHEN** a request for an unknown hash is answered 404
- **THEN** the response carries `Cache-Control: no-store`

### Requirement: Erasure removes the entry

When the punishments of an owner are erased on a valid erasure request, the erasure SHALL clear the publication columns of those punishments, so that their entries leave the list at once and their hashes answer 404.

#### Scenario: Erased punishment disappears

- **WHEN** the owner of a published punishment is erased
- **THEN** the punishment is no longer in the list, the summary no longer counts it, and its former hash answers 404

### Requirement: Portal page for the public list

The portal SHALL serve a `/bans` page that shows the summary, the list with a kind and status filter, and a "load more" control that follows `nextCursor`. The page SHALL offer no search by name, UUID or Ban ID.

#### Scenario: Page loads without a session

- **WHEN** a visitor without a portal session opens `/bans`
- **THEN** the page shows the summary and the first page of entries

#### Scenario: Load more

- **WHEN** the visitor selects "load more" and the first page had a `nextCursor`
- **THEN** the next page is appended and the control disappears when `nextCursor` is `null`

### Requirement: Portal BFF relays the public list only

The portal backend-for-frontend SHALL read the public list only through `/api/bans`, `/api/bans/summary` and `/api/bans/:hash`, which map to the three public routes with no session and no cookie forwarded. It SHALL relay the backend's cache headers on these three routes and SHALL keep `no-store` on every other route.

#### Scenario: Public list relayed with cache headers

- **WHEN** a visitor requests `/api/bans`
- **THEN** the BFF forwards the request without a cookie and returns the backend's `Cache-Control` and `ETag`

#### Scenario: Other BFF routes stay uncached

- **WHEN** a session route of the BFF answers
- **THEN** its `Cache-Control` is `no-store`

### Requirement: Published entries carry no personal identifiers

Entries are published data under `amend-data-protection-principle`: they SHALL NOT carry an IP address, a raw UUID or a name. A public reason that contains an IP address, a UUID or a Ban ID SHALL be refused when the template is saved, with a validation error that does not echo the value.

#### Scenario: Reason with an IP address is refused

- **WHEN** a template is saved with a `publicReason` that contains an IPv4 address
- **THEN** the save is refused with a validation error and the address is not echoed in the response
