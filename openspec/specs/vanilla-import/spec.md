# vanilla-import Specification

## Purpose
Imports existing vanilla Minecraft ban lists (`banned-players.json`, optionally
`banned-ips.json`) into Blackhole through the normal punishment write path, so imported bans are
enforced, hashed and announced like any other punishment.

## Requirements

### Requirement: Import endpoint
The system SHALL accept a multipart upload at `POST /admin/import/vanilla` with a required `bannedPlayers` file part, an optional `bannedIps` file part and an optional `dryRun` query flag (default false), and SHALL respond with an import summary. The endpoint MUST be hidden from the published API specification.

#### Scenario: Successful import
- **WHEN** an admin uploads a valid `banned-players.json`
- **THEN** the response is HTTP 200 with a summary of the import

#### Scenario: Unreadable file
- **WHEN** an uploaded file cannot be read or parsed as a JSON array
- **THEN** the response is HTTP 400 describing the read failure

### Requirement: Import summary
The summary SHALL report `dryRun`, `playersTotal`, `playersImported`, `playersSkippedExisting`, `playersInvalid`, a list of `invalidEntries` descriptions, `ipsTotal` and `ipsSkipped`.

#### Scenario: Mixed file
- **WHEN** a file with 3 valid new entries, 1 already-banned player and 1 malformed entry is imported
- **THEN** the summary reports 5 total, 3 imported, 1 skipped as existing and 1 invalid with a description

### Requirement: Player identity hashing
The system MUST convert each entry's raw player UUID to the same SHA-512 owner hash the proxy produces, and MUST NOT store the raw UUID or player name.

#### Scenario: Imported ban targets the hashed owner
- **WHEN** an entry for a player UUID is imported
- **THEN** the resulting punishment profile is keyed by the SHA-512 hash of that UUID
- **AND** the raw UUID is not persisted

### Requirement: Invalid entry handling
The system SHALL skip an entry whose UUID is missing or malformed, or whose `expires` value is neither empty, `forever`, nor a valid `yyyy-MM-dd HH:mm:ss Z` date, counting it as invalid with a description. Invalid entries MUST NOT abort the rest of the import.

#### Scenario: Bad UUID in one entry
- **WHEN** one entry has a malformed UUID among otherwise valid entries
- **THEN** that entry is reported invalid and all other valid entries are imported

### Requirement: Imported punishment shape
Each imported entry SHALL become a network ban issued by a fixed, deterministic system import identity, preserving the entry's `created` time (falling back to the current time if missing or unparseable) and its expiration. An `expires` of `forever` or empty MUST produce a permanent ban.

#### Scenario: Permanent vanilla ban
- **WHEN** an entry with `expires` set to `forever` is imported
- **THEN** a network ban without an expiration date is created with the system import identity as its source

### Requirement: Reason templates
The system SHALL attach each imported ban to a network punishment template matching its reason, reusing an existing template with the same reason or creating one marked as imported from vanilla. An entry with a blank reason MUST use the reason `Imported vanilla ban`.

#### Scenario: Shared reason reuses a template
- **WHEN** two entries with the same reason are imported
- **THEN** both bans reference the same punishment template

### Requirement: Already-expired entries
An entry whose expiration lies in the past SHALL be imported directly into the player's punishment history and MUST NOT become an active ban.

#### Scenario: Expired vanilla ban
- **WHEN** an entry whose `expires` date has already passed is imported
- **THEN** the punishment appears in the player's history and the player has no active ban from it

### Requirement: Idempotent re-import
The system MUST skip, counting as existing, any entry whose player already has an active ban or already has a history entry from a vanilla import with the same `created` timestamp, so re-importing the same file creates no duplicates.

#### Scenario: Same file imported twice
- **WHEN** the same `banned-players.json` is imported a second time
- **THEN** every previously imported entry is counted as skipped-existing and no new punishments are created

### Requirement: Dry run
When `dryRun` is true, the system SHALL compute the same summary counts as a real import but MUST NOT create punishments, templates or profiles, and MUST NOT publish any events.

#### Scenario: Preview an import
- **WHEN** an admin uploads a file with `dryRun=true`
- **THEN** the summary reports how many entries would be imported
- **AND** no data is written

### Requirement: IP bans are not imported
The system SHALL count entries of an uploaded `banned-ips.json` as `ipsTotal` and report all of them as `ipsSkipped`, and MUST NOT import them, since IP bans are not a supported concept.

#### Scenario: IP list supplied
- **WHEN** a `banned-ips.json` with 4 entries is uploaded alongside the players file
- **THEN** the summary reports `ipsTotal` 4 and `ipsSkipped` 4 and no IP data is stored

### Requirement: Import propagation
For each imported ban the system SHALL invalidate cached state for the player and publish a `punishment.created` event tagged with `importSource` `vanilla`, plus a `profile.created` event when a new profile was created, so imported bans are enforced like any other punishment.

#### Scenario: Active imported ban is enforced
- **WHEN** a non-expired vanilla ban is imported for a player without a profile
- **THEN** a `profile.created` and a `punishment.created` event are published for that owner
