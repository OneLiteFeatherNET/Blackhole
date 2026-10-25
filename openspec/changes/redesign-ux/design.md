# Design

Every decision names the audit finding it answers (`audit.md` section and item). "Current" below means the state on
`main` at e86fea6. Where the audit could not measure something (contrast, 390 px layout), the decision says so.

## Context

- The panel (`web/`) has nine routes in four navigation groups plus an admin-only "System" group (audit 1.1).
  The portal (`portal/`) has no router and no component library (audit 3). The Discord relay labels every staff
  message "Moderation team" (audit 4, `OutboxRelay.java:218`).
- The appeal chat design already settled that staff are not named to players (`add-appeal-portal` design 10, 16;
  `add-appeal-chat` design 7: "Moderation team (never a name)"). The user decision of 2026-10-25 reverses that for
  staff messages. Those design documents are amended by the deltas in this change, not edited.
- `staff_actor` (V9) stores the Entra `name` claim for every principal and anonymisation clears it
  (`verified-actor` spec). `staff_link` (V10) links an `oid` to a Minecraft UUID one to one. `staff_link` keeps the
  raw `minecraft_uuid` (see Risks).
- Principle 1 (`openspec/config.yaml`): raw player UUIDs are hashed or tokenised wherever they are published. The
  avatar design follows this.
- Migrations on `main` run to V24 (`V24__player_seen.sql`). The number of the new migration is fixed at
  implementation time (see "Flyway migrations").
- The brand skill `anthropic-skills:onelitefeather-brand` was not available in the session that wrote this
  design. Tokens therefore follow the repo's existing MD3 setup (seed `#6750A4`, `web/scripts/generate-md3-tokens.mjs`)
  and the Roboto font already used by the panel. The portal's brand gradient (audit 3) is kept as the single brand
  token. Open question 5 asks for a brand check before PR 1.

## Goals / Non-Goals

**Goals:** one navigation and one design system across the three surfaces; staff identity that players see and
that is governed by a clear rule; lists and errors that behave the same everywhere; accessibility fixes from the
audit.

**Non-Goals:** see the proposal. Notably no pagination API (separate change), no player accounts, no redesign of
the punishment model.

---

## 1. Navigation

### Before (audit 1.1-1.7)

| Group | Items |
|---|---|
| overview (no heading) | Dashboard / Übersicht (`/`) |
| Moderation | Cases (`/cases`), Appeals (`/appeals`), Reports (`/reports`), Punished players (`/punished`), Players (`/players`) |
| Rules | Sections (`/sections`), Templates (`/templates`), Appeal conditions (`/appeal-conditions`), Client rules (`/client-rules`) |
| System (admin only) | Admin / Verwaltung (`/admin`) |

Problems: the Dashboard heading and item are both "Übersicht" (audit 1.5); "Players" is a lookup, not a list (1.4,
1.2); the Sections page holds the staff and Discord account panels (1.3, 6.6); no single place lists what needs
attention (1.6); detail pages have text back links and no breadcrumbs (1.7).

### After

Panel sidebar, in this order. Routes are kept unless a row says "renamed route"; every renamed route keeps a redirect.

| Group (heading always shown) | Item (route) | EN / DE | Badge |
|---|---|---|---|
| (ungrouped home) | Inbox (`/`, replaces the dashboard) | Inbox / Posteingang | total of the inbox |
| **Work** / **Arbeit** | Cases (`/cases`) | Cases / Fälle | open cases |
| | Appeals (`/appeals`) | Appeals / Einsprüche | open appeals |
| | Reports (`/reports`) | Reports / Meldungen | open reports |
| **Players** / **Spieler** | Player lookup (`/players`) | Player lookup / Spielersuche | none |
| | Active punishments (`/punished`) | Active punishments / Aktive Strafen | none |
| **Configuration** / **Konfiguration** | Sections (`/sections`) | Sections / Bereiche | none |
| | Templates (`/templates`) | Templates / Vorlagen | none |
| | Appeal terms (`/appeal-conditions`) | Appeal terms / Einspruchsbedingungen | none |
| | Client rules (`/client-rules`) | Client rules / Client-Regeln | none |
| **Administration** / **Verwaltung** (admin only) | Administration (`/admin`, tabs: Staff accounts `/admin/staff`, Imports, CloudNet) | Administration / Verwaltung | none |

Changes against the before table:

- **Dashboard becomes Inbox.** The active-punishment cards and the escalation table move to the Inbox as a section
  "Automatic escalations" (kept, from the current dashboard) or to Active punishments; no information is dropped.
- **Groups are renamed** to match what the user does (Work, Players, Configuration, Administration). Every group has
  a heading, so no one-item unlabelled group remains (1.1).
- **Player lookup and Active punishments move from Moderation to a Players group.** Both are about players, not
  about cases, so they no longer sit next to the case lists (audit 1.2). "Players" is renamed "Player lookup" because
  it is a lookup form (1.4).
- **Sections, Templates, Appeal terms and Client rules** stay in Configuration. Admin-only *editing* is shown in
  each page; viewers see the same page read-only (unchanged).
- **Account panels leave Sections.** The staff's own link (Minecraft and Discord) moves into the account menu
  ("My profile", "Link my accounts"). Admin lists of all staff links (`StaffLinksPanel`, `DiscordLinksPanel`,
  `StaffActorsPanel`) move to `/admin/staff` (1.3, 6.6).
- **Administration** keeps its tools but in tabs with a heading, so the admin area has the same structure as the
  rest (1.2).

The account menu (top bar, every signed-in user) gets: My profile / Mein Profil (display name and avatar choice,
see section 5), Link my Minecraft account / Minecraft-Konto verknüpfen, Link Discord / Discord verknüpfen.

### Decision 2026-10-25: two workspaces (supersedes the single-rail navigation)

The user split the panel into two workspaces. This decision replaces the "After" table above and the rail groups of
the navigation. Everything else in this section (the Inbox, breadcrumbs, glossary) still applies, with the workspace
added where stated.

**Before** (the table above, as on `main`): one rail for everyone, with the admin tabs at the bottom.

| Rail group | Items | Visible to |
|---|---|---|
| (Inbox) | Inbox / Posteingang | every role with read access |
| Work / Arbeit | Cases, Appeals, Reports | every role with read access |
| Players / Spieler | Player lookup, Active punishments | every role with read access |
| Configuration / Konfiguration | Sections, Templates, Appeal terms, Client rules | every role with read access (editing: admins) |
| Administration / Verwaltung | tabs: Staff accounts, Imports, CloudNet (on `/admin`) | ADMIN |

**After**: a workspace switcher at the top left of the app bar. Each workspace has its own rail.

| Workspace (switcher) | Rail group | Item | Route | Visible to |
|---|---|---|---|---|
| Moderation / Moderation | (Inbox) | Inbox / Posteingang | `/` | every role with read access |
| Moderation | Work / Arbeit | Cases / Fälle | `/cases` | every role with read access |
| Moderation | Work / Arbeit | Appeals / Einsprüche | `/appeals` | every role with read access |
| Moderation | Work / Arbeit | Reports / Meldungen | `/reports` | every role with read access |
| Moderation | Players / Spieler | Player lookup / Spielersuche | `/players` | every role with read access |
| Moderation | Players / Spieler | Active punishments / Aktive Strafen | `/punished` | every role with read access |
| Administration / Verwaltung | Configuration / Konfiguration | Templates / Vorlagen | `/admin/templates` | ADMIN |
| Administration / Verwaltung | Configuration / Konfiguration | Sections / Bereiche | `/admin/sections` | ADMIN |
| Administration / Verwaltung | Configuration / Konfiguration | Client rules / Client-Regeln | `/admin/client-rules` | ADMIN |
| Administration / Verwaltung | Configuration / Konfiguration | Appeal terms / Einspruchsbedingungen | `/admin/appeal-terms` | ADMIN |
| Administration / Verwaltung | System / System | Staff accounts / Teammitglieder-Konten | `/admin/staff` | ADMIN |
| Administration / Verwaltung | System / System | Imports / Importe | `/admin/imports` | ADMIN |
| Administration / Verwaltung | System / System | CloudNet / CloudNet | `/admin/cloudnet` | ADMIN |

Rules:

- **Switcher.** The switcher lists Moderation and Administration. It is shown to ADMIN only. Every other role has
  Moderation alone, sees no switcher and no Administration entry, and the breadcrumbs carry no workspace name.
- **Access.** Every `/admin/*` route needs ADMIN. The router guard (`meta.admin`, `authGuard.ts`) sends any other role to
  the no-access page with `reason=admin`. The navigation config does not decide access; it only hides what the guard
  would refuse.
- **Moderator information.** Moderators do not browse configuration. Where they need it, it appears in the moderation
  dialogs: the punish dialog's template picker (`ApplyTemplateDialog`) and the report resolve dialog's template list
  read `GET /templates`, which the moderator role may call. Those pickers must keep working for MODERATOR; the tests
  name that.
- **Administration landing.** `/admin` redirects to `/admin/templates`. Templates is the first configuration page and
  the first thing an administrator sets up. No overview page is invented; "System status" is not offered because no
  page for it exists.
- **Tabs retired.** The tab strip of `AdminView` is removed. Staff accounts, Imports and CloudNet become rail items
  with their existing URLs (`/admin/staff`, `/admin/imports`, `/admin/cloudnet`); each is its own page.
- **Inbox stays the Moderation home** at `/`.
- **URL is the source of truth.** The workspace of a page comes from its route (`meta.workspace`). The last workspace
  is also stored in `localStorage` (try/catch, convenience only): when a signed-in administrator opens the bare `/`
  after sign-in, the remembered workspace's home opens. Any explicit URL wins over the stored value.
- **Breadcrumbs.** When the switcher is shown, detail pages (case, report, player) start their trail with the workspace
  name, for example Moderation › Cases › `CASE-REF`.

**Old URLs redirect** (the query string is kept):

| Old route | New route |
|---|---|
| `/admin` | `/admin/templates` |
| `/templates` | `/admin/templates` |
| `/sections` | `/admin/sections` |
| `/client-rules` | `/admin/client-rules` |
| `/appeal-conditions` | `/admin/appeal-terms` |

Unchanged: `/`, `/cases`, `/cases/:reference`, `/appeals`, `/reports`, `/reports/:id`, `/players`, `/players/:owner`,
`/punished`, `/admin/staff`, `/admin/imports`, `/admin/cloudnet`.

**Glossary additions.** Workspace / Arbeitsbereich (the switcher's label); Moderation / Moderation; Administration /
Verwaltung (the workspace, replacing the former rail group of the same name); System / System (rail group of
Staff accounts, Imports and CloudNet).

**Consequences.** Moderators lose the configuration pages in the navigation; a moderator who opens a configuration URL
lands on the no-access page, which explains the admin requirement. The navigation test of `redesign-ux` task 2b.1
(the single-rail shape) is replaced by the per-workspace shape in task 3f.1.

### Inbox (`/`)

Four sections, each with a count, the first 10 items, and a "Show all" link to the filtered list:

1. **Needs an answer / Wartet auf Antwort**: appeal chats whose last visible message is from the player, oldest
   first. Replaces the N+1 timeline read per pending appeal (audit 7, "Appeals, unanswered marker").
2. **Open appeals / Offene Einsprüche**: pending appeals without a decision, oldest first.
3. **New reports / Neue Meldungen**: reports with status open, oldest first.
4. **Unassigned cases / Nicht zugewiesene Fälle**: open cases with no assignee.

Backed by a new read `GET /inbox` (backend, `feat(api)`) that returns the four counts and up to 10 items each in one
query per section. Sections the user cannot read are omitted, not shown as errors. The Inbox replaces the dashboard
cards (audit 6.5), and the nav badges come from the same response so they cannot disagree with the page.

### Breadcrumbs

Every detail page uses `PageShell` breadcrumbs: Cases / Fälle › `CASE-REF`; Reports / Meldungen › Report #id;
Player lookup › player name; Administration › Staff accounts. Text back links are removed (1.7). The parent item is
marked current by route match, not by prefix (`MdNavigationDrawer.vue:41-44`).

### Glossary

One term per concept, in every surface. "Replaces" lists what is retired from `main`.

| Concept | EN | DE | Replaces / retired | Where |
|---|---|---|---|---|
| The sanction itself | Punishment | Strafe | "Bans" (untranslated DE, `punished.kind.BAN`) | panel, portal |
| Kind: ban | Ban | Sperre | "Bans" in DE | panel, portal, Discord |
| The record with its chat | Case | Fall | portal "Area" | panel; players see "your punishment" |
| A section of punishments | Section | Bereich | portal "Area"/"Bereich" | panel, portal, Discord |
| The request to reverse a punishment | Appeal | Einspruch | panel "Appeals" as the only form | panel, portal (plural in nav) |
| The chat of an appeal | Chat | Chat | "Conversation", "Konversation" | panel, portal |
| A person on the team | Staff member | Teammitglied | "Staff", "Team", "Moderation team", "Moderationsteam" | portal, panel, Discord |
| The team as a group | Staff | Team | "Moderation team" | panel nav groups only |
| The player | Player | Spieler | none | all |
| Credential of the portal | Ban ID | Ban-ID | "ban ID" (Discord), "Ban-ID" vs "Ban ID" mix | all |
| Reusable punishment text | Template | Vorlage | "Punishment templates", "Strafvorlagen" | panel |
| A player's complaint | Report | Meldung | the detail page used "Meldung" as its title while the list said "Meldungen"; the title is now "Meldung" everywhere | panel, Discord |
| Who works on a case | Assignee | Bearbeiter | none | panel |
| Front page of the panel | Inbox | Posteingang | "Übersicht" (dashboard, group heading) | panel |
| Lookup of a player | Player lookup | Spielersuche | "Players", "Spieler" (as nav) | panel |
| Punishments that are in force | Active punishments | Aktive Strafen | "Punished players", "Gesperrte Spieler" | panel |
| Eligibility text of the appeal | Appeal terms | Einspruchsbedingungen | "Appeal conditions", "Rahmenbedingungen" | panel; portal "Conditions" / "Bedingungen" |
| Record of a finished chat | Protocol | Protokoll | "transcript", "export" for the player copy | panel, portal, Discord |
| Staff message from an unlinked Discord account | Unverified staff | Nicht verifiziertes Teammitglied | "unverified staff (Discord)" | Discord, panel |
| Removed message | Message removed | Nachricht entfernt | "message removed" (portal), "deleted" (panel) | portal, panel |
| Share of evidence into a chat | Shared evidence | Geteilter Nachweis | "Shared by the moderation team" | portal |

Rule: the portal and the panel use the same English and German term for a concept. Player-facing strings stay
English only in the chat (decision 7 of `add-appeal-chat`), so the German column applies to the portal's German
locale and to the panel. A Discord message has one language per message (see section 8).

---

## 2. Design-system contract (panel `web/`, portal adopts the same rules)

### Spacing: one scale on a 4 px base

From the audit's counts (section "Spacing"): `gap-2` 82, `p-3` 70, `gap-3` 60, `gap-1` 34, `gap-4` 29, `p-4` 24,
`px-4` 18, `gap-5` 17, `mt-3` 16, `pt-2` 15. The scale keeps the steps that carry them and drops the rest:

| Token | Value | Tailwind | Used for |
|---|---|---|---|
| `space-1` | 4 px | `1` | icon-to-label, chip inner gap |
| `space-2` | 8 px | `2` | gap between controls in a row, dense row padding |
| `space-3` | 12 px | `3` | toolbar gap, dense card body, list row padding |
| `space-4` | 16 px | `4` | card body, form field gap, page gutter on phones |
| `space-6` | 24 px | `6` | gap between page sections, page gutter on desktop |
| `space-8` | 32 px | `8` | gap between major page blocks only |

Rules: one role, one step. Card body is `space-4` everywhere (`MdCard.vue:14` already does; `ProtocolSection.vue:134`
moves from `p-3` to `p-4`). `gap-5` (20 px, 17 uses) becomes `gap-4` or `gap-6` by role. Retired: `0.5`, `1.5`, `5`,
`10`, and the arbitrary values `grid-cols-[auto_1fr]` (use a shared `DefinitionList` component) and `max-w-[88%]`
(a token). A lint test fails on any `p-5`, `gap-5`, `*-0.5`, `*-1.5` or `[...]` spacing class in `web/src`.

### Type ramp: one role per style

| Role | Token | Audit uses today | Rule |
|---|---|---|---|
| Page title (the only `h1`) | `headline-small` | `headline-small` 8 (LeaveView, ReportDetail h2) | set by `PageShell` only |
| Section heading | `title-medium` | `title-medium` 26 | `h2` |
| Card or sub-section | `title-small` | `title-small` 17 | `h3` |
| Body | `body-medium` | 92 | default text, dialog body |
| Lead paragraph | `body-large` | 60 | intro of an empty state or a page only |
| Meta (time, count, reference) | `body-small` | 82 | secondary text, `text-on-surface-variant` |
| Field label, table header, dl label | `label-medium` | 4 | new use, replaces ad-hoc `body-small` labels in dl grids |
| Button, chip, tab | `label-large` | 40 | never `body-*` for controls |
| Inbox counts | `display-small` | 3 | Inbox only |

Retired: `title-large` (16, one of which is the Dashboard heading), `headline-medium`, `headline-large` (2 each),
`label-small` (2), and `body-small` for dl content. A test scans for the retired classes.

### Buttons

| Variant | Use | Height |
|---|---|---|
| Filled | the one primary action of a page or dialog | 40 px |
| Tonal | the secondary action; a row's primary action | 40 px |
| Outlined | a neutral choice in a dialog (e.g. "Show all templates") | 40 px |
| Text | cancel, inline help links | 40 px |
| Error-filled | confirm of a destructive action only | 40 px |
| Icon | toolbar and overflow menus; always with an accessible name | 40 px |

Rules: at most one filled button per page section. Destructive actions are never a row button; they are in the
overflow menu and confirmed in a dialog with the error-filled button (audit 2 Buttons, the text buttons for Edit and
Delete at `SectionsView.vue:121-126`, `TemplatesView.vue:100-103`, `ClientRulesView.vue:147-150`).
`MdButton` gets an `as="link"` option that renders `RouterLink` with the same classes, so a navigation is a real
link and looks like a tonal button; the link-styled-as-button in `ReportsView.vue:84-95` and
`ReportDetailView.vue:93-95` goes away (audit 2, 5).

The ad-hoc link class string (about 15 uses, audit 2 "Text links") becomes a `TextLink` component.

### Chips

| Variant | Shape | Use | Audit reference |
|---|---|---|---|
| Filter chip | `rounded-full`, 32 px, check icon when selected | toggles in a filter toolbar | `MdChip.vue:11` moves from `rounded-md-sm` |
| Input chip | `rounded-full`, 32 px, remove button | an applied filter shown below the toolbar | new (audit 2 "missing feedback") |
| Status chip | `rounded-full`, 24 px, not interactive | status of a case, appeal, report; "Verified", "Unverified", "Deleted" | the tag pills of `CaseHeader.vue:136` |

Rule: a chip that is clickable is a filter chip; a chip that is not clickable is a status chip. Nothing else uses
`rounded-md-sm`. The six `rounded-md-sm` uses are migrated in PR 1.

### One filter idiom: `FilterToolbar`

One component, one row on desktop, wrapping on phones, always in this order:
search field (dense) → select filters (dense) → toggle group (filter chips) → sort (select plus one direction button
whose label is the target, e.g. "Newest first") → spacer → the page's primary action.

Below the toolbar, a row of input chips shows every applied filter (search text, tag, section, kind, "Assigned to
me"), each removable, and a "Clear all" text button when more than one is applied. A filter that is applied and
not shown as a chip is a bug (audit 2: the tag filter `CasesView.vue:77-82`, the `filter-mine` chip in
`PunishedPlayersView.vue:132`).

The four idioms in audit 2 (chips, pending/all pair, text plus submit, selects) are all `FilterToolbar` props:
`toggle-group` (single choice, e.g. Pending / All), `select`, `search` (debounced, server-side, see section 10),
`tag` (a text field whose value becomes an input chip; no submit button). The sort direction button in
`PunishedPlayersView.vue:141-143` shows its target, not its current state (audit 2 Wording).

PunishedPlayers, the worst case (audit 2, "three chip rows, two selects, a direction button and a search"), becomes
one toolbar: search, section select, kind toggle, "Automatic" toggle, sort select with direction. Its eight columns
move to a table with column priority (section 2, table).

### Row actions: one pattern

Every table row has at most two actions:

1. **The primary action**: the last cell, one tonal button named by its verb ("Open", "Decide", "Edit"). It is a
   `RouterLink` when it navigates (`as="link"`). The row itself is not clickable.
2. **Overflow**: an icon button (`RowMenu`, `aria-haspopup="menu"`, accessible name "More actions for <row>") with the
   secondary actions. Destructive actions live here, styled as error text.

This replaces the link beside an outlined button in `AppealsView.vue:108-119` and the link-styled-as-button plus
resolve in `ReportsView.vue:84-95` (audit 2 "Buttons and links", audit 6.9).

### Table component: `DataTable`

Replaces `MdDataTable` (audit 2 Tables).

- Props: `columns` (`key`, `label`, `sortable`, `priority` 1-3, `align`), `rows`, `caption` (required, visually
  hidden if needed), `loading` (skeleton rows), `error` (a `ProblemBanner`), `empty` (an `EmptyState` slot).
- Headers: `scope="col"`, `aria-sort` when sortable, the sort control is the header button.
- Cell content: one primary line, optional one secondary line in `body-small`. No stacked `<p>` lines, no `dl`
  grids in cells (`SectionsView.vue:88-89`, `SectionsView.vue:102-120`, `ClientRulesView.vue:121-127`). Details move
  to the detail page.
- Row hover: the state-layer token (`--md-sys-state-hover`), not `hover:bg-on-surface/[0.08]` (`MdDataTable.vue:20`).
- Narrow screens (below 840 px): priority 3 columns are hidden, and each row is a card with the header label next to
  the value (`data-label`). No horizontal scroll. The audit could not measure 390 px; the card layout is verified in
  the Playwright check listed in the tasks (outside the unit tests).
- Pagination footer: `ListFooter` (section 10), not inside the table.
- Raw `<table>` in `AnticheatDetail.vue:30` and `VanillaImportPanel.vue:185` use `DataTable`.

### Page template: `PageShell`

Slots, in order: `breadcrumbs`, `title` (renders the one `h1`), `description` (lead, optional), `actions` (the
primary action), `toolbar` (`FilterToolbar`, optional), default content, `aside` (optional side panel).

- Desktop (1024 px and up): `aside` sits to the right of the content at 320 px. Below that width it stacks below the
  content, or is a drawer when the page is a chat (the case page).
- Container: `max-w-6xl` for lists and detail pages, `max-w-3xl` for forms, full width for the chat. Gutter
  `space-4` on phones and `space-6` on desktop (today's `p-4 md:p-6` at `App.vue:133` stays, now in one place).
- The duplicate page title is removed: `App.vue:134` renders the route title only as the document title and the
  `PageShell` `h1` is the single visible one (audit 2 "Page headers", 6.13).

### States: empty, loading, error

- **EmptyState**: icon (from one shared icon module, see below), title, one sentence, and one next step. Variants:
  `no-items` (with the create action), `no-matches` (with "Clear filters", which is the input-chip "Clear all"),
  `no-access` (with the reason). It replaces the plain paragraphs in `CasesView.vue:87`, `AppealsView.vue:84`,
  `ReportsView.vue:64`, `TemplatesView.vue:85`, `ClientRulesView.vue:111`, `PunishedPlayersView.vue:153-156` and
  the empty dashboard active list (`DashboardView.vue:57-67`, audit 2 "Empty states").
- **Loading**: `SkeletonRows` in a table and `SkeletonBlock` on detail pages on first load (audit 2: the top
  progress bar only, `ResourceState.vue:18`). The top progress bar stays for background refetches. Containers set
  `aria-busy="true"` while loading.
- **Error**: `ProblemBanner` (section 11) inside the content, with a retry button. It replaces `ResourceState`'s
  raw `error.message` (`ResourceState.vue:20-37`).

Icons: the inline SVG paths in `navigation.ts:29-84` and `App.vue:22` move to one `icons` module with a name per
icon. The brand mark stays a component (`MdTopAppBar.vue:16-19`). Icons next to a text label are decorative
(`aria-hidden`); icon-only buttons have a label.

### Dense field variant

- `MdTextField` and `MdSelect` get `density="dense"`: height 40 px (`h-10`), inner padding 12 px (`px-3`), used only
  inside a `FilterToolbar` and in compact dialogs with more than two fields.
- The default is 56 px (`h-14`) with `px-4`. Supporting text is `px-4` in both (`MdDurationField.vue:96` changes from
  `px-1`).
- Left edges align in a mixed toolbar because select and text field share the padding (audit 2 "Form fields").
- `--md-field-bg` is not set inline in dialogs (`MdDialog.vue:76`); the field background comes from the surface
  token so a field looks the same on the page and in a dialog (audit 2, low).
- `AppealConditionsView.vue:93-103` moves from the raw `<label>`/`<textarea>` to `MdTextField` with a counter in the
  supporting text and `aria-describedby` (audit 2 "Form fields" H, 6.10).

### When to use what

| Need | Use | Not |
|---|---|---|
| Show the page's name | `PageShell` `title` | a `h2` or a styled `p` |
| Choose one of 2 or 3 visible options | toggle group in the toolbar | a select |
| Choose one of more options | select (dense in toolbars) | a chip wall |
| Narrow a list | `FilterToolbar` filter | client-side search on one page |
| Go to a detail page | link (`as="link"`) or primary row action | a text link with a copied class string |
| Do something to a row | the primary action, or the overflow menu | two buttons side by side |
| Delete or revoke | overflow menu, then error-filled confirm dialog | a text button |
| Confirm a short decision or enter two or three fields | `MdDialog` | a page |
| Show a list of more than ten rows | a page, or a side panel | a dialog (`ClientRulesView.vue:170` wide hit list) |
| Show a status | status chip | a coloured text |
| Show a count in the navigation | badge | a card |
| Show a failure | `ProblemBanner` in place | a snackbar (except the 403 notice) |

---

## 3. Sharing tokens between `web/` and `portal/`

**Options considered.**

- A. A shared npm package (a workspace) for tokens and components.
- B. Duplicated tokens with a parity test in each app.
- C. One token source at the repo root, consumed by both apps through a build step, with a parity test.

**Recommendation: C.** One source file set `design/tokens/` (the MD3 seed, the generator moved from
`web/scripts/generate-md3-tokens.mjs`, the font and brand token). Each app runs a `prebuild` step that writes its
generated `md3-tokens.generated.css`, and a Vitest parity test in each app fails when the committed file differs
from the generator output for the same source. Components stay per app.

**Why not A.** The two apps have separate lockfiles and separate Docker build contexts (`web/Dockerfile`,
`portal/Dockerfile`). A workspace package changes both contexts and the lockfiles in one step, a risk to the
release pipeline that the tokens do not justify. The package would also have to carry Vue components the portal
does not use yet.

**Why not B alone.** Audit 3 shows the drift that duplication already caused: the body token differs
(`--md-sys-color-surface` against `--md-sys-color-background`), the font differs (Roboto against Roboto Flex), the
brand colour is hard-coded in the portal only, and only the panel has a contrast test. Two copies with a test
still have two sources of truth.

**Components.** The portal adopts the same rules, not the same code: its own `Button`, `Chip`, `TextField` and
`EmptyState`, written to the same class contract, in PR 6b (`feat(portal)`). This is more code than option A but
keeps the portal bundle small. The duplication is recorded as a trade-off (see Risks), as `add-appeal-portal` did.

**Font.** Roboto everywhere; `Roboto Flex` is removed from the portal (`portal/src/styles/main.css:14`). Subject to
the brand check (open question 5).

---

## 4. Staff identity: the model

### 4.1 What a staff member is to a player

- A **display name** (chosen by the staff member, see 4.2) and an **avatar** (4.4).
- Shown on every staff message in the appeal chat, on the shared-evidence card, on the appeal decision text, in the
  player copy of the protocol, and in the Discord ticket channel.
- The player never sees the Entra name, the `oid`, the Minecraft UUID, the Discord id, or the staff trust level.
  "Verified" is an internal flag; the player sees only the name.

### 4.2 The display name (`staff-profile` capability)

- **Who sets it.** The staff member, in the panel, under My profile. An `ADMIN` cannot set another person's name,
  because the name is the staff member's own statement to players.
- **Required before writing to players.** A staff account without a display name cannot post an appeal chat
  message, send an appeal decision with a player message, post a shared-evidence card, or reply from a linked
  Discord account. Those writes answer `409` with Problem Details `code: STAFF_PROFILE_REQUIRED`. Reading the
  case, reviewing, deciding without a message, and dismissing are not blocked (they show no name to players).
- **Default.** Empty. The Entra `name` claim is never used as the default, and is never copied into the profile.
- **Rules.** 2 to 32 characters after trimming; letters, digits, space, hyphen, underscore, full stop and
  apostrophe only; Unicode normalisation NFKC; no `@`, `#`, `:`, `<`, `>`, `*`, `` ` `` or `|` (markup in chat);
  no URL, IP, UUID or Ban ID lookalike (the existing `PiiGuard` rules); no reserved word ("Staff", "Team", "Moderation",
  "Admin", "System", "Bot", "Support", "Discord", and their German forms "Team", "Moderation", "Verwaltung",
  "System", "Bot", "Support"), compared case-insensitively.
- **Uniqueness.** Among named profiles, case-insensitive and after NFKC folding. Two staff members cannot both be
  "Mia". The conflict answers `409` `NAME_TAKEN` (a field error on the form). Recommended default; open question 2.
- **Pseudonyms.** Allowed. The staff member chooses; the profile does not check that it is a real name.
- **Changes.** A rename applies to new messages. Messages already written keep the name they had (4.6).

### 4.3 Storage

Migration `V<n>__staff_profile.sql` (number fixed at implementation, see "Flyway migrations"):

- `staff_profile`: `oid uuid primary key` (no foreign key, the same pattern as `staff_actor`), `display_name
  varchar(32) null`, `display_name_folded varchar(32) null` with a partial unique index where it is not null,
  `avatar_mode varchar(16) not null default 'AUTO'` (`AUTO` or `INITIALS`), `avatar_key varchar(32) null` (set when
  the account is linked, see 4.4), `notice_acknowledged_at timestamptz null`, `updated_at timestamptz not null`,
  `anonymized_at timestamptz null`.
- `staff_avatar`: `avatar_key varchar(32) primary key`, `png bytea not null`, `fetched_at timestamptz not null`.
  A cache of 64 px heads. One row per linked staff account.

Existing staff have no profile row: the default is "no name". Existing messages keep their generic label (6.3).

### 4.4 Avatar: a head from the linked account, initials otherwise

**Recommended default.** The avatar is a Minecraft head rendered from the staff member's linked Minecraft UUID,
if the account is linked and `avatar_mode = AUTO`; otherwise an initials badge (first letter of the first two
words of the display name) drawn locally in SVG with a colour chosen from six tonal roles by hash of the
`avatar_key`.

**How the head is produced (backend only).**

1. When a staff link is created or refreshed, the link service computes `avatar_key = base64url(HMAC-SHA256(
   avatar key, "avatar:" + minecraft_uuid))` truncated to 22 characters and stores it in `staff_profile.avatar_key`.
   The key is a new secret `BLACKHOLE_STAFF_AVATAR_KEY` with a local-dev default in `application.yml`, documented
   by a comment (project convention).
2. A job fetches the skin for the UUID from the fixed session-server host configured by
   `BLACKHOLE_STAFF_AVATAR_SOURCE_URL` (default: the Mojang session server), crops the head, scales it to 64 px and
   stores the PNG in `staff_avatar`. The host is fixed by configuration, not caller-supplied, so SSRF hardening
   (principle 5) is not triggered; the URL is still validated at startup.
3. The job runs on link, then weekly. A failed fetch keeps the old PNG, and falls back to initials when there is
   none (the avatar never blocks a message).
4. Unlink and anonymisation delete the `staff_avatar` row and clear `avatar_key`.

**What the player's browser sees.** Only `GET /api/avatars/{avatar_key}` on the appeal host (through the portal
BFF). The response is the PNG with `Cache-Control: public, max-age=86400` and no cookie. The key is an HMAC, so the
URL reveals neither the UUID nor the account. Initials are rendered in the browser with no request at all. No
third-party host is contacted from the player's browser.

**Discord.** The embed author icon is an absolute URL on the appeal host (`https://appeal.onelitefeather.net/
avatars/{key}`), fetched by Discord. It is the same public image; Discord learns the key, not the UUID. Initials use
no icon.

**Why not a third-party head service called from the browser.** It would send the staff member's UUID and the
player's IP to a third party on every page view. Principle 1 forbids that.

### 4.5 Staff notice and consent

- Showing a staff member's name and public Minecraft skin to a banned player is new processing of staff personal
  data. The change adds a notice in the My profile dialog, which must be acknowledged before the name is saved
  (checkbox, stored as `notice_acknowledged_at`). The notice says that players see the name and the head, that the
  name is recorded in the chat and in the protocol, and that the head can be switched to initials.
- **Opt-in for the head** is the stricter alternative. The recommended default is notice plus the initials choice;
  open question 3.
- **Legal points, marked pending legal confirmation:** the lawful basis for showing staff identity (legitimate
  interest against a staff notice); whether the Mojang lookup needs a notice; the retention of the avatar cache (this
  design uses 30 days after the last refresh for unlinked, and until anonymisation for linked accounts); the wording
  of the staff notice; and whether staff in other countries need a different notice.

### 4.6 Snapshot or live name

**Recommended: snapshot the name in the chat event at write time; read the avatar live.**

- A `message.added` written by a staff member carries `author.staffDisplayName` in its payload (payload version 2).
  The chat then shows the name the staff member had when they wrote, the same as a protocol. Renames do not rewrite
  history, which matches the append-only event log.
- The avatar is live: an unlinked account loses its head at once, which is the desired effect of unlinking.
- Trade-off: a staff member who is later renamed keeps the old name in old messages. The anonymisation rule (4.8)
  covers the case that matters (leaving the team).

### 4.7 Protocols

- **Protocol version 2** (`schemaVersion` 2) writes the snapshot name for each staff entry and the avatar key in the
  JSON copy. The text copy writes the name.
- **Protocol version 1** (existing, stored) is **not rewritten**: it keeps "Moderation team" / "Staff member (name
  removed)". The renderer reads the version and uses the matching label.
- **Player copy** (`GET /public/appeal/protocol`, `appeal-portal` "The player copy is player-visible only"): staff
  entries show the name. The copy adds one sentence to its header in both languages: "Staff members appear by the
  display name they chose. Their names are recorded in this chat." Wording pending legal confirmation.
- **Retention:** a protocol keeps staff names as long as its case exists (the existing rule). Anonymisation of a
  staff member regenerates the protocols that name them (the same rule that `discord-transcript` uses for
  anonymised Discord participants).

### 4.8 Erasure and anonymisation

`POST /admin/staff-actor/{oid}/anonymize` (existing, `verified-actor`) also: clears `staff_profile.display_name`
and `display_name_folded`, sets `anonymized_at`, deletes the `avatar_key` and the `staff_avatar` row, removes the
name snapshot from the `message.added` payloads of that `oid` (set to null, the same mechanism as text redaction),
and regenerates the affected protocols. The operation stays audited with the admin's verified actor. Pending legal
confirmation: whether a staff name in a player's protocol must be replaced rather than removed.

---

## 5. Staff identity in the three surfaces

| Surface | Today | After |
|---|---|---|
| Portal chat (`ConversationView.vue:239-241`, `MessageBubble.vue:12-13,70`) | "Moderation team" once per run | staff avatar and name on the first bubble of each run; name is the snapshot |
| Portal share card (`en.json:167`) | "Shared by the moderation team" | "Shared by <name>" |
| Portal decision (`en.json` decision copy) | decision enum and neutral text | the player message, if any, shows the author's name |
| Panel chat (`ConversationSection.vue:152-159`) | `actor.name` or the first 8 characters of the oid | the display name (never the oid); the avatar; if the profile is missing, "Staff member (no name yet)" to the team only |
| Panel composer (`ConversationSection.vue`) | enabled | disabled with a `ProblemBanner` "Set your display name to reply" and a link to My profile, until the name is saved |
| Discord ticket channel (`OutboxRelay.java:213-222`, `JdaGateway.java:576`) | "Moderation team" | the display name as the embed author, the avatar as the author icon |
| Protocol (`ProtocolModel.java:54-60`, `PublicProtocolService.java:31,96`) | "Moderation team", "Staff member (name removed)" | v2: display name; v1 unchanged |

---

## 6. Backend contract changes

The exact files, from the audit's section 4 table, plus the files the new capability needs.

1. **Public conversation DTO** (`backend/src/main/java/net/onelitefeather/blackhole/backend/publicapi/dto/PublicConversationDTO.java`).
   The `Author` enum (lines 22-25) keeps `PLAYER`, `STAFF`, `SYSTEM`. The `Entry` record (from line 43) gets an
   optional `staff` object `{displayName, avatarKey}`. `avatarKey` is `null` for initials. The javadoc "Staff are
   never named" (lines 15, 22) is replaced. The portal turns `avatarKey` into `/api/avatars/{key}`.
   Compatibility: additive; the portal is the only client.
2. **Reader** (`publicapi/JdbcPublicConversationReader.java:117`). `author` for `STAFF` rows reads
   `payload->'author'->>'staffDisplayName'` (null for version 1 rows) and the `avatar_key` of `staff_profile` by the
   row's `actor_oid`. The name-column drop at line 117 is removed. The reader keeps the player's visibility filter.
3. **Protocol** (`casetimeline/protocol/`):
   - `PublicProtocolService.java:31` and `:96`: `who()` maps `STAFF` to the display name for version 2, to
     "Moderation team" for version 1.
   - `ProtocolModel.java:54-60`: add `STAFF_LABEL_NAMED` (not a constant name; the name comes from the entry).
   - `ProtocolModel.java:360-372` (`authorLabel`) and `:380-398` (`eventAuthor`): VERIFIED staff use the snapshot
     name for version 2; the staff copy keeps the stored labels.
   - `ProtocolRedaction.java`: clears the name snapshot when the staff member is anonymised.
4. **Event write** (`casetimeline/CaseEventPayload.java:472`, the author object). A `message.added` by a staff member
   carries `author.staffDisplayName` (from `staff_profile` at write time), `payloadVersion = 2`. The staff message
   service (the case conversation service in `casetimeline/`; the exact class is named in PR 4b and must be checked
   against the code at implementation) answers `STAFF_PROFILE_REQUIRED` before the event is written.
5. **Discord** (`backend/src/main/java/net/onelitefeather/blackhole/backend/discord/dto/DiscordTicketDTO.java:262`).
   The outbox `ADD` item gets `author {kind, displayName, avatarUrl}`. The javadoc "the moderation team" changes.
6. **Discord relay** (`discord/src/main/java/net/onelitefeather/blackhole/discord/sync/OutboxRelay.java:213-222`
   `label()`, `:218-219`) and (`discord/src/main/java/net/onelitefeather/blackhole/discord/gateway/JdaGateway.java:576`):
   `setAuthor(displayName, null, avatarUrl)` when the author is a named staff member; `setAuthor(label)` for other
   participants (labels per section 8).
7. **Panel** (`web/src/components/case/ConversationSection.vue:152-159`): `authorName()` uses the DTO name and never
   the oid prefix (the fallback at line 157 is removed).
8. **Staff profile API** (new): `GET /staff-profile/me` and `PUT /staff-profile/me` (human token, any staff role;
   service principal 403). `PUT` takes `{displayName, avatarMode, noticeAcknowledged}`, answers the sealed
   `Response` or `Error` variant (the DTO contract). Named `staff-profile` in the OpenAPI client spec.
9. **Staff profile validation** lives in a `@Singleton` service in `staffprofile/` (the feature package, not a
   generic `service/`). The controller calls one service method (principle 3).
10. **Inbox** (new): `GET /inbox` in `inbox/` (feature package), one service method, four queries.

---

## 7. Portal flow: login, conditions, appeal, chat

One guided flow, the same on mobile and desktop, implemented as a route state machine (`portal/src/flow/`), not as
separate pages with their own back buttons.

| Step | Route (view ref) | Content | Leaves when |
|---|---|---|---|
| 1 Ban ID | `login` | Ban ID field (dense-free, default height), "Where do I find it?" link, the Ban ID rules | session established |
| 2 Terms | `conditions` | the appeal terms (`ConditionsText`), a checkbox "I have read the appeal terms" | the checkbox is ticked and the eligibility is checked |
| 3 Appeal | `appeal` | the reason field (counter, supporting text), the eligibility result, the submit button | the appeal is filed |
| 4 Chat | `chat` | the punishment facts, the chat (`MessageBubble`, `ChatComposer`), the decision when decided | never (end state) |

- The server state drives the start point: a player with a filed appeal lands on the chat; a player with an
  eligible but not filed appeal lands on step 3. Going back is allowed until the appeal is filed.
- **Stepper.** Mobile: "Step 2 of 4 · Terms" with a progress bar, one line. Desktop: a horizontal stepper above the
  content. Each step has `aria-current="step"` on the active item. The stepper items are text, not icons.
- **Focus.** On each step change the new `h1` takes focus, so a screen reader announces the step.
- **Draft.** The reason text is kept in memory across steps and across the session-expiry page (the existing
  behaviour of `add-appeal-portal`).
- **Chat layout.** Mobile: full width with a 16 px gutter; the composer is sticky at the bottom; the facts are a
  collapsed disclosure above the chat. Desktop: facts at 320 px on the left and the chat on the right, the
  container at `max-w-5xl`.
- **Older messages.** The chat loads the newest page; older messages load with a "Load older messages" button at the
  top (section 10). Polling fetches only messages after the last `seq`, so the whole thread is no longer read on
  every poll (audit 7, "Portal conversation").

---

## 8. Discord: embeds, tone and language

- **Author** is the staff display name, the icon is the avatar. Unverified Discord staff: "Unverified staff ·
  <Discord name>", no icon. Player: "Player (portal)" as today. Other participants: "Other participant".
- **Footer**: the case reference and the appeal number, as now.
- **Tone** (applies to every bot message, player-visible or staff-only): sentence case, no exclamation marks, no
  "Moderation team", short. Examples: "Appeal received." "The appeal was decided: granted." "Waiting for a reply from
  the team."
- **Language.** One language per message. Messages a player can see are in English (decision 7 of `add-appeal-chat`).
  Staff-only bot embeds (reports, ticket opened, leak warnings) keep the bilingual "EN · DE" form, because the team is
  bilingual (audit 2 "Discord-facing wording"). This is a change from the current mixed embed titles only for the
  player-visible ones.
- **Staff without a profile** (linked, but no display name): the bot does not drop the message. The message is stored
  with visibility `STAFF` (hidden from the player) and the bot replies in the channel, ephemeral where possible:
  "Set your display name in the panel under My profile before your replies reach the player." The staff member
  cannot make a message reach the player until the name is set.

---

## 9. Accessibility (audit section 5)

- **Headings.** One `h1` per view, from `PageShell`. Sections `h2`, sub-sections `h3`. Fixes: the report detail
  duplicate (`ReportDetailView.vue:100`, audit 2, 6.13) and the skipped level on the case page (`CaseView.vue:91`).
- **Focus.** The `md-state` focus ring is the one focus style; `PlayerDetailView.vue:75` gets it (it has none).
  `ReportsView.vue:84-95` and `ReportDetailView.vue:93-95` get it through `as="link"`. Dialogs keep their focus trap
  and return (`MdDialog.vue:15-59`, good as is). The portal step change moves focus to the step `h1`.
- **Labels.** `AppealConditionsView.vue:93-103` gets a labelled `MdTextField`; the counter is in the supporting text,
  linked with `aria-describedby`. Every table has a caption. Icon-only buttons have text labels (`MdIconButton.vue:9-10`
  keeps `aria-label` and drops the duplicate `title` where the visible text is already the label).
- **Live regions.** The chat thread keeps `role="log"` with `aria-live="polite"` (`ConversationSection.vue:260-267`).
  The second `role="status"` announcer that reads each new message (`:257`) is removed; one `StatusAnnouncer`
  reports actions ("Message sent", "Appeal decided"). `AppealsView.vue:83` announces the count once when the list
  loads, not on every change.
- **Reporter hash.** The shortened hash is visible text with `aria-describedby` pointing to "pseudonymous reporter
  identifier", not only a `title` (`ReportDetailView.vue:118-120`).
- **Snackbar.** `MdSnackbar.vue:8` closes after 6 s. Errors do not use the snackbar (section 11). Success messages
  pause on hover and focus and have an action where one makes sense.
- **Contrast.** Not measured in the audit. The palette pairs are checked by `web/tests/contrast.test.ts` once
  `npm run generate` has produced `md3-tokens.generated.css`. The staff and player bubble borders and the filter chip
  borders are added to that test (open item from the audit). The portal gets the same test (audit 3 says it has none).
- **Reflow.** Table cards below 840 px; the audit's 390 px check is run by Playwright at implementation.

---

## 10. Pagination in the UI

Depends on `standardize-api-pagination-and-problems`: every list returns `{items, nextCursor}`, and errors are Problem
Details. This change does not define the API; it defines the UI.

- **`useCursorList`** (a composable, panel and portal): takes a fetch function and the filter values; holds `items`,
  `nextCursor`, `loading`, `error`; `loadMore()`; `reset()` on any filter change. Filters are kept in the route query
  (so Back returns to the same filter and page).
- **`ListFooter`**: "Load more" button (outlined), the count "Showing N" (no total: cursor APIs do not return one).
  While loading the button is disabled and `aria-busy` is set. When `nextCursor` is null the footer says "All
  shown".
- **Server-side filters and search.** The search and the filters are sent to the API. The client-side search over
  `punished.data.value?.items` (`PunishedPlayersView.vue:85-89`) and the client-side filters of appeals, reports,
  templates and ELO history are removed. The search field is debounced on the client (no timer in tests: the
  debounce is a function that tests call directly, not `setTimeout` waits).
- **Removed client code:** `collectPages`, `MAX_PAGES`, `PAGE_SIZE = 200` and `EVIDENCE_PAGE_SIZE` in
  `web/src/api/blackhole.ts` (lines 285-350). Every full-list collection (appeals, reports, templates, ELO history,
  staff links) becomes a `useCursorList`.
- **Filter parameters the UI needs** that the pagination change does not name: `q` (search) on
  `/punishment/active`, `/report` and `/appeal`; `assignee=me` on `/case`; `open=true` on `/inbox` sections. Each
  is added by the PR that first uses it, in the same API PR, and the client spec is regenerated (see Open question 4
  and tasks 8a).
- **Portal.** The chat loads the newest page and older messages by cursor; polling is for `seq > last`.
- **Evidence.** The 20-item numbered pages (`EvidencePanel.vue:26-42`) become `useCursorList`.

---

## 11. Error display

### One mapping

A single function `problemCopy(problem, t)` in `web/src/api/problem.ts` and `portal/src/api/problem.ts` (the same
rules in both; the test file is the same):

1. The input is the Problem Details JSON (`type`, `title`, `status`, `detail`, `code`, `traceId`), or a network
   failure (`kind: NETWORK`, status 0).
2. If `code` is known (the catalogue `errors.<CODE>.title` and `errors.<CODE>.body` in both locale files), the copy is
   the translation. Example: `STAFF_PROFILE_REQUIRED`, `NAME_TAKEN`, `FORBIDDEN`, `FROZEN`, `FORFEITED`.
3. If `code` is unknown or missing, the copy is the generic translation for the status class (`errors.status.4xx`,
   `errors.status.5xx`, `errors.network`), and the server's `title` and `detail` are **not** shown.
4. The `traceId`, when present, is shown as "Reference: <id>" for support. It is not a secret and is the only server
   text that is shown.

**Recommended default.** The server `detail` is never shown, even for validation errors, which are shown by field
name and a translated message keyed by the field and the `code`. Open question 4 asks whether validation details may
be shown for field-level messages only.

### Where it is shown

- Page or list load: `ProblemBanner` in place with a retry button (`ResourceState.vue` is rewritten to use it).
- Dialog: `ProblemBanner` inside the dialog. The error clears when the dialog reopens and when a new request starts
  (audit 6.1, "Dialogs keep the error after a retry", `ReportResolveDialog.vue:37-49`).
- Form field: `FieldError` under the field, using the `field` from the Problem Details extension
  (`errors[].field`) when the API sends it.
- Snackbar: only the forbidden notice (`App.vue:147`, `auth.forbidden`), with the same translated copy.

### Silent failures become visible

- `AppealsView.vue:46` ("a failed read just shows no marker"): the inbox and appeals list show the `ProblemBanner`.
- `AccountLinkDialog.vue:82` and `DiscordLinkSection.vue:78` (`catch {}`): the error is shown in place with retry.
- Portal `CaseView.vue:48` (shows only `app.loading` when facts are missing): an error branch is added.
- Portal `ConversationView.vue:298`: a stream error that is not a reconnect is shown.
- Lint: an empty `catch` is an error (ESLint `no-empty`, enabled for both apps).

### Raw text removed

The about 40 call sites that read `result.error.message` (audit 8) move to `problemCopy`. The list in audit 8 is the
checklist for PR 9a; a test greps both apps for `error.message` and `.message` in template bindings and fails on any
match outside `problem.ts`.

### Portal unguarded key

`ChatComposer.vue:67` (`t(\`chat.errors.${refusal}\`)` without `te()`) uses `problemCopy`, so an unknown refusal
shows the generic copy, never a raw key.

### Titles

One generic title in both apps: "Something went wrong" / "Etwas ist schiefgelaufen" (today `state.error.UNKNOWN` in
the panel and `conversation.errors.UNAVAILABLE` in the portal differ, audit 8 low).

---

## 12. Tests (F.I.R.S.T.)

- **Vitest** for `web/` and `portal/`, component tests with `@vue/test-utils`. Each behaviour is one test, named after
  the behaviour.
- **Fast and independent.** No `setTimeout` waits; the debounce and the step timers are tested with an injected
  scheduler. No shared module-level state; each test mounts its own component.
- **Repeatable.** Dates come from an injected clock (the Inbox "oldest first" and the staff-profile notice time).
  The network is a fake fetch. No real backend.
- **Self-validating.** Assertions only; the audit's counts become tests: a lint test asserts that the retired classes
  (section 2) appear nowhere in `web/src` and `portal/src`.
- **Backend** tests are JUnit, in the feature packages (`staffprofile/`, `inbox/`, `publicapi/`, `casetimeline/`),
  one behaviour per test, and no `@Transactional` (principle 6 and the project's known gap).
- **Discord** tests (`discord/src/test/java`) are unit tests with the relay behind an interface, as the existing
  `OutboundTextTest` does.
- **Real-app check.** Each PR that changes a page is verified in the running app (backend and Docker containers from
  `docker/docker-compose.yml`), per the project's runtime-verification rule. The 390 px and 1440 px checks are
  Playwright runs outside the unit suite.
- **Test first.** Every task group starts with the failing test (red), then the change (green), then the refactor.

---

## Flyway migrations

- **One migration** for the staff identity: `V<n>__staff_profile.sql`, where `<n>` is the next free number at
  implementation time (main is at V24 today, so V25 if nothing else lands first; other in-flight changes may take
  the number first). It creates `staff_profile` and `staff_avatar` with the indexes in 4.3. It is append-only
  (principle 6). It backfills nothing: no profile means no name, and the existing events keep their generic label
  (payload version 1).
- **Payload version 2** of `message.added` needs no DDL; it is a JSON field, and version 1 rows are read as before.
- **Inbox** needs no migration (read-only, existing indexes; to verify with `EXPLAIN` in the backend test).
- **Migration numbers in the in-flight designs are stale.** `add-appeal-portal` names `V13__ban_id.sql`,
  `V14__player_session.sql` and `V17__appeal_protocol.sql`. On `main`, V13 is `ban_id`, V14 is `discord_link`, V16 is
  `player_session` and V17 is `appeal_protocol`. This change does not renumber them; the implementation uses the
  files on `main`. Recorded as a risk.

## Client spec

- The HTTP API changes in three PRs (inbox, staff profile, conversation DTO). Each regenerates
  `client/specs/blackhole-api-<next>.yml`, where `<next>` is the next free version at implementation time (main is at
  0.0.40). The Velocity plugin is not affected; the portal and the panel use the generated client only where they
  already do.

## Risks / Trade-offs

- **[Staff pseudonyms impersonate]** uniqueness, reserved words, the notice, and a staff member's own
  responsibility; a moderator can ask for a rename, which is a change in the profile, not in history.
- **[A required name blocks an urgent reply]** by design; the reply can be made by an admin after the name is set;
  the composer says why it is blocked.
- **[The Mojang lookup sends a staff UUID to Mojang]** the UUID is public Minecraft data and the lookup happens on link
  and weekly, never from a player's browser. Pending legal confirmation.
- **[Raw `staff_link.minecraft_uuid`]** `staff_link` already stores the Minecraft UUID in clear (V10). The avatar
  feature does not add a copy, but principle 1 asks for hashing. Out of scope here; a follow-up change hashes it. The
  avatar code never sends the UUID anywhere except the Mojang lookup.
- **[Snapshot names keep old names]** accepted (4.6); anonymisation clears them.
- **[Duplicate components between `web/` and `portal/`]** accepted; the token source is shared (section 3), the
  components are not, as in `add-appeal-portal`.
- **[The Discord avatar is a public URL]** the image is the staff member's public skin or initials; the key is an HMAC.
- **[Legacy staff messages still say "Staff"]** accepted, and shown as "Staff" with no name, so players are not told
  a name that was not recorded.
- **[The brand is not checked]** the brand skill was unavailable; tokens follow the repo; open question 5.
- **[Stale migration numbers in in-flight designs]** recorded above; the implementation follows `main`.

## Migration Plan

1. PR 1 (tokens, base components) and PR 2 (page template, navigation, Inbox) ship to the panel with no data change
   (PR 2a adds the read-only `GET /inbox` first, client spec bumped).
2. Page PRs 3a-3d. Each page is independent and behind no flag; the rollback is a revert of that PR.
3. Staff identity: 4a (migration, profile API, required-name rule), 4b (conversation snapshot, public DTO, protocols),
   5 (panel My profile). Before 4a is deployed, no staff write is blocked. After 4a, staff must set a name before a
   reply reaches a player; the release note says so and the panel shows the banner from 5.
4. Portal 6a (names and avatars), 6b (guided flow). Players see names from the deployment of 4b.
5. Discord 7 after 4b.
6. Pagination UI 8a, 8b after the pagination API change is deployed.
7. Error display 9a-9c, docs 10.

Rollback: each PR reverts on its own. The staff profile table and the payload version 2 are ignored by the previous
backend; the required-name rule is switched off by reverting 4a.

## Open items

- Brand check (open question 5) before PR 1.
- Contrast numbers after `npm run generate` (audit open items).
- Confirm the default page size of `/case` and `/punishment/active` (audit open items).
- Confirm that a Discord-sourced player message reaches the outbox (audit open items; affects the "Player (portal)"
  label).
- Read `components/case/conversation.ts`, `ParticipantErasure.vue`, `ProtocolSection.vue`, `CaseEventEntry.vue`,
  `components/admin/*`, `components/reports/*`, `components/appeals/*` in full before PRs 3a-3d (audit open items).
