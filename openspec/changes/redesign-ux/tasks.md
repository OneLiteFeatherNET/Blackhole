# Tasks

Each group is one or more small stacked PRs, one Conventional Commit type per PR. Every group starts with the
**failing** test (red), then the change (green), then the cleanup (refactor). Tests are F.I.R.S.T.: no sleeps, an
injected clock, no shared state, no network, one behaviour per test.

Paths are exact where the audit or the design names them. Where a file is "new", its directory is given and the name
follows the existing component naming in the same directory. Haiku agents: open the file named in the task first and
confirm the line before you edit it. If a line has moved, search for the quoted text instead.

Stacking: each PR is based on the previous PR's branch until it merges; open PRs against `main` with the stack noted
in the body. Commits follow Conventional Commits; no `Co-Authored-By` trailer.

**Dependencies:** group 8 needs `standardize-api-pagination-and-problems` merged. Groups 4 to 7 need
`add-appeal-portal`, `add-appeal-chat` and `add-discord-appeal-tickets` archived or rebased (proposal). Group 9a needs
the Problem Details shape of that same API change.

---

## 1. Tokens and base components (`refactor(web)`)

PR title: `refactor(web): consolidate design tokens and base components`

- [x] 1.1 Red: create `web/tests/design-system.test.ts` with three tests. (a) a retired-class scan of `web/src/**/*.vue`
  for the classes in `redesign-ux` design section 2 (`p-5`, `gap-5`, `*-0.5`, `*-1.5`, `rounded-md-sm` on buttons,
  `text-title-large`, `text-headline-medium`, `text-headline-large`, `text-label-small`, arbitrary `[...]` spacing);
  the scan reads `web/tests/design-baseline.json`, which lists the files still to migrate, and fails on any retired
  class in a file not in the baseline. (b) the spacing tokens exist in `web/src/styles/main.css`. (c) the parity test
  `web/tests/tokens-parity.test.ts` compares `web/src/styles/md3-tokens.generated.css` with the output of the
  generator for the source in `design/tokens/`. Run `npm test` in `web/` and see them fail.
- [x] 1.2 Create the token source directory `design/tokens/` at the repo root with `seed.json` (seed `#6750A4`), the
  brand token file (portal gradient colours, from `portal/src/styles/utilities.css`) and `README.md` for the token
  rules. Move `web/scripts/generate-md3-tokens.mjs` to read from `design/tokens/`, keep its output path
  `web/src/styles/md3-tokens.generated.css`, and add the `prebuild` step to `web/package.json`. Make 1.1 (c) green.
- [x] 1.3 Add the spacing and type role tokens to `web/src/styles/main.css` (`--space-1` 4 px to `--space-8` 32 px,
  the role names of design section 2). Keep the existing `--text-*` tokens; add no raw font sizes.
- [x] 1.4 Components, each with its own failing test first (`*.test.ts` next to the component):
  - `web/src/components/md/MdButton.vue` (lines 11-16, 23): add variants `tonal`, `outlined`, `text`, `error`
    (filled, error roles); `as="link"` renders `RouterLink` with the same classes; disabled uses the state token,
    not `opacity-[0.38]`.
  - `web/src/components/md/MdChip.vue` (line 11): `rounded-full`; props `variant` `filter` | `input` | `status`;
    `filter` shows a check when selected; `input` shows a remove button with the accessible name "Remove filter <name>".
  - `web/src/components/md/MdTextField.vue` (lines 34-35, 39, 49): `density="dense"` = `h-10 px-3`; default `h-14 px-4`;
    supporting text `px-4` in both.
  - `web/src/components/md/MdSelect.vue` (line 228, 273): the same density props; replace `z-[60]` with a token.
  - `web/src/components/md/MdDialog.vue` (line 76): remove the inline `--md-field-bg`; field background from the
    surface token (line 69 `bg-scrim/[0.32]` becomes the scrim token).
  - `web/src/components/md/MdDataTable.vue` (line 20): state-layer token for row hover. This is the base of the new
    `DataTable` in group 3; the rename happens in group 3.
  - `web/src/components/md/MdCard.vue` (line 14): body `p-4` stays; `web/src/components/ProtocolSection.vue` (lines
    134, 147): `p-3` and `p-4` become `p-4`.
  - `web/src/components/md/MdNavigationDrawer.vue` (line 107-108): `h-[calc(100dvh-4rem)]` becomes a token.
- [x] 1.5 New components (red test first, then the component): `web/src/components/TextLink.vue` (replaces the
  copied class string used about 15 times, audit 2 "Text links"), `web/src/components/EmptyState.vue` (variants
  `no-items`, `no-matches`, `no-access`), `web/src/components/SkeletonRows.vue`, `web/src/components/SkeletonBlock.vue`.
- [x] 1.6 Extract the inline SVG icon paths from `web/src/nav/navigation.ts` (lines 29-84) and `web/src/App.vue`
  (line 22) into `web/src/icons/index.ts` with one named export per icon. The navigation file imports names, not
  paths. Keep the brand mark in `web/src/components/md/MdTopAppBar.vue` (lines 16-19).
- [x] 1.7 Update `web/tests/design-baseline.json` to remove any file already migrated in this PR. Run the full `web/`
  test suite and the contrast test (`web/tests/contrast.test.ts`) after `npm run generate`.

---

## 2. Page template, navigation and Inbox (`feat(api)`, then `feat(web)`)

### 2a. Inbox read endpoint (`feat(api)`)

PR title: `feat(api): add the inbox summary for the staff panel`

- [x] 2a.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/inbox/InboxServiceTest.java`. Tests, one
  behaviour each: counts of the four sections; at most ten items per section; oldest first in each section; a case
  with an assignee is not in "Unassigned cases"; an appeal with a decision is not in "Open appeals"; a chat whose last
  visible message is staff's is not in "Needs an answer". Use a fixed `java.time.Clock` and an in-memory fixture
  (no database). Run `./gradlew :backend:test --tests "*InboxServiceTest"` and see it fail.
- [x] 2a.2 Green: create `backend/src/main/java/net/onelitefeather/blackhole/backend/inbox/`:
  - `InboxService.java` (`@Singleton`, one method `summary()` returning the sealed `InboxDTO.Response` or `InboxDTO.Error`).
  - `InboxDTO.java` (sealed marker interface with `Response` and `Error` records, the micronaut-dto-contract shape).
  - `InboxQueries.java` (JDBC, four queries, the "needs an answer" query reads `case_event` for `message.added`
    with `visibility = 'PLAYER'` and the last author kind).
  - `InboxApi.java` (the `@Operation` and `@ApiResponse` annotations, micronaut-openapi-contract) and
    `InboxController.java` (thin: one service call, map to `HttpResponse`).
  - Route `GET /inbox`, `@Version` per the controller convention (`.claude/rules/controller-versioning.md`).
- [x] 2a.3 Client spec: copy the latest `client/specs/blackhole-api-0.0.40.yml` to
  `client/specs/blackhole-api-<next>.yml` (next free at implementation time), add `/inbox`, and bump `inputSpec` in
  `client/build.gradle.kts`. Run `./gradlew :client:openApiGenerate`.
- [x] 2a.4 Red then green: `InboxControllerTest` (the controller answers 200 with the service result and nothing else).
  Run `./gradlew :backend:test`.

### 2b. Navigation, page shell and Inbox page (`feat(web)`)

PR title: `feat(web): regroup the navigation and add the inbox home`

- [x] 2b.1 Red: `web/src/nav/navigation.test.ts` asserts the groups, headings and German labels of the design's
  "After" table (glossary terms); `web/src/views/InboxView.test.ts` asserts the four sections, the counts, "Show all"
  links and the empty state; `web/src/components/PageShell.test.ts` asserts exactly one `h1` and the slot order
  (breadcrumbs, title, description, actions, toolbar, content, aside); `web/src/components/Breadcrumbs.test.ts`
  asserts that the last item has `aria-current="page"`. Use a fake fetch for `GET /inbox`.
- [x] 2b.2 Green: `web/src/nav/navigation.ts` (lines 25-88 and `visibleGroups` lines 91-96) rewritten to the four
  groups; `web/src/router/index.ts` (lines 23-43): `/` renders `InboxView`, `/admin/staff` added, redirects for any
  renamed route; `web/src/views/InboxView.vue` (new) replaces `DashboardView.vue` (keep its escalation table and
  active list as sections, audit 6.5); `web/src/components/PageShell.vue` and `web/src/components/Breadcrumbs.vue`
  (new); `web/src/components/md/MdNavigationDrawer.vue` (lines 41-44) marks the current item by route match.
- [x] 2b.3 `web/src/App.vue`: navigation badges (lines 41-46, `navCounts`) read from the inbox response; remove the
  route-title `h1` at line 134 so `PageShell` provides the only one.
- [x] 2b.4 Account menu: `web/src/components/account/AccountMenu.vue` gets "Link my Minecraft account" and "Link
  Discord" entries that open `AccountLinkDialog.vue` and `DiscordLinkSection.vue` (the own-account panels move here
  from the Sections page; the removal from Sections is task 3d.2). Update `AccountMenu.test.ts`.
- [x] 2b.5 Locales `web/src/locales/en.json` and `de.json`: the nav keys of the glossary (Inbox / Posteingang, Work /
  Arbeit, Players / Spieler, Configuration / Konfiguration, Administration / Verwaltung, Appeal terms /
  Einspruchsbedingungen, Active punishments / Aktive Strafen, Player lookup / Spielersuche). Test: both files have
  the same keys (the existing i18n test pattern).
- [x] 2b.6 Refactor: remove `DashboardView.vue` once `InboxView.vue` passes; update `web/tests/design-baseline.json`.

---

## 3. Page-by-page migration (`refactor(web)`, one PR per group)

Each PR: red test first (the page's existing `*.test.ts`, extended to the new structure), then migration to
`PageShell`, `FilterToolbar` (task 3a.1 creates it), `DataTable` and the row-action pattern, then the baseline
update. Each PR removes its files from `web/tests/design-baseline.json`.

### 3a. Cases and the case page (`refactor(web)`)

- [x] 3a.1 Create `web/src/components/FilterToolbar.vue` (red test first: `FilterToolbar.test.ts` with the four
  kinds `search`, `select`, `toggle-group`, `tag`, the single toolbar row, and an input chip per applied filter with
  "Clear all"). Create `web/src/components/DataTable.vue` (red: `DataTable.test.ts`: caption, `aria-sort`, empty
  slot, skeleton, card layout below 840 px with `data-label`). Create `web/src/components/RowMenu.vue` (red: menu
  keyboard test). Create `web/src/components/ProblemBanner.vue` only as a stub used by `DataTable` for `error`;
  its full mapping is task 9a.1.
- [x] 3a.2 Migrate `web/src/views/CasesView.vue`: the tag text field plus submit (lines 77-82) becomes the `tag` kind;
  status and kind chips (lines 72-76) become the `toggle-group`; the table (lines 57-66) becomes `DataTable` with
  priority columns; the empty state (line 87) becomes `EmptyState` `no-matches` with "Clear filters" when filters are
  applied. Route query keeps the filter (admin-web-ui "Filter idiom and URL state").
- [x] 3a.3 Migrate `web/src/views/CaseView.vue` (line 69-71 back link becomes breadcrumbs; line 91 heading level)
  and `web/src/components/case/CaseHeader.vue` (line 113 `grid-cols-[auto_1fr]` becomes `DefinitionList`; line 136
  tag pills become status chips).

### 3b. Appeals and reports (`refactor(web)`)

- [x] 3b.1 Red: `web/src/views/AppealsView.test.ts` and `web/src/views/ReportsView.test.ts` assert: one primary action
  per row, the overflow menu holds the secondary actions, the pending/all toggle is the `toggle-group` filter, the
  empty states. Note that the silent failure at `AppealsView.vue:46` is fixed in 9b, not here.
- [x] 3b.2 Migrate `web/src/views/AppealsView.vue` (lines 78-81 toggle, 108-119 row actions: "Open case" becomes a
  link, "Decide" the primary action, line 83 announcement moved to once per load, 84 empty state).
- [x] 3b.3 Migrate `web/src/views/ReportsView.vue` (lines 59-62 toggle, 84-95 link styled as a button becomes
  `as="link"`, `RouterLink`s with button classes removed).
- [x] 3b.4 Migrate `web/src/views/ReportDetailView.vue` (line 100 duplicate `h2` removed; line 105 heading; lines
  93-95 back link becomes breadcrumbs; lines 112, 155 text links become `TextLink`; lines 118-120 reporter hash gets
  `aria-describedby` instead of `title`) and the components `web/src/components/reports/*` and
  `web/src/components/appeals/*` that the views render (read them in full first; they were sampled in the audit).

### 3c. Players and active punishments (`refactor(web)`)

- [x] 3c.1 Red: `web/src/views/PlayersView.test.ts` (lookup form, not-found), `web/src/views/PunishedPlayersView.test.ts`
  (toolbar with search, section select, kind toggle, "Automatic" toggle and sort; the search is sent to the API;
  no client-side filter of the page; the total line is not shown).
- [x] 3c.2 Migrate `web/src/views/PlayersView.vue` (line 35 NOT_FOUND mapping moves to `problemCopy` in 9a; here the
  layout only) and `web/src/views/PlayerDetailView.vue` (lines 73-75 back link to breadcrumbs; line 75 focus style via
  `TextLink`).
- [x] 3c.3 Migrate `web/src/views/PunishedPlayersView.vue`: the three chip rows, two selects, direction button and
  search (lines 122-147) become one `FilterToolbar`; the eight columns (`DataTable` priority 1-3); the sort direction
  button shows its target (line 141-143); the empty messages (lines 153-156) become one `EmptyState` per case.

### 3d. Configuration pages and the account panels (`refactor(web)`)

- [x] 3d.1 Red: `web/src/views/SectionsView.test.ts`, `TemplatesView.test.ts`, `AppealConditionsView.test.ts`,
  `ClientRulesView.test.ts`: the table cells have one content type (no `dl` in cells), the template step unit labels
  (`TemplatesView.vue:93-97`: "30 min / 10 ELO"), the edit and delete are the row menu, the appeal terms text field
  is `MdTextField` with a counter.
- [x] 3d.2 Migrate `web/src/views/SectionsView.vue`: remove `StaffLinksPanel` and `DiscordLinksPanel` (lines 146-147)
  (the lists move in 3e.2, the own links moved in 2b.4); cells (lines 85-129) to `DataTable`; row actions (lines
  121-126) to the row pattern; delete confirmation with the error-variant button (line 142); the dialog error (line
  137) stays until 9b.
- [x] 3d.3 Migrate `web/src/views/TemplatesView.vue` (lines 85-106 table, 93-97 step labels with units, 100-103 row
  actions, 125 error-variant confirm, 80-83 primary action) and `web/src/views/ClientRulesView.vue` (lines 114-153
  table, 121-127 dl grid, 147-150 row actions, 170 the hit list moves from a dialog to a page section with
  `PageShell`, because it lists more than ten rows).
- [x] 3d.4 Migrate `web/src/views/AppealConditionsView.vue` (lines 93-103 raw `label` and `textarea` become
  `MdTextField`; line 105 counter into supporting text; line 84 heading level).
- [x] 3d.5 Terminology pass in these pages: "Templates" / "Vorlagen" (drop "Strafvorlagen" and "Punishment
  templates"), "Sections" / "Bereiche" (drop "Area"), per the glossary. Locales en and de, with the key test.

### 3e. Administration (`refactor(web)`)

- [ ] 3e.1 Red: `web/src/views/AdminView.test.ts`: tabs with headings, the viewer does not see the area (the nav test
  from 2b covers it too).
- [ ] 3e.2 Create `web/src/views/StaffAccountsView.vue` (route `/admin/staff`) holding `StaffActorsPanel.vue`,
  `StaffLinksPanel.vue` and `DiscordLinksPanel.vue` (`web/src/components/admin/` and `web/src/components/account/`),
  their lists on `DataTable` with the row pattern. Their unpaged `body.items` stay until group 8.
- [ ] 3e.3 `web/src/views/AdminView.vue` (lines 13-19): tabs Staff accounts, Imports, CloudNet.
- [ ] 3e.4 `web/src/components/admin/VanillaImportPanel.vue` (line 185 raw table to `DataTable`) and
  `web/src/components/evidence/AnticheatDetail.vue` (line 30 raw table to `DataTable`).

---

## 4. Staff identity backend (`feat(staff)`, then `feat(case)`)

### 4a. Migration, profile API and avatars (`feat(staff)`)

PR title: `feat(staff): add the staff display name and avatar profile`

- [ ] 4a.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/staffprofile/StaffNameRulesTest.java`
  (length 2 and 32 accepted, 1 and 33 rejected; allowed characters; markup characters rejected; a URL, an IP, a
  UUID and a Ban ID lookalike rejected; every reserved word rejected in any case; NFKC folding), and
  `StaffProfileServiceTest.java` (save with name; `NAME_TAKEN` on folded duplicate; `NAME_NOT_ALLOWED` on a reserved
  word; `422` without the notice; the Entra name is never the default; a service principal is refused).
  `StaffAvatarKeysTest.java` (the key is stable for the same UUID, differs between UUIDs, contains no UUID text, has 22
  characters). Fixed clock. Run and see them fail.
- [ ] 4a.2 Migration `backend/src/main/resources/db/migration/V<n>__staff_profile.sql` (next free number at
  implementation time; main is at V24): table `staff_profile` (columns of design section 4.3, partial unique index
  on `display_name_folded`), table `staff_avatar`. No backfill. Append-only (`liquibase-checker` style rules do not
  apply; this is Flyway). Red first: a migration test that runs the script on an empty schema and checks the unique
  index.
- [ ] 4a.3 Green, in `backend/src/main/java/net/onelitefeather/blackhole/backend/staffprofile/`:
  `StaffNameRules.java` (pure validation), `StaffAvatarKeys.java` (HMAC-SHA256 with `BLACKHOLE_STAFF_AVATAR_KEY`,
  base64url, 22 characters), `StaffProfileService.java` (`@Singleton`, get, save, the notice check, the sealed
  `StaffProfileDTO` variants), `StaffProfileDTO.java` (sealed marker: `UpdateRequest`, `Response`, `Error`),
  `StaffProfileApi.java` (annotations), `StaffProfileController.java` (thin, `GET` and `PUT /staff-profile/me`),
  `StaffProfileRepository.java` (JDBC).
- [ ] 4a.4 Avatar job: `backend/src/main/java/net/onelitefeather/blackhole/backend/staffprofile/StaffAvatarJob.java`
  (red first: `StaffAvatarJobTest.java` with a fake source client: a fetch stores a 64 px PNG, a failed fetch keeps
  the old PNG, unlink deletes the row). The source client is an interface `StaffAvatarSource` with the production
  implementation calling the URL from `BLACKHOLE_STAFF_AVATAR_SOURCE_URL` (validated at startup as an `https` URL).
  The job runs on link and weekly. Tests never use the network.
- [ ] 4a.5 Hook the link service: the staff-link confirm path (the service that binds a Minecraft UUID to an `oid`,
  `staff-account-link` spec) computes and stores `avatar_key` and triggers the avatar job. Red first in its test.
- [ ] 4a.6 Configuration: `backend/src/main/resources/application.yml` gets `BLACKHOLE_STAFF_AVATAR_KEY` (local-dev
  default, commented as a secret that must be set in production) and `BLACKHOLE_STAFF_AVATAR_SOURCE_URL` (default the
  session server), with comments as the project convention requires.
- [ ] 4a.7 Anonymisation: extend the existing staff-actor anonymise service (`POST /admin/staff-actor/{oid}/anonymize`;
  locate the class under `backend/src/main/java/.../staff*` before editing) to clear the profile name, the avatar key
  and the avatar row. Red first. Name snapshots and protocols follow in 4b.
- [ ] 4a.8 Client spec: `client/specs/blackhole-api-<next>.yml` with `/staff-profile/me`; bump `client/build.gradle.kts`.
  Run `./gradlew :backend:test` and `./gradlew :client:openApiGenerate`.

### 4b. Conversation snapshot, public DTO and protocol v2 (`feat(case)`)

PR title: `feat(case): record staff names in the appeal chat and protocol`

- [ ] 4b.1 Red, one JUnit test per behaviour, in the existing packages:
  - `casetimeline/CaseEventPayloadTest.java`: a staff `message.added` has `payloadVersion` 2 and `author.staffDisplayName`.
  - `appeal/` (the service that writes staff chat messages and the decision's `playerMessage`; locate it before
    editing): `STAFF_PROFILE_REQUIRED` answered before any event is written; a decision without a player message
    needs no name.
  - `publicapi/JdbcPublicConversationReaderTest.java`: `STAFF` entries carry `displayName` and `avatarKey`; version 1
    rows carry `displayName` null.
  - `casetimeline/protocol/PublicProtocolServiceTest.java`: version 1 keeps "Moderation team"; version 2 shows the
    snapshot name; no Ban ID, Discord id, Minecraft UUID or Entra name in either rendering (the existing identifier
    test pattern).
  - `casetimeline/protocol/ProtocolModelTest.java`: `authorLabel` and `eventAuthor` for named and legacy staff.
  - `casetimeline/protocol/ProtocolRedactionTest.java`: anonymisation clears the snapshot and regenerates.
  Run `./gradlew :backend:test` and see them fail.
- [ ] 4b.2 Green:
  - `backend/src/main/java/net/onelitefeather/blackhole/backend/publicapi/dto/PublicConversationDTO.java` (lines 15,
    22-25, `record Entry` from line 43): add `StaffAuthor(displayName, avatarKey)`; replace the javadoc "Staff are never
    named".
  - `backend/src/main/java/net/onelitefeather/blackhole/backend/publicapi/JdbcPublicConversationReader.java` (line 117):
    read the snapshot and join `staff_profile` for `avatar_key`.
  - `backend/src/main/java/net/onelitefeather/blackhole/backend/casetimeline/CaseEventPayload.java` (line 472): write
    `author.staffDisplayName` and `payloadVersion` 2 for staff messages.
  - `backend/src/main/java/net/onelitefeather/blackhole/backend/casetimeline/protocol/PublicProtocolService.java`
    (lines 31, 96): `who()` by version.
  - `backend/src/main/java/net/onelitefeather/blackhole/backend/casetimeline/protocol/ProtocolModel.java`
    (lines 54-60 add the named label; 360-372 and 380-398 labels); add the protocol header notice of design 4.7 (text
    pending legal confirmation, marked in the code as a constant).
  - `backend/src/main/java/net/onelitefeather/blackhole/backend/casetimeline/protocol/ProtocolRedaction.java`: clears
    the snapshot on anonymisation and marks superseded versions.
  - The staff message service and the appeal review service: refuse with `STAFF_PROFILE_REQUIRED` (sealed `Error`).
- [ ] 4b.3 Client spec: bump with the conversation DTO change; regenerate.
- [ ] 4b.4 Refactor: the `who()` duplication between `PublicProtocolService` and `ProtocolModel` removed by one
  function, with the existing tests green.

---

## 5. Panel profile setting (`feat(web)`)

PR title: `feat(web): add my profile with the staff display name`

- [ ] 5.1 Red: `web/src/components/account/ProfileDialog.test.ts` (saves through the API; the notice checkbox must be
  ticked before save; `NAME_TAKEN` and `NAME_NOT_ALLOWED` shown as field errors; the avatar mode choice shows its
  effect text), `web/src/components/case/ConversationSection.test.ts` (author block shows the name, never the oid;
  the composer is disabled with the banner when the save returns `STAFF_PROFILE_REQUIRED`; the link in the banner
  opens the profile dialog).
- [ ] 5.2 Green: `web/src/components/account/ProfileDialog.vue` (new; dialog from `MdDialog.vue`, dense fields not used
  here; the notice text from the design section 4.5 in both locales); `web/src/components/account/AccountMenu.vue`
  gets the "My profile" entry; `web/src/api/blackhole.ts` gets `getMyStaffProfile` and `putMyStaffProfile`
  (handwritten client, the panel's existing pattern); `web/src/components/case/ConversationSection.vue` (lines 152-159
  `authorName()` without the oid fallback; lines 286-303 author block with avatar; line 257 and 260-267 live-region
  fix is 9b, not here; the composer gate with the banner).
- [ ] 5.3 Locales `staffProfile.*` (en, de), key test.

---

## 6. Portal: names, tokens, components and the guided flow

### 6a. Names and avatars in the chat (`feat(portal)`)

PR title: `feat(portal): show staff names and avatars in the appeal chat`

- [ ] 6a.1 Red: `portal/src/components/MessageBubble.test.ts` (name and avatar on the first bubble of a run; a staff
  entry without a name shows "Staff"; initials and no image request when the key is null), `portal/src/views/
  ConversationView.test.ts` (the author label comes from the entry, not the i18n key only), `portal/src/api/
  contract.test.ts` (the `author.staff` shape).
- [ ] 6a.2 Green: `portal/src/api/contract.ts` (the `Entry` author type with `staff`), `portal/src/api/portal.ts`
  (mapping), `portal/src/views/ConversationView.vue` (lines 239-241 `authorLabel`), `portal/src/components/
  MessageBubble.vue` (lines 12-13 prop, 70 grouping; the avatar slot), new `portal/src/components/StaffAvatar.vue`
  (initials rendered locally in SVG), new BFF route `portal/server/routes/api/avatars/[key].get.ts` (proxies the
  backend `GET /public/staff-avatar/{key}`, `Cache-Control: public, max-age=86400`, no cookie).
- [ ] 6a.3 Share card: `portal/src/locales/en.json` line 167 and `de.json`: "Shared by {name}" instead of the moderation
  team; `conversation.author.STAFF` (`en.json` line 109) becomes "Staff" / "Team" (fallback only).
- [ ] 6a.4 Locale parity test passes (`portal/src/i18n.test.ts`).

### 6b. Portal uses the shared tokens and Roboto (`refactor(portal)`)

PR title: `refactor(portal): use the shared design tokens and Roboto`

- [ ] 6b.1 Red: `portal/tests/tokens-parity.test.ts` compares `portal/src/styles/md3-tokens.generated.css` with the
  generator output for `design/tokens/` (the same test as the panel, pointing at the shared source). Add a contrast
  test `portal/tests/contrast.test.ts` modelled on `web/tests/contrast.test.ts` (audit 3: the portal has none).
- [ ] 6b.2 Green: `portal/package.json` gets the `prebuild` step reading `design/tokens/`; `portal/src/styles/main.css`
  (line 14) font to Roboto (`Roboto Flex` removed); `portal/src/styles/utilities.css` (the body rule uses
  `--md-sys-color-surface`, the brand gradient reads the brand token from `design/tokens/`). `portal/Dockerfile` and
  `portal/Dockerfile.dockerignore` are checked so the build context includes `design/tokens/` (verify by a local
  `docker build` as the project's runtime check).

### 6c. Portal components with the shared class contract (`feat(portal)`)

PR title: `feat(portal): add button, chip, field and state components`

- [ ] 6c.1 Red: `portal/src/components/Button.test.ts`, `Chip.test.ts`, `TextField.test.ts` (dense and default heights,
  the tonal, filled, error variants; `as="link"`), `EmptyState.test.ts`, `ProblemBanner.test.ts` (stub: full in 9c).
- [ ] 6c.2 Green: the four components in `portal/src/components/` with the class contract of the panel (the same
  variant names and the same tokens). Replace the raw elements: `portal/src/components/ChatComposer.vue:171` (the send
  button), `portal/src/views/AppealFormView.vue:91` (the submit button and the textarea), `portal/src/views/CaseView.vue`
  (lines 77-92, the buttons and links).

### 6d. Guided flow: login, terms, appeal, chat (`feat(portal)`)

PR title: `feat(portal): guide the appeal from login to the chat`

- [ ] 6d.1 Red: `portal/src/flow/appealFlow.test.ts` (the step comes from the server state: no appeal → login or terms;
  eligible and unfiled → appeal; filed → chat; going back allowed before filing and not after), `portal/src/components/
  Stepper.test.ts` (`aria-current="step"`, "Step 2 of 4" text on mobile), `portal/src/App.test.ts` (focus moves to the
  step heading on change; the view is not a `view` ref switch any more).
- [ ] 6d.2 Green: `portal/src/flow/appealFlow.ts` (new state machine, pure functions, no timers),
  `portal/src/components/Stepper.vue` (new), `portal/src/App.vue` (lines 17-18 `view` ref replaced by the flow; lines
  58-69 language switch stays; line 82 container `max-w-3xl` on forms and `max-w-5xl` on the chat),
  `portal/src/views/LoginView.vue` (step 1), `portal/src/components/ConditionsText.vue` (step 2 checkbox),
  `portal/src/views/AppealFormView.vue` (lines 35-42 errors stay until 9c; step 3 layout), `portal/src/views/
  ConversationView.vue` (step 4 layout: facts at 320 px on desktop, collapsed above the chat on mobile; sticky
  composer), `portal/src/views/ExpiredView.vue` (keeps the in-memory draft, the existing behaviour).
- [ ] 6d.4 Locales: step names and the stepper labels in `portal/src/locales/en.json` and `de.json`; key parity test.

---

## 7. Discord author and avatar (`feat(discord)`)

### 7a. Backend outbox item (`feat(discord)`)

PR title: `feat(discord): send the staff display name and avatar to the ticket relay`

- [ ] 7a.1 Red: `backend/src/test/java/.../discord/dto/DiscordTicketDTOTest.java` (the outbox `ADD` item carries
  `author {kind, displayName, avatarUrl}`; `avatarUrl` contains only the avatar key on the appeal host; unverified
  staff carry no name), and a test that the avatar URL contains no UUID. Run and see them fail.
- [ ] 7a.2 Green: `backend/src/main/java/net/onelitefeather/blackhole/backend/discord/dto/DiscordTicketDTO.java`
  (line 262 javadoc; the outbox author object); the code that builds the outbox item from a `message.added` event
  (locate the outbox writer under `backend/src/main/java/.../discord/`); the avatar URL built from the public base URL
  setting (`blackhole.public.base-url`, added to `application.yml` with a comment).

### 7b. Relay embeds (`feat(discord)`)

PR title: `feat(discord): show staff names and avatars on ticket embeds`

- [ ] 7b.1 Red: `discord/src/test/java/net/onelitefeather/blackhole/discord/sync/OutboxRelayTest.java` (verified staff:
  author is the display name; unverified: "Unverified staff · <Discord name>"; player: "Player (portal)"; the label
  function has no "Moderation team" branch) and `discord/src/test/java/.../gateway/JdaGatewayEmbedTest.java` (the
  embed author name and icon URL; the footer carries the reference; no icon when none is given). The embed builder
  is behind an interface so the test does not need JDA's network.
- [ ] 7b.2 Green: `discord/src/main/java/net/onelitefeather/blackhole/discord/sync/OutboxRelay.java` (lines 213-222
  `label()`, 218-219 mapping), `discord/src/main/java/net/onelitefeather/blackhole/discord/gateway/JdaGateway.java`
  (line 576 `setAuthor(displayName, null, avatarUrl)`), `discord/src/main/java/net/onelitefeather/blackhole/discord/
  text/` (the English tone strings for the bot's player-visible messages; check `OutboundTextTest` stays green).

### 7c. Unnamed linked staff are held back (`feat(discord)`)

PR title: `feat(discord): hold back ticket replies from staff without a name`

- [ ] 7c.1 Red: `discord/src/test/java/.../backend/TicketsTest.java` (a linked staff member without a name: the message
  is stored with `STAFF` visibility, and the bot's reply text is queued for the channel; a named one is not held).
- [ ] 7c.2 Green: `discord/src/main/java/net/onelitefeather/blackhole/discord/backend/Tickets.java` (line 154 ingest;
  the held-back path and the channel reply), `discord/src/main/java/net/onelitefeather/blackhole/discord/ticket/
  ChannelTexts.java` (lines 19-21: the player-facing text about "the moderation team" changes to the staff notice).

---

## 8. Pagination UI (after the API change) (`feat(web)`, `feat(portal)`)

**Prerequisite:** `standardize-api-pagination-and-problems` is merged; every list returns `{items, nextCursor}`.
The filter parameters `q` on `/punishment/active`, `/report` and `/appeal`, and `assignee=me` on `/case`, are added by
that change or by the PR of task 8a.1 (open question 4).

### 8a. Panel lists (`feat(web)`)

PR title: `feat(web): page the lists from the cursor and filter on the server`

- [ ] 8a.1 Red: `web/src/composables/useCursorList.test.ts` (appends on `loadMore`, `reset` on any filter change,
  `nextCursor` null shows "All shown", an error keeps the items already shown), `web/src/components/ListFooter.test.ts`
  (disabled and `aria-busy` while loading; "Showing N"), `web/src/views/PunishedPlayersView.test.ts` (the search text
  is sent to the API and no client filter runs over the page).
- [ ] 8a.2 Green: new `web/src/composables/useCursorList.ts`, new `web/src/components/ListFooter.vue`; remove
  `collectPages`, `MAX_PAGES`, `PAGE_SIZE`, `EVIDENCE_PAGE_SIZE`, `CONVERSATION_PAGE_SIZE` from
  `web/src/api/blackhole.ts` (lines 285-350) and `pageQuery` callers; move the lists: `CasesView.vue` (cursor),
  `AppealsView.vue`, `ReportsView.vue` (lines 24-31), `PunishedPlayersView.vue` (lines 85-89 client search removed),
  `TemplatesView.vue` (line 21), `web/src/components/evidence/EvidencePanel.vue` (lines 26-42) and `EvidenceList.vue`
  (lines 19-21), `web/src/components/player/PlayerDetail.vue` (line 54, ELO history), `ReportDetailView.vue`
  (lines 63-67, reports against the player).
- [ ] 8a.3 Admin lists (`StaffActorsPanel.vue:17`, `StaffLinksPanel.vue:17`, `DiscordLinksPanel.vue:17`) use the
  cursor list if the API returns it; otherwise they stay unpaged and this is recorded in the PR body.

### 8b. Portal conversation (`feat(portal)`)

PR title: `feat(portal): load older messages and poll only new ones`

- [ ] 8b.1 Red: `portal/src/views/ConversationView.test.ts` (the poll request carries the last `seq`; "Load older
  messages" prepends and keeps focus on the action; no full re-read on each poll), `portal/src/api/portal.test.ts`
  (cursor paging of the conversation).
- [ ] 8b.2 Green: `portal/src/views/ConversationView.vue` (lines 64-82 poll and read loop), `portal/src/api/portal.ts`
  (line 113 cursor), `portal/src/api/contract.ts` (line 273 page type).

---

## 9. Error display (`feat(web)`, `refactor(web)`, `fix(web)`, `feat(portal)`)

### 9a. One error mapping in the panel (`feat(web)`)

PR title: `feat(web): show one translated error copy for every failed request`

- [ ] 9a.1 Red: `web/src/api/problem.test.ts` (known code gives the translation; unknown code gives the status-class
  copy; the server `title` and `detail` are never in the output; a network failure gives the network copy; a
  `traceId` is shown as "Reference: …"), `web/src/components/ProblemBanner.test.ts` (role alert, retry button,
  reference line), `web/tests/no-raw-errors.test.ts` (greps `web/src` for `error.message` and `.message` in templates
  outside `problem.ts`; fails on a match, with the baseline list from audit 8 in the test's expected-empty state after
  9b).
- [ ] 9a.2 Green: `web/src/api/problem.ts` (new), `web/src/api/http.ts` (lines 82-105 `errorFromBody` reads Problem
  Details and the `code`; lines 108-118 `toResult` network kind), `web/src/api/types.ts` (lines 211-218 `ApiError`
  gains `code`, `traceId`, `field`), `web/src/components/ProblemBanner.vue` (full), `web/src/components/ResourceState.vue`
  (lines 20-37 rewritten to `ProblemBanner`), `web/src/locales/en.json` and `de.json` (`errors.<CODE>.title/body`,
  `errors.status.4xx`, `errors.status.5xx`, `errors.network`, `errors.generic`). Keep the existing `explain()` mapping in
  `web/src/components/appeals/AppealDecisionDialog.vue` (lines 51-57) by moving its codes to `errors.*`.

### 9b. Migrate the raw-message call sites (`refactor(web)`)

PR title: `refactor(web): show problem copy in dialogs and sections`

- [ ] 9b.1 Red: the no-raw-errors test from 9a.1 fails on the listed sites; one test per dialog renders a rejected
  request and asserts the translated copy and no backend text.
- [ ] 9b.2 Green, one change per listed site (audit 8): `web/src/components/reports/ReportResolveDialog.vue` (lines
  37-49, 67 and the error reset on open and on each request), `web/src/components/reports/ReportDismissDialog.vue`
  (line 40), `web/src/components/case/CaseHeader.vue` (line 75), `web/src/views/SectionsView.vue` (line 63),
  `web/src/views/TemplatesView.vue` (lines 62-63, 120), `web/src/views/ClientRulesView.vue` (lines 72, 90, 161),
  `web/src/components/case/BanIdPanel.vue` (line 53), `web/src/views/PlayersView.vue` (line 35), and the other sites
  the grep finds (about 40 in total; the PR body lists each).

### 9c. Silent failures become visible (`fix(web)`)

PR title: `fix(web): show failed reads and link errors instead of dropping them`

- [ ] 9c.1 Red: `web/src/views/AppealsView.test.ts` (a rejected read shows the problem banner with retry; no empty
  list is shown as success), `web/src/components/account/AccountLinkDialog.test.ts` (a rejected link shows the copy;
  the existing test is extended, no `catch {}`), `web/src/components/account/DiscordLinkSection.test.ts` (same).
- [ ] 9c.2 Green: `web/src/views/AppealsView.vue` (line 46 "a failed read just shows no marker" removed: the failure is
  shown in the inbox and the list), `web/src/components/account/AccountLinkDialog.vue` (line 82 `catch {}`),
  `web/src/components/account/DiscordLinkSection.vue` (line 78 `catch {}`). Add ESLint `no-empty` as an error in
  `web/eslint.config.*` (or the existing config file; check it exists first).
- [ ] 9c.3 Dialog error stays until the dialog reopens or a new request starts (test in 9b.1 covers it).

### 9d. Portal error copy (`feat(portal)`)

PR title: `feat(portal): show one translated copy for every portal error`

- [ ] 9d.1 Red: `portal/src/api/portal.test.ts` (reads `code` and `traceId`; unknown and non-JSON bodies give the
  `UNAVAILABLE` copy; a thrown fetch gives the network copy), `portal/src/views/CaseView.test.ts` (an error branch shows
  the banner with retry; not only `app.loading`), `portal/src/components/ChatComposer.test.ts` (an unknown refusal
  shows the generic copy, not a key), `portal/src/views/ConversationView.test.ts` (a stream failure that does not
  reconnect shows the banner).
- [ ] 9d.2 Green: `portal/src/api/portal.ts` (lines 55-104 `call()`), `portal/src/api/problem.ts` (new, same rules and test
  file as the panel), `portal/src/views/CaseView.vue` (line 48 error branch), `portal/src/views/ConversationView.vue`
  (lines 253-257, 295-298 and the stream error), `portal/src/components/ChatComposer.vue` (line 67 uses `problemCopy`),
  `portal/src/views/AppealFormView.vue` (lines 35-42), `portal/src/components/ProblemBanner.vue` (full),
  `portal/src/locales/en.json` and `de.json` (one generic title, "Something went wrong" / "Etwas ist schiefgelaufen").

---

## 10. Docs and the pull requests (`docs(appeal)`, `docs(openspec)`)

- [ ] 10.1 `docs(appeal)`: `portal/README.md`, `web/README.md`, and the operator notes under `docs/` (read the folder
  first and add to the existing appeal or privacy page, do not create a new file): the staff notice text, the
  protocol header notice, `BLACKHOLE_STAFF_AVATAR_KEY` and `BLACKHOLE_STAFF_AVATAR_SOURCE_URL` (the operator must set
  the key), the rule that a staff member needs a name before replying, and the legal points marked pending legal
  confirmation. Test: none (documentation); the review checks the wording against design section 4.5.
- [ ] 10.2 `docs(openspec)`: after every PR of groups 1 to 9 has merged, archive the change with
  `openspec archive redesign-ux` (the archive commit `docs(openspec): archive redesign-ux`, per the project rule),
  and sync the deltas to the main specs.
- [ ] 10.3 Open the pull requests. For each PR of this list, in order, open it against `main` with its title as the
  Conventional Commit subject, the stack noted in the body, and the body ending with the session URL. Groups and
  titles: 1 `refactor(web): consolidate design tokens and base components`; 2a `feat(api): add the inbox summary for
  the staff panel`; 2b `feat(web): regroup the navigation and add the inbox home`; 3a to 3e `refactor(web): …` with
  the group names; 4a `feat(staff): add the staff display name and avatar profile`; 4b `feat(case): record staff names
  in the appeal chat and protocol`; 5 `feat(web): add my profile with the staff display name`; 6a `feat(portal): show
  staff names and avatars in the appeal chat`; 6b `refactor(portal): use the shared design tokens and Roboto`; 6c
  `feat(portal): add button, chip, field and state components`; 6d `feat(portal): guide the appeal from login to the
  chat`; 7a `feat(discord): send the staff display name and avatar to the ticket relay`; 7b `feat(discord): show staff
  names and avatars on ticket embeds`; 7c `feat(discord): hold back ticket replies from staff without a name`; 8a
  `feat(web): page the lists from the cursor and filter on the server`; 8b `feat(portal): load older messages and
  poll only new ones`; 9a `feat(web): show one translated error copy for every failed request`; 9b `refactor(web):
  show problem copy in dialogs and sections`; 9c `fix(web): show failed reads and link errors instead of dropping
  them`; 9d `feat(portal): show one translated copy for every portal error`; 10.1 `docs(appeal): …`.
  Run `gh pr create --base main` per PR. Then wait for the checks of each PR; do not merge.
