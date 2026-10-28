## Why

A network that switches from LiteBans, AdvancedBan or BanManager to Blackhole today has exactly one
import path: a vanilla `banned-players.json`. Those three plugins hold years of bans, mutes,
warnings and kicks, and most of them are not in a vanilla file at all. Without an import, staff
start with an empty history, and a player who is still banned on the old setup can join the new
network until a moderator notices and re-enters the ban by hand. Staff currently have to choose
between re-typing hundreds of punishments and accepting that old offences disappear.

## What Changes

- New capability `plugin-import`: an admin uploads the per-table exports of a LiteBans,
  AdvancedBan or BanManager database and the backend imports them through the same dry run,
  summary, reason-template mapping and retemplate flow the vanilla import already uses.
- Input is file upload only. The backend never opens a connection to the old database, so no
  caller-supplied address is ever dialled (principle 5).
- Mapping: active bans and mutes are enforced in their section, inactive or expired ones are
  history; kicks become `KICK` history; warnings become `WARN` once `add-warnings` lands and
  history-only until then; IP bans, IP ranges and IP mutes become pending import records that
  are not enforced until the firewall change lands; the legacy staff name is kept on the record,
  never as a verified staff actor.
- Re-running an import is idempotent: every source row is recorded once and skipped on later runs.
- `admin-web-ui`: the import panel gets a source selector and one file input per table; the
  imported-punishments section lists the imports of every source.
- `punishment-management`: the imported-punishments list and the retemplate endpoint cover all
  import sources; kicks are excluded; a mute import links to a mute template.
- Not BREAKING. The vanilla endpoint and its responses are unchanged. The new endpoint is hidden
  from the published API like the vanilla one, so the generated client is expected to gain no
  operation (checked in tasks).

## Capabilities

### New Capabilities
- `plugin-import`: file-based import of LiteBans, AdvancedBan and BanManager exports.

### Modified Capabilities
- `admin-web-ui`: the import panel and the imported-punishments section cover all sources.
- `punishment-management`: the imported-punishments list and retemplate rules cover all import
  sources and distinguish ban-type from mute-type imports.

## Impact

- Backend: `imports/` package (new source readers, a streaming import service, a record table);
  `punishment` package only through its existing entities and `metaData` keys.
- Database: one new Flyway migration, `V<next>__plugin_import_record.sql` (main is at V27 today; the number is assigned at implementation time). Existing rows are not changed.
- Events: `punishment.created` and `profile.created` keep their shapes; `importSource` carries the
  plugin name.
- Admin web UI: the import panel and the imported-punishments section.
- Operations: a configured upload size limit and row cap; memory stays bounded because files are streamed.

Ships as: feat(imports): add plugin imports from LiteBans, AdvancedBan and BanManager

## Out of scope

- A live or direct database connection to the old plugin, whether JDBC or any other protocol.
- Importing names and IP addresses from the old player-history tables (name history depends on the
  information-gathering work and is a later change).
- Importing the old plugins' notes, reports, evidence, appeals, reason lists or configuration.
- Incremental sync from a plugin that is still running; an import is a one-off snapshot.
- Enforcing imported IP bans (that is `add-ip-bans-via-firewall`; this change only stores them).
- BanManager's global cross-server tables.
- An asynchronous import job queue; v1 streams synchronously with a row cap.
