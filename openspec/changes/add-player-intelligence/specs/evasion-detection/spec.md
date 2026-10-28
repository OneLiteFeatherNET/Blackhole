## MODIFIED Requirements

### Requirement: Keyed, non-reversible IP storage
The system MUST store only an HMAC-SHA512 of the IP keyed with `blackhole.evasion.ip-salt` for every sighting recorded through `POST /evasion/record`, and MUST NOT persist the raw IP address in any form on that path. Sessions recorded through `player-intelligence` store the clear IP as stated there and also store the same keyed token, so that correlation reads one token for both sources.

#### Scenario: Stored token is not the IP
- **WHEN** a sighting is recorded for an IP through `POST /evasion/record` or through a session report
- **THEN** the persisted record contains a 128-character hex token derived from the IP and salt
- **AND** a sighting recorded through `POST /evasion/record` does not persist the raw IP

#### Scenario: Same IP yields the same token
- **WHEN** two sightings for the same IP are recorded under the same salt, one through each path
- **THEN** both are stored under the same token so they can be correlated


### Requirement: Evasion signal on shared IP
After each recorded sighting or session, the system SHALL check whether more than one distinct owner has a sighting on the same IP token with a last-seen time within `blackhole.evasion.detection-window-days` (default 7), reading both the legacy sightings and the session records, and if so MUST publish an `evasion.detected` event containing the token and the list of distinct owner hashes.

#### Scenario: Two accounts share an IP within the window
- **WHEN** owner B logs in from an IP that owner A used 2 days earlier
- **THEN** an `evasion.detected` event is published listing both owner hashes and the token

#### Scenario: One account on a legacy row and one on a session
- **WHEN** owner A has a legacy sighting for an IP and owner B reports a session from the same IP within the window
- **THEN** an `evasion.detected` event is published listing both owner hashes

### Requirement: Retention sweep
The system SHALL periodically, on the `blackhole.evasion.retention-sweep-cron` schedule (default 03:30 daily), permanently delete every legacy sighting whose last-seen time is older than `blackhole.evasion.retention-days` (default 90). Session records are deleted by the session retention in `player-intelligence`, not by this sweep.

#### Scenario: Stale sighting is purged
- **WHEN** the retention sweep runs and a legacy sighting was last seen 100 days ago
- **THEN** that sighting is deleted

#### Scenario: Recent sighting is kept
- **WHEN** the retention sweep runs and a legacy sighting was last seen 10 days ago
- **THEN** that sighting is retained

#### Scenario: Session records are not touched by the evasion sweep
- **WHEN** the evasion retention sweep runs and a session record is 30 days old
- **THEN** the session record is retained
