# player-resolution Specification

## Purpose
Describes how the backend resolves a Minecraft player name to a UUID through a fixed-priority chain of external sources (Otis, Mojang, NameMC), with per-source toggles, a bounded in-memory cache, and a lookup endpoint used for example by the proxy to target offline players.

## Requirements

### Requirement: Player lookup endpoint
The backend SHALL expose `GET /player/resolve/{name}` (API version 1) returning HTTP 200 with `uuid`, `name` and `source` when the name is resolved, and HTTP 404 with an `errorMessage` when it is not.

#### Scenario: Name resolved
- **WHEN** a client requests `GET /player/resolve/Notch` and a resolver finds the player
- **THEN** the response is 200 with the player's UUID, name and the source that resolved it (`otis`, `mojang` or `namemc`)

#### Scenario: Name not found
- **WHEN** no enabled resolver finds the requested name
- **THEN** the response is 404 with error message "No resolver found a player named <name>"

### Requirement: Player name validation
The lookup endpoint MUST reject names that do not match 3 to 16 characters of letters, digits or underscore with HTTP 404 and error message "Not a valid Minecraft player name", without contacting any resolver.

#### Scenario: Invalid name
- **WHEN** a client requests `GET /player/resolve/a-b`
- **THEN** the response is 404 with "Not a valid Minecraft player name" and no external source is queried

### Requirement: Fixed-priority resolver chain
The backend SHALL try enabled resolvers in the fixed order Otis, then Mojang, then NameMC, and MUST stop at the first resolver that returns a player. A resolver that errors or finds nothing MUST be treated as no match and the next resolver tried.

#### Scenario: Otis hit
- **WHEN** Otis is enabled and knows the name
- **THEN** the result comes from Otis and Mojang and NameMC are not queried

#### Scenario: Fallback to Mojang
- **WHEN** Otis returns no match or fails and Mojang knows the name
- **THEN** the result comes from Mojang with source `mojang`

#### Scenario: All resolvers miss
- **WHEN** every enabled resolver returns no match or fails
- **THEN** the lookup reports that the player was not found

### Requirement: Otis resolver activation
The Otis resolver SHALL be active only when `blackhole.player-resolver.otis.enabled` is true (default true) and `blackhole.player-resolver.otis.base-url` is set to a non-empty value (no default); otherwise it MUST be skipped.

#### Scenario: Base URL not configured
- **WHEN** `BLACKHOLE_PLAYER_RESOLVER_OTIS_BASE_URL` is unset
- **THEN** Otis is not part of the chain and resolution starts with Mojang

#### Scenario: Otis disabled explicitly
- **WHEN** `blackhole.player-resolver.otis.enabled` is false even though a base URL is set
- **THEN** Otis is not part of the chain

### Requirement: Mojang resolver toggle
The Mojang resolver SHALL query Mojang's public name-to-UUID API and be active unless `blackhole.player-resolver.mojang.enabled` is false (default true).

#### Scenario: Mojang disabled
- **WHEN** `BLACKHOLE_PLAYER_RESOLVER_MOJANG_ENABLED` is `false`
- **THEN** Mojang is never queried

### Requirement: NameMC resolver opt-in
The NameMC resolver SHALL be disabled by default and active only when `blackhole.player-resolver.namemc.enabled` is true. When active it MUST be tried last, extract the UUID from NameMC's exact-search page, and treat any failure or unexpected page shape as no match.

#### Scenario: Default configuration
- **WHEN** NameMC enablement is not configured
- **THEN** NameMC is never queried

#### Scenario: NameMC page changed
- **WHEN** NameMC is enabled but its page no longer contains a profile link
- **THEN** NameMC returns no match and the lookup does not fail

### Requirement: Resolution cache
Successful resolutions SHALL be cached in the shared cache keyed by the case-insensitive name, with a TTL of `blackhole.player-resolver.cache.ttl` (default PT5M). A cached entry MUST be returned without querying resolvers on any replica; misses MUST NOT be cached.

#### Scenario: Repeated lookup within TTL
- **WHEN** `Notch` was resolved less than the TTL ago and `notch` is requested
- **THEN** the cached result is returned without contacting any resolver

#### Scenario: Not-found is not cached
- **WHEN** a lookup finds no player
- **THEN** the next lookup of the same name queries the resolver chain again

### Requirement: Lookup returns the owner hash
A successful player lookup SHALL additionally return the SHA-512 owner hash of the resolved UUID, computed exactly as proxies compute it, so clients never need to hash UUIDs themselves.

#### Scenario: Lookup includes owner
- **WHEN** `GET /player/resolve/Steve` succeeds
- **THEN** the response contains `uuid`, `name`, `source` and `owner`, where `owner` equals the hash the proxy uses for Steve
