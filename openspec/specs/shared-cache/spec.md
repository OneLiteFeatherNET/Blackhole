# shared-cache Specification

## Purpose
Defines the shared Redis-protocol cache (Dragonfly in production) that all backend replicas and proxies use to read hot data without each keeping its own copy.

## Requirements

### Requirement: Shared cache for hot reads
The backend SHALL cache punishment profiles, ELO profiles, punishment templates (individually and as the full list) and player resolutions in a single shared Redis-protocol cache visible to every backend replica, under keys prefixed `blackhole:cache:v1:`.

#### Scenario: Read served from shared cache
- **WHEN** replica A has cached the profile of owner `h1` and replica B receives a read for `h1`
- **THEN** replica B answers from the shared cache without querying the database

### Requirement: Cache entries expire
Every cache entry SHALL carry a TTL configured per resource under `blackhole.cache.*`, so that a missed eviction can only cause bounded staleness.

#### Scenario: Entry expires after TTL
- **WHEN** a cached template list is older than its configured TTL
- **THEN** the next read loads it from the database and caches it again

### Requirement: Writes evict shared entries
Any write that changes a cached resource SHALL delete the affected cache keys after the database write succeeds, so the next read on any replica sees the new state.

#### Scenario: Template update visible on all replicas
- **WHEN** a template is updated through replica A
- **THEN** a subsequent read of that template or of the template list through replica B returns the updated template

### Requirement: Cache failures do not fail requests
If the shared cache is unreachable or times out, reads SHALL fall back to the database and writes SHALL still succeed; cache errors MUST NOT be returned to callers.

#### Scenario: Cache down
- **WHEN** the shared cache is unreachable and a profile is requested
- **THEN** the profile is returned from the database with a success response

### Requirement: Cache keys contain no raw PII
Cache keys and values SHALL identify players only by hashed owner identifiers, except player-resolution entries which are keyed by the public player name and hold the public UUID returned by the resolver.

#### Scenario: Profile key uses hashed owner
- **WHEN** a punishment profile is cached
- **THEN** its key contains the SHA-512 owner hash and not the raw player UUID

### Requirement: Proxies read templates from the shared cache
The proxy plugin SHALL read the punishment template list from the shared cache for command tab completion and only call the backend API when the entry is missing.

#### Scenario: Tab completion without API call
- **WHEN** a staff member tab-completes a template and the template list is cached
- **THEN** suggestions are produced without an HTTP request to the backend
