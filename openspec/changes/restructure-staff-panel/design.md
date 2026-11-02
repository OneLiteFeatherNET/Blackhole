# Design

Starting point: the panel on `main` after `redesign-ux` (routes, two workspaces, `PageShell`, `FilterToolbar`,
`DataTable`, the four inbox sections) and `add-case-workspace-tabs` (case tabs, `?tab=`). Terms follow the glossary
of `redesign-ux`.

## What this supersedes

| From | What stays | What changes |
|---|---|---|
| `redesign-ux` section 1 (navigation) | The two workspaces, the switcher, the route names, the redirects of its table | Group names, the Today group, badges, icons, bar/rail per width, shortcuts. The inbox is no longer only a count list. |
| `redesign-ux` "Inbox (`/`)" | The four sections and their oldest-first rule as the base | The inbox becomes a queue: a "mine" section, urgency, row actions, role-specific content. The "recently changed" section comes with the API change. |
| `redesign-ux` "Breadcrumbs" | Breadcrumb rule (route match, not prefix) | Breadcrumbs are part of the entity header (decision 4). |
| `redesign-ux` section 2 ("States", "Row actions") | All of it | Used as is. Disabled actions gain a visible reason (decision 4). |
| `add-case-workspace-tabs` | The tab query and the case bar | The case bar becomes the shared entity header. Close gets undo. |

Nothing in `redesign-ux` is edited; this change is the next step on top of it.

## Decision 1: the moderator inbox is a work queue

**Question.** What does a moderator see first, and what may they do from there?

**Decision.** The inbox (`/`) shows sections in this order. Each section has a count (not capped), the first ten
rows, and a "Show all" link to the same list filtered in the URL.

| # | Section | Rows | Source (after phase 1) | Viewer | Moderator | Admin |
|---|---|---|---|---|---|---|
| 1 | Assigned to me | cases with `assignee` = my oid, not closed, oldest activity first | `GET /case?assignee=<oid>&status=` (oid from `GET /auth/me`) | read | read, Close, Assign to me | as moderator |
| 2 | Unassigned and urgent | cases with no assignee and priority URGENT or HIGH, not closed | `GET /case?priority=URGENT` and `GET /case?priority=HIGH`, merged; rows without an assignee kept | read | read, Assign to me | as moderator |
| 3 | Appeals waiting for a decision | pending appeals without a decision | `GET /inbox` `openAppeals` | read | read, Open | as moderator |
| 4 | Answers waiting | appeal chats whose last visible message is from the player | `GET /inbox` `needsAnswer` | read | read, Open | as moderator |
| 5 | Open reports | reports with status OPEN | `GET /inbox` `newReports` | read | read, Open | as moderator |
| 6 | Changed by others today | cases and appeals whose last change came from another staff member | needs the follow-up after `add-staff-panel-read-endpoints` (not in phases 1-7) | read | read | as moderator |

Rules:

- **Counts and badges come from one read.** The navigation badges and the section counts use the same
  responses, so they cannot disagree. Badges are capped at `99+` (existing `badgeText`).
- **Sections a role cannot read are hidden, not shown as errors** (as `redesign-ux` already says).
- **Row actions** are one per row and only where the action is safe and reversible: "Assign to me" (undo:
  "Unassign" in the same row for 10 s) and "Close" on a case (undo, decision 4). Decisions on appeals and
  resolving reports are not row actions: they open the entity, where the reason is visible.
- **Every row is a link** to the entity's own URL (`/cases/:ref`, `/appeals/:id`, `/reports/:id`). The row is
  not clickable as a whole with a hidden target; the link is the row's name.
- **Empty states** say what the section is for and what is done: "No case is assigned to you. Cases you take
  appear here." and "No appeal waits for a decision." No empty state suggests an action the role cannot do.
- **Role differences** (roles in `Roles.java`: ADMIN > MODERATOR > VIEWER):
  - VIEWER: every section read-only. The row action area shows nothing; the header of the page says
    "Read-only: moderators act on cases and appeals."
  - MODERATOR: all actions above.
  - ADMIN: the moderator view plus the switcher to Administration (decision 2). The inbox does not add admin
    sections; the exemption reviews stay in Administration.
- **"Me" needs the verified oid.** `GET /auth/me` returns `oid` and `roles` from the verified token. If the
  caller has no oid (for example during a not-yet-linked session), section 1 shows "Your account has no staff
  identity yet" and links to the account menu, instead of an empty list.

**Sketch, inbox at expanded width (>= 840dp):**

```
+-----+----------------------------------------------------------------------+
| nav | Inbox                                          [search........] (me) |
|     |                                                                      |
| Today|  Assigned to me                      3   [Show all >]              |
| Work |  ---------------------------------------------------------------     |
| ... |  A1B2C3D4  Open   Player ab12…  since 2 h    [Close]   [Open >]      |
|     |  E5F6A7B8  Review Player cd34…  since 1 d    [Close]   [Open >]      |
|     |                                                                      |
|     |  Unassigned and urgent              2   [Show all >]                 |
|     |  ---------------------------------------------------------------     |
|     |  C9D0E1F2  Open   Player ef56…  since 3 h    [Assign to me] [Open >] |
|     |                                                                      |
|     |  Appeals waiting for a decision     5   [Show all >]                 |
|     |  …                                                                   |
+-----+----------------------------------------------------------------------+
```

**Sketch, inbox at compact width (< 600dp):** the same sections in one column; the navigation bar sits at the
bottom with five destinations: Inbox, Cases, Appeals, Reports, More.

```
+------------------------------+
| Inbox              [search]  |
| Assigned to me          3    |
|  A1B2C3D4  Open  2 h   >     |
|  E5F6A7B8  Review 1 d  >     |
| Unassigned and urgent   2    |
|  C9D0E1F2  Open  3 h   >     |
| Appeals to decide       5    |
|  …                           |
|                              |
| [Inbox][Cases][Appeals]      |
|       [Reports][More]        |
+------------------------------+
```

**Alternatives rejected.**

- *One mixed list sorted by age.* Rejected: a moderator cannot tell an appeal from a case at a glance, and "mine"
  disappears in the crowd.
- *A personal dashboard with charts.* Rejected: the numbers are what the team acts on; charts add reading time
  and no action.
- *Assignment as a dialog for every row.* Rejected: one click to take an item is the point. The undo covers
  the mistake.
- *Client-side search over the sections.* Rejected: lists filter on the server (`redesign-ux` section 10).

## Decision 2: navigation by job, M3 widths, no drawer

**Question.** How are the destinations grouped, does the workspace split stay, and which Material 3 layout is
used at each width?

**Decision.** The workspace split stays (decided 2026-10-25 and 2026-10-27). Within Moderation, destinations are
grouped by the job a moderator does:

| Group | Destinations (route) | Icon (proposed Material Symbol) | Badge |
|---|---|---|---|
| Today | Inbox (`/`) | `inbox` | total of the inbox (assigned + unassigned urgent + appeals + reports) |
| Work | Cases (`/cases`) | `folder_open` | open cases |
| Work | Appeals (`/appeals`) | `gavel` | appeals waiting for a decision |
| Work | Reports (`/reports`) | `flag` | open reports |
| Players | Player lookup (`/players`) | `person_search` | none |
| Players | Active punishments (`/punished`) | `block` | none |
| Protection | Join guard (`/guard`) | `shield` | none |
| Protection | Area bans (`/area-bans`) | `map` | none |
| Protection | IP bans (`/ip-bans`) | `router` | none |
| Protection | Allow list (`/intel/allow-list`) | `playlist_add_check` | none |
| Protection | VPN policy (`/intel/policy`) | `vpn_lock` | none |

Administration (ADMIN only) keeps `redesign-ux`'s groups: Configuration (Templates, Sections, Client rules,
Appeal terms), Review (Exemption reviews), System (Staff accounts, Rollback, Imports, CloudNet, Kick, Audit).
Every item gets its own icon: the six System items currently share one (`ADMIN_ICON`), and that is fixed.

**Widths and layout** (window size classes, M3):

| Width | Navigation | Notes |
|---|---|---|
| Compact, < 600dp | Navigation bar at the bottom, five items: Inbox, Cases, Appeals, Reports, More. "More" opens a sheet with the other groups. | The bar is the only navigation (M3: compact windows use a navigation bar). |
| Medium, 600-839dp | Collapsed navigation rail with the five primary items and a "More" button that opens an expanded rail as a modal (not a drawer). | M3 Expressive no longer recommends the drawer; the modal expanded rail is the replacement. |
| Expanded, >= 840dp | Expanded standard rail, always open, with the group headings. | Matches the redesign's "groups with headings". |

Rules:

- **No navigation drawer** is added anywhere. The current `MdNavigationDrawer` is replaced by the rail
  component on medium and expanded widths.
- **The workspace switcher** is the dropdown at the top right of the app bar (decision of 2026-10-27), shown to
  ADMIN only. On compact it is an icon button.
- **Badges** are a small dot-count at the icon's upper right on compact and medium, and beside the label on
  the expanded rail (M3 rail guidance).
- **Keyboard shortcuts** for the top destinations, pressed as a chord outside text fields: `g i` Inbox, `g c`
  Cases, `g a` Appeals, `g r` Reports, `g p` Player lookup. `?` opens a list of the shortcuts. Chords are
  ignored while focus is in an input, a textarea or a dialog.
- **Global search** (top bar) is scoped by type. Typed input is classified in the search component: a player
  name opens the player, `#A1B2C3D4` or a bare short reference opens a case, a short id of an appeal or a
  report opens that item. The result list names the type of each hit ("Player", "Case", "Appeal", "Report").
  Suggestions and the typed text are cleared on navigation (the current search keeps them, which is listed
  in the audit).

**Alternatives rejected.**

- *A drawer on medium.* Rejected: the M3 guidance no longer recommends it; a modal rail does the same job.
- *Merging the two workspaces into one rail.* Rejected: moderators would see configuration they cannot use, and
  the split is decided and in place.
- *Icons only, no labels, on the rail.* Rejected: the audit found the icons ambiguous; labels stay on the rail.
- *More than five items in the bar.* Rejected: M3 limits the bar to five; the extra groups sit behind "More".

## Decision 3: unique, stable, shareable URLs

**Question.** What is the address of every entity and every view state, and how are players addressed without
putting a name or a Ban ID in a URL?

**Decision.**

- **Entities** get one canonical URL each, and the same URL opens the same entity for anyone with access:
  - Case: `/cases/:reference`, where `:reference` is the case's short reference (`shortId`). The label
    `#A1B2C3D4` in the UI has no `#` in the URL.
  - Appeal: `/appeals/:id`, where `:id` is the appeal identifier (UUID). The appeal page is new (phase 6).
  - Report: `/reports/:id`, where `:id` is the report identifier (UUID). Exists.
  - Player: `/players/:owner`, where `:owner` is the owner hash. Exists.
- **Players are not renamed to a readable address.** Options considered:
  1. *Name in the URL* (`/players/by-name/Steve`). Rejected: a player name in the path is written to access logs,
     browser history and referrers. Principle 1 allows names to be stored, but nothing needs them in a URL.
  2. *A new short owner id.* Rejected: it is a new key to store, keep unique and erase, for a readable title
     that the header can show anyway.
  3. *Keep the owner hash, show the readable name in the header and the page title.* **Chosen.** The owner hash
     is stable and already in use; the title shows the last known name from the name history.
- **Name lookup** stays in the search box and on `/players`. The name is sent to `GET /player/resolve/{name}`,
  and the browser then replaces the address with `/players/:owner`. The name is never in the address bar.
- **Ban IDs never appear in a URL, a log or an event.** No route takes a Ban ID or a punishment identifier.
  The case and appeal pages reach punishments through their own ids.
- **View state** is in the query, so a link opens the same view:
  - Tab: `?tab=` (case tabs from `add-case-workspace-tabs`; player and appeal pages use the same parameter).
  - Filters: `?status=`, `?assignee=` (`me` or an oid), `?priority=`, `?section=`, `?tag=`, `?q=` on lists.
  - Queue: `?view=mine|unassigned|appeals|reports` on the inbox (opens the same section list).
  - Open item in a list-detail layout: the item's own URL (`/cases/:ref?status=open`), so the list state and the
    open item are both in the address (decision 5).
  - Unknown or missing parameter values fall back to the default and are removed with `replace`, not left in
    the address as garbage.
- **Filters are not stored** on the device or the server. The URL is the only memory. A nav click to `/cases`
  opens the defaults (open, all assignees). The open question below lists the alternative.

**URL table (old to new).** "Kept" means the URL does not change.

| Old URL | New URL | Status | Notes |
|---|---|---|---|
| `/` | `/` | kept | Inbox. Adds `?view=`. |
| `/cases` | `/cases` | kept | Adds `?status=`, `?assignee=`, `?priority=`, `?section=`, `?tag=`. Old `status` values unchanged. |
| `/cases/:reference` | `/cases/:reference` | kept | Adds `?tab=` (from `add-case-workspace-tabs`). In list-detail, the list is the parent. |
| `/cases/:reference?status=…` | `/cases/:reference?status=…` | kept | The list state is kept while an item is open. |
| `/appeals` | `/appeals` | kept | `?show=all` becomes `?status=all`; `?show=all` redirects with the query kept. Default `?status=pending`. |
| `/appeals?show=all` | `/appeals?status=all` | redirect | Phase 5. |
| `/appeals/:id` | `/appeals/:id` | **new** | Phase 6. Needs `add-staff-panel-read-endpoints`. Before that, the list-detail pane shows the appeal from the list response. |
| `/reports` | `/reports` | kept | Adds `?status=open` (default) and `?player=` (owner hash). |
| `/reports/:id` | `/reports/:id` | kept | Adds the resolve action; no new URL. |
| `/players` | `/players` | kept | The search box resolves a name and replaces the address with `/players/:owner`. |
| `/players/:owner` | `/players/:owner` | kept | Adds `?tab=` (punishments, reports, cases, evasion, names). |
| `/punished` | `/punished` | kept | Adds `?kind=`. |
| `/ip-bans`, `/guard`, `/area-bans`, `/intel/policy`, `/intel/allow-list` | same | kept | No change. |
| `/admin` | `/admin/templates` | kept (`redesign-ux`) | |
| `/templates`, `/sections`, `/client-rules`, `/appeal-conditions` | `/admin/...` | kept (`redesign-ux`) | Old routes redirect, query kept. |
| `/admin/staff`, `/admin/imports`, `/admin/cloudnet`, `/admin/kick`, `/admin/audit`, `/admin/rollback` | same | kept | |

Every redirect keeps the query. A link that has no route (for example `/cases/:reference/timeline`) lands on
the case page with `?tab=notes-audit`, not on "not found".

## Decision 4: one entity header, the activity, cross-links, undo

**Question.** How does a moderator see the state of an entity and where something happens?

**Decision.** Case, appeal, report and player pages share one header component, `EntityHeader`:

```
Moderation > Cases > A1B2C3D4                                   [ ? ] [Account]
+------------------------------------------------------------------------------+
| CASE  A1B2C3D4   [ In review ]   Player ab12…   Assignee: Mira   Priority: High |
| Primary action: [ Close case ]                       [ … more ]               |
| Tabs: Overview | Conversation | Notes & audit                                |
+------------------------------------------------------------------------------+
```

Parts, in this order:

1. **Type label**: CASE, APPEAL, REPORT, PLAYER (text, not only colour).
2. **Short id**: case short reference, appeal id (first eight characters with a copy button), report id, or the
   player's readable name with the owner hash as a secondary line.
3. **State pill**: the state of the entity in words and colour, from one mapping:
   - Case: OPEN, IN_REVIEW, WAITING, CLOSED.
   - Appeal: SUBMITTED, ELIGIBLE_PENDING_REVIEW, IN_REVIEW (pending); GRANTED_FULL_LIFT, GRANTED_DURATION_REDUCTION,
     DENIED, INELIGIBLE (decided).
   - Report: OPEN, UNDER_REVIEW (pending); ACTIONED, DISMISSED (resolved).
   - Player: punishment state (active, expired, lifted) and whether any is in force.
4. **Owner** (player link) and **assignee** (name, or "Unassigned" with the action to take it).
5. **Primary action**: one, the next step of the entity (Close, Reopen, Decide, Resolve, Take). A disabled
   primary action shows its reason as text under the header (not only as a tooltip), and the reason is linked
   to the button with `aria-describedby`. Reason examples: "A viewer cannot change a case.", "This appeal is
   already decided.", "Waiting for the player's answer."
6. **Overflow menu** with the other actions.
7. **Breadcrumb**: workspace > list > short id, using the route match rule of `redesign-ux`.

**Activity per entity**, shown as a timeline on the "Notes & audit" tab (case) or the "Activity" tab (appeal,
report, player). What exists and what is added:

| Entity | Shown now | Added here |
|---|---|---|
| Case | timeline (`GET /case/:ref/timeline`), notes, protocols | status changes with the previous status and the actor's name; assignments |
| Appeal | conversation (`caseTimeline`), decision text | the decision and its time as the last item; needs the appeal read (phase 6) |
| Report | the report fields only | status changes (resolution) as the report's own history. Not in the backend yet: the page shows "created" and "resolved" with the times. The timeline for reports is an open question. |
| Player | punishments, reports, evasion, names | links to the cases and appeals of the player, in date order |

**Cross-links.** Every entity page links to the entities it belongs to, in the "Related" block of the overview:

- case to its appeal, its report and its player, and to the punishment of the player (as now);
- appeal to its case, its punishment and its player;
- report to its player and its case (once the report has one; no link otherwise);
- player to all cases, appeals, reports and punishments (filtered lists, with `?owner=` in the query).

**Confirmation and undo.**

- **Close and reopen a case** happen in one click (as now, with no confirmation) and show a snackbar for ten
  seconds with **Undo**. Undo sends the case update with the version read after the first change, so a
  conflict stops the undo and says so.
- **Reopen** returns the case to the status it had before it was closed. That status is read from the case
  timeline (the last status change before CLOSED). If none is found, the case is reopened to OPEN, and the
  snackbar says so.
- **Appeal decisions and report resolutions** keep their dialogs: they are not undone with a snackbar, because
  the player is notified.
- **A conflict** (409 on any update) keeps the entered text and offers a reload, as `add-case-workspace-tabs`
  already does.

**Alternatives rejected.**

- *A separate header per entity type.* Rejected: four headers drift apart, which is the problem today.
- *Confirmation dialogs for close and reopen.* Rejected for close and reopen: they are reversible and a dialog
  on every close slows the frequent last step. Undo gives the same protection without the extra click.

## Decision 5: list-detail on expanded widths

**Question.** How do cases, appeals and reports open without losing the list?

**Decision.** Cases, appeals and reports use the list-detail layout, which the M3 guidance calls canonical for
this kind of data:

```
Expanded (>= 840dp)
+-----+----------------------------+----------------------------------------------+
| nav | List (360-400dp)           | Detail (rest of the width)                   |
|     | [status v] [assignee v]    | CASE A1B2C3D4  [ In review ]  Player ab12…   |
|     | > A1B2C3D4  In review  2h  |   Primary: [Close case]                      |
|     |   E5F6A7B8  Open       1d  |   Tabs: Overview | Conversation | Notes      |
|     |   C9D0E1F2  Waiting    3d  |   ...                                        |
|     | j/k move, Enter opens      |                                              |
+-----+----------------------------+----------------------------------------------+
URL: /cases/A1B2C3D4?status=in_review  (the open row is the URL)

Medium (600-839dp): the detail replaces the list in the same column; the list is
one Back away (the header's "< Cases" link). The rail is collapsed.

Compact (< 600dp): list at /cases, detail at /cases/A1B2C3D4 as its own page.
```

Rules:

- **The URL names the open item.** Opening a row pushes `/<list>/<id>` and keeps the list query. Back returns to
  the list with the item unselected at expanded width, and to the list page at compact width.
- **The list is not re-fetched** when the open item changes; only the detail loads.
- **Keyboard**: `j` and `k` move the row selection, `Enter` opens the selected row, `Esc` closes the detail
  (back to the list URL), and the focus returns to the row. Shortcuts are off in text fields.
- **Selection is visible**: the open row has a selected state and `aria-current="true"`.
- **Bulk actions** stay in `add-case-bulk-actions`; the docked toolbar pattern is used when they land.
- **Appeals** use the same layout once `/appeals/:id` exists (phase 6). Until then the appeals list keeps its
  table and its decision dialog.

**Alternatives rejected.**

- *A table with a modal for the detail.* Rejected: the audit found the modal hides the context and cannot be
  linked.
- *A detail in a drawer on the right.* Rejected: the M3 guidance does not recommend a drawer for this.

## Decision 6: phases

Each phase is one code PR, `feat(web)`, usable on its own. The order keeps the panel working after each one.
Phase 1 needs nothing from the backend. Phases that need the companion change say so.

| # | PR title | Needs | Why this order |
|---|---|---|---|
| 1 | `feat(web): turn the inbox into a work queue` | nothing new (`/inbox`, `/case`, `/auth/me`) | The biggest pain, and no navigation change. |
| 2 | `feat(web): group the navigation by job and add badges and shortcuts` | nothing | Once the inbox is useful, the rail can point to it. |
| 3 | `feat(web): add the entity header with state, owner and breadcrumbs` | nothing | Every later page uses the header. |
| 4 | `feat(web): open cases and reports in list and detail panes` | nothing | Cases and reports exist already; appeals follow in phase 6. |
| 5 | `feat(web): add shareable urls for view state and a name-free player lookup` | nothing | Query parameters and the redirects of `show=all`. |
| 6 | `feat(web): add the appeal detail page at /appeals/:id` | `add-staff-panel-read-endpoints` (`GET /appeal/{identifier}`) | Needs the appeal read. Until then appeals keep the list. |
| 7 | `feat(web): resolve reports on their page and undo case closing` | nothing | The resolve endpoint exists; undo uses the case timeline. |

The keyboard list movement (`j`, `k`, `Enter`, `Esc`) is part of phase 4, not a phase of its own.

The companion change `add-staff-panel-read-endpoints` (`feat(api)`) is proposed separately and merged before
phase 6. It adds `GET /appeal/{identifier}` and the inbox fields `kind`, `status`, `priority`, `assignee` and
`lastActor`. Inbox section 6 (changed by others) follows that change in its own small web PR; it is not one of
the seven phases above.

## Risks and trade-offs

- **The "me" section depends on the verified oid.** If `/auth/me` returns no oid, section 1 says so (decision 1).
- **Undo can race with another moderator.** The version check stops it; the snackbar then says "The case changed
  since. Reload to see it."
- **Shortcuts collide with browser or screen-reader keys.** Only chords that start with `g` are used, and they
  are off in text fields and dialogs.
- **Names in the header.** The player's name is shown in the header, not in the URL. Names are personal data
  already stored under principle 1; their display stays in the panel for staff only.
- **More PRs.** Seven PRs take longer than one. Each one is small and leaves the panel usable.

## Open questions for the maintainer

1. **Remember filters across visits?** This proposal keeps them in the URL only. Remembering the last filter per
   viewer would need `localStorage` (a per-device convenience) or a server-side preference (new state with
   retention). Which one, if any?
2. **Reports timeline.** Should reports get their own history (status changes with actor), which needs backend
   work, or is "created" and "resolved" enough?
3. **Urgency.** Is "urgent" a case priority (HIGH) only, or should an appeal close to its deadline count too?
4. **Shortcut keys.** Are the `g` chords acceptable to the team, or do they prefer number keys?
5. **Reopen target.** Is "back to the previous status" what the team wants, or always OPEN as today?
6. **Player display name.** Is the last known name from the name history the right title for the header, given
   that names are personal data (principle 1)?
