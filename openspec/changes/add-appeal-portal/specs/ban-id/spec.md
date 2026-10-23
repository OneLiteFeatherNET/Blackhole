# Spec Delta

## Purpose
Defines the Ban ID: the 8 character credential a player uses to open the appeal portal for one punishment.

## ADDED Requirements

### Requirement: Ban ID format and uniqueness
Every punishment SHALL have exactly one current Ban ID of 8 characters from the Crockford base32 alphabet (`0-9`, `A-Z` without `I`, `L`, `O`, `U`), generated from a cryptographically secure random source. Ban IDs MUST be unique across all punishments, and a generated candidate that equals any legacy short id MUST be discarded and regenerated.

#### Scenario: Collision is retried
- **WHEN** a generated candidate already exists as a Ban ID
- **THEN** a new candidate is generated and the punishment receives a unique one

#### Scenario: Candidate equals a legacy short id
- **WHEN** a candidate equals the legacy short id of any punishment
- **THEN** it is discarded and regenerated

### Requirement: Input normalisation
The system SHALL normalise a typed Ban ID by removing whitespace and one leading `#`, upper-casing it, and mapping `O` to `0` and `I` or `L` to `1`. Input that is not exactly 8 alphabet characters afterwards MUST be treated as not matching any Ban ID.

#### Scenario: Typed with ambiguous characters
- **WHEN** a player types the Ban ID `#k7m2-o9` in lower case with an `o` for the zero
- **THEN** the normalised form is used for the lookup

#### Scenario: Wrong length
- **WHEN** the input has 7 characters after normalisation
- **THEN** it matches nothing

### Requirement: Storage never holds the plain Ban ID
The system SHALL store the Ban ID only as a keyed hash (HMAC-SHA256) for lookups and as an authenticated encryption (AES-256-GCM, bound to the punishment identifier) for display, both derived from the secret `BLACKHOLE_BANID_KEY`. A non-development start without that key MUST fail. The plain Ban ID SHALL exist only in memory.

#### Scenario: Database holds no plain text
- **WHEN** the punishment table is read directly after a Ban ID was issued
- **THEN** no column contains the Ban ID in clear

#### Scenario: Missing key
- **WHEN** the backend starts in a non-development context without `BLACKHOLE_BANID_KEY`
- **THEN** it refuses to start

### Requirement: The Ban ID is never logged or published
The system MUST NOT write a Ban ID to any log, metric label, trace attribute, URL, query string, domain event, timeline payload, note or error response, and MUST reject a note, message or statement that contains a Ban ID shaped token.

#### Scenario: Full flow leaves no trace
- **WHEN** a Ban ID is issued, revealed, used to log in and marked leaked in a test
- **THEN** the captured logs and published events do not contain it

#### Scenario: Staff types it into a note
- **WHEN** a note contains an existing Ban ID in `#XXXXXXXX` form
- **THEN** the note is rejected with HTTP 400 or 422

### Requirement: Reveal for staff and proxy
`GET /punishment/{reference}/ban-id` SHALL return the current Ban ID for `MODERATOR`, `ADMIN` and `PROXY`, with `Cache-Control: no-store`. `VIEWER` MUST get 403, and the Ban ID MUST NOT appear in any other response.

#### Scenario: Viewer cannot reveal
- **WHEN** a `VIEWER` calls the reveal route
- **THEN** the response is HTTP 403

#### Scenario: Proxy reveals for the ban screen
- **WHEN** the proxy calls the reveal route for an active ban
- **THEN** the response contains the Ban ID and is not cacheable

### Requirement: Legacy short ids stay for staff lookup
The hex short id of an existing punishment SHALL remain valid for the staff short id lookup, unchanged including the 409 for an ambiguous id, and MUST NOT be accepted as a portal credential. The staff lookup SHALL also accept a Ban ID, which never yields an ambiguous result.

#### Scenario: Legacy id at login
- **WHEN** a player types the legacy hex short id into the portal
- **THEN** the login is refused like any unknown Ban ID

#### Scenario: Ambiguous legacy id
- **WHEN** staff look up a legacy short id shared by two punishments
- **THEN** the response is HTTP 409 with the candidates as today

#### Scenario: Staff look up by Ban ID
- **WHEN** staff look up a punishment by its Ban ID
- **THEN** exactly that punishment is returned

### Requirement: Backfill of existing punishments
The system SHALL issue a Ban ID to every punishment without one by an idempotent background job at application start, in batches, and expose the number still missing as the gauge `blackhole.banid.missing`. A punishment without a Ban ID MUST behave as an unknown ID at login.

#### Scenario: Backfill is idempotent
- **WHEN** the job runs twice over the same data
- **THEN** every punishment has one Ban ID and none changed on the second run

#### Scenario: Not yet backfilled
- **WHEN** a punishment has no Ban ID yet
- **THEN** no login can succeed for it

### Requirement: Reissue
An `ADMIN` SHALL be able to reissue a Ban ID with a mandatory reason code; the old Ban ID MUST stop working at once, all player sessions of the punishment MUST be deleted, the generation MUST increase, and a leak mark MUST be cleared.

#### Scenario: Old ID is dead
- **WHEN** an admin reissues a Ban ID and the player uses the old one
- **THEN** the login is refused

#### Scenario: Moderator cannot reissue
- **WHEN** a `MODERATOR` calls the reissue route
- **THEN** the response is HTTP 403
