## MODIFIED Requirements

### Requirement: Import summary
The summary SHALL report `dryRun`, `playersTotal`, `playersImported`, `playersSkippedExisting`, `playersInvalid`, a list of `invalidEntries` descriptions, `ipsTotal`, `ipsImported`, `ipsSkipped`, `ipsInvalid` and `reasonGroups`: the imported entries grouped by original reason, each with the key of the template it is (or, for a dry run, would be) linked to and a count.

#### Scenario: Mixed file
- **WHEN** a file with 3 valid new entries, 1 already-banned player and 1 malformed entry is imported
- **THEN** the summary reports 5 total, 3 imported, 1 skipped as existing and 1 invalid with a description

#### Scenario: Reason groups
- **WHEN** two entries with the reason `Spam` and one with another reason are imported
- **THEN** `reasonGroups` lists `Spam` with count 2 and the other reason with count 1, each with its template key

#### Scenario: IP entries counted
- **WHEN** a `banned-ips.json` with 2 valid new entries, 1 entry already imported and 1 malformed entry is imported
- **THEN** the summary reports `ipsTotal` 4, `ipsImported` 2, `ipsSkipped` 1 and `ipsInvalid` 1

## ADDED Requirements

### Requirement: Vanilla IP ban import
When a `bannedIps` file is supplied, the system SHALL import each entry of `banned-ips.json` as an IP ban with source `IMPORT`, the entry's address (single address or CIDR) as its range, the entry's `created` time (falling back to the current time when missing or unparseable), and the expiry derived from `expires` exactly as for player entries (`forever` or empty is permanent; a malformed date marks the entry invalid). The system SHALL NOT skip the file.

#### Scenario: Permanent vanilla IP ban
- **WHEN** an entry with address `203.0.113.7` and `expires` `forever` is imported
- **THEN** a permanent IP ban for `203.0.113.7/32` with source `IMPORT` exists

#### Scenario: Expired vanilla IP ban
- **WHEN** an entry whose `expires` date has passed is imported
- **THEN** the entry is stored as lifted, appears in the history and creates no active ban

#### Scenario: Malformed address
- **WHEN** one entry has an address that does not parse
- **THEN** that entry is counted as invalid with a description and the other entries are imported

### Requirement: Idempotent vanilla IP import
The system MUST skip, counting as skipped, any IP entry whose canonical range and `created` timestamp match an entry already imported, so that re-importing the same file creates no duplicates.

#### Scenario: Same file imported twice
- **WHEN** the same `banned-ips.json` is imported a second time
- **THEN** every entry is counted as skipped and no IP ban is created

### Requirement: IP imports are records
An IP import SHALL NOT deduct ELO, SHALL NOT record an escalation group, step or cost, and SHALL NOT be placed in any section.

#### Scenario: No score change for an IP ban import
- **WHEN** an IP ban is imported with a template whose first step costs ELO
- **THEN** no player's scores change and no escalation step is recorded

### Requirement: IP import dry run
When `dryRun` is true, the system SHALL compute the IP counts of the summary without creating any IP ban and without publishing any event.

#### Scenario: Preview of IP entries
- **WHEN** an admin uploads a `banned-ips.json` with `dryRun=true`
- **THEN** the summary reports the IP counts and no IP ban exists afterwards
