# Spec Delta

## Purpose
Adds the hashed client fingerprint as a second evasion signal next to the hashed IP.

## ADDED Requirements

### Requirement: Client fingerprint signal
The system SHALL link two distinct owners that share a stored client fingerprint inside the fingerprint window (default 14 days) and SHALL report each link with its origin: `IP`, `CLIENT` or `BOTH`. A fingerprint shared by more than the configured maximum of owners (default 5) inside the window MUST link nobody. A `CLIENT`-only link SHALL be presented as a weak hint.

#### Scenario: Same fingerprint, different accounts
- **WHEN** two owners submit the same rare fingerprint within the window
- **THEN** each is linked to the other with origin `CLIENT`

#### Scenario: Same fingerprint and same IP
- **WHEN** the owners also share an IP token inside the IP window
- **THEN** the link has origin `BOTH`

#### Scenario: Common fingerprint
- **WHEN** six owners share one fingerprint inside the window
- **THEN** none of them is linked by it

#### Scenario: Outside the window
- **WHEN** the other owner was last seen with the fingerprint before the window
- **THEN** no link is reported

### Requirement: Client signal events
For links with origin `CLIENT` or `BOTH` the system SHALL publish an `evasion.client_match` event with the hashed owners and the origin and MUST NOT include the fingerprint, any trait or any IP. The existing `evasion.detected` event SHALL keep being published for the IP signal only.

#### Scenario: Event content
- **WHEN** a client match is found
- **THEN** the event holds owner hashes and the origin and no fingerprint value

### Requirement: Sightings response carries the origin
`GET /evasion/{owner}/sightings` SHALL add the origin to each linked owner and MUST still contain neither an address nor a fingerprint. With fingerprinting disabled only the IP origin is reported.

#### Scenario: Response content
- **WHEN** staff read the sightings of an owner linked by fingerprint
- **THEN** the linked owner appears with origin `CLIENT` and no fingerprint or address is returned

### Requirement: Fingerprint submission route
`POST /evasion/fingerprint` SHALL accept the owner hash, the brand, channels and mods from the `PROXY` role only, SHALL answer 200 once stored, 503 when fingerprinting is off and a validation error for a malformed owner hash or oversized trait lists (at most 64 characters brand, 256 channels, 256 mods, 128 characters each).

#### Scenario: Oversized list
- **WHEN** more than 256 channels are submitted
- **THEN** the request is rejected with a validation error and nothing is stored
