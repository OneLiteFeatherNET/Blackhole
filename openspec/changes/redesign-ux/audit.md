# UX audit for the redesign

Scope: staff panel `web/`, player portal `portal/`, Discord ticket view and relay `discord/`, plus the staff-identity
handling in `backend/`. Written 2026-10-25 from the code on `origin/main` (commit e86fea6). Step 1 of 2; no product code changed.

Method and limits:
- Every view under `web/src/views/` was read; the `web/src/components/md/*` library was read in full.
- Component files under `web/src/components/case`, `appeals`, `reports`, `player`, `evidence`, `admin`, `account` and
  `templates` were sampled, not read in full. Findings from them are marked "sampled".
- Tokens: `web/src/styles/main.css` and `portal/src/styles/main.css`. The generated `md3-tokens.generated.css` is **not
  present** in this checkout (it is produced by `npm run generate`), so no colour values were measured. The existing
  contrast test `web/tests/contrast.test.ts` covers those pairs.
- **Screenshots were not taken.** The Playwright MCP browser failed to launch (`Browser is already in use for
  .../mcp-chrome-3761794`, then an initialise timeout). `openspec/changes/redesign-ux/screens/` was not created.
- Counts were made with `grep` over the source tree and are approximate where stated.

---

## 1. Navigation today

Source: `web/src/nav/navigation.ts:25-88`; rendered by `web/src/App.vue:49-56` and `web/src/components/md/MdNavigationDrawer.vue`.
Visibility: `visibleGroups` (`navigation.ts:91-96`) hides `admin` unless the session is an administrator.

| Group (heading shown?) | Item (route) | Label EN / DE | Badge |
|---|---|---|---|
| overview (no heading) | `/` dashboard | Dashboard / Übersicht | none |
| moderation (Moderation / Moderation) | `/cases` | Cases / Fälle | open cases |
| | `/appeals` | Appeals / Einsprüche | pending appeals |
| | `/reports` | Reports / Meldungen | unresolved reports |
| | `/punished` | Punished players / Gesperrte Spieler | none |
| | `/players` | Players / Spieler | none |
| rules (Rules / Regelwerk) | `/sections` | Sections / Bereiche | none |
| | `/templates` | Templates / Vorlagen | none |
| | `/appeal-conditions` | Appeal conditions / Rahmenbedingungen | none |
| | `/client-rules` | Client rules / Client-Regeln | none |
| system (System / System) | `/admin` (admin only) | Admin / Verwaltung | none |

Routes and page titles: `web/src/router/index.ts:23-43`. Drawer modes: `App.vue:58` (compact = modal, medium = rail,
expanded = drawer).

Problems:
1. **Nine top-level destinations in four groups**, plus a one-item "System" group that only admins see (`navigation.ts:76-87`).
   The overview group is a single item with no heading.
2. **Wrong group.** `/players` is a name lookup, not a list, yet it sits in Moderation next to the case lists.
   `/sections` and `/templates` are admin-edited configuration, but they sit in Rules next to `/client-rules`,
   while `/admin` holds a different set of admin tools (`AdminView.vue:13-19`).
3. **Sections page holds account panels.** `web/src/views/SectionsView.vue:146-147` renders `StaffLinksPanel` and
   `DiscordLinksPanel` for admins at the bottom of the sections table. Those are staff account links, not sections.
   The Admin page (`AdminView.vue:15-18`) holds the other admin tools.
4. **Unclear names.** "Players" is a lookup form, not a list (`PlayersView.vue:44-53`). "Punished players" is
   the list of active punishments (`PunishedPlayersView.vue`). "Appeal conditions" is a versioned text
   (`AppealConditionsView.vue`). "Sections" and "Templates" both appear in the punishment setup flow with no
   connecting label.
5. **Wording clash in German.** The overview group heading and the overview item are both "Übersicht"
   (`web/src/locales/de.json` `nav.groups.overview` and `nav.dashboard`).
6. **No "needs attention" entry point.** Work is spread across badges on three items (cases, appeals, reports).
   The dashboard shows open reports and pending appeals as two cards (`DashboardView.vue:35-47`), and there is no
   single inbox that combines unanswered appeal messages, open reports and assigned cases.
7. **Breadcrumb-less detail pages.** `/players/:owner`, `/reports/:id` and `/cases/:reference` are reached from
   tables, and the back links are inline text links (`CaseView.vue:69-71`, `ReportDetailView.vue:93-95`,
   `PlayerDetailView.vue:73-75`). The side nav marks the parent item as current only through the prefix match
   (`MdNavigationDrawer.vue:41-44`).

---

## 2. Inconsistencies by category

Severity: H = high, M = medium, L = low.

### Page headers
- **H** Duplicate titles. `App.vue:134` renders the route title as `<h1>`. `ReportDetailView.vue:100` then renders
  `<h2>` with the same text (`reports.detail.title` = "Report" / "Meldung"). Other views have no own page title;
  they start with a `<p>` or a section.
- **M** Heading levels skip. Views use `h2` (`DashboardView.vue:55,72`, `AppealConditionsView.vue:84`), `h3`
  (`CaseView.vue:91`, `ConversationSection.vue:244`) and no `h2` on the case page, so `h3` sits directly under the
  app `h1`.
- **M** Heading sizes differ: `text-title-large` (`DashboardView.vue:55`), `text-title-medium`
  (`AppealConditionsView.vue:84`, `ReportDetailView.vue:105`, `CaseView.vue:91`), `text-headline-small`
  (`ReportDetailView.vue:100`, `LeaveView.vue:26`).
- **L** Each list view repeats the same intro pattern: a `<p class="text-body-large text-on-surface-variant">`
  inside `flex flex-wrap items-center gap-3`, then the primary button (`SectionsView.vue:80-83`,
  `TemplatesView.vue:80-83`, `ClientRulesView.vue:102-105`). This is consistent, but the intro is `text-body-large`
  on some pages and `text-body-medium` on others (`PunishedPlayersView.vue:152`).

### Filter and toolbar patterns
- **H** Four different filter idioms. Status and kind filters are `MdChip` toggles (`CasesView.vue:72-76`,
  `PunishedPlayersView.vue:123-131`). Pending/All is a chip pair (`AppealsView.vue:78-81`, `ReportsView.vue:59-62`).
  The tag filter on cases is a text field plus a submit button, with no chip for the applied tag
  (`CasesView.vue:77-82`). The section and sort filters are `MdSelect`s (`PunishedPlayersView.vue:134-144`), and the
  sort direction is a toggle button labelled with the current state (`PunishedPlayersView.vue:141-143`).
- **H** `PunishedPlayersView.vue:122-147` stacks **three chip rows, two selects, a direction button and a search field**
  above an eight-column table. Search and sort controls sit in the same flow with no grouping.
- **M** Filter chips use `rounded-md-sm` (`MdChip.vue:11`), while buttons use `rounded-md-full` (`MdButton.vue:23`).
  Filter chips therefore look like tags, not like MD3 filter chips.
- **M** Active-filter feedback is missing for the tag filter (`CasesView.vue:77-82`) and for `filter-mine` on
  punished players (`PunishedPlayersView.vue:132`), where the chip is the only signal.

### Tables and lists
- **H** Client-side search on a server-paged table. `PunishedPlayersView.vue:85-89` filters only the current
  page (`punished.data.value?.items`); the total on line 152 counts all pages. A match on another page is invisible.
- **M** `MdDataTable.vue` has no sorting, no empty row, no caption and no mobile card layout. Column headers are
  `scope="col"` (`MdDataTable.vue:14`) but do not carry `aria-sort`, and the sort on punished players is a
  separate control.
- **M** Row hover is hard-coded: `hover:bg-on-surface/[0.08]` (`MdDataTable.vue:20`), an arbitrary opacity rather than a
  state-layer token.
- **M** Cells mix three kinds of content: stacked `<p>` lines (`SectionsView.vue:88-89`), `dl` grids
  (`SectionsView.vue:102-120`, `ClientRulesView.vue:121-127`) and bare text. `TemplatesView.vue:93-97` writes steps as
  `1. 30m / 10` with no unit label for the ELO cost.
- **M** Raw tables bypass the component: `components/evidence/AnticheatDetail.vue:30` and
  `components/admin/VanillaImportPanel.vue:185`.
- **L** Overflow on narrow screens: `MdDataTable.vue:10` uses `overflow-x-auto`; the case table has eight columns
  (`CasesView.vue:57-66`). Not measured at 390 px (no screenshot).

### Buttons and links
- **H** Two row-action patterns in the same table. `AppealsView.vue:108-119` puts a text-link "Open case" and an
  outlined `MdButton` "Decide" in one cell. `ReportsView.vue:84-95` puts a link styled as a button
  (`md-state inline-flex h-10 ... rounded-md-full px-4`, line 89) beside a `MdButton` resolve. Both are "actions"
  and read differently.
- **M** Text links to detail pages use an ad-hoc class string about 15 times:
  `text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary`
  (e.g. `DashboardView.vue:87`, `CasesView.vue:93`, `PunishedPlayersView.vue:162,185`, `ReportDetailView.vue:112,155`).
  No component exists for it.
- **M** Raw `<button>` in the library's stead: `components/ResourceState.vue:29-36` (retry, its own class list).
  `views/ReportsView.vue` and `views/ReportDetailView.vue` use `RouterLink` with button-like classes instead of
  `MdButton`.
- **L** Text buttons in row actions: `SectionsView.vue:121-126`, `TemplatesView.vue:100-103`,
  `ClientRulesView.vue:147-150` use `variant="text"` for Edit and Delete, so the destructive action has the same
  weight as the safe one.
- **L** Inconsistent destructive colour: the delete dialogs use the same filled button for confirm
  (`TemplatesView.vue:125`, `SectionsView.vue:142`); no error variant exists in `MdButton.vue:11-16`.

### Dialogs
- **M** `MdDialog.vue:77` sets `max-w-md` (default) or `max-w-2xl` (wide); the same dialog component is used for
  delete confirmations and for the wide hit list (`ClientRulesView.vue:170`).
- **L** Dialog body text is `text-body-medium text-on-surface-variant` (`MdDialog.vue:82`), while the portal and
  panel inline messages are `text-body-medium` on error containers. Not wrong, but the copy colour differs from
  the page body.
- **L** Errors in dialogs are an inline `role="alert"` block with `bg-error-container` in four places
  (`SectionsView.vue:137`, `TemplatesView.vue:120`, `ClientRulesView.vue:161`), each with a different `data-testid`.

### Form fields
- **H** `AppealConditionsView.vue:93-103` uses a raw `<label>` and `<textarea>` with its own classes
  (`rounded-md-xs border border-outline ... focus:border-2`), not `MdTextField`. The counter on line 105 is a
  separate `<span>`.
- **M** `MdTextField.vue:34-35` height: `h-14` by default, `h-10` when `dense`. `CasesView.vue:79` uses dense inside a
  toolbar; `PunishedPlayersView.vue:146` uses the default height in the same toolbar.
- **M** `MdSelect.vue:228` uses `h-14`, and `pl-4 pr-12`; the text field uses `px-4`. Left edges are not aligned in
  the mixed toolbars.
- **L** Supporting text uses `px-4` in `MdTextField.vue:49` but `px-1` in `MdDurationField.vue:96`.

### Empty, loading and error states
- **H** Empty states are plain paragraphs and are not consistent: `CasesView.vue:87` (`cases-empty`),
  `AppealsView.vue:84`, `ReportsView.vue:64`, `TemplatesView.vue:85`, `ClientRulesView.vue:111` (wrapped in a
  condition), `PunishedPlayersView.vue:153-156` (three messages: empty, filtered empty, search empty). None has
  an illustration, a next step or a create action.
- **H** Loading: `ResourceState.vue:18` shows a top progress bar only. On the first load there is no skeleton, so
  the content area stays blank.
- **M** Error display is `ResourceState.vue:20-37` (title from `state.error.<kind>`, then the raw `error.message`).
  See section 8.
- **M** Dashboard active list has no empty state: `DashboardView.vue:57-67` renders nothing when
  `activePunishmentsBySection` is empty; only the escalations table has one (`DashboardView.vue:73-75`).

### Colours and tokens
- **M** Arbitrary colour opacity in the web library: `bg-scrim/[0.32]` (`MdDialog.vue:69`,
  `MdNavigationDrawer.vue:98`), `disabled:opacity-[0.38]` (`MdButton.vue:23`, `MdTextField.vue:34`),
  `hover:bg-on-surface/[0.08]` (`MdDataTable.vue:20`). These are MD3 values written by hand.
- **L** `web/src/components/md/MdDialog.vue:76` sets `--md-field-bg` inline, so the text field inside a dialog gets a
  different background from one on the page (`MdTextField.vue:39`).
- **L** Staff vs player bubbles differ by one surface step only: `surface` vs `surface-container-low`
  (`ConversationSection.vue:280`), with `border-outline-variant` on both.

### Spacing (web/src)
- Counted with grep over `web/src/**/*.vue` (non-test). About **60 distinct padding/gap/margin utilities** over
  the Tailwind scale steps 0, 0.5, 1, 1.5, 2, 3, 4, 5, 6, 8, 12 and 0, 1, 2, 3, 4, 6, 8, 12. The most used:
  `gap-2` (82), `p-3` (70), `gap-3` (60), `gap-1` (34), `gap-4` (29), `p-4` (24), `px-4` (18), `gap-5` (17),
  `mt-3` (16), `pt-2` (15). `p-3` and `p-4` are used for the same role (card body) in different places:
  `MdCard.vue:14` uses `p-4`, `ProtocolSection.vue:134` uses `p-3`, `ProtocolSection.vue:147` uses `p-4`.
- Arbitrary values: `grid-cols-[auto_1fr]` (8 uses, e.g. `SectionsView.vue:102`, `CaseHeader.vue:113`),
  `max-w-[88%]` (`ConversationSection.vue:278`), `z-[60]` (`MdSelect.vue:273`),
  `h-[calc(100dvh-4rem)]` (`MdNavigationDrawer.vue:107-108`).

### Typography (web/src)
- No raw font-size utilities (`text-xs`, `text-sm`, `text-base`, `text-lg`, `text-xl`, `text-2xl` and up): 0 uses.
  The type scale is fully tokenised (`main.css` `--text-*`).
- Usage counts: `text-body-medium` 92, `text-body-small` 82, `text-body-large` 60, `text-label-large` 40,
  `text-title-medium` 26, `text-title-small` 17, `text-title-large` 16, `text-headline-small` 8, `text-label-medium` 4,
  `text-display-small` 3, `text-label-small` 2, `text-headline-medium` 2, `text-headline-large` 2.
- `text-body-small` is used for both secondary meta text and dl content (`SectionsView.vue:102`); the scale does not
  separate the two roles.

### Radii and shadows (web/src)
- `rounded-md-md` 96 uses for panels, cards, notices and list items. `rounded-md-full` 26 (buttons, toggles),
  `rounded-md-xs` 13 (text fields, snackbar), `rounded-md-sm` 6 (chips, tags, statuses).
- Chips (`rounded-md-sm`) and the tag pills in the case header (`CaseHeader.vue:136`) are one radius step away from
  the MD3 full-round chip.

### Icons
- Icons are inline SVG paths hard-coded in `navigation.ts:29-84` and `App.vue:22`, and passed as `path` props.
  No icon set is used. Icons in the rail are decorative (`MdNavigationDrawer.vue:144`); the text label carries the
  meaning.
- **L** The brand mark is an inline SVG (`MdTopAppBar.vue:16-19`), and the portal uses a PNG logo
  (`portal/src/App.vue:52`).

### Wording (en / de)
Inventory by key (`web/src/locales/en.json`, `de.json`; `portal/src/locales/en.json`, `de.json`):
- **H** Case vs Fall vs Area: web `case.title` is "Case"/"Fall" (`en.json` `case.title`), but the portal labels the
  section as "Area"/"Bereich" (`portal/src/locales/en.json` `case.section`), while the panel calls it "Section"
  (`nav.sections`).
- **H** Appeal vs Einspruch: "Appeals"/"Einsprüche" in the panel (`nav.appeals`), "Appeal"/"Einspruch" in the
  portal (`appeal.title`). The German for the decision is "Einspruch" throughout, which is consistent. The
  inconsistency is in the English terms for the same object: "Appeals" (panel) and "Appeal" (portal).
- **H** Staff: "Staff" (`case.conversation.role.STAFF`), "Team" (`case.conversation.staff` de), "Moderation team"
  (portal `conversation.author.STAFF`; Discord `OutboxRelay.java:218`). Four names for one actor.
- **M** Reports vs Meldung: `reports.title` "Meldungen", but `reports.detail.title` "Meldung" and the Discord embed
  uses "Meldung" too (`EmbedRenderer.java:58`).
- **M** Templates: "Punishment templates" (`templates.title`), "Strafvorlagen" (de) vs nav "Vorlagen" (de).
- **M** Ban ID: "Ban ID" (en) and "Ban-ID" (de) in the panel (25 de hits) and the portal (15). The Discord side
  uses "ban ID" (`Messages.java:169`).
- **M** "Bans" is used untranslated in German for `punished.kind.BAN` (`de.json`), while the rest of the
  punishment vocabulary is "Strafe" (57 uses in panel de) and "Sperre" (3).
- **L** Assignee: "Assignee" / "Bearbeiter" in `case.list.columns.assignee`; "Assigned to me" / "Mir zugewiesen"
  for the filter.
- **L** `punished.sort.ASC` / `DESC` shows the current direction, not the target of the toggle
  (`PunishedPlayersView.vue:141-143`).
- **L** Across the two surfaces, the English portal says "Moderation team" while the English panel says "Staff"
  (`case.conversation.role.STAFF`); the German panel says "Team" and the German portal "Moderationsteam".

### Discord-facing wording
- Embed titles are bilingual, EN · DE (`EmbedRenderer.java:54-72`: "New report · Neue Meldung"). Field labels are
  bilingual too ("Player · Spieler"). This is the only surface that shows both languages at once.
- Relay labels are English only: "Moderation team", "Player (portal)", "Other" (`OutboxRelay.java:213-222`).

---

## 3. Portal vs panel differences

| Area | Panel `web/` | Portal `portal/` |
|---|---|---|
| Font | `--font-sans: Roboto` (`web/src/styles/main.css`) | `"Roboto Flex", Roboto, ...` (`portal/src/styles/main.css:14`) |
| Body background token | `--md-sys-color-surface` (`web/src/styles/main.css`, body rule) | `--md-sys-color-background` (`portal/src/styles/utilities.css`, body rule) |
| Brand colour | none in the panel; primary MD3 roles only | `olf-gradient` utility with hard-coded hex `#f7931d`, `#ec008b`, `#91268f`, `#2a388f` (`portal/src/styles/utilities.css`); header panel `bg-[#1f1f23]/75` (`portal/src/App.vue:51`) |
| Motion | `web/src/styles/main.css` and `web/src/motion/*`: MD3 durations, easings, stagger, reduced-motion overrides | No motion tokens; `md-state` only (`utilities.css`) |
| Component library | `web/src/components/md/*` (MdButton, MdTextField, MdSelect, MdDialog, MdDataTable, MdChip, MdSnackbar, MdNavigationDrawer, and others) | None. Buttons, inputs and links are raw elements with copied classes (e.g. `portal/src/views/CaseView.vue:77-92`, `ChatComposer.vue:171`, `AppealFormView.vue:91`) |
| Navigation | Vue Router, nine routes (`web/src/router/index.ts`) | No router; a `view` ref switches between case, appeal and conversation (`portal/src/App.vue:17-18`). A reload always lands on the case page. |
| Page header | App `h1` + view `h2` (duplicate case in report detail) | One `h1` per view (`portal/src/views/CaseView.vue:27`, `ConversationView.vue:263`) |
| Container | full width, `p-4 md:p-6` (`web/src/App.vue:133`) | `max-w-3xl` (`portal/src/App.vue:82`) |
| Language switch | none found in `web/src/App.vue` (locale setup in `web/src/i18n.ts`, not read) | visible en/de buttons with `aria-pressed` (`portal/src/App.vue:58-69`) |
| Chat bubbles | `rounded-md-md`, `p-4`, `max-w-[88%]` (`ConversationSection.vue:277-278`) | `rounded-md-lg`, `px-4 py-3`, `max-w-[85%]` (`MessageBubble.vue:67`, `ConversationView.vue:314`) |
| Notice blocks | `rounded-md-md p-3`, `bg-error-container` (e.g. `ConversationSection.vue:246`) | `rounded-md-sm px-4 py-3`, `bg-tertiary-container` (e.g. `ConversationView.vue:287`) |
| Tests | Vitest plus a contrast test on the generated tokens (`web/tests/contrast.test.ts`) | Vitest, no contrast test found |

The panel's token generator is `web/scripts/generate-md3-tokens.mjs` (seed `#6750A4`, output
`src/styles/md3-tokens.generated.css`). `portal/src/styles/main.css` imports a file of the same name, but the portal
generator was not found and its seed was not checked.

---

## 4. Appeal chat today

### Portal (player view)
- Bubble: `portal/src/components/MessageBubble.vue`. The player's own messages are `bg-secondary-container`, right-aligned
  (`ConversationView.vue:313`, `self-end`). Staff, system and other messages are `bg-surface-container-high`, left-aligned.
- Author label: `t('conversation.author.<AUTHOR>')` (`ConversationView.vue:239-241`); STAFF is "Moderation team" /
  "Moderationsteam" (`portal/src/locales/en.json:109`, `de.json` same key). The name is shown only on the first bubble
  of a run of the same author (`MessageBubble.vue:70`, `grouped`).
- No avatar. `grep -ri avatar` over `web/src`, `portal/src`, `backend/src/main/java`, `discord/src/main/java` returns
  nothing.
- Markers and days are `<li role="presentation">` (`ConversationView.vue:304-308`).

### Panel (staff view)
- Bubble: `web/src/components/case/ConversationSection.vue:277-285`. Staff are `self-end` with `bg-surface`; the
  player is `self-start` with `bg-surface-container-low`.
- Header line (`ConversationSection.vue:286-303`) carries up to **seven pills plus a time**: author name, "from player",
  "from Discord", Discord trust ("verified"/"unverified"), attachment count, "staff only", "edited", "deleted".
- Author name: `authorName()` (`ConversationSection.vue:152-159`) returns the staff member's name when the backend
  sends `actor.name`, otherwise the first 8 characters of the oid followed by "…", otherwise "Staff".
- Actions on each staff bubble: edit, delete, redact (`ConversationSection.vue:343-359`), as text buttons.
- No avatar.
- The thread is `role="log" aria-live="polite"` (`ConversationSection.vue:260-267`), and a separate
  `role="status"` region announces the last action (`ConversationSection.vue:257`).

### Discord (ticket channel relay)
- Staff messages written in the panel are posted into the ticket channel as embeds with the author label as
  `setAuthor(label)` (`discord/src/main/java/net/onelitefeather/blackhole/discord/gateway/JdaGateway.java:576`).
  The label comes from `OutboxRelay.label()` (`discord/src/main/java/net/onelitefeather/blackhole/discord/sync/OutboxRelay.java:213-222`):
  - `STAFF` -> "Moderation team"
  - `PLAYER` -> "Player (portal)"
  - `BOT`, `EXTERNAL` -> "Other"
  - `null` -> "Message"
- The footer carries the reference (`JdaGateway.java:576`). No avatar and no staff name.
- Discord-originated staff messages are ingested with the Discord author name
  (`discord/.../backend/Tickets.java:154`, `authorName`), and the backend decides whether that name is shown.
  Whether a Discord-sourced player message ever reaches the outbox (and so gets the "(portal)" label) was not verified.
- Discord ticket channel text (`discord/.../ticket/ChannelTexts.java:19-21`) tells players that messages from
  "the moderation team" appear there.

### Backend (where staff identity is removed or kept)

Locations where a staff name or identifier is hidden from players, or would have to change to show a per-staff
display name and avatar:

| # | File:line | What it does today | Change needed for named staff with avatar |
|---|---|---|---|
| 1 | `backend/src/main/java/net/onelitefeather/blackhole/backend/publicapi/dto/PublicConversationDTO.java:22-25` | `enum Author { PLAYER, STAFF, SYSTEM }`; javadoc "Staff are never named" (line 15, 22) | Add a staff entry type with display name and avatar URL; keep the enum for the player/system split |
| 2 | `backend/src/main/java/net/onelitefeather/blackhole/backend/publicapi/dto/PublicConversationDTO.java:43+` (`record Entry`) | No name or avatar field on the entry | Add `staffDisplayName`, `staffAvatarUrl` (or an opaque staff-profile id) |
| 3 | `backend/src/main/java/net/onelitefeather/blackhole/backend/publicapi/JdbcPublicConversationReader.java:117` | `String author = "PLAYER".equals(...) ? "PLAYER" : "STAFF";` (name column dropped in SQL) | Select the verified staff name (and the staff profile reference) for STAFF rows; respect the removed-name rule |
| 4 | `backend/src/main/java/net/onelitefeather/blackhole/backend/publicapi/service/PublicProtocolService.java:31` and `:96` | Javadoc "Moderation team, nobody is named"; `who()` maps STAFF to "Moderation team" | Same change as 1-3 for the downloaded protocol |
| 5 | `backend/src/main/java/net/onelitefeather/blackhole/backend/casetimeline/protocol/ProtocolModel.java:54-60` | `PLAYER_LABEL = "Player"`, `ANONYMOUS_STAFF_LABEL = "Staff member (name removed)"` | Protocol is the stored staff copy; keep names here, only the public copy changes |
| 6 | `backend/src/main/java/net/onelitefeather/blackhole/backend/casetimeline/protocol/ProtocolModel.java:360-372` (`authorLabel`) | VERIFIED staff -> name, else `ANONYMOUS_STAFF_LABEL`; UNVERIFIED -> "Unverified staff" | Stays for the staff copy; the public path must not reuse it |
| 7 | `backend/src/main/java/net/onelitefeather/blackhole/backend/casetimeline/protocol/ProtocolModel.java:380-398` (`eventAuthor`) | Staff label from `entry.actorName()`; kind "STAFF" | Same as 6 |
| 8 | `backend/src/main/java/net/onelitefeather/blackhole/backend/casetimeline/protocol/ProtocolRedaction.java` | Replaces staff names with anonymous labels when a participant is erased | Redaction must also cover any new staff avatar or name stored for the public side |
| 9 | `backend/src/main/java/net/onelitefeather/blackhole/backend/casetimeline/CaseEventPayload.java:472` | `author.put("kind", authorKind.name())`; no name in the public-facing author object | Decide what is stored for a named author; reuse the verified actor name |
| 10 | `backend/src/main/java/net/onelitefeather/blackhole/backend/discord/dto/DiscordTicketDTO.java:262` | Javadoc: `STAFF` shown as "the moderation team" | Same as 1 for the Discord relay |
| 11 | `discord/src/main/java/net/onelitefeather/blackhole/discord/sync/OutboxRelay.java:218-219` | `STAFF -> "Moderation team"`, `PLAYER -> "Player (portal)"` | Post the staff display name and avatar (JDA author icon) per message |
| 12 | `discord/src/main/java/net/onelitefeather/blackhole/discord/gateway/JdaGateway.java:576` | `setAuthor(label)`; no icon URL | Add `setAuthor(name, null, avatarUrl)` |
| 13 | `portal/src/locales/en.json:109` and `de.json` (`conversation.author.STAFF`) | "Moderation team" / "Moderationsteam" | Replaced by the per-message name |
| 14 | `portal/src/views/ConversationView.vue:239-241` (`authorLabel`) | Label from the i18n key only | Use the entry's staff name and avatar when present |
| 15 | `portal/src/components/MessageBubble.vue:12-13`, `70` | Prop `authorLabel` "Staff are never named"; name shown only when not grouped | Add avatar slot; keep grouping per staff member, not per author kind |
| 16 | `portal/src/locales/en.json:167` ("Shared by the moderation team") | Share card title | Name the staff member who shared, if the change allows it |
| 17 | `web/src/components/case/ConversationSection.vue:152-159` | Panel already shows `actor.name`, falls back to oid prefix | Keep; add an avatar; replace the oid fallback with a display name when the name is removed |
| 18 | `web/src/components/case/ConversationSection.vue:286-303` | Staff identity is the first pill row, text-only | Put avatar and name in one author block |
| 19 | `web/src/components/case/ConversationSection.vue:375-378` (`erase-player`) | Erasure removes player text only | No identity change; listed for completeness |
| 20 | `web/src/components/case/ParticipantErasure.vue` (sampled) | Erasure by participant id; role label from the first message | Name and avatar must follow the same erasure rule |

Backend anonymisation terms searched: "Moderation team", "STAFF", `authorKind` (in `backend/src/main/java`). The
hits are the list above and the enum/field uses in `casetimeline` and `discord`.

---

## 5. Accessibility issues found

Focus and keyboard
- Focus rings are present for `md-state` (`web/src/styles/main.css`, `@utility md-state`) and for links with
  `focus-visible:outline-2`. Good.
- `MdDialog.vue:15-59` moves focus into the dialog, traps Tab, closes on Escape and returns focus to the opener.
  `ReportsView.vue:103`, `ClientRulesView.vue:170` use it. Good.
- `MdNavigationDrawer.vue:65-92` supports arrow keys, Home and End in the nav, and Escape in the modal drawer. Good.
- **M** `ReportsView.vue:84-95` and `ReportDetailView.vue:93-95` use `RouterLink` with button classes. Keyboard
  behaviour is right, but the element is a link styled as a button.
- **L** `PlayerDetailView.vue:75` uses `text-label-large underline` with no focus-visible style.

Labels and names
- **M** `components/case/ConversationSection.vue:260-267`: `role="log"` plus `aria-live="polite"` on the thread,
  and a second `role="status"` announcer (`:257`). A new message can be announced twice.
- **M** `views/AppealsView.vue:83`: an sr-only `role="status"` that announces "N unanswered" on every change.
- **M** Headings skip levels (see section 2, page headers).
- **L** `components/md/MdIconButton.vue:9-10`: label is both `aria-label` and `title`. Good, but the rail uses icons
  with the label below (`MdNavigationDrawer.vue:144-148`), so the visible text is the name.
- **L** `views/ReportDetailView.vue:118-120`: the reporter hash is shown with `title`; a screen reader reads the
  shortened hash and "pseudonymous". The full value is only in the tooltip.

Contrast
- Not measured. The generated tokens are missing from this checkout (`md3-tokens.generated.css`). The existing
  test `web/tests/contrast.test.ts` checks the palette pairs when the file is generated.
- Visual: the staff and player bubbles differ by one surface step (`ConversationSection.vue:280`), which relies on
  the border and the author pill to separate them.

Timing and motion
- `MdSnackbar.vue:8` closes the message after 6 s. The snackbar has no action in the case page.
- Motion: reduced-motion overrides exist (`web/src/styles/main.css`, `prefers-reduced-motion`). Good.

Reflow and layout
- `MdDataTable` scrolls horizontally on narrow screens (`MdDataTable.vue:10`). Not measured at 390 px.

Screen reader structure
- Portal and panel both have a skip link (`portal/src/App.vue:42-47`, `web/src/App.vue:99-104`). Good.
- Panel: `ResourceState.vue:20-37` puts the error in `role="alert"` and the retry button in the same block. Good.

---

## 6. Top 15 findings, ranked

1. **H** Players see every staff member as "Moderation team"; no name, no avatar. `portal/src/locales/en.json:109`, `ConversationView.vue:239-241`, `MessageBubble.vue:12-13`; backend `PublicConversationDTO.java:22-25` and `JdbcPublicConversationReader.java:117`.
2. **H** No avatar exists anywhere in the codebase (web, portal, backend, discord). Any per-staff avatar is new data, with an erasure and redaction rule to design.
3. **H** Discord relays staff as "Moderation team" for every staff member. `discord/.../sync/OutboxRelay.java:218`, `JdaGateway.java:576`.
4. **H** Navigation: nine destinations in four groups, Players and Punished players in Moderation, Sections and Templates in Rules, Admin alone in System. `web/src/nav/navigation.ts:25-88`.
5. **H** No "needs attention" inbox. Work is split across three badges (`navCounts`, `App.vue:41-46`) and two dashboard cards (`DashboardView.vue:35-47`).
6. **H** Account-link panels on the Sections page. `web/src/views/SectionsView.vue:146-147`.
7. **H** Punished players: three chip rows, two selects, a direction toggle and a search, with client-side search over one page only. `PunishedPlayersView.vue:85-89,122-147`.
8. **H** Four filter idioms (chips, pending/all pair, text plus submit, selects) and no applied-filter chip for the case tag. `CasesView.vue:72-82`; `AppealsView.vue:78-81`.
9. **H** Two row-action patterns in one table (link beside outlined button). `AppealsView.vue:108-119`; `ReportsView.vue:84-95`.
10. **H** Raw `<textarea>`/`<label>` on the appeal conditions page instead of `MdTextField`. `AppealConditionsView.vue:93-103`.
11. **H** Terminology: Case/Fall/Area/Section and Appeal/Einspruch in both languages; staff named four ways. `en.json` `case.section` (portal), `nav.sections`, `case.conversation.role.STAFF`, `conversation.author.STAFF`.
12. **M** Raw backend error text is shown in about 40 places, English and untranslated. `ReportResolveDialog.vue:49`, `SectionsView.vue:63`, `TemplatesView.vue:62-63`, `ClientRulesView.vue:72`; portal uses codes only. See section 8.
13. **M** Duplicate page title on report detail (`App.vue:134` h1 and `ReportDetailView.vue:100` h2), and skipped heading levels on the case page (`CaseView.vue:91`).
14. **M** Staff message header carries up to seven pills, and staff/player bubbles differ by one surface step. `ConversationSection.vue:280,286-303`.
15. **M** Silent failures: unanswered-appeal reads drop errors (`AppealsView.vue:46`), and two catch blocks swallow errors (`web/src/components/account/AccountLinkDialog.vue:82`, `DiscordLinkSection.vue:78`).

---

## 7. Lists and pagination in the UI

Client calls are in `web/src/api/blackhole.ts`. `PAGE_SIZE = 200` (line 287), `MAX_PAGES = 25` (line 293),
`EVIDENCE_PAGE_SIZE = 20` (line 285), `CONVERSATION_PAGE_SIZE = 200` (line 291). `collectPages` (line 350) loads every
page up to `MAX_PAGES`, so a "full" list is up to 5000 items. `caseListQuery` (line 340) sends only the parameters
that are set. `pageQuery` (line 311) always sends `page` and `size`.

| List (view / component) | Paging | Items per request | Filters and sort | API call (method path query) | Refs |
|---|---|---|---|---|---|
| Cases | server, numbered prev/next (`nav`) | server default; client sends no `size` | server: `status`, `assignee`, `tag`, `page`; no sort param | GET `/case?status&assignee&tag&page` | `CasesView.vue:31-45, 106-110`; `blackhole.ts:746` |
| Case timeline | server, "load more" by cursor | 50 (`PAGE_SIZE`) | server: `type`, `actorTrust`, `from`, `to`, `order`, `cursor` | GET `/case/{reference}/timeline?cursor&size&type&order&...` | `CaseTimeline.vue:17, 97-98, 116, 179-180`; `blackhole.ts:754` |
| Punished players | server, numbered prev/next | server default (not sent) | server: `kind`, `section`, `permanent`, `expiresBefore`, `automatic`, `actor`, `sort`, `direction`, `page`. Search is **client-side**, current page only | GET `/punishment/active?kind&section&permanent&automatic&actor&sort&direction&page` | `PunishedPlayersView.vue:34-66, 85-89, 196-200`; `blackhole.ts:743` |
| Appeals | none (full list collected) | 200 per request, up to 25 requests | client: pending/all filter; no sort | GET `/appeal?pageable={}&page&size=200` (collected) | `AppealsView.vue:26, 33`; `blackhole.ts:587-589` |
| Appeals, unanswered marker | none | one timeline read per pending appeal | client | GET `/case/{ref}/timeline?types=message.added&order=desc&size=1` per appeal (N+1) | `AppealsView.vue:47-61`; `blackhole.ts:754` |
| Reports | none (full list collected) | 200 per request, up to 25 | client: open/all filter; no sort | GET `/report?pageable={}&page&size=200` (collected) | `ReportsView.vue:24, 29-31`; `blackhole.ts:554-558` |
| Reports against a player (detail) | none | 200 per request | client sort by `createdAt` | GET `/report?reportedHash&page&size=200` (collected) | `ReportDetailView.vue:63-67`; `blackhole.ts:563-572` |
| Evidence (case / report side) | server, numbered prev/next | 20 | server: `includeInactive`, `page`, `size` | GET `/punishment/{reference}/evidence?includeInactive&page&size=20` | `EvidencePanel.vue:26-42`; `EvidenceList.vue:19-21`; `blackhole.ts:633-640` |
| Evidence (report detail) | fixed: `page=0`, `totalPages=1` | all referenced items fetched by id | none | GET `/evidence/{identifier}` per reference | `ReportDetailView.vue:144`; `blackhole.ts:650` |
| Templates | none (full list collected) | 200 per request, up to 25 | none | GET `/template?pageable={}&page&size=200` (collected) | `TemplatesView.vue:21, 85-106`; `blackhole.ts:473-481` |
| Sections | none | unpaged | none | GET `/section` | `SectionsView.vue:23, 85-129`; `blackhole.ts:488-491` |
| Client rules | none | unpaged | none | GET `/client-rule` | `ClientRulesView.vue:22, 114-153`; `blackhole.ts:513-516` |
| Client rule hits (dialog) | none | unpaged | none | GET `/client-rule/{key}/hits` | `ClientRulesView.vue:80-91`; `blackhole.ts:531-533` |
| Player ELO history | none (full list collected) | 200 per request | section filter (server) | GET `/elo/{owner}/history?section&pageable={}&page&size=200` (collected) | `PlayerDetail.vue:54`; `blackhole.ts:461-470` |
| Player evasion sightings | none | unpaged | none | GET `/evasion/{owner}/sightings` | `EvasionHints.vue:21`; `blackhole.ts:617` |
| Dashboard escalations | none | unpaged (server's list) | none | GET `/dashboard/summary` | `DashboardView.vue:15, 76-98`; `blackhole.ts:435` |
| Admin: staff actors | none | unpaged, `body.items` | none | GET `/admin/staff-actor` | `StaffActorsPanel.vue:17`; `blackhole.ts:738` |
| Admin: staff links | none | unpaged, `body.items` | none | GET `/admin/staff-link` | `StaffLinksPanel.vue:17`; `blackhole.ts:691` |
| Admin: Discord links | none | unpaged, `body.items` | none | GET `/admin/discord-link` | `DiscordLinksPanel.vue:17`; `blackhole.ts:698` |
| Admin: imported punishments | none | unpaged | none | GET `/admin/import/punishments` | `ImportedPunishmentsPanel.vue:18`; `blackhole.ts:734` |
| Admin: CloudNet catalogue | none | unpaged | none | GET `/cloudnet/catalogue` | `blackhole.ts:502` |
| Players (lookup) | none (single result) | 1 | name, server-validated | GET `/player/resolve/{name}` | `PlayersView.vue:13, 32`; `blackhole.ts:438` |
| Portal conversation | cursor, read to the end on every poll | 200 (`PAGE_SIZE`), up to 25 pages per poll | none | GET `/api/conversation?cursor=` (BFF) | `portal/src/views/ConversationView.vue:64-82`; `portal/src/api/portal.ts:113`; `portal/src/api/contract.ts:273` |
| Portal appeal, case | none | no list | none | GET `/api/punishment`, `/api/appeal` | `portal/src/views/CaseView.vue` |

Notes:
- Full-list collection (appeals, reports, templates, ELO history) means every view loads all pages before it
  renders, and client-side filters run over that result.
- The Cases and Punished pages are the only ones with a true page cursor in the UI, and Punished's search is the
  only client filter on a server page.
- The portal is cursor-based with no UI paging; it reads the entire thread on every poll.

---

## 8. Error display in the UI

How errors reach the user:

- **Parsing (web)**: `web/src/api/http.ts:82-105` `errorFromBody`. It reads `errorMessage` first, then
  `_embedded.errors[0].message` (Micronaut validation), then `message`, and `kind` if it is one of
  `VALIDATION | NOT_FOUND | CONFLICT`. When `kind` is absent it maps the HTTP status (404 NOT_FOUND, 409 CONFLICT,
  400 VALIDATION, 401 UNAUTHORIZED, 403 FORBIDDEN). Without a message it uses `HTTP <status>`.
- **Network (web)**: `http.ts:108-118` `toResult` maps a thrown error to `kind: 'NETWORK'`, `status: 0`.
- **Type**: `web/src/api/types.ts:211-218`: `ApiError { status, message, kind }`, `kind` includes `NETWORK` and `UNKNOWN`.
- **List and page errors**: `web/src/components/ResourceState.vue:20-37` shows a `role="alert"` block with
  `t('state.error.<kind>')` as the title (`web/src/locales/en.json` `state.error`: VALIDATION, NOT_FOUND, CONFLICT,
  NETWORK, UNKNOWN, UNAUTHORIZED, FORBIDDEN) and the **raw `error.message`** below it, then a retry button.
  The title is translated; the detail is not.
- **Dialog and inline errors (web)**: about 40 call sites read `result.error.message` directly and show it, e.g.
  `components/reports/ReportResolveDialog.vue:49,67`, `components/reports/ReportDismissDialog.vue:40`,
  `components/case/CaseHeader.vue:75`, `views/SectionsView.vue:63`, `views/TemplatesView.vue:62-63`,
  `views/ClientRulesView.vue:72`, `views/ClientRulesView.vue:90`, `views/ReportDetailView.vue` (generic
  `reports.detail.loadFailed`, no detail).
- **Mapped messages (web)**: `components/appeals/AppealDecisionDialog.vue:51-57` `explain(status, message)` maps
  403 / 409 / 404 to translated copy and falls back to the raw message. `components/case/BanIdPanel.vue:53` maps 404 to
  `case.banId.notIssued` and falls back to the raw message. `views/PlayersView.vue:35` maps NOT_FOUND to
  `players.notFound` and falls back to the raw message.
- **403 handling (web)**: `web/src/api/http.ts:38` calls `auth.forbidden()`, which opens a snackbar
  (`web/src/App.vue:147`, `t('auth.forbidden')`). Views also show their own error.
- **Portal parsing**: `portal/src/api/portal.ts:55-104` `call()`. Only `code` (and `availableFrom`) is read from the
  JSON body. A missing or non-string `code` becomes `UNAVAILABLE`; a body that is not JSON becomes `UNAVAILABLE`;
  a thrown fetch becomes `UNAVAILABLE` with status 0 (`:61`). The raw message is **not** read or shown.
- **Portal display**: keyed by code with a fallback, `portal/src/views/ConversationView.vue:253-257`
  (`conversation.errors.<code>` else `conversation.errors.UNAVAILABLE`), `portal/src/views/AppealFormView.vue:35-42`
  (`appealForm.errors.<code>` else `UNAVAILABLE`; NOT_ELIGIBLE uses `availableFrom`). Both guard with `te()`.
- **Portal unguarded key**: `portal/src/components/ChatComposer.vue:67` builds `t(\`chat.errors.${refusal}\`)` without a
  `te()` check, so an unknown refusal code would show a raw key. `ChatComposer.vue:148` does guard with
  `INVALID_FILE`.
- **Portal read errors**: `ConversationView.vue:73-74, 295-297` maps FORBIDDEN to the limited notice and every other
  read failure to one generic string (`conversation.loadFailed`), with no code.
- **Portal case page**: `portal/src/views/CaseView.vue:48` shows only `app.loading` when `facts` is missing. There
  is no error branch in this view; whether the session store surfaces a failure was not verified.

Inconsistencies and gaps:
- **H** Two error vocabularies: the panel has codes plus raw messages; the portal has codes only. The same backend
  failure can read in English in one and in translated copy in the other.
- **H** Raw backend text in the German panel (about 40 call sites). The backend sentences are not translated, and
  for `HTTP <status>` fallbacks there is no context.
- **M** Silent failures: `AppealsView.vue:46` ("A failed read just shows no marker"); `web/src/components/account/AccountLinkDialog.vue:82`
  and `DiscordLinkSection.vue:78` (`catch {}`) drop the error completely.
- **M** The panel shows errors in four places: `ResourceState` (page), `role="alert"` blocks (dialogs and sections),
  `MdSnackbar` (forbidden only), and `explain()` mapping. There is no shared "error to copy" function.
- **M** Dialogs keep the error after a retry until the next open (`ReportResolveDialog.vue:37-49` resets on open
  only), so an old message can remain while a new request is in flight.
- **L** `portal/src/views/ConversationView.vue:298` shows the live reconnect state, but a stream error that is not
  a reconnect is not shown.
- **L** Error titles differ: `state.error.UNKNOWN` ("Something went wrong") in the panel, `conversation.errors.UNAVAILABLE`
  in the portal, `chat.errors.INVALID_FILE`. The copy is not aligned.

---

## Open items for step 2

- Generate the MD3 tokens (`npm run generate`) and measure the contrast of the staff and player bubble and the
  chip borders.
- Retry the login-page screenshots at 1440 px and 390 px once the Playwright browser profile is free. Target
  folder: `openspec/changes/redesign-ux/screens/`.
- Confirm whether Discord-sourced player messages reach the outbox (so the "Player (portal)" label is right).
- Confirm the default page size of `/case` and `/punishment/active` on the backend (the client sends no `size`).
- Read the components not read in full: `components/case/conversation.ts`, `ParticipantErasure.vue`,
  `ProtocolSection.vue`, `CaseEventEntry.vue`, `components/admin/*`, `components/reports/*`, `components/appeals/*`.
