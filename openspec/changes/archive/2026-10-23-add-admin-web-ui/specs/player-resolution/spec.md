# Spec Delta

## ADDED Requirements

### Requirement: Lookup returns the owner hash
A successful player lookup SHALL additionally return the SHA-512 owner hash of the resolved UUID, computed exactly as proxies compute it, so clients never need to hash UUIDs themselves.

#### Scenario: Lookup includes owner
- **WHEN** `GET /player/resolve/Steve` succeeds
- **THEN** the response contains `uuid`, `name`, `source` and `owner`, where `owner` equals the hash the proxy uses for Steve
