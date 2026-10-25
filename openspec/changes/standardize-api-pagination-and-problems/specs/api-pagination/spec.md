## ADDED Requirements

### Requirement: Every list route uses the cursor envelope
Every route that returns a list (the 37 routes in `design.md` D10) SHALL accept the query parameters `limit` and `cursor` and SHALL return a JSON object with the members `items` (the list) and `nextCursor` (an opaque string, or `null` on the last page). The framework `page`, `size`, `sort` and `totalPages` parameters and members SHALL NOT be used by any list route.

#### Scenario: Last page
- **WHEN** a client requests a list route and fewer rows remain than the limit
- **THEN** `nextCursor` is `null`

#### Scenario: Next page
- **WHEN** a client requests a list route and more rows remain than the limit
- **THEN** `nextCursor` is a non-empty string and passing it as `cursor` returns the following rows without repeating or skipping any row

#### Scenario: Routes with a single page
- **WHEN** a list route whose result is bounded by its contract (for example the `GET /auth/me` roles) is requested
- **THEN** it returns `items` with `nextCursor` `null`

### Requirement: Limit defaults and bounds
The `limit` parameter SHALL default to 50 and SHALL accept values from 1 to 200 inclusive. A value outside that range SHALL be answered with HTTP 400 and a problem with an `errors` entry for `limit`. The server SHALL NOT clamp a value silently.

#### Scenario: Default limit
- **WHEN** a list route is requested without `limit`
- **THEN** at most 50 items are returned

#### Scenario: Limit above the maximum
- **WHEN** a client requests `limit=500`
- **THEN** the response is HTTP 400 with an `errors` entry for `limit`

### Requirement: Opaque signed cursors
A `nextCursor` SHALL be an opaque signed token: base64url of the sort-key values, a `.`, then base64url of an HMAC-SHA256 with the key `BLACKHOLE_CURSOR_KEY`. Sort keys SHALL NOT contain a Ban ID, raw player UUID, IP address or internal surrogate id. A cursor that fails verification or decoding SHALL be answered with HTTP 400 and code `INVALID_CURSOR`, never treated as the first page.

#### Scenario: Tampered cursor
- **WHEN** a client alters one character of a `nextCursor` and requests the next page
- **THEN** the response is HTTP 400 with `code` `INVALID_CURSOR`

#### Scenario: Cursor carries no identifier that leaks
- **WHEN** a `nextCursor` is decoded for a punishment list
- **THEN** its payload contains the sort-key timestamp and the public punishment reference only

### Requirement: Server-side filters and sorting
Filters and sort order SHALL run on the server. A list route SHALL NOT return an unbounded list. The sort order of each route SHALL be the one in `design.md` D10, with the public item identifier as the final tiebreaker so that pages are stable.

#### Scenario: Stable pages
- **WHEN** two rows have the same primary sort value
- **THEN** they appear in the same order on every page request and neither is repeated nor skipped

### Requirement: Total counts only on count routes
A total count SHALL be offered only through a separate count route, and only for a route whose consumer asks for it. A list response SHALL NOT carry a `total` or `totalPages` member.

#### Scenario: List has no total
- **WHEN** any list route is requested
- **THEN** the body has no `total` or `totalPages` member

### Requirement: Existing cursors keep their semantics
The routes that already paginate with a cursor (`GET /case/{reference}/timeline`, `GET /discord/tickets/{id}/outbox`, `GET /public/conversation`) SHALL keep their sort key (`seq`) and ordering, and SHALL use the same envelope and the `limit` and `cursor` parameter names. The old names `afterSeq` and `size` SHALL NOT be accepted.

#### Scenario: Timeline cursor continues
- **WHEN** a client follows `nextCursor` from `GET /case/{reference}/timeline`
- **THEN** the next page starts at the event after the last one returned, in `seq` order
