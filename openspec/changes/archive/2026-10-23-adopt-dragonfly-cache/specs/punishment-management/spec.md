# Spec Delta

## MODIFIED Requirements

### Requirement: Cache invalidation on punishment writes
Every write that changes a profile's punishment state (apply, revoke, expiry sweep, direct profile create/update/delete) SHALL evict the shared cache entry for that owner's profile, so that every replica observes the change on its next read without a separate invalidation broadcast.

#### Scenario: Revocation invalidates cache
- **WHEN** an active ban is revoked
- **THEN** the cached profile for that owner is evicted from the shared cache
- **AND** the next profile read on any replica reflects the revocation
