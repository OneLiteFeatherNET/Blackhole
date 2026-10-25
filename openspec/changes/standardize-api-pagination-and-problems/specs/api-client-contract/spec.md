## ADDED Requirements

### Requirement: One API spec version for every client
The generated Java client (`client/`), the web UI types, the portal BFF and the Discord and Velocity plugins SHALL all be built from the same OpenAPI spec version, `client/specs/blackhole-api-0.0.41.yml` (or the next free version at implementation time). The spec SHALL document the `Problem` schema once, the `Limit` and `Cursor` parameters and the `CursorPage` schema for every list operation, and SHALL include `POST /admin/import/vanilla`.

#### Scenario: Spec and clients agree
- **WHEN** the client is regenerated from the spec
- **THEN** every list operation returns a `CursorPage` type and no list operation returns a `Page` type

### Requirement: Clients read problem bodies by code
Every client SHALL decide error handling by HTTP status and by the problem extension member `code`, never by the `title` or `detail` text. The web UI, the portal BFF and the Discord bot SHALL accept `application/problem+json` as a JSON error body. A non-JSON error body SHALL still be treated as an outage (HTTP 503 from the portal BFF), so a content-type mismatch never turns an error into silent success.

#### Scenario: Portal maps a conflict
- **WHEN** a player appeal returns HTTP 409 with `code` `NOT_ELIGIBLE` and `availableFrom`
- **THEN** the portal BFF relays the code and the ISO `availableFrom` to the browser unchanged

#### Scenario: Discord reads the transcript mismatch
- **WHEN** the backend answers a relay with HTTP 409 and `code` `TRANSCRIPT_MISMATCH`
- **THEN** the Discord bot reads `backendCount` and `backendLastMessageId` and runs its rescan

### Requirement: Velocity keeps its status-based branches
The Velocity plugin SHALL keep branching on HTTP status and generated `ApiException.getCode()` as it does today, and every status code SHALL stay the same. Velocity SHALL read every page of a list route through `nextCursor` (including templates, which previously read page 0 only). Velocity SHALL be released in the same release as the backend, because a plugin built against the previous client cannot read the new list envelope.

#### Scenario: Template list is complete
- **WHEN** the backend has more templates than fit on one page
- **THEN** Velocity loads all of them before it applies a template

#### Scenario: Status branch is unchanged
- **WHEN** the backend answers an unknown client rule with HTTP 404
- **THEN** Velocity treats the rule as absent exactly as it does today
