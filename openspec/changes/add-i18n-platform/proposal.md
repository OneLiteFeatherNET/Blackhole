## Why

Blackhole has two kinds of player- and staff-facing text, and neither is translated as a system. The
Velocity proxy renders its punishment screens from two ResourceBundles that ship inside the plugin jar,
but `PunishmentScreens.localeOf` forces `Locale.ENGLISH` for every player (interim decision of
`add-appeal-conditions` and `add-appeal-chat`, "player-facing text is English only for now"). Content
the staff creates in the backend, such as template names, the reason of each escalation step, section
names, client-rule deny messages, kick reasons and appeal conditions, exists only in one language and
cannot be edited without a redeploy. Players from German-speaking communities see English on the
network while the existing `proxy-enforcement` requirement "Localized messages" already promises German
for German clients.

Translation has to be a platform feature: one content store in the backend, one editing place in the
administration workspace, and a proxy that picks up a new translation within seconds without a restart.

## What Changes

- **Supersedes the interim English-only decision.** Player-facing punishment, kick, appeal and client-rule
  messages are localised. The decision of `add-appeal-conditions` (proposal, "Player-facing text is English
  only for now") and decision 7 of `add-appeal-chat` are superseded for every message that comes from the
  translation catalog. The English-only chat rule of the appeal chat is kept for free text that staff or
  players type; only system texts are translated.
- **New capability `i18n-catalog`.** One translation store in the backend, keyed by typed keys
  (`template.<key>.name`, `template.<key>.step.<stepId>.reason`, `section.<key>.name`,
  `clientrule.deny.<id>`, `kick.reason.<id>`, `appeal.conditions`, `canned.<kind>.<id>`, `system.<id>`).
  Append-only rows per (key, locale, version) with a global sequence number. Locales: `en` (default, always
  complete) and `de`. Validation on save: MiniMessage syntax, placeholder set per key, length limits.
  Catalog export for the proxy (`GET /i18n/catalog?since=<seq>`, PROXY role, ETag / `If-None-Match`), admin
  CRUD with cursor pagination and Problem Details, the NATS event `i18n.catalog_changed` (version only, no
  content).
- **Velocity live catalog.** The proxy registers one custom Adventure `MiniMessageTranslator` with the
  `GlobalTranslator` at startup. Its catalog is a snapshot behind a volatile reference that is swapped on
  refresh, so translations change without re-registration. Refresh is triggered by a subscribe-only NATS
  event with a periodic fallback poll. Fallback when the backend is down: last known catalog on disk, then
  the bundled defaults (see design). Locale per viewer from `Player#getEffectiveLocale`, fallback chain
  exact locale -> language -> `en`.
- **Per-step reason model.** Each escalation step of a punishment template carries its own reason
  translation key. A punishment stores the key, its arguments and a snapshot of the rendered `en` text for
  history and audit, so later edits never rewrite a history row.
- **Admin translation editor** in the Administration workspace of the panel: list of keys with missing-
  translation markers, edit per locale, a fallback preview (what a `de` player sees for a key with no `de`
  text), version history per key.
- **Portal** renders backend texts in the player's language from a selector (the player's choice is stored;
  `Accept-Language` is used only when no choice exists). UI strings stay in `portal/src/locales/*.json`.
- **Discord** embeds use the guild or user locale where it is known, otherwise `en`. Bot UI strings stay
  in `Lang`/`Messages`.
- **Migration.** Current English texts become the `en` translations; existing German defaults from
  `velocity/src/main/resources/blackhole_de.properties` are imported as `de`; the existing
  `appeal_conditions` rows become the `appeal.conditions` key history (the old table stays read-only for one
  release).
- **BREAKING (generated client and Velocity plugin):** none of the existing endpoints or fields change
  meaning. The new endpoints and the new reason fields are additive. The client spec is regenerated (new
  operations, no removals). Velocity ships a new config key `blackhole.i18n.refresh-interval` (default
  `PT5M`), documented in `application.yml`.

## Capabilities

### New Capabilities

- `i18n-catalog`: the translation store, its keys and validation, the catalog export and its versioning, the
  admin CRUD, the locale fallback chain, and the rules for which texts are catalog texts and which are
  bundle texts.

### Modified Capabilities

- `proxy-enforcement`: the requirement "Localized messages" changes from "English as the default locale
  with German available, rendered from translation keys" to "every player-facing message comes from the
  catalog; the live catalog refreshes without a restart; escaping of player-supplied text". The scenario
  "German client" stays and becomes true.
- `punishment-management`: templates carry a reason translation key per escalation step; punishments store
  the reason key, arguments and the `en` snapshot.
- `admin-web-ui`: adds the "Translation editor" requirement to the Administration workspace. The existing
  "Localised interface" requirement (panel UI texts in German and English, default German) is not changed
  here; see open question 3.

## Out of scope

- Machine translation. Every translation is entered by a staff member; the system never fills in a
  translation automatically, except the `en` and `de` import of existing texts at migration time.
- Translating staff-typed free text: punishment notes, report text, appeal messages, evidence captions.
  These stay in the language the author typed them in.
- More than the two locales `en` and `de`. The schema allows any BCP-47 tag, but only `en` and `de` are
  accepted by validation until a follow-up change adds one.
- Per-player language stored in the backend. The player's choice in the portal is stored on the portal
  session only; the proxy uses the client locale.
- Changing UI strings of the panel, the portal or the bot into the catalog. They stay in their bundles.
- The `structure-case-evidence` change itself. This change provides the storage keys `canned.<kind>.<id>`
  that the parallel change writes through; it does not define the canned comments.
- The `add-kick` change is amended by a follow-up task, not implemented here (see Impact).

## Impact

- **Backend** (`backend/`): new feature package `i18n/` (store, service, validation, controller, DTOs)
  and a Flyway migration appended at implementation time (next free `V<n>__i18n_catalog.sql`; `main` is at
  V26, `add-kick` takes V27, so the number is not fixed here). Template step reason column appended in a
  later migration. NATS publisher for `i18n.catalog_changed`.
- **Velocity** (`velocity/`): `BlackholeApiClients` gains the catalog client; `BlackholeVelocity.registerTranslations`
  is replaced; `PunishmentScreens.localeOf` returns the viewer locale; the bundles stay as the offline
  fallback; a NATS subscriber for the change event (subscribe-only, as in `switch-event-bus-to-nats`).
- **Panel** (`web/`), **portal** (`portal/`), **Discord** (`discord/`): see What Changes.
- **Client** (`client/specs/`): new operations regenerated.
- **Open change `add-kick`:** its proposal says kick messages are English only. This change requires that
  kick reasons and the kick screen use the catalog; a follow-up task amends `add-kick` before it ships.
- **Open change `staff-temp-bans-elo-escalation`:** owns the template step table. The per-step reason
  depends on it; the `feat(template)` PR is stacked after that change is merged.
- **Open change `add-appeal-conditions`:** its `appeal_conditions` table is reused as history of the
  catalog key `appeal.conditions`; its English default text becomes the `en` row.
- **Docs:** `openspec/config.yaml` stays unchanged. The superseding decision is recorded in this proposal and
  in `design.md` (decision D0); it is not a change to a project principle.

## Commit plan

This change ships as stacked PRs, one Conventional Commits type per commit (see `tasks.md`):

- `docs(openspec): propose i18n for content and the minecraft proxy` (this change)
- `feat(i18n): add the translation store, catalog export and admin api` (backend, types `feat`, scope `i18n`)
- `feat(template): add a reason translation key to every escalation step` (backend, scope `template`)
- `feat(velocity): translate punishment screens through a live catalog` (velocity)
- `feat(web): add the translation editor to the administration workspace` (web)
- `feat(portal): render backend texts in the player's language` (portal)
- `feat(discord): localise embeds from the catalog` (discord)
- `docs(openspec): archive add-i18n-platform` (after the last PR merges; unless shipped with the last implementation PR)
