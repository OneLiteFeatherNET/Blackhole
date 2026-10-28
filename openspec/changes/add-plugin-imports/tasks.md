## 1. Record table and migration

- [ ] 1.1 Add `V<next>__plugin_import_record.sql` (next free version on main; V27 today): table `plugin_import_record` with `source_plugin`, `source_key`, `outcome`, `punishment_identifier` (nullable), `pending_payload` (nullable), `created_at`, unique on (`source_plugin`, `source_key`)
- [ ] 1.2 Add the `PluginImportRecordEntity` and its repository in the `imports` package
- [ ] 1.3 JUnit tests for the repository's unique-key behaviour against a scratch database created from the migration (no shared state, fresh schema per test class)
- [ ] 1.4 Verify the migration against a scratch database as in the blackhole verification pattern

## 2. Source readers

- [ ] 2.1 Streaming CSV reader that takes a `InputStream`, reads the header, checks required columns, and yields rows one at a time
- [ ] 2.2 Per-source column definitions for LiteBans (`bans`, `mutes`, `warnings`, `kicks`, `history`), AdvancedBan (`punishments`, `punishmentHistory`) and BanManager (`bans`, `banRecords`, `mutes`, `muteRecords`, `warnings`, `kicks`, `ipBans`, `ipMutes`, `ipRangeBans`, `players`, `history`)
- [ ] 2.3 JUnit tests for the reader: missing column rejected before the first row, extra columns ignored, quoted commas and newlines, an empty file, a file with only a header (all in-memory, no real filesystem)
- [ ] 2.4 JUnit tests per source for the column definitions (one fixture stream per table, built in the test)

## 3. Mapping to punishments

- [ ] 3.1 Mapper for LiteBans rows: active versus history, permanent versus temporary (`-1` and `0`), IP rows to pending records, warnings to history-only with `importedKind`, kicks to `KICK`
- [ ] 3.2 Mapper for AdvancedBan rows, including the natural source key and the rule that a row present in both tables is one punishment
- [ ] 3.3 Mapper for BanManager rows: seconds to milliseconds, `expires` `0` as permanent, binary UUIDs and `players` name lookup
- [ ] 3.4 Time-unit sanity window (before 2010-01-01 or more than one day in the future is invalid), in a pure function with an injected `java.time.Clock`
- [ ] 3.5 JUnit tests for every mapper rule above, one behaviour per test, with a fixed `Clock` and no system time

## 4. Import service

- [ ] 4.1 `PluginImportService` (`@Singleton`, `imports` package): reads each file as a stream, checks the record table for each source key, writes through the repositories in batches of 500, publishes events per row after its write, returns a sealed `PluginImportDTO.Response` or `Error`
- [ ] 4.2 Dry run path: runs the reads, checks and mapping, reports counts and `reasonGroups`, and writes nothing and publishes nothing
- [ ] 4.3 Limits from `blackhole.import.max-upload-bytes` and `blackhole.import.max-rows-per-table`, documented with a comment in `application.yml` using `${ENV_VAR:default}`
- [ ] 4.4 Template checks before any write: `templateKey` and `muteTemplateKey` exist, are enabled, and match the kind of the rows that use them
- [ ] 4.5 JUnit tests with in-memory fakes for the repositories and a fixed `Clock`: idempotent re-run, interrupted run completed, dry run writes nothing, mute row without mute template refused, oversized table refused, invalid rows never echo their content, `punishment.created` published only for active rows with `importSource` set, no ELO change

## 5. Controller and contract

- [ ] 5.1 `PluginImportApi` interface with the OpenAPI annotations, hidden from the published specification like the vanilla import
- [ ] 5.2 `PluginImportController` (`@Version`, `ADMIN` role) with `@Part` file parts through `StreamingFileUpload`, one service call, and mapping of the result to an `HttpResponse`
- [ ] 5.3 JUnit tests for the controller's response mapping (200, 400, 413, 403) with the service faked
- [ ] 5.4 Regenerate the OpenAPI client spec with `./gradlew :client:openApiGenerate` and confirm no generated operation changed; if one did, bump the spec version in `client/build.gradle.kts`

## 6. Retemplate and list

- [ ] 6.1 Extend `GET /admin/import/punishments` to every import source and exclude kicks
- [ ] 6.2 Allow a mute-type import to be linked to a mute-section template and refuse a ban-type import to one
- [ ] 6.3 JUnit tests for the grouped list across sources, the kick exclusion, the mute and ban rules, and the unchanged never-overwrite rule

## 7. Admin web UI

- [ ] 7.1 Extend the import panel (`web/src/components/admin/VanillaImportPanel.vue`) with a source selector, one file input per table, the source label and the mute template picker
- [ ] 7.2 Show per-file summary counts and a separate line for pending IP records
- [ ] 7.3 Extend the imported-punishments section with the source and the legacy staff label
- [ ] 7.4 German and English texts in `web/src/locales/de.json` and `en.json`
- [ ] 7.5 Component tests (Vitest, as in `VanillaImportPanel.test.ts`): disabled buttons until a template and the required files are chosen, mute templates not offered for bans, per-file counts shown, pending IP line shown, legacy staff label shown; no real network (the API client is faked)

## 8. Runtime verification

- [ ] 8.1 Build a sample export for each source from a real plugin database running in the local Docker setup, including one permanent, one temporary, one expired, one removed, one kick, one warning, one IP ban, one IP range and one mute per source
- [ ] 8.2 Run the dry run and the import against the local backend and check the summary, the active list and the history of each sample player
- [ ] 8.3 Run the same import a second time and confirm every row is counted as skipped and no punishment was added

## 9. Follow-ups gated on other changes

- [ ] 9.1 When `add-warnings` lands: convert `importedKind = WARNING` history rows into `WARN` rows
- [ ] 9.2 When `add-ip-bans-via-firewall` lands: convert `PENDING_IP` records into IP bans, IP ranges and IP mutes with their expiry
- [ ] 9.3 When `amend-data-protection-principle` lands: confirm the clear-text legacy UUID and IP retention rules, or switch to hashing

## 10. Ship

- [ ] 10.1 Open the code pull request under the title `feat(imports): add plugin imports from LiteBans, AdvancedBan and BanManager`, with no file under `openspec/`
