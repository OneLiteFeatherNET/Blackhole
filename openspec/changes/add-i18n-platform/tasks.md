# Tasks

Conventions for every task:
- **Red first.** The test named in the task is written and fails for the stated reason before production code changes.
  A bug fix starts with a failing reproduction test.
- **F.I.R.S.T.** Unit tests use no sleep, no system clock (inject `Clock`), no network, no shared static state,
  and `@TempDir` for files. Scheduler and NATS are fakes that the test drives by hand.
- **Agents.** All implementation, tests, lookups, log triage and PR commands go to Haiku subagents (`model: "haiku"`,
  `isolation: "worktree"`, branch from the up-to-date `origin/main` or from the previous PR of this stack). The main
  session reviews each diff and test run before the next PR starts.
- **Stack.** PR `N+1` is based on the branch of PR `N`. Every PR title is a valid Conventional Commit (lower case,
  no trailing period). Each commit has one type.
- **Client spec.** Any PR that changes an HTTP operation regenerates `client/specs` and runs `./gradlew :client:openApiGenerate`.

## 1. Pre-work (no PR, findings go into design.md)

- [ ] 1.1 Pin the Adventure version bundled by Velocity 4.3.0. Run `./gradlew :velocity:dependencies --configuration compileClasspath`
  and grep for `adventure`. Record the version in `design.md` (Context) and state whether `MiniMessageTranslator` (since 4.20.0)
  exists. If it does not, `CatalogTranslator` implements `Translator` directly (design D8). Agent: haiku.
- [ ] 1.2 Check the exact `StandardTags` factory names and the `MiniMessageTranslator#getMiniMessageString` signature against
  the pinned Adventure javadoc, and write them into the Velocity task notes. Agent: haiku.

## 2. PR A1: `feat(i18n): add the translation store and validation` (backend)

Files (new unless marked): `backend/src/main/resources/db/migration/V<n>__i18n_catalog.sql`;
`backend/src/main/java/net/onelitefeather/blackhole/backend/i18n/CatalogKeyKind.java`,
`CatalogTextValidator.java`, `I18nTextRepository.java` (JDBC, as `JdbcAppealConditionsStore`),
`I18nService.java`, `I18nDefaultsImporter.java`; resources `backend/src/main/resources/i18n/defaults-en.properties`,
`defaults-de.properties` (copies of the content keys of `velocity/src/main/resources/blackhole_{en,de}.properties`).
Tests under `backend/src/test/java/net/onelitefeather/blackhole/backend/i18n/`.

- [ ] 2.1 Red: `CatalogKeyKindTest` - each key pattern maps to its kind with the argument count and length limit;
  an unknown pattern is rejected. Then green with `CatalogKeyKind`.
- [ ] 2.2 Red: `CatalogTextValidatorTest` - table test with one behaviour per case: unclosed tag rejected with position;
  `<click:run_command:/x>` rejected; `<arg:1>` on a one-argument key rejected by name; `de` text with a missing placeholder
  reported as parity violation (warning, not rejection); length over limit rejected; `fr` rejected; `de` without `en` rejected.
  Then green with `CatalogTextValidator`. The validator takes no `Clock` and does no I/O.
- [ ] 2.3 Red: `I18nServiceTest` with an in-memory fake repository - a save takes the next catalog version under the lock
  and inserts a version one higher than the current one; a rejected save stores nothing and does not advance the catalog
  version; history is append-only (an earlier version cannot be overwritten through the service). Then green with `I18nService`.
- [ ] 2.4 Migration: `V<n>__i18n_catalog.sql` creates `i18n_catalog` (one row, version 0), `i18n_text` with the
  `(key, locale, version)` primary key and `UNIQUE(catalog_version)`, and view `i18n_current`. Seed the `en` rows of
  `punishment_sections` display names, template names and the `appeal_conditions` rows as `appeal.conditions`, with
  `INSERT ... SELECT`. Check with the scratch-DB Flyway DDL technique
  (see project memory); do not edit an applied migration.
- [ ] 2.5 Red: `I18nDefaultsImporterTest` - inserts only missing keys; running twice adds nothing; a key present in `de`
  is not overwritten. Then green. Wire it at startup behind `blackhole.i18n.import-defaults` (default true, documented in
  `application.yml` with a comment).
- [ ] 2.6 Run `./gradlew :backend:test`. Green. Commit with `feat(i18n): add the translation store and validation`.

## 3. PR A2: `feat(i18n): serve the catalog export and admin api` (backend, client)

Files: `backend/.../i18n/controller/I18nController.java` (thin), `I18nApi.java` (OpenAPI annotations),
`backend/.../i18n/dto/I18nDTO.java` (sealed marker interface with `CatalogResponse`, `TextRequest`, `TextResponse`, `Error`),
`backend/.../i18n/CatalogNatsPublisher.java` (publishes `i18n.catalog_changed` after commit, version only),
`client/specs/blackhole-api-<next>.yml`. Tests: `I18nControllerTest`, `CatalogNatsPublisherTest`, `CatalogExportTest`.

- [ ] 3.1 Red: `CatalogExportTest` - `since=120` returns entries with catalog version 121 to 125 only; `since` equal to
  the current version returns no entries; the response contains no PII; `If-None-Match` equal to the ETag gives 304
  with no body. Then green with the service method and the cursor page (`standardize-api-pagination-and-problems`).
- [ ] 3.2 Red: `I18nControllerTest` - the endpoint requires the PROXY or DISCORD role (no BOT role); a caller without one of them
  is refused; `PUT /i18n/texts/{key}/{locale}` with a bad text returns a Problem Details error with the pointer.
  Then green. The controller calls exactly one service method per endpoint.
- [ ] 3.3 Red: `CatalogNatsPublisherTest` - a publish happens once after commit with `{version}` only; a rejected save
  publishes nothing. Use a fake publisher. Then green. Subject `blackhole.events.i18n.catalog_changed`.
- [ ] 3.4 Update `client/specs/blackhole-api-<next>.yml` with the new operations and bump the spec filename in
  `client/build.gradle.kts` (`inputSpec`). Run `./gradlew :client:openApiGenerate` and `./gradlew :client:compileJava`.
- [ ] 3.6 Red: the content read endpoints (the public appeal conditions read and the template and section reads used by
  the portal and the panel) accept an optional `locale` query parameter (`en` or `de`, default `en`) and apply the
  fallback chain of design D8; an unsupported value is a 400 Problem Details error. Then green.
- [ ] 3.5 Run `./gradlew :backend:test :client:build`. Green. Commit with `feat(i18n): serve the catalog export and admin api`.

## 4. PR B: `feat(template): add a reason translation key to every escalation step` (backend)

Depends on `staff-temp-bans-elo-escalation` being merged (it owns the step table). Files: a later
`V<n+1>__template_step_reason.sql` (adds `step_id`, `reason_key` to the step table, backfills `step_id` as `s<ordinal>`
and `reason_key` as `template.<key>.step.<stepId>.reason`, and adds `reason_key`, `reason_args` to punishments);
`backend/.../punishment/TemplateStep.java`; `PunishmentTemplateService.java`; `PunishmentEntity.java`;
`PunishmentSyncHandler.java` (Redis mirror payload gains `reasonKey`, `reasonArgs`). Tests:
`TemplateStepReasonTest`, `PunishmentReasonSnapshotTest`, `PunishmentSyncPayloadTest`.

- [ ] 4.1 Red: a template with three steps saved without an `en` reason on step 2 is rejected with a Problem Details error
  naming step 2; reordering keeps each step's `stepId` and reason key. Then green.
- [ ] 4.2 Red: a punishment created from step 1 stores the key, the arguments and the plain-text `en` snapshot; changing the
  catalog text afterwards does not change the snapshot. Then green.
- [ ] 4.3 Red: reason arguments containing a player name are rejected by the PII guard. Then green.
- [ ] 4.4 Red: the Redis mirror payload contains `reasonKey` and `reasonArgs` and still contains `reason` (the snapshot).
  Then green.
- [ ] 4.5 Run `./gradlew :backend:test`. Commit with `feat(template): add a reason translation key to every escalation step`.

## 5. PR C1: `feat(velocity): add the catalog client and the live snapshot cache` (velocity)

Files (new): `velocity/src/main/java/net/onelitefeather/blackhole/velocity/i18n/CatalogClient.java` (HTTP via
`BlackholeApiClients`, `If-None-Match`, follows `nextCursor`), `CatalogSnapshot.java` (immutable record),
`CatalogCache.java` (atomic write of `plugins/blackhole/i18n-catalog.json`), `CatalogRefresher.java` (refresh on NATS
event and on a periodic tick; scheduler and clock injected), `CatalogNatsSubscriber.java` (core NATS, subscribe-only, uses
`NatsConfig`). Tests under `velocity/src/test/java/.../i18n/`. The velocity module gets its first test sources;
add the test dependencies it needs in `velocity/build.gradle.kts`.

- [ ] 5.1 Red: `CatalogSnapshotTest` - a snapshot is immutable; lookup by exact key and locale; no mutation after build.
- [ ] 5.2 Red: `CatalogCacheTest` (`@TempDir`) - a saved snapshot loads back equal; a corrupt file loads as empty, not as an
  exception; a write is atomic (temporary file, then move; a failed write keeps the old file).
- [ ] 5.3 Red: `CatalogRefresherTest` with a fake `CatalogClient` and a manual scheduler - an event with a newer version
  triggers one refresh; an event with an equal or older version does nothing; the periodic tick refreshes; a failing
  refresh keeps the old snapshot and logs the status code only; a complete response swaps the snapshot once.
- [ ] 5.4 Red: `CatalogNatsSubscriberTest` with a fake NATS connection - a message with `{version}` reaches the refresher;
  a malformed message is ignored and logged without content.
- [ ] 5.5 Run `./gradlew :velocity:test`. Commit with `feat(velocity): add the catalog client and the live snapshot cache`.

## 6. PR C2: `feat(velocity): translate punishment screens through the catalog` (velocity)

Files: `velocity/.../i18n/CatalogTranslator.java` (fallback chain), `velocity/.../BlackholeVelocity.java`
(`registerTranslations` replaced by the catalog translator, bundles kept as the last source),
`velocity/.../component/PunishmentScreens.java` (`localeOf` returns `player.getEffectiveLocale()`, the forced English line
is removed), `velocity/.../enforcement/PunishmentSyncMessage.java` (`reasonKey`, `reasonArgs`),
`ProfileMessages.java`, `StaffMessages.java`, `ClientRuleMessages.java`. Tests: `CatalogTranslatorTest`,
`PunishmentScreensLocaleTest`, `ArgumentEscapingTest`.

- [ ] 6.1 Red: `CatalogTranslatorTest` - table test over the fallback chain: `de_AT` finds `de`; `fr_FR` falls to `en`;
  a key missing everywhere returns null and logs once; a snapshot without `en` falls to the bundle. Then green.
- [ ] 6.2 Red: `PunishmentScreensLocaleTest` with a fake `Player` returning `de_DE` - the denial message is German; with
  `fr_FR` it is English. Then remove the forced English line and green.
- [ ] 6.3 Red: `ArgumentEscapingTest` - a player name containing `<red>` and a staff reason containing
  `<click:run_command:/ban>` render as literal text with no style or click event. Then green.
- [ ] 6.4 Red: a refresh of the catalog changes the next rendered message without re-registering the translator (the test
  swaps the snapshot through the cache and renders again). Then green.
- [ ] 6.5 Run `./gradlew :velocity:test`. Commit with `feat(velocity): translate punishment screens through the catalog`.

## 7. PR D: `feat(web): add the translation editor to the administration workspace` (web)

Files (new): `web/src/api/i18n.ts` (typed calls, Problem Details via the existing `http.ts`),
`web/src/views/admin/TranslationEditor.vue`, `web/src/components/i18n/FallbackPreview.vue`,
`web/src/i18n/fallback.ts` (the chain, pure). Locale keys in `web/src/locales/en.json` and `de.json`. Routes in the
Administration workspace router. Tests: `web/src/i18n/fallback.test.ts`, `web/src/api/i18n.test.ts`,
`web/src/views/admin/TranslationEditor.test.ts`, extend `web/src/i18n.test.ts` (key parity).

- [ ] 7.1 Red: `fallback.test.ts` - the same chain table as 6.1 (exact, language, en), and the preview label names the
  locale the text came from. Then green.
- [ ] 7.2 Red: `TranslationEditor.test.ts` - a key without `de` shows the missing marker; a parity violation shows its
  marker; a rejected save keeps the typed text and shows the message from the Problem Details `detail`; the history
  view has no edit control. Use the existing component test setup. Then green.
- [ ] 7.3 Red: `i18n.test.ts` key parity for the new keys in `en.json` and `de.json`. Then green.
- [ ] 7.4 Run `npm test` and the type check in `web/`. Commit with `feat(web): add the translation editor to the administration workspace`.

## 8. PR E: `feat(portal): render backend texts in the player's language` (portal)

Files: `portal/src/locale/resolveLocale.ts` (new, pure: stored choice, then `Accept-Language`, then `en`), `portal/src/locale/storage.ts`
(new: cookie or `localStorage` wrapper, every access in try/catch), `portal/src/i18n.ts` (uses the resolved locale; the
"browser language is never read" rule is removed), `portal/src/components/LocaleSelect.vue` (new),
`portal/src/locales/{en,de}.json`. No server route and no server-side profile. Content requests carry the resolved locale
as a `locale` query parameter. Tests: `resolveLocale.test.ts`, `storage.test.ts`, `LocaleSelect.test.ts`, `i18n.test.ts` (parity).

- [ ] 8.1 Red: `resolveLocale.test.ts` - table test: header `de-AT,en;q=0.5` gives `de`; `fr-FR` gives `en`; no header gives `en`;
  a malformed header gives `en`; a stored `de` beats an `en` header; a stored value `fr` is ignored. Then green.
- [ ] 8.2 Red: `storage.test.ts` - a storage that throws on every access makes the resolver fall back to the header
  without throwing; a stored choice is written only to the browser storage and no request is made. Then green.
- [ ] 8.3 Red: `LocaleSelect.test.ts` - choosing `de` writes the choice to the browser, switches the UI strings and makes
  the next content request carry `locale=de`. Then green.
- [ ] 8.4 Red: `i18n.test.ts` key parity for every new key in `en.json` and `de.json`. Then green.
- [ ] 8.5 Run `npm test` and the type check in `portal/`. Commit with `feat(portal): render backend texts in the player's language`.

## 9. PR F: `feat(discord): localise embeds from the catalog` (discord)

Files: `discord/.../HttpBackendGateway.java` (catalog fetch with ETag), `discord/.../i18n/CatalogText.java` (new; chain with
`en` last, plain-text rendering), the embed builders that use `Lang`. Tests under `discord/src/test/java/...`:
`CatalogTextTest`, `EmbedLocaleTest`.

- [ ] 9.1 Red: `CatalogTextTest` - chain order (exact, language, en); a guild locale is used before the user locale; the output is
  plain text (no MiniMessage tags survive). Then green.
- [ ] 9.2 Red: `EmbedLocaleTest` - a German guild gets German embed text; a guild without a locale falls back to the user
  locale; neither falls back to `en`. Then green.
- [ ] 9.3 Run `./gradlew :discord:test`. Commit with `feat(discord): localise embeds from the catalog`.

## 10. Follow-up PR: `docs(openspec): localise the kick messages in add-kick` (openspec)

- [ ] 10.1 Amend `openspec/changes/add-kick/proposal.md` and its delta spec: kick reasons and the kick screen come from the
  catalog (`kick.reason.<id>`), not English only. Run `openspec validate --all --strict`. Commit with
  `docs(openspec): localise the kick messages in add-kick`.

## 11. Open the pull requests (last task)

- [ ] 11.1 For each PR in this order: the proposal (`docs(openspec): propose i18n for content and the minecraft proxy`),
  A1, A2, B, C1, C2, D, E, F and the add-kick follow-up. Push the branch, then run
  `gh pr create --base <previous branch or main> --title "<commit line from proposal.md Commit plan>"`. The PR body ends
  with the line `https://claude.ai/code/session_01TFcvDovaYNHZewdbM2f8Ru`. Do not merge. Run `gh pr checks --watch` on each
  PR once it is open. Agent: haiku.
