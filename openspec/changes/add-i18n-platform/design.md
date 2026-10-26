# Design

## Context

See proposal.md - Why. Current state, verified in the repository on 2026-10-26:

- **Velocity** (`velocity/`, Velocity API 4.3.0 per `settings.gradle.kts`): `BlackholeVelocity.registerTranslations`
  already builds a `MiniMessageTranslationStore` from `ResourceBundle`s (`blackhole_en`, `blackhole_de`) and adds
  it to `GlobalTranslator`. `PunishmentScreens.localeOf` returns `Locale.ENGLISH` regardless of the client.
  Texts use the `bh:` theme (`BlackholeTheme`) and `<arg:n>` placeholders.
- **Backend** (`backend/`): latest migration `V26__staff_profile.sql`. `TemplateStep` holds `duration` and `eloCost`
  only, no reason. The escalation model (template steps as their own table, `escalation_group`) is in the
  unmerged change `staff-temp-bans-elo-escalation`. Appeal conditions are an append-only table
  (`V19__appeal_conditions.sql`, version 1 seeded in English).
- **Web** (`web/`) and **portal** (`portal/`): vue-i18n 11, `en.json`/`de.json`. The portal's `i18n.ts` deliberately
  does not read the browser language. The panel spec `admin-web-ui` says "defaulting to German"; the code defaults to `en`.
- **Discord** (`discord/`): `Lang` (EN/DE) from the Discord locale; reply strings in `Messages`.
- **Event bus**: NATS JetStream (`switch-event-bus-to-nats`). Velocity is subscribe-only. Subjects are
  `blackhole.events.<type>` (D1 of that change).
- **Not verified**: the Adventure version bundled by Velocity 4.3.0. The local Gradle cache holds only Adventure
  4.26.1 and 5.2.0 and Velocity API 3.5.1, and the upstream POM is not reachable from this environment. Task 1.1
  pins it before any Velocity code is written.

## Goals / Non-Goals

**Goals:** one content store for every catalog text; a per-step reason model; a proxy that refreshes translations live
and keeps working when the backend is down; a safe markup boundary between staff text and the proxy's MiniMessage.

**Non-Goals:** machine translation; locales beyond `en` and `de` (the schema allows any tag, validation does not);
translating staff free text; changing the bundles of the panel, portal and bot into the catalog.

## Decisions

### D0. Superseded decision
The interim decision "player-facing text is English only" (`add-appeal-conditions` proposal; `add-appeal-chat`
decision 7) is superseded by this change for every catalog text. It stays in force for free text typed by a player
or staff member. `add-kick` (proposal, "Player-facing kick messages are English only") is amended by task 10.1.
The superseded decision is recorded here and in the proposal; `openspec/config.yaml` is not changed.

### D1. Data model: one generic store with typed keys
**Choice:** one table `i18n_text` for all catalog texts, keyed by `(key, locale, version)`, plus one row of
`i18n_catalog` that holds the catalog version.

**Alternative:** one translation table per entity (`template_translation`, `section_translation`,
`kick_reason_translation`, `canned_comment_translation`, ...).

**Why the generic store:**
1. The proxy needs one catalog. A per-entity design means one export per entity and a merge on the proxy.
2. New content kinds are added without a migration: canned comments (`structure-case-evidence`), kick reasons
   and system texts all become a key pattern and a validator entry, not a table.
3. Validation, the admin editor, the parity check and the history view are written once.
4. Per-entity tables give foreign keys. Keys are generated from the owning entity's id, and the owning service
   deletes or renames the keys in the same transaction, so the lost integrity is small. A nightly orphan check
   reports keys with no owner (report only, never deleted).

**Schema (migration number chosen at implementation, `V<n>__i18n_catalog.sql`):**

```
i18n_catalog   (id smallint PK = 1, version bigint NOT NULL)       -- the single row that orders all saves
i18n_text      (key varchar(200), locale varchar(16), version int,
                catalog_version bigint NOT NULL,                    -- = i18n_catalog.version at save
                text text NOT NULL, created_at bigint NOT NULL, recorded_by_oid uuid NULL,
                PRIMARY KEY (key, locale, version),
                UNIQUE (catalog_version))
```

**Ordering:** a save runs `UPDATE i18n_catalog SET version = version + 1 WHERE id = 1 RETURNING version` in the
same transaction, then inserts the text with that value. The row lock serialises writers, so commit order equals
catalog version order. A plain Postgres sequence was rejected: a sequence value can be taken by a transaction that
commits later than a higher value, so a consumer polling `since=N` could skip a row for good.

**Current text:** the row with the highest `version` for `(key, locale)`. A view `i18n_current` exposes it.

**Key kinds** are code, not data: `CatalogKeyKind` maps each key pattern to its argument count, length limit and
whether it needs an `en` text. Adding a kind is a code change plus a test.

### D2. How a punishment stores its reason
A punishment created from a template step stores:

| Column | Content | Used by |
|---|---|---|
| `reason_key` | `template.<key>.step.<stepId>.reason` or `system.<id>` (null for free text) | proxy screens, live rendering |
| `reason_args` | JSON array of at most 4 strings, validated against the key kind (no player names, no IPs) | proxy screens |
| `reason` | the `en` text rendered at creation, plain text (MiniMessage tags removed) | history, audit, existing readers |

`reason` already exists and is read by the proxy sync and by the panel. It becomes the snapshot; no reader changes
meaning. Rows created before this change get `reason_key` null and keep their text. The snapshot is taken with
`PlainTextComponentSerializer` on the `en` text rendered with the arguments.

**Proxy rendering:** the Redis mirror payload gains `reasonKey` and `reasonArgs` next to `reason`. A proxy that
does not know the new fields uses `reason`, which is the English snapshot, so an old proxy shows English but never a
raw key.

### D3. Per-step reason keys
`TemplateStep` gains `stepId` (a short string such as `s1`, `s2`, assigned at creation, unique per template,
never reused) and `reasonKey`, derived from the template key and `stepId`. The reason text lives in the catalog,
not in the step row. Reordering steps does not change keys. A step can only be saved with an `en` reason.
This depends on `staff-temp-bans-elo-escalation` (the step table); the step column is appended in a later migration.

### D4. Catalog export: `GET /i18n/catalog`
- **Role:** PROXY (and, if decided in question 3, BOT). Denied by default otherwise.
- **Query:** `since` (long, default 0) and the cursor and size of the standard page model. Ordered by `catalog_version`.
- **Response:** `{ "version": <current catalog version>, "entries": [{"key", "locale", "version", "text"}], "nextCursor" }`.
  `entries` never contains PII; the text is the current version of each key and locale with `catalog_version > since`.
- **ETag:** `W/"i18n-<version>"` with the version at response time. A request whose `If-None-Match` matches gets
  `304` with no body. The proxy's periodic poll sends it, so an unchanged catalog costs one small request.
- **Versus a whole-catalog response:** the catalog is a few hundred rows; the incremental form is kept because the
  NATS event tells the proxy which version it missed and the proxy should not refetch everything.
- **Client:** the new operations are added to `client/specs/blackhole-api-*.yml`; the generated client is regenerated.

### D5. Admin API
Under `/i18n` (versioned like the other controllers, the controller/service/DTO rules apply):
- `GET /i18n/texts` cursor-paged list, one row per key with the `en` and `de` current text, the version, and a
  `missing` and `parityViolation` flag per locale.
- `GET /i18n/texts/{key}/history` (append-only list).
- `PUT /i18n/texts/{key}/{locale}` body `{ "text": "..." }`. Creates a new version. Expected failures use the
  Problem Details codes of `standardize-api-pagination-and-problems`; validation errors list each problem with a
  `pointer` into the text.
- The request and response DTOs follow the sealed marker interface contract (`CreateRequest`/`UpdateRequest`/`Response`/`Error`).

### D6. Validation on save (`CatalogTextValidator`, pure, no I/O)
1. **Length:** kind limit (500 characters for a line, 2000 for an appeal condition body).
2. **Markup:** the text parses with the catalog MiniMessage instance (D7). The instance has no `click`, `hover` or
   `insert` resolver, so those tags fail to parse.
3. **Placeholders:** the set of `<arg:n>` indices found in the text must be a subset of `0..argCount-1`. For a
   non-`en` locale the set must equal the set of the `en` text (parity). A violation is saved only as a warning
   in the listing, because a translator may need a moment; the proxy falls back to `en` for that key (see D8).
4. **Locale:** `en` or `de`. A text for a key without `en` is rejected.

### D7. Markup boundary and escaping
- The proxy's catalog MiniMessage instance is `MiniMessage.builder().tags(...)` with an explicit resolver list:
  the `bh:` theme, colour and decoration tags, newline, and `<arg:n>` (verify the exact `StandardTags` factory names
  against the pinned Adventure version in task 1.1). `click`, `hover`, `insert`, `font`, `keybind`, `translatable`
  are not in the list.
- Arguments are not parsed. The translator receives arguments as `Component.text(value)`, so a player name or a staff
  reason containing `<red>` or `<click:...>` is shown literally. Only the catalog text is MiniMessage.
- The backend never sends pre-rendered MiniMessage for player-supplied text; it sends the key and the arguments as
  plain strings (D2).
- Discord: the bot renders catalog text to plain text (`PlainTextComponentSerializer`) before it goes into an embed.

### D8. Velocity: translator, cache, refresh
- **Translator:** one class `CatalogTranslator` registered once with `GlobalTranslator.translator().addSource(...)`.
  Preferred base: `MiniMessageTranslator` (since Adventure 4.20.0; overrides `getMiniMessageString(key, locale)`),
  if the pinned Adventure version has it. Otherwise implement `Translator` directly. The lookup reads the current
  `CatalogSnapshot` from an `AtomicReference` on every call, so a refresh needs no re-registration.
- **Fallback chain** (pure function, unit-tested): exact locale in the snapshot, then language in the snapshot, then
  `en` in the snapshot, then the bundled text of the exact locale, then the bundled `en`, then `null`. A `null` leaves
  the key untranslated and logs one warning per key (key only, no player data).
- **Snapshot:** immutable `CatalogSnapshot(long version, Map<(key, locale), text>)`. A refresh builds a new snapshot
  and swaps it only when the whole response was read.
- **Triggers:** a NATS subscriber on `blackhole.events.i18n.catalog_changed` (core subscription, subscribe-only).
  The handler compares the event version with the local version and calls `refresh()` when the event is newer. A
  periodic task (`blackhole.i18n.refresh-interval`, default `PT5M`) calls `refresh()` too, which covers a missed
  event and a NATS outage. Both use an injected scheduler and clock so tests drive them by hand.
- **Refresh:** `GET /i18n/catalog?since=<local version>` with `If-None-Match`. Pages are followed until
  `nextCursor` is null. On success the new snapshot is swapped and written to disk. On failure the old snapshot stays
  and the failure is logged with the status code only.
- **Offline fallback (decision):** on start, the proxy loads the last snapshot from disk
  (`plugins/blackhole/i18n-catalog.json`, written atomically with a temporary file and a move), then the bundled
  texts for keys that are not in it. A missing or unreadable file is the same as an empty snapshot. Disk comes before
  the bundles because it is what staff last saved; the bundles are the last resort for a fresh install with no backend.
- **Per-player locale:** `Player#getEffectiveLocale()`, read when the message is built. `PunishmentScreens.localeOf`
  returns it; the forced `Locale.ENGLISH` line is removed.

### D9. Panel and portal
- **Panel:** the Translation editor is a page in the Administration workspace. It uses the admin API (D5).
  The preview uses the same chain as D8 and marks any example argument as an example. UI strings stay in
  `web/src/locales/{en,de}.json`; a key-parity test covers them as today.
- **Portal:** the locale choice is stored on the portal session (`PUT /session/locale`, validated against
  `en`/`de`). The chosen locale selects both the UI strings (vue-i18n) and the backend content texts. Without a
  choice both are `en`, which keeps the existing decision not to read the browser language. See question 2.

### D10. Discord
`discord` loads the catalog with the same export and a bot role (question 3). A message is rendered in the guild
locale, or the user locale when the guild has none, through the same chain as D8 with `en` as the last step.
`Lang` and `Messages` stay for bot UI texts.

## Risks / Trade-offs

- **Adventure version unknown.** Velocity 4.3.0 may bundle an Adventure older than 4.20 (no `MiniMessageTranslator`).
  → Task 1.1 pins it before code. Fallback: implement `Translator` directly; the design does not change.
- **Stale or wrong translations go live.** A staff member saves a broken text that the proxy shows to every player.
  → Validation on save (D6), the preview, and the proxy keeps the previous snapshot if the refresh response is not valid.
- **Markup from staff reaches players.** → D7: explicit tag list, arguments are never parsed, `click`/`hover`/`insert` are refused.
- **Out-of-order commits lose a row for a consumer.** → D1 ordering via the single catalog row.
- **Old proxies see new fields.** → They ignore `reasonKey`/`reasonArgs` and show the English snapshot. No raw keys reach players.
- **A backend outage leaves a proxy without the last change.** → Periodic poll plus disk snapshot (D8).
- **The number of keys grows.** → The export is incremental and the listing is paged; expected size is hundreds of keys.
- **Staff reason arguments carry personal data.** → Arguments are typed by key kind and checked by the existing PII guard (`punishment-management` delta).

## Migration Plan

1. **Schema:** append `V<n>__i18n_catalog.sql` (create `i18n_catalog` with one row at version 0, `i18n_text`, view
   `i18n_current`). Additive only.
2. **Seed, English:** the same migration copies the current English content: template names, section display names,
   and the `appeal_conditions` rows as `appeal.conditions` (version = existing version, catalog_version assigned in
   order). It uses `INSERT ... SELECT` so the rows are the same on every database.
3. **Seed, system texts:** a startup importer (`I18nDefaultsImporter`) reads `i18n/defaults-en.properties` and
   `i18n/defaults-de.properties` from the backend classpath (copies of the proxy bundles' content keys) and inserts only
   keys that have no row for that locale. `INSERT ... ON CONFLICT DO NOTHING`, so several replicas can start at once.
4. **Snapshot columns:** append the `reason_key`, `reason_args` columns to punishments and `step_id`, `reason_key` to
   template steps (later migration, after `staff-temp-bans-elo-escalation`). Existing rows: keys null, `reason` unchanged.
5. **Proxy:** ships after the backend endpoint is live. Until then it uses the bundles.
6. **Rollback:** the proxy's `blackhole.i18n.enabled` switch (default true) returns it to the bundles; the backend
   keeps the tables. Nothing is dropped. The `appeal_conditions` table stays read-only for one release.

## Open Questions

1. The exact Adventure version of Velocity 4.3.0 and whether `MiniMessageTranslator` exists there (task 1.1 answers it).
2. Portal without a language choice: browser `Accept-Language` for content only, or both `en` (default recommended)?
3. Which role the Discord bot uses for `GET /i18n/catalog`: an extra BOT app role, or PROXY? (Recommended: BOT, read-only.)
4. The panel's default UI language: the `admin-web-ui` spec says German, the code says `en`. This change does not change
   it (recommended: keep German as the spec says, and record the mismatch as a separate fix).
5. Who may save a catalog text: ADMIN only, or MODERATOR too? (Recommended: ADMIN, because a text reaches every player.)
