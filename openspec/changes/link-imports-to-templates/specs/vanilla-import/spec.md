# Spec Delta

## Purpose

Imported vanilla bans are linked to punishment templates.

## MODIFIED Requirements

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

### Requirement: Dry run
When `dryRun` is true, the system SHALL validate the templates and compute the same summary counts and `reasonGroups` as a real import but MUST NOT create punishments or profiles, and MUST NOT publish any events.

#### Scenario: Preview an import
- **WHEN** an admin uploads a file with `dryRun=true`
- **THEN** the summary reports how many entries would be imported and the template of each reason
- **AND** no data is written
