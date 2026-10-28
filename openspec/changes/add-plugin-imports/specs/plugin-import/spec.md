## ADDED Requirements

### Requirement: Plugin import from uploaded exports
The system SHALL let an `ADMIN` import a LiteBans, AdvancedBan or BanManager database by uploading one CSV file per source table to `POST /admin/import/plugin`, with `source`, `sourceLabel` and `templateKey` required and `muteTemplateKey`, `reasonTemplates` and `dryRun` optional. The system MUST NOT open any connection to a database or host named by the caller.

#### Scenario: Import of a LiteBans export
- **WHEN** an admin uploads the `bans`, `mutes`, `warnings`, `kicks` and `history` files of a LiteBans database with `source=litebans`
- **THEN** the response is HTTP 200 with an import summary per file

#### Scenario: Required file missing
- **WHEN** a `source=advancedban` upload lacks the `punishmentHistory` file
- **THEN** the response is HTTP 400 naming the missing file and nothing is written

#### Scenario: Caller-supplied connection refused
- **WHEN** a request supplies a JDBC URL or host instead of files
- **THEN** the response is HTTP 400 and no connection is opened

#### Scenario: Not an admin
- **WHEN** a moderator or viewer calls the endpoint
- **THEN** the response is HTTP 403 and nothing is written

### Requirement: Source header validation
The system SHALL read the header line of each uploaded file and MUST reject the file before reading any row when a column required for that table is absent. Extra columns SHALL be ignored.

#### Scenario: Unknown export layout
- **WHEN** a LiteBans `bans` file has no `until` column
- **THEN** the response is HTTP 400 naming the file and the column, and nothing is written

#### Scenario: Extra columns
- **WHEN** a file has the required columns plus additional ones
- **THEN** the import proceeds and ignores the additional columns

### Requirement: Source rows are imported once
The system SHALL record each source row it imports or leaves pending in a record keyed by source plugin and source key (`<table>:<id>` for LiteBans and BanManager, `<uuid>|<start>|<punishmentType>` for AdvancedBan), and MUST skip, counting it as `skippedExisting`, any row that already has a record. Re-running the same files MUST create no duplicate punishments or records.

#### Scenario: Same export imported twice
- **WHEN** the same LiteBans export is imported a second time
- **THEN** every row is counted as `skippedExisting` and no punishment is created

#### Scenario: Interrupted import is completed
- **WHEN** an import stops after half of its rows
- **THEN** a second run imports the remaining rows and skips the rest

#### Scenario: AdvancedBan row in both tables
- **WHEN** a punishment is present in both `Punishments` and `PunishmentHistory`
- **THEN** exactly one punishment is created for it

### Requirement: Active and historical rows
The system SHALL create an active punishment only for a source row that is still in force: a LiteBans row with `active` set and `until` `-1` or in the future, an AdvancedBan row present in `Punishments` and not expired, or a BanManager row in the active ban or mute tables. Every other row SHALL be created as history, marked lifted, and MUST NOT take an active slot.

#### Scenario: Active LiteBans ban
- **WHEN** a LiteBans ban with `active` 1 and `until` `-1` is imported
- **THEN** the player has an active permanent ban in the section of its template

#### Scenario: Removed LiteBans ban
- **WHEN** a LiteBans ban with `active` 0 is imported
- **THEN** the punishment appears in the player's history and the player has no active ban from it

#### Scenario: Expired temporary ban
- **WHEN** an AdvancedBan `TEMP_BAN` row in `Punishments` whose `end` lies in the past is imported
- **THEN** the punishment is history only

### Requirement: Permanent and temporary punishments and time units
The system SHALL treat a LiteBans `until` of `-1` or `0`, an AdvancedBan `end` of `-1` or a non-temporary type, and a BanManager `expires` of `0` as permanent. For temporary punishments the expiry SHALL be the source timestamp converted to milliseconds, with BanManager values read as seconds. A timestamp before 2010-01-01 or more than one day in the future MUST make its row invalid with a description.

#### Scenario: BanManager temporary ban
- **WHEN** a BanManager ban with `expires` as a seconds timestamp in the future is imported
- **THEN** the resulting punishment expires at that instant in milliseconds

#### Scenario: Wrong unit detected
- **WHEN** a row's `time` is a seconds value in a millisecond column
- **THEN** that row is counted invalid and the rest of the file is imported

### Requirement: Kicks are kick history
The system SHALL import every kick row as a `KICK` punishment with no duration, never active and not linked to a template. A kick row MUST NOT appear in the imported-punishments list for retemplating.

#### Scenario: LiteBans kick
- **WHEN** a LiteBans kick row is imported
- **THEN** the player's history shows a kick with its reason and no active punishment results from it

### Requirement: Warnings before the warning kind exists
The system SHALL import each warning row as a history-only punishment linked to `templateKey`, with `metaData.importedKind` set to `WARNING`, until the `WARN` punishment kind exists. A warning MUST NOT occupy an active slot or count as a ban.

#### Scenario: LiteBans warning imported
- **WHEN** a LiteBans warning with `warned` 1 is imported
- **THEN** the punishment is history, carries `importedKind` `WARNING` and `importedWarnRead` true, and no active punishment results from it

### Requirement: IP rows are pending records, not enforced
The system SHALL store each IP ban, IP range ban and IP mute row (LiteBans `ipban` 1, AdvancedBan `IP_BAN` and `TEMP_IP_BAN`, BanManager `bm_ip_bans`, `bm_ip_mutes` and `bm_ip_range_bans`) as a pending import record that holds the address or CIDR, reason, expiry and legacy actor, and MUST NOT create a punishment or enforce the address. A player row carrying the same `ipban` flag MUST NOT be imported as a player ban.

#### Scenario: IP ban pending
- **WHEN** a LiteBans row with `ipban` 1 and `ipban_wildcard` 0 is imported
- **THEN** the summary counts it under `ipRecordsPending` and no punishment is created for it

#### Scenario: IP range pending
- **WHEN** a LiteBans row with `ipban_wildcard` 1 and the range `172.16.0.0/16` is imported
- **THEN** a pending record with the range is stored and nothing is enforced

### Requirement: Mute and ban templates are kept apart
The system SHALL link ban, IP-ban and warning imports to a template outside a mute section, given by `templateKey`, and mute imports to a template inside a mute section, given by `muteTemplateKey`. The system MUST refuse a request before writing anything when a template is missing, disabled, or of the wrong kind for the rows that use it.

#### Scenario: Mute row without a mute template
- **WHEN** an import has mute rows and no `muteTemplateKey`
- **THEN** the response is HTTP 400 and nothing is written

#### Scenario: Ban template used for a mute
- **WHEN** `muteTemplateKey` names a template in a ban section
- **THEN** the response is HTTP 400 and nothing is written

### Requirement: Legacy staff are not verified actors
The system SHALL set the source of every imported punishment to the deterministic import identity for its plugin and the recording actor to the verified caller. The legacy staff name SHALL be kept in `metaData.importedActorName`, and a legacy UUID, where the source has one, in `metaData.importedActorUuid`. The system MUST NOT set the actor of an imported punishment from a legacy value.

#### Scenario: Legacy moderator
- **WHEN** a LiteBans ban was issued by a staff member with UUID U and name `Alex`
- **THEN** the punishment keeps `importedActorName` `Alex` and the verified caller as recorder, and no actor is set from U

#### Scenario: Console
- **WHEN** a row was issued by `CONSOLE`
- **THEN** the legacy name is `CONSOLE` and no legacy UUID is stored

### Requirement: Imported rows are records, not offences
Imports from plugins SHALL follow the vanilla rule: no ELO change, no escalation group, step or cost, and no count towards any template's escalation.

#### Scenario: No score change
- **WHEN** a LiteBans ban is imported with a template whose first step costs ELO
- **THEN** the player's scores are unchanged and the punishment has no escalation step

### Requirement: Streamed and bounded import
The system SHALL read each uploaded file as a stream, process rows in batches, and MUST NOT hold a whole file in memory. An upload above `blackhole.import.max-upload-bytes` or a table above `blackhole.import.max-rows-per-table` SHALL be refused before any write, with HTTP 413 or 400 respectively.

#### Scenario: Oversized table
- **WHEN** a `bans` file has more rows than `blackhole.import.max-rows-per-table`
- **THEN** the response is HTTP 400 and nothing is written

### Requirement: Dry run reports without writing
When `dryRun` is true, the system SHALL run every read, check and mapping, report the per-file counts, invalid rows with row numbers, `skippedExisting` and `reasonGroups`, and MUST NOT create punishments, records or profiles, or publish events.

#### Scenario: Preview a LiteBans import
- **WHEN** an admin uploads a LiteBans export with `dryRun=true`
- **THEN** the summary reports the counts per file and no data is written

### Requirement: Invalid rows never leak their content
Invalid-row descriptions SHALL name the file, the row number and the reason, and MUST NOT include the row's player identifier, IP address or reason text.

#### Scenario: Bad UUID row
- **WHEN** a row has a malformed UUID
- **THEN** the description names the file and row number, and does not contain the UUID

### Requirement: Import propagation
For each imported active punishment the system SHALL invalidate cached state for the owner and publish `punishment.created` with `importSource` set to the plugin name, plus `profile.created` when a profile was created. History-only punishments SHALL NOT be published as active punishments.

#### Scenario: Active imported ban is enforced
- **WHEN** an active LiteBans ban is imported for a player without a profile
- **THEN** `profile.created` and `punishment.created` are published for that owner with `importSource` `litebans`

#### Scenario: History is not announced as active
- **WHEN** a removed LiteBans ban is imported
- **THEN** no `punishment.created` event marks it active
