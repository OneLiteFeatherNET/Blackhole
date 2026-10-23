# vanilla-import Specification

## Purpose
Imports existing vanilla Minecraft ban lists (`banned-players.json`, optionally
`banned-ips.json`) into Blackhole through the normal punishment write path, so imported bans are
enforced, hashed and announced like any other punishment.

## Requirements

### Requirement: Import endpoint
The system SHALL accept a multipart upload at `POST /admin/import/vanilla` with a required `bannedPlayers` file part, an optional `bannedIps` file part, an optional `reasonTemplates` text part, a required `templateKey` query value and an optional `dryRun` query flag (default false), and SHALL respond with an import summary. The endpoint MUST be hidden from the published API specification and MUST require the `ADMIN` role.

#### Scenario: Successful import
- **WHEN** an admin uploads a valid `banned-players.json` with an existing `templateKey`
- **THEN** the response is HTTP 200 with a summary of the import

#### Scenario: Unreadable file
- **WHEN** an uploaded file cannot be read or parsed as a JSON array
- **THEN** the response is HTTP 400 describing the read failure

#### Scenario: Template key missing
- **WHEN** the upload carries no `templateKey`
- **THEN** the response is HTTP 400 and nothing is written

#### Scenario: Not an admin
- **WHEN** a moderator or viewer calls the endpoint
- **THEN** the response is HTTP 403 and nothing is written

### Requirement: Import summary
The summary SHALL report `dryRun`, `playersTotal`, `playersImported`, `playersSkippedExisting`, `playersInvalid`, a list of `invalidEntries` descriptions, `ipsTotal`, `ipsSkipped` and `reasonGroups`: the imported entries grouped by original reason, each with the key of the template it is (or, for a dry run, would be) linked to and a count.

#### Scenario: Mixed file
- **WHEN** a file with 3 valid new entries, 1 already-banned player and 1 malformed entry is imported
- **THEN** the summary reports 5 total, 3 imported, 1 skipped as existing and 1 invalid with a description

#### Scenario: Reason groups
- **WHEN** two entries with the reason `Spam` and one with another reason are imported
- **THEN** `reasonGroups` lists `Spam` with count 2 and the other reason with count 1, each with its template key

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
Each imported entry SHALL become a ban issued by a fixed, deterministic system import identity, in the section of its template, preserving the entry's `created` time (falling back to the current time if missing or unparseable) and its expiration, and recording the verified caller as the principal that wrote it. An `expires` of `forever` or empty MUST produce a permanent ban. An import is a record of an earlier ban, not a new offence: it MUST NOT deduct ELO, MUST NOT record an escalation group, step or cost, and MUST NOT count towards the template's escalation.

#### Scenario: Permanent vanilla ban
- **WHEN** an entry with `expires` set to `forever` is imported
- **THEN** a ban without an expiration date is created in the template's section with the system import identity as its source

#### Scenario: No score change
- **WHEN** a ban is imported with a template whose first step costs ELO
- **THEN** the player's scores are unchanged and the punishment has no escalation step

#### Scenario: Case timeline
- **WHEN** a ban is imported
- **THEN** its case has a `punishment.applied` event without a step or scores

### Requirement: Reason templates
The system SHALL link each imported ban to the template named by `templateKey`, or, for an entry whose trimmed original reason is a key of the optional `reasonTemplates` JSON object, to the template that key maps to. The reason shown to the player MUST be the template's reason; the original vanilla reason (`Imported vanilla ban` when blank) SHALL be kept in the punishment metadata as `importedReason`. Every referenced template MUST exist, MUST be in an enabled section and MUST NOT be in a `MUTE` section, and is checked before anything is written.

#### Scenario: Shared reason reuses a template
- **WHEN** two entries are imported with the same `templateKey`
- **THEN** both bans reference that template and keep their own `importedReason`

#### Scenario: Mapped reason
- **WHEN** `reasonTemplates` maps `Griefing` to another template and an entry has the reason `Griefing`
- **THEN** that entry is linked to the mapped template and the others to `templateKey`

#### Scenario: Mute template refused
- **WHEN** `templateKey`, or a template in `reasonTemplates`, is in a `MUTE` section
- **THEN** the response is HTTP 400 and no punishment, profile or event is created

#### Scenario: Unknown template
- **WHEN** a referenced template key does not exist
- **THEN** the response is HTTP 404 and nothing is written

#### Scenario: Malformed mapping
- **WHEN** `reasonTemplates` is not a JSON object of strings
- **THEN** the response is HTTP 400 and nothing is written

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
When `dryRun` is true, the system SHALL validate the templates and compute the same summary counts and `reasonGroups` as a real import but MUST NOT create punishments or profiles, and MUST NOT publish any events.

#### Scenario: Preview an import
- **WHEN** an admin uploads a file with `dryRun=true`
- **THEN** the summary reports how many entries would be imported and the template of each reason
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
