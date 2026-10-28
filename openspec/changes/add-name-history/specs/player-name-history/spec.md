## ADDED Requirements

### Requirement: Name history per account
The system SHALL keep, for every owner hash that logs in and with its raw UUID, one entry per name the account has used, with the most recent casing of that name and the first and last time the name was seen in a login. Names MUST be matched without regard to case.

#### Scenario: New name is recorded
- **WHEN** an owner logs in with a name it has not used before
- **THEN** a history entry exists with the first and last seen time set to that login

#### Scenario: Known name in another case
- **WHEN** an owner logs in with a name it used before, written with different letter case
- **THEN** the existing entry's last seen time is updated, the entry count does not grow, and the name shows the newest casing

#### Scenario: Name change
- **WHEN** an owner logs in with a new name after an old one
- **THEN** both names appear in the owner's history, the old one with its last seen time from its final login

### Requirement: Backfill from existing session records
When the name history is first created, the system SHALL fill it from the names in the existing session records, one entry per owner and name with the earliest and latest login times of that name.

#### Scenario: Backfill
- **WHEN** the migration runs over sessions for two names of one owner
- **THEN** the history holds both names with the time range of each

### Requirement: Names of one owner
The system SHALL answer `GET /intel/players/{owner}/names` for VIEWER and above and for PROXY with the names of that owner, newest last seen first, 50 per page by default, using the standard page parameters. A malformed owner SHALL be answered with a validation error.

#### Scenario: Owner with two names
- **WHEN** a viewer requests the names of an owner that used two names
- **THEN** the response carries the raw UUID and lists both with first and last seen, the most recently seen first

#### Scenario: Unknown owner
- **WHEN** the owner has no history
- **THEN** the response is 200 with an empty list

### Requirement: Old-name lookup
The system SHALL answer `GET /intel/names/{name}/owners` for VIEWER and above and for PROXY with the owners that have used that name, matched without regard to case and exactly, at most 20 owners, newest last seen first. Each entry SHALL carry the owner hash, the raw UUID, the owner's most recent name, and the first and last seen time of the matched name.

#### Scenario: Old name finds the account
- **WHEN** a viewer requests an owners lookup for a name the account used before it changed
- **THEN** the response lists that owner with its current name

#### Scenario: Partial name
- **WHEN** the requested name is only part of a name in the history
- **THEN** the response is an empty list

#### Scenario: Name used by two accounts
- **WHEN** two owners both used the name
- **THEN** the response lists both

### Requirement: Name retention
The system SHALL delete name history entries whose last seen time is older than `blackhole.intel.name-retention-days` (default 180) on the `blackhole.intel.name-retention-sweep-cron` schedule (default 04:00 daily). The retention MUST be independent of the session retention.

#### Scenario: Old name is deleted
- **WHEN** the sweep runs and a name was last seen 200 days ago
- **THEN** that entry is deleted

#### Scenario: Name outlives its sessions
- **WHEN** the session retention has removed the sessions of a name last seen 100 days ago
- **THEN** the name entry is kept until its own retention ends

### Requirement: Erasure removes names
When an owner's data is erased, the system SHALL delete that owner's name history entries together with its sessions and sightings, and MUST NOT delete entries of other owners.

#### Scenario: Erasure of one owner
- **WHEN** an erasure is requested for an owner that has two names
- **THEN** both entries are gone and the entries of another owner are untouched

### Requirement: Names are never published
Name history, UUIDs included, MUST NOT appear in public pages, the public ban list, or in any domain event.

#### Scenario: Event payload
- **WHEN** a session with a name is stored
- **THEN** no published event contains the name
