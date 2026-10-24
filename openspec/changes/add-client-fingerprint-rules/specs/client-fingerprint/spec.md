# Spec Delta

## Purpose
Builds a keyed, non-reversible fingerprint from client traits so ban-evasion recognition has a second signal next to the hashed IP. The fingerprint is personal data: only the keyed hash is stored, with a bounded retention, and it is a signal and never proof.

## ADDED Requirements

### Requirement: Collected traits
The proxy SHALL collect the client brand, the registered plugin channels and the legacy mod list of a player and SHALL send them for fingerprinting once they settle, and again only when the set changed. Channels in the `minecraft:` namespace MUST be left out. The protocol version MUST NOT be part of the fingerprint.

#### Scenario: Traits settle
- **WHEN** a player has been connected to a server for the settle period
- **THEN** the proxy sends brand, channels and mods once

#### Scenario: Unchanged traits
- **WHEN** no trait changed since the last submission
- **THEN** nothing is sent again

#### Scenario: Vanilla channels
- **WHEN** a player registers `minecraft:brand` and `fabric:common`
- **THEN** only `fabric:common` is part of the fingerprint

### Requirement: Keyed hash only
The backend SHALL compute the fingerprint as the hex HMAC-SHA256 of a versioned, length-prefixed canonical form of brand, sorted channels and sorted mods, keyed by a key derived from a dedicated secret, and SHALL store only that hash with its scheme version and the owner hash. It MUST NOT persist the raw brand, channels or mods, MUST NOT store them next to a raw UUID, and MUST NOT log request bodies.

#### Scenario: Stored values
- **WHEN** traits are submitted
- **THEN** the stored row contains a 64-character hex fingerprint, the scheme `v1` and the owner hash
- **AND** no raw trait appears in storage, logs, events or metrics

#### Scenario: Same traits, same hash
- **WHEN** two players submit equal traits in a different order
- **THEN** the fingerprints are equal

#### Scenario: Different traits
- **WHEN** a single channel differs
- **THEN** the fingerprints differ

### Requirement: Disabled without a key
Fingerprinting SHALL be off when the fingerprint key is not configured outside the dev and test profiles: submission answers 503, nothing is stored, rules are unaffected. In the dev and test profiles an unset key SHALL mean a random key per run.

#### Scenario: Key unset in production
- **WHEN** the key is unset and the proxy submits traits
- **THEN** the response is 503 and nothing is stored

### Requirement: Generic fingerprints are not stored
A fingerprint without channels and mods whose brand is empty, `vanilla`, `fabric`, `forge` or `neoforge` SHALL NOT be stored for correlation.

#### Scenario: Plain vanilla client
- **WHEN** a vanilla client with no extra channels is submitted
- **THEN** no fingerprint row is created

### Requirement: Retention and erasure
Fingerprint rows SHALL be deleted after the configured retention (default 30 days since last seen) by a daily sweep and SHALL be deleted when the owner's data is erased.

#### Scenario: Stale fingerprint
- **WHEN** a row was last seen before the retention window
- **THEN** the sweep deletes it

#### Scenario: Erasure
- **WHEN** a player's data is erased
- **THEN** the player's fingerprint rows are deleted

### Requirement: Best effort, never proof
The fingerprint MUST NOT by itself trigger a rule action, a punishment or an ELO change. Documentation and the panel SHALL state that brand, channels and mods are chosen by the client and can be faked or hidden.

#### Scenario: Fingerprint match alone
- **WHEN** a new account shares a fingerprint with a banned account and nothing else
- **THEN** only a weak hint is shown to staff and no punishment results
