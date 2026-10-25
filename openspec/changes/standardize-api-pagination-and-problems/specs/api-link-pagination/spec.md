## ADDED Requirements

### Requirement: Next-page Link header on every paginated list
Every list response whose `nextCursor` is non-null SHALL also carry the HTTP header
`Link: <URL>; rel="next"` (RFC 8288). The URL SHALL be the request path with the same query
parameters as the request, `cursor` replaced by the new `nextCursor` value, and every other
parameter (including `limit` and all filters) kept with its value. The body envelope
`{items, nextCursor}` SHALL remain unchanged. When `nextCursor` is `null`, the response SHALL NOT
carry a `Link` header.

#### Scenario: Header on a page with a successor
- **WHEN** a client requests `GET /punishment?limit=10&includeInactive=true` and more rows remain
- **THEN** the response has `Link: </punishment?limit=10&includeInactive=true&cursor=<nextCursor>>; rel="next"` and the body `nextCursor` equals the cursor in the header

#### Scenario: No header on the last page
- **WHEN** a client requests a list route and no rows remain after the page
- **THEN** the body `nextCursor` is `null` and the response has no `Link` header

#### Scenario: Filters survive into the link
- **WHEN** a client requests `GET /case/{reference}/timeline?limit=20` and a successor exists
- **THEN** the `Link` target keeps `limit=20` and no parameter is dropped or reordered in value

#### Scenario: The link leaks no identifier
- **WHEN** a `Link` header is produced for any list route
- **THEN** the header contains no Ban ID, raw player UUID or IP address, checked by the no-PII test on a fixed fixture
