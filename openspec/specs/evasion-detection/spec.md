# evasion-detection Specification

## Purpose
Detects likely ban evasion by correlating login sightings of different player accounts that
share an IP address, without ever storing the IP in a reversible form. Correlation data is
treated as personal data with its own bounded retention period.

## Requirements

### Requirement: Login sighting recording
The system SHALL accept login sightings via `POST /evasion/record` with an `owner` (a 128-character hex SHA-512 player hash) and a non-blank `ip`, and SHALL respond with HTTP 200 once the sighting is recorded and checked for evasion.

#### Scenario: Sighting is recorded
- **WHEN** the proxy reports a login for a hashed owner and an IP while detection is configured
- **THEN** the response is HTTP 200
- **AND** a sighting linking the owner to the IP's keyed hash exists

#### Scenario: Malformed owner hash
- **WHEN** a sighting is submitted with an `owner` that is not a 128-character hex string
- **THEN** the request is rejected with a validation error

### Requirement: Keyed, non-reversible IP storage
The system MUST store only an HMAC-SHA512 of the IP keyed with `blackhole.evasion.ip-salt`, and MUST NOT persist the raw IP address in any form.

#### Scenario: Stored token is not the IP
- **WHEN** a sighting is recorded for an IP
- **THEN** the persisted record contains a 128-character hex token derived from the IP and salt
- **AND** the raw IP does not appear in storage

#### Scenario: Same IP yields the same token
- **WHEN** two sightings for the same IP are recorded under the same salt
- **THEN** both are stored under the same token so they can be correlated

### Requirement: Detection disabled without a salt
When `blackhole.evasion.ip-salt` is unset or blank, the system MUST respond to `POST /evasion/record` with HTTP 503 and MUST NOT record, hash or correlate anything.

#### Scenario: Salt not configured
- **WHEN** a sighting is submitted while no IP salt is configured
- **THEN** the response is HTTP 503
- **AND** nothing is stored

### Requirement: Sighting refresh
For an owner and IP token pair that was already recorded, the system SHALL update the existing sighting's last-seen time and increment its occurrence count instead of creating a duplicate.

#### Scenario: Repeat login from the same IP
- **WHEN** the same owner logs in again from the same IP
- **THEN** a single sighting exists for that pair with an occurrence count of 2 and a refreshed last-seen time

### Requirement: Evasion signal on shared IP
After each recorded sighting, the system SHALL check whether more than one distinct owner has a sighting on the same IP token with a last-seen time within `blackhole.evasion.detection-window-days` (default 7), and if so MUST publish an `evasion.detected` event containing the token and the list of distinct owner hashes.

#### Scenario: Two accounts share an IP within the window
- **WHEN** owner B logs in from an IP that owner A used 2 days earlier
- **THEN** an `evasion.detected` event is published listing both owner hashes and the token

### Requirement: No signal for a single account
The system MUST NOT raise an evasion signal when only one distinct owner has used the IP token within the detection window, including when other owners used it only before the window started.

#### Scenario: Single account on an IP
- **WHEN** an owner logs in repeatedly from the same IP and no other owner has used it
- **THEN** no `evasion.detected` event is published

#### Scenario: Other account outside the window
- **WHEN** owner B logs in from an IP that owner A last used 10 days earlier with a 7-day window
- **THEN** no `evasion.detected` event is published

### Requirement: Retention sweep
The system SHALL periodically, on the `blackhole.evasion.retention-sweep-cron` schedule (default 03:30 daily), permanently delete every sighting whose last-seen time is older than `blackhole.evasion.retention-days` (default 90).

#### Scenario: Stale sighting is purged
- **WHEN** the retention sweep runs and a sighting was last seen 100 days ago
- **THEN** that sighting is deleted

#### Scenario: Recent sighting is kept
- **WHEN** the retention sweep runs and a sighting was last seen 10 days ago
- **THEN** that sighting is retained

### Requirement: Configurable windows
The detection window, retention period and retention sweep schedule SHALL each be configurable through `blackhole.evasion.detection-window-days`, `blackhole.evasion.retention-days` and `blackhole.evasion.retention-sweep-cron` (environment-variable overridable), independently of one another.

#### Scenario: Shortened detection window
- **WHEN** the detection window is configured to 1 day
- **THEN** only sightings last seen within the past day are considered for correlation

### Requirement: Read sightings for a player
The system SHALL answer `GET /evasion/{owner}/sightings` for VIEWER and above (not PROXY) with the other owners that shared a hashed address with `owner` inside the detection window, each with its owner hash, the reason `SHARED_IP`, the number of shared addresses and its first and last sighting, at most 50 and newest first. A malformed owner SHALL be answered with 400.

#### Scenario: Two owners on one address
- **WHEN** owners A and B were recorded from the same IP and a viewer requests the sightings of A
- **THEN** the response lists B with reason `SHARED_IP` and no IP or token

#### Scenario: No shared address
- **WHEN** a viewer requests the sightings of an owner nobody shares an address with
- **THEN** the response is 200 with an empty `linkedOwners`

### Requirement: Sightings carry no address
The sightings response MUST NOT contain an IP, a token or a raw UUID.

#### Scenario: Response is free of addresses
- **WHEN** a viewer requests the sightings of an owner that shares an address
- **THEN** the body contains no IP, no token and no raw UUID

### Requirement: Sightings without a salt
When `blackhole.evasion.ip-salt` is unset the sightings response SHALL be 200 with `detectionEnabled` false and no owners.

#### Scenario: Detection is off
- **WHEN** a viewer requests sightings while no salt is configured
- **THEN** the response is 200 with `detectionEnabled` false and no owners
