## Context

The vanilla import (`openspec/specs/vanilla-import`) reads a whole `banned-players.json` into memory
(`CompletedFileUpload.getBytes()`), links each entry to a template through
`ImportTemplateResolver`, dedupes by owner plus the vanilla `created` timestamp, and leaves
retemplating to `punishment-management` (`GET /admin/import/punishments`,
`POST /admin/import/punishments/retemplate`). The `KICK` kind exists on main (`V27__kick.sql`,
`PunishmentKind`). The change `add-warnings`, the change `add-ip-bans-via-firewall` and the change
`amend-data-protection-principle` are not in `openspec/changes/` on main as of 2026-10-28, so this
proposal treats them as unavailable and names the fallback for each.

### Source schemas (researched 2026-10-28)

| | LiteBans | AdvancedBan | BanManager |
|---|---|---|---|
| Ban, mute rows | `litebans_bans`, `litebans_mutes`: `uuid`, `ip`, `reason`, `banned_by_uuid`, `banned_by_name`, `removed_by_uuid`, `removed_by_name`, `removed_by_date`, `time` (ms), `until` (ms, `-1` = permanent), `server_scope`, `server_origin`, `silent`, `ipban`, `ipban_wildcard`, `active` | `Punishments` (active) and `PunishmentHistory` (every punishment): `id`, `name`, `uuid` (player UUID, or the IP for IP types), `reason`, `operator` (staff name, no UUID), `punishmentType`, `start` (ms), `end` (ms, `-1` = permanent), `calculation` | `bm_player_bans`, `bm_player_mutes` (active) and `bm_player_ban_records`, `bm_player_mute_records` (past): `player_id`, `actor_id` (both binary UUIDs, names in `bm_players`), `reason`, `created`, `updated`, `expires` (seconds, `0` = permanent), `silent`; mutes add `soft`, `onlineOnly` |
| Warnings | `litebans_warnings`: as bans plus `warned` (player was told), `active` | `WARNING` / `TEMP_WARNING` rows in both tables | `bm_player_warnings`: `player_id`, `actor_id`, `reason`, `created`, `expires` (seconds), `read`, `points` |
| Kicks | `litebans_kicks`: `uuid`, `ip`, `reason`, `banned_by_*`, `time`, `until`, `server_*`, `silent`; no active flag | `KICK` rows, history only (`Punishment.create` skips `Punishments` for kicks) | `bm_player_kicks`: `created` and actor, no active flag |
| IP bans | `litebans_bans` / `litebans_mutes` with `ipban = 1`; `ipban_wildcard = 1` marks a range such as `172.16.0.0/16` | `IP_BAN` / `TEMP_IP_BAN` rows; the IP sits in `uuid` (BanManager's converter reads it that way) | `bm_ip_bans`, `bm_ip_mutes`, `bm_ip_range_bans` (`ip` as `VARBINARY(16)`) |
| Player history | `litebans_history`: `uuid`, `name`, `ip`, `date` | `name` column on each row | `bm_player_history`: `player_id`, `name`, `ip`, `join`, `leave` (seconds) |
| Active flag | `active` column (`bit`) | Presence in `Punishments` | Presence in `bm_*` active tables; past rows in `*_records` |
| Staff identity | UUID and name (`banned_by_uuid`, `banned_by_name`), `CONSOLE` for console | Name only (`operator`, 16 chars) | Binary UUID in `actor_id`, name in `bm_players` |
| Server scope | `server_scope`, `server_origin` | none | none on bans (the section is the scope) |
| Table names | prefix fixed by config (`litebans_`) | fixed | configured in `config.yml` `tables:` (defaults `bm_player_bans` etc.) |

Sources:
- LiteBans column set and `until` rules: [BanManager `LiteBans.java` converter](https://github.com/BanManagement/BanManager/blob/HEAD/common/src/main/java/me/confuser/banmanager/common/storage/conversion/LiteBans.java) (`convertExpiry` treats `-1` and `0` as permanent; inactive rows become records); [BanManager `LiteBansImportTest.java`](https://github.com/BanManagement/BanManager/blob/HEAD/common/src/test/java/me/confuser/banmanager/common/storage/conversion/LiteBansImportTest.java) (`CREATE TABLE` for bans, mutes, warnings, kicks, history).
- Real LiteBans table as MariaDB dump (`litebans_bans`, with `template`, `removed_by_reason`, `active bit`): [HeliCraft-MC/HeliCraft-monorepo, `archive/apps/teapot/.dev/dbs/litebans_bans.sql`](https://github.com/HeliCraft-MC/HeliCraft-monorepo/blob/HEAD/archive/apps/teapot/.dev/dbs/litebans_bans.sql).
- AdvancedBan tables, types and the active/history split: [`DevLeoko/AdvancedBan` `SQLQuery.java`](https://github.com/DevLeoko/AdvancedBan/blob/HEAD/core/src/main/java/me/leoko/advancedban/utils/SQLQuery.java), [`PunishmentType.java`](https://github.com/DevLeoko/AdvancedBan/blob/HEAD/core/src/main/java/me/leoko/advancedban/utils/PunishmentType.java), [`Punishment.java`](https://github.com/DevLeoko/AdvancedBan/blob/HEAD/core/src/main/java/me/leoko/advancedban/utils/Punishment.java) (`create` writes history always, `Punishments` unless a kick; `delete` removes from `Punishments` only).
- AdvancedBan import as BanManager does it (`start` and `end` divided by 1000, `-1` permanent, IP types read from `uuid`): [BanManager `AdvancedBan.java` converter](https://github.com/BanManagement/BanManager/blob/HEAD/common/src/main/java/me/confuser/banmanager/common/storage/conversion/AdvancedBan.java).
- BanManager tables and units: [`config.yml`](https://github.com/BanManagement/BanManager/blob/HEAD/common/src/main/resources/config.yml) (`tables:`), [`PlayerBanData.java`](https://github.com/BanManagement/BanManager/blob/HEAD/common/src/main/java/me/confuser/banmanager/common/data/PlayerBanData.java) (`expires` `0` = never, seconds), [`PlayerWarnData.java`](https://github.com/BanManagement/BanManager/blob/HEAD/common/src/main/java/me/confuser/banmanager/common/data/PlayerWarnData.java), [`PlayerHistoryData.java`](https://github.com/BanManagement/BanManager/blob/HEAD/common/src/main/java/me/confuser/banmanager/common/data/PlayerHistoryData.java), and the migration deltas in [`db/local/V1__baseline.sql`](https://github.com/BanManagement/BanManager/blob/HEAD/common/src/main/resources/db/local/V1__baseline.sql). The full baseline `CREATE TABLE` is generated by the ORMLite entity classes, so the column list above is taken from those entities plus the migration; verify on a real export before implementation (open question 1).

Two timing facts drive the mapping: LiteBans and AdvancedBan store epoch milliseconds, BanManager
stores epoch seconds. A permanent ban is `-1` (LiteBans, AdvancedBan end) or `0` (BanManager
expires).

## Goals / Non-goals

Goals: import the active state and the history of bans, mutes, warnings and kicks from all three
plugins; keep staff attribution honest; make re-runs and partial failures safe; keep memory bounded
for large exports; reuse the vanilla dry run, reason mapping and retemplate UI.

Non-goals: a live connection, name or IP history, notes, reports, evidence, appeals, enforcing IP
bans, and any change to the vanilla import's behaviour.

## Decisions

### 1. Input mode: per-table CSV upload, no database connection

The admin exports each table the plugin needs as CSV with the source's own tooling and uploads
the files in one multipart request. Export recipes (`mysql --batch`, `sqlite3 -csv`, H2
`CALL CSVWRITE`, and for BanManager `BIN_TO_UUID` for the binary UUID and `INET6_NTOA` for IPs)
go into the import panel help text.

Rejected: **direct read-only JDBC connection** (the option the brief names). It makes the backend
open an outbound connection to an address an admin typed. Even with an allow-list of hosts, the
JDBC URL carries driver properties that can change what the driver does on connect (for example
MySQL's local-file and deserialization options), so the URL would need a parser and a deny-list of
properties. It also cannot reach the file-based databases that LiteBans (SQLite by default) and
BanManager (H2 by default) use unless the file is on the backend host. Principle 5 requires SSRF
hardening before any such request, and this design avoids the request altogether. A JDBC mode can
be a later change with its own validation.

Rejected: **SQL dump upload** (`mysqldump`, `.sql` files). Each database speaks its own dialect
(MariaDB, H2, SQLite), a full dump carries every table, and executing uploaded SQL is off the
table. A parser for three dialects costs more than the CSV reader it would replace.

### 2. Upload contract: one part per table, streamed, validated headers

`POST /admin/import/plugin` takes `source` (`litebans`, `advancedban` or `banmanager`), a
`sourceLabel` (free text, recorded on each record for the operator, not part of the dedupe key;
see decision 3), `templateKey` and `muteTemplateKey` (query values, see decision 9),
`reasonTemplates` (text part, as the vanilla import), `dryRun`, and one file part per table:

| source | required parts | optional parts |
|---|---|---|
| `litebans` | `bans`, `mutes`, `warnings`, `kicks`, `history` | none |
| `advancedban` | `punishments`, `punishmentHistory` | none |
| `banmanager` | `bans`, `banRecords`, `mutes`, `muteRecords`, `warnings`, `kicks`, `ipBans`, `ipMutes`, `ipRangeBans` | `players`, `history` |

`players` (BanManager) resolves the actor name for `actor_id`. A missing required part is HTTP 400
and nothing is written. Each file's first line is its header; the reader requires the columns
listed in the spec and ignores extra columns. An unknown or missing required column is HTTP 400
naming the file and column, before any row is read. `players` and `history` are read only when
present; without them, actor names fall back to the empty value and history is counted as skipped.

### 3. Idempotency by source row: a record table

Every source row that produces a punishment or a pending record writes one row to
`plugin_import_record` (migration `V<next>__plugin_import_record.sql`):
`source_plugin`, `source_key` (unique together with `source_plugin`), `outcome`
(`IMPORTED`, `PENDING_IP`), `punishment_identifier` (nullable), `pending_payload` (nullable JSON),
`created_at`. The source key is:

- LiteBans: `<table>:<id>` (`bans:12`, `mutes:4`, `warnings:9`, `kicks:3`).
- BanManager: `<table>:<id>` for the table's own row id.
- AdvancedBan: `<uuid>|<start>|<punishmentType>`, the same natural key `Punishment` uses
  (`SELECT_EXACT_PUNISHMENT`). A row present in both `Punishments` and `PunishmentHistory` is one
  punishment, so the history row is skipped once the active row is imported, and a history row
  alone is imported as history.

Re-running the same files skips every row with a record and counts it as `skippedExisting`.
Because the record is written after the punishment, a crash in between leaves a row without a record;
the next run imports it once. Duplicate punishments are therefore possible only in that window and
are avoided by checking the punishment's natural key (owner, section, `createdAt`, source
`importSource`) before writing.

Rejected: **dedupe by the vanilla rule** (owner plus `created` timestamp in `metaData`). It cannot
tell two warnings written in the same millisecond apart, and it does not cover pending rows.

The `sourceLabel` is not part of the key: a second export of the same database must dedupe against
the first. Two different databases of the same network are an operator error the open question
below covers.

### 4. Staff: legacy name on the record, never a verified actor

Every imported punishment has `source` set to the deterministic identity
`UUID.nameUUIDFromBytes("blackhole-import-<source>")`, the same approach as `SYSTEM_IMPORT_SOURCE`
in the vanilla import, and `recordedByOid` set to the verified caller. The legacy staff value goes
into `metaData`: `importedActorName` (the name as written in the source) and, where the source
has a UUID, `importedActorUuid`. A console actor (`CONSOLE` in LiteBans, the operator name in
AdvancedBan) gets the name only.

The UI shows a legacy actor as "legacy staff: <name>, not linked". No `actorOid` is ever set
from a legacy value, so audit reads cannot mistake an old moderator for a current staff member.
Keeping `importedActorUuid` as clear text relies on `amend-data-protection-principle` (legitimate
interest for staff attribution, with the existing retention rule); without that change the
UUID is hashed with `UUIDHasher` instead.

### 5. Active versus history

| source state | result |
|---|---|
| LiteBans `active = 1` and (`until = -1` or `until` in the future) | active punishment, enforced |
| LiteBans `active = 1` and `until` in the past | history (expired) |
| LiteBans `active = 0` | history (removed); `removed_by_*` into `metaData` |
| AdvancedBan row in `Punishments`, not expired | active |
| AdvancedBan row only in `PunishmentHistory`, or expired temp type | history |
| BanManager row in `bm_player_bans` / `bm_player_mutes`, `expires` `0` or in the future | active |
| BanManager row in `*_records` | history |

History is written with the punishment marked lifted, so it reads like a revoked punishment and
never takes an active slot.

### 6. Permanent versus temporary

Permanent when LiteBans `until` is `-1` or `0`, AdvancedBan `end` is `-1` or the type is not a temp
type, or BanManager `expires` is `0`. Otherwise the expiry is the source value converted to
milliseconds (`× 1000` for BanManager). Timestamps outside a sane range (before 2010-01-01 or more
than one day in the future) are counted invalid, because they usually mean the wrong unit.

### 7. IP bans, IP ranges and IP mutes: pending records, not enforced

Blackhole has no firewall-backed IP ban yet. Each IP row (LiteBans `ipban = 1`, AdvancedBan
`IP_BAN`/`TEMP_IP_BAN`, BanManager `bm_ip_*`) is stored as a `plugin_import_record` row with
`outcome = PENDING_IP` and `pending_payload` holding the kind (`IP`, `IP_RANGE`, `IP_MUTE`), the
address or CIDR, reason, expiry, legacy actor and source key. Nothing is enforced and no punishment
is created. The summary counts them as `ipRecordsPending`.

Rejected: **enforce imported IP bans through Velocity only**. It would create a second enforcement
path that the firewall change is meant to replace, and it would deny logins without the OPNsense
alias the operator relies on. Rejected: **drop IP rows**, because the operator still needs the
list to apply it through the firewall later. The follow-up tasks convert `PENDING_IP` rows once
`add-ip-bans-via-firewall` exists.

Player rows of an IP ban are not imported as player bans: LiteBans sets `ipban = 1` on a row that
also carries a UUID, and the BanManager converter imports only the IP part.

### 8. Warnings

A warning becomes a `WARN` punishment once `add-warnings` defines the kind. Until then it is a
history-only punishment with `metaData.importedKind = WARNING`, never active, linked to
`templateKey`. LiteBans `warned` and BanManager `read` are kept in `metaData` (`importedWarnRead`).
BanManager `points` is kept as `importedWarnPoints`. The fallback is a conscious gap: warnings
stop counting as warnings in the UI until the kind lands, and the follow-up task rewrites
`importedKind = WARNING` history rows into `WARN` rows.

Rejected: **block the whole import until `add-warnings` lands**. Bans and mutes are the urgent part
of a migration and should not wait for a second feature.

### 9. Kicks

Kicks become `KICK` punishments (kind from `V27`), with no duration and never active, and no
`templateKey` link. Kick rows are never listed in the imported-punishments retemplate list (see
`punishment-management`). LiteBans kicks have `until` but no active flag; the value is ignored.

### 10. Mutes need a mute template

The vanilla rule refuses mute templates because vanilla imports only bans. Plugin imports carry
mutes, so the import takes a second key, `muteTemplateKey`, that must name a template in a mute
section. Ban, IP-ban and warning imports keep requiring a non-mute `templateKey`. A mute row linked
to a ban template, or a ban row linked to a mute template, is refused before anything is written.
Reason mapping (`reasonTemplates`) applies to each type with the same rule.

### 11. Large files: streamed, batched, no outer transaction

The upload is read as `StreamingFileUpload` rather than `CompletedFileUpload.getBytes()`. Each
table is parsed line by line with a streaming CSV reader (library choice at implementation time,
added to the version catalog if none is present). Rows are processed in batches of 500:
the batch is read, each row is checked against the record table, then written through the
repository, and events are published per row after its write. No transaction spans the import,
because `@Transactional` is unusable in this codebase (see `CLAUDE.md`). Idempotency (decision 3)
is what makes an interrupted run safe to repeat.

Memory is bounded by the batch size, not the file size. Limits are configured in `application.yml`
with `${ENV_VAR:default}` defaults and a comment: `blackhole.import.max-upload-bytes` (default
512 MiB) and `blackhole.import.max-rows-per-table` (default 500000). Exceeding a limit is HTTP 413
(upload) or HTTP 400 (rows) and nothing is written. The synchronous request will not finish a
500000-row import within a typical proxy timeout; the open question on an async job covers that.

### 12. Dry run

`dryRun=true` runs the same reads, checks and mapping, reads the record table for skips, and
writes nothing and publishes no event. The summary counts per file: `rows`, `imported`
(active and history), `kicks`, `warningsHistoryOnly`, `ipRecordsPending`, `skippedExisting`,
`invalid` with row numbers and reasons (never row content, see the spec), and `reasonGroups` with
the template each reason would get, as the vanilla summary does.

### 13. Endpoint, DTOs and layering

A thin `PluginImportController` (hidden from the published spec, role `ADMIN`) calls one
`PluginImportService` method. The service is a `@Singleton` in the `imports` package and owns the
readers, the mapper and the record repository. The result is a sealed `PluginImportDTO` marker
interface with `Response` and `Error` records (micronaut-dto-contract), so expected failures are
returned, not thrown.

### 14. Retemplate and the imported-punishments list are reused, not copied

Imports carry `metaData.importSource` (`litebans`, `advancedban`, `banmanager`, next to `vanilla`),
`importedReason` and the legacy actor. The existing list and retemplate endpoints (see the
`punishment-management` delta) cover them. Kicks are excluded. The web panel reuses the same
reason-grouped table and template picker.

## Risks / Trade-offs

- **Export fidelity.** Binary UUIDs and `bit` columns export differently per tool. A wrong recipe
  yields rows that all fail the UUID check, which the dry run reports. Mitigation: the recipes are
  tested against a sample export per source in the runtime verification task.
- **Wrong time unit.** Milliseconds and seconds differ by 1000. The sanity window in decision 6
  rejects most mistakes, and the dry run shows the expiry distribution.
- **Two databases, one network.** Source keys are per source row, so importing the export of a
  second, different database for the same network can double-count players. Mitigation: the
  summary shows the `sourceLabel` of each prior import, and the operator is told in the panel.
- **Event volume.** Thousands of `punishment.created` events on NATS in one request. Batches
  publish in order and the proxy mirror tolerates it, but the first real import should run in a
  staging deployment.
- **Warnings as history-only** show up as history until `add-warnings` lands (decision 8).
- **Pending IP rows hold IP addresses** until the firewall change converts or deletes them. This
  relies on `amend-data-protection-principle`, and pending rows get the same retention rule as
  other IP data.
- **Legacy staff names** identify former moderators. They are shown only to ADMIN.

## Migration plan

1. Add `V<next>__plugin_import_record.sql` (additive: one table, unique key on
   `(source_plugin, source_key)`). No existing row changes.
2. Ship the backend, then the web panel. The endpoint is hidden, so the generated client stays
   unchanged unless an operation becomes published (checked in tasks).
3. Run a dry run per source on a staging copy of a real export, then the import.
4. Rollback of an import: delete the punishments whose `metaData.importSource` is the plugin and
   the matching `plugin_import_record` rows, in one admin SQL script per deployment. Not an API.
5. Follow-ups, after the dependent changes land: convert `PENDING_IP` records into IP bans,
   convert `importedKind = WARNING` history into `WARN` rows.

## Open questions

1. Confirm the LiteBans and BanManager column sets against a real export from the current release
   of each plugin (the research used the BanManager test schema, a MariaDB dump of LiteBans, and
   BanManager's ORMLite entities; the upstream LiteBans SQL was not retrievable at research time).
2. Keep `sourceLabel` as informational only (current design), or make it part of the dedupe key and
   refuse a second database under the same label?
3. Should kick rows from `KICK` history be linked to a template, or stay template-less as now
   (decision 9)?
4. Is a synchronous request acceptable up to the row cap, or does a large import need an
   asynchronous job with a progress endpoint? The answer sets whether tasks include a job queue.
5. Do soft mutes and `onlineOnly` BanManager mutes map to MUTE, or stay history-only until the
   mute model covers them? The proposal assumes history-only and a count in the summary.
6. Confirm that `amend-data-protection-principle` exists before implementation starts. The change
   is named in the brief but is not on main.
