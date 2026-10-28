## ADDED Requirements

### Requirement: Retention period for every store of clear-text personal data
Every store that holds clear-text personal data (IP addresses, raw player UUIDs or player names) SHALL have a documented retention period, and a periodic sweeper SHALL permanently delete rows older than that period. The retention period SHALL be configurable through an environment-overridable `blackhole.<area>.retention-days` setting with a documented default.

#### Scenario: Expired rows are swept
- **WHEN** the sweeper for a store runs and a row is older than the store's retention period
- **THEN** the row is permanently deleted

#### Scenario: Rows within the period are kept
- **WHEN** the sweeper for a store runs and a row is younger than the store's retention period
- **THEN** the row is retained

#### Scenario: Store without a documented retention period
- **WHEN** a feature proposes a store of clear-text personal data without a retention period and sweeper
- **THEN** the proposal is not accepted until the period and the sweeper are documented

### Requirement: Erasure of one player's personal data on request
The system SHALL let an `ADMIN` erase all clear-text personal data held for one player (raw UUID, player names and IP-derived records linked only to that player) on a documented erasure request. The erasure SHALL be recorded in an audit entry containing the reason, the erasing admin's verified actor and the time, and the audit entry itself SHALL NOT contain the erased personal data.

#### Scenario: Admin erases one player
- **WHEN** an admin erases the personal data of a player on a documented request
- **THEN** the player's UUID, names and linked IP records are deleted from every store that holds them
- **AND** an audit entry records the reason, the admin's `oid` and the time without the erased values

#### Scenario: Moderator cannot erase
- **WHEN** a moderator requests the erasure of a player's personal data
- **THEN** the response is HTTP 403 and nothing is deleted

#### Scenario: Retained records are listed
- **WHEN** an erasure keeps records because another rule requires them
- **THEN** the erasure response names the kept record types and the rule that keeps them

### Requirement: Published data carries no personal identifiers
Data that is published, meaning served on public pages or sent in events to third parties, SHALL NOT contain an IP address, a raw player UUID or a player name. Published identifiers SHALL be an opaque entry identifier or a keyed hash that cannot be reversed by the recipient.

#### Scenario: Public ban list entry
- **WHEN** the public ban list is served
- **THEN** each entry contains its reason, date, duration or expiry and a stable entry hash, and no IP address, UUID or player name

#### Scenario: Event to a third party
- **WHEN** an event is published to a subject that is not the backend itself
- **THEN** the event payload contains no IP address, raw UUID or player name
