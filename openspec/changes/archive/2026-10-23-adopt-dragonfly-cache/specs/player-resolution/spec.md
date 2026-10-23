# Spec Delta

## MODIFIED Requirements

### Requirement: Resolution cache
Successful resolutions SHALL be cached in the shared cache keyed by the case-insensitive name, with a TTL of `blackhole.player-resolver.cache.ttl` (default PT5M). A cached entry MUST be returned without querying resolvers on any replica; misses MUST NOT be cached.

#### Scenario: Repeated lookup within TTL
- **WHEN** `Notch` was resolved less than the TTL ago and `notch` is requested
- **THEN** the cached result is returned without contacting any resolver

#### Scenario: Not-found is not cached
- **WHEN** a lookup finds no player
- **THEN** the next lookup of the same name queries the resolver chain again
