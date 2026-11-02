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

| # | Section | Rows | Source (phase 2) | Viewer | Moderator | Admin |
|---|---|---|---|---|---|---|
| 1 | Assigned to me | cases with `assignee` = my oid, not closed, oldest activity first | `GET /case?assignee=<oid>&status=` (oid from `GET /auth/me`) | read | read, Close, Assign to me | as moderator |
| 2 | Unassigned and urgent | cases with no assignee and priority URGENT or HIGH, not closed | `GET /case?priority=URGENT` and `GET /case?priority=HIGH`, merged; rows without an assignee kept | read | read, Assign to me | as moderator |
| 3 | Appeals waiting for a decision | pending appeals without a decision | `GET /inbox` `openAppeals` | read | read, Open | as moderator |
| 4 | Answers waiting | appeal chats whose last visible message is from the player | `GET /inbox` `needsAnswer` | read | read, Open | as moderator |
| 5 | Open reports | reports with status OPEN | `GET /inbox` `newReports` | read | read, Open | as moderator |
| 6 | Changed by others today | cases and appeals whose last change came from another staff member | needs the follow-up after `add-staff-panel-read-endpoints` (not one of the fifteen phases) | read | read | as moderator |

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

## Decision 3: unique, stable, shareable URLs for every entity and view state

**Scope (maintainer decision, 2026-11-02).** Every entity and every view of the panel gets a URL, not only
cases, appeals, reports and players. The full inventory, the canonical URL of each entity and the old-to-new
table are in decision 7. The rules are requirements in `specs/staff-deep-links`.

**Rules in short:**

- **One canonical URL per entity and per view state.** Opening it shows the same view for any staff member with access.
- **Reload, Back and Forward restore the exact view**: tab, filters, sort, page, open item and open dialog.
- **Dialogs, forms and confirmations are routes.** A create, edit or action state is a child route (`/new`,
  `/edit`, `/revoke`, `/lift`). The parent page stays rendered and the dialog renders over it while its route is
  active. Back closes it, a reload reopens it, and the link can be shared.
- **Tabs and sub-tabs**: a tab that is only a view is `?tab=`. A tab that belongs to one item is a path segment.
- **Filters, sort, page and cursor are query parameters.** Nothing about a list lives in component state alone.
- **The open item of a list-detail is in the path** (`/cases/:reference`), the list state in the query.
- **Items inside a page** (a note, a message, a protocol, a timeline event, a share) are addressed by a hash:
  `#event-<id>`. The hash is not sent to the server, so it stays out of access logs. The item is highlighted
  when the page opens and stays highlighted until the reader clicks elsewhere or navigates. No timer.
- **Copy link** is on every entity header. It copies the absolute URL of the current view, including query and hash.
- **Unknown ids** show the panel's not-found view with a link back to the list. A child route with a missing item
  never opens an empty dialog.
- **Old URLs redirect** and keep their query string. No existing panel URL returns 404.
- **URL-only state.** Filters, sort and page are never written to local storage, session storage or a server
  preference. The URL is the only memory (see the open question on remembered filters).

**Players keep the owner hash (alternatives rejected).** A name in the path (`/players/by-name/Steve`) would be
written to access logs, browser history and referrers, and nothing needs it there. A new short owner id would be
one more key to store, keep unique and erase, for a title the header can show anyway. The owner hash is stable,
already in use, and the header shows the last known name from the name history. The name lookup stays in the
search box and calls `GET /player/resolve/{name}`.

**Identifiers that may appear in a URL.** Only identifiers that staff already see in the panel:

- short ids: case reference, punishment short id;
- UUIDs already shown to staff: appeal, report, evidence, audit, kick, rollback, warning, IP ban, area ban
  identifiers, and note, message, protocol and share ids;
- the owner hash of a player (as today);
- the staff member's oid (already shown as assignee and author);
- a section key, a client rule key, a template id, an intel classification and an appeal-terms version number.

**Never in a URL, a query, a hash, a log or an event** (GDPR principle 1, and the Ban ID rule of the maintainer):
a Ban ID, a player's raw UUID (including a Minecraft UUID, which the staff-link lookup takes as a path value
today; the panel does not use that route in a URL), a player name, an IP address, a Discord user id, a feed token,
a session, or any other secret. The Ban ID is revealed as an action on the case, never as a route.

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
| Appeal | conversation (`caseTimeline`), decision text | the decision and its time as the last item; needs the appeal read (phase 8) |
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
- **Appeals** use the same layout once `/appeals/:id` exists (phase 8). Until then the appeals list keeps its
  table and its decision dialog.

**Alternatives rejected.**

- *A table with a modal for the detail.* Rejected: the audit found the modal hides the context and cannot be
  linked.
- *A detail in a drawer on the right.* Rejected: the M3 guidance does not recommend a drawer for this.

## Decision 6: phases

Each phase is one code PR, `feat(web)`, usable on its own, and small enough to review in one sitting. The
order keeps the panel working after each one. Phase 1 is the foundation that every later phase uses: routes
for dialogs, copy link and the not-found view. Each area after it is addressed in its own phase, so an
area's URLs ship with its screens and no URL is added without a page behind it.

| # | PR title | Needs | Covers (rows of 7.1) |
|---|---|---|---|
| 1 | `feat(web): open dialogs from the route and add copy link` | nothing | the dialog pattern, the copy-link button, not-found for unknown ids (all rows use it) |
| 2 | `feat(web): turn the inbox into a work queue` | nothing (`/inbox`, `/case`, `/auth/me`) | 1 |
| 3 | `feat(web): group the navigation by job and add badges and shortcuts` | nothing | navigation for all rows |
| 4 | `feat(web): add the entity header with state, owner and breadcrumbs` | `add-case-workspace-tabs` merged | header for rows 2, 4, 7, 9, 13, 38 (shell) |
| 5 | `feat(web): open cases and reports in list and detail panes` | nothing | rows 2, 9 (list-detail with the open item in the path) |
| 6 | `feat(web): keep list filters and sort in the url and redirect old paths` | nothing | query state of every list (rows 1, 6, 8, 12, 16, 18, 24, 27, 29, 31, 35, 37, 41, 46, 48) and the redirects of 7.2; name lookup replaces the address |
| 7 | `feat(web): address case notes, messages, protocols and evidence by url` | nothing | rows 2 to 5, `#event-<id>` highlight, child routes for note and message edits |
| 8 | `feat(web): add the appeal detail page at /appeals/:id` | companion `GET /appeal/{identifier}` | rows 6, 7 |
| 9 | `feat(web): resolve and dismiss reports on their own routes` | nothing | row 9 |
| 10 | `feat(web): address players, punishments and warnings by url` | nothing | rows 10 to 15 (punish, warn, ELO, kick and revoke as child routes) |
| 11 | `feat(web): address staff accounts and links by staff id` | companion `GET /admin/staff-actor/{oid}` | rows 37 to 40 |
| 12 | `feat(web): address rollback, kick, audit and import entries by url` | companion `GET /admin/audit/{auditId}` for the audit entry | rows 41 to 49 |
| 13 | `feat(web): address templates, sections, client rules and appeal terms by url` | optional companion for one terms version | rows 27 to 34 |
| 14 | `feat(web): address exemption reviews by url` | companion `GET /exemption/reviews/{id}` | rows 35, 36 |
| 15 | `feat(web): address ip bans, area bans, guard, vpn policy and allow list by url` | companions `GET /area-bans/{id}`, `GET /intel/allow-list/{owner}` | rows 16 to 25 |

Phases 8, 11, 12, 14 and 15 wait for the companion change. The other phases do not. If the companion merges
in parts, each part unblocks its phase. The "changed by others" inbox section (decision 1, section 6) is a web
PR after phase 2 and the `GET /inbox` fields of the companion change. It is not one of the fifteen phases.

The keyboard list movement (`j`, `k`, `Enter`, `Esc`) is part of phase 5, not a phase of its own.

Phase 1 is the only place where the dialog pattern is defined. Phases 7 to 15 use it and do not invent their own
open state for dialogs.

The companion change `add-staff-panel-read-endpoints` (`feat(api)`) is proposed separately and merged in parts,
before the phases that need it (table in 7.3). It must not be merged into any web PR.

## Decision 7: every entity and view has a URL

Inventory from `web/src/router/index.ts`, every list view, every dialog, drawer and sheet that shows or edits one
thing, every tab and every admin tab. "Read by id today" says whether the backend has a read of one item without
the list. "Companion" names the read endpoints that `add-staff-panel-read-endpoints` must add (listed below the
tables). Dialogs that open from a route take the child-route forms of decision 3.

### 7.1 Inventory and canonical URLs

| # | Entity or view | Today | Canonical URL | Create, edit and action routes | Tabs and query | Read by id today |
|---|---|---|---|---|---|---|
| 1 | Inbox | `/` | `/` | none | `?view=mine\|unassigned\|appeals\|answers\|reports` | n/a |
| 2 | Case | `/cases/:reference` | `/cases/:ref` | close and reopen are actions with undo, no route; notes `/cases/:ref/notes/new`, `/cases/:ref/notes/:noteId/edit`, `/cases/:ref/notes/:noteId/redact`; messages `/cases/:ref/messages/:messageId/edit`, `/cases/:ref/messages/:messageId/redact`; participant erasure `/cases/:ref/participants/:participantId/erase` | `?tab=overview\|conversation\|notes-audit`; list state `?status=` etc. | yes, `GET /case/{ref}` |
| 3 | Case timeline event, note, message, share (items of a case) | in the case page | `/cases/:ref?tab=notes-audit#event-<id>` (conversation items use `?tab=conversation#event-<id>`) | as in row 2 | the hash | yes (timeline, notes) |
| 4 | Case protocol | in the case page (`CaseProtocolApi`) | `/cases/:ref/protocols/:evidenceId` | download is an action | none | yes, `GET /case/{ref}/protocol/{evidenceId}` |
| 5 | Evidence item (chat attachment, link, file) | `EvidencePanel`, `EvidenceItem`, `EvidenceDialog` | `/evidence/:id` (item page with its case and punishment links) | add `/cases/:ref/evidence/new`, share `/evidence/:id/share`, delete `/evidence/:id/delete` | none | yes, `GET /evidence/{identifier}` |
| 6 | Appeal list | `/appeals` (table, decision dialog) | `/appeals?status=pending\|all` | none | `status`, `q` | list only |
| 7 | Appeal detail (new) | none | `/appeals/:id?tab=conversation\|decision\|case` | decide `/appeals/:id/decide` | `tab` | **no**, companion |
| 8 | Report list | `/reports` | `/reports?status=open\|resolved\|all&player=<owner>` | none | `status`, `player` | list only |
| 9 | Report detail | `/reports/:id` | `/reports/:id` | resolve `/reports/:id/resolve`, dismiss `/reports/:id/dismiss` | none | yes, `GET /report/{identifier}` |
| 10 | Player lookup | `/players` | `/players` | none (name is typed, never in the URL) | none | by name only, `GET /player/resolve/{name}` |
| 11 | Player | `/players/:owner` | `/players/:owner?tab=overview\|punishments\|reports\|cases\|evasion\|elo\|names\|intel\|warnings\|evidence` | punish `/players/:owner/punish`, warn `/players/:owner/warn`, ELO adjust `/players/:owner/elo/adjust`, kick `/players/:owner/kick`, exemption `/players/:owner/exemption` | `tab` | yes, `GET /elo/{owner}`, `GET /evasion/{owner}/sightings`, `GET /exemption/{owner}` |
| 12 | Active punishments list | `/punished` | `/punished?kind=&state=` | none | `kind`, `state` | list only |
| 13 | Punishment | none (shown in player and case) | `/punished/:shortId?tab=details\|evidence\|history` | revoke `/punished/:shortId/revoke` | `tab` | yes, `GET /punishment/short/{shortId}` |
| 14 | Warning | `WarnDialog` in player | `/warnings/:id` | none | none | yes, `GET /warn/{id}` |
| 15 | Ban ID reveal | `BanIdPanel` on the case | **none**: an action on the case, never a URL | the reveal and leak and reissue actions stay on the case | none | action only |
| 16 | IP bans list | `/ip-bans` | `/ip-bans?status=active\|lifted` | new `/ip-bans/new` | `status` | list only |
| 17 | IP ban detail | none (`IpBanLiftDialog`) | `/ip-bans/:id` | lift `/ip-bans/:id/lift` | none | yes, `GET /ipban/{id}` |
| 18 | Area bans list | `/area-bans` | `/area-bans?status=active\|lifted` | new `/area-bans/new` | `status` | list only |
| 19 | Area ban detail | none (`AreaBanLiftDialog`) | `/area-bans/:id` | lift `/area-bans/:id/lift` | none | **no**, companion |
| 20 | Join guard state | `/guard` | `/guard` | level `/guard/level` (action) | none | yes, `GET /guard/state` |
| 21 | Join guard settings | on `/guard` | `/guard/settings` | edit `/guard/settings/edit` | none | yes, `GET /guard/settings` |
| 22 | VPN policy | `/intel/policy` | `/intel/policy` (list of classes) | edit `/intel/policy/:classification/edit` | none | one small list, no companion |
| 23 | VPN policy entry | none | `/intel/policy/:classification` | as row 22 | none | from the list |
| 24 | Allow list | `/intel/allow-list` | `/intel/allow-list?page=` | new `/intel/allow-list/new`, remove `/intel/allow-list/:owner/remove` | `page` | list only |
| 25 | Allow-list entry | none | `/intel/allow-list/:owner` | as row 24 | none | **no**, companion |
| 26 | Player intel hits | in the player | `/players/:owner?tab=intel` | none | `tab` | yes, `GET /intel/players/{owner}/hits` |
| 27 | Templates (admin) | `/admin/templates` | `/admin/templates?q=` | new `/admin/templates/new`, edit `/admin/templates/:id/edit` (delete there) | `q` | yes, `GET /template/{identifier}` |
| 28 | Template detail | none | `/admin/templates/:id` | as row 27 | none | yes |
| 29 | Sections (admin) | `/admin/sections` | `/admin/sections?q=` | new `/admin/sections/new`, edit `/admin/sections/:key/edit`, disable `/admin/sections/:key/disable` | `q` | yes, `GET /section/{key}` |
| 30 | Section detail | none | `/admin/sections/:key` | as row 29 | none | yes |
| 31 | Client rules (admin) | `/admin/client-rules` | `/admin/client-rules?q=` | new `/admin/client-rules/new`, edit `/admin/client-rules/:key/edit` | `q` | yes, `GET /client-rule/{key}` |
| 32 | Client rule detail | none | `/admin/client-rules/:key?tab=details\|hits` | as row 31 | `tab` | yes; hits `GET /client-rule/{key}/hits` |
| 33 | Appeal terms (current) | `/admin/appeal-terms` | `/admin/appeal-terms` | new version `/admin/appeal-terms/new` | none | yes, `GET /appeal-conditions/` |
| 34 | Appeal terms version | none (version list) | `/admin/appeal-terms/versions/:version` | as row 33 | none | from the versions list (one small request), companion optional |
| 35 | Exemption reviews (admin) | `/admin/exemption-reviews` | `/admin/exemption-reviews?status=open\|decided` | none | `status` | list only |
| 36 | Exemption review detail | `ExemptionDecisionDialog` | `/admin/exemption-reviews/:id` | decide `/admin/exemption-reviews/:id/decide` | none | **no**, companion |
| 37 | Staff accounts (admin) | `/admin/staff` | `/admin/staff?q=` | none | `q` | list only |
| 38 | Staff member | none | `/admin/staff/:oid?tab=profile\|links\|activity` | anonymize `/admin/staff/:oid/anonymize`, unlink links as actions | `tab` | **no**, companion |
| 39 | Minecraft and Discord links of a staff member | `StaffLinksPanel`, `DiscordLinksPanel` | `/admin/staff/:oid?tab=links` (no link has its own URL, no Minecraft UUID or Discord id appears) | unlink as an action | `tab` | via row 38 |
| 40 | My profile and my links | account menu, `AccountLinkDialog` | `/profile`, `/profile/link/minecraft`, `/profile/link/discord` | link forms are child routes | none | yes, `GET /staff-profile/me`, `GET /staff-link/me`, `GET /discord/link/me` |
| 41 | Rollback jobs (admin) | `/admin/rollback` | `/admin/rollback?status=` | new (preview) `/admin/rollback/new`, cancel `/admin/rollback/:id/cancel` | `status` | yes, `GET /admin/rollback/{id}` |
| 42 | Rollback job detail | in the rollback panel | `/admin/rollback/:id` | as row 41 | none | yes |
| 43 | Imports (admin) | `/admin/imports` (vanilla import, punishment groups) | `/admin/imports?tab=vanilla\|punishments&group=<key>` | vanilla import form `/admin/imports/vanilla/new` | `tab`, `group` | groups are one read, no id needed |
| 44 | CloudNet (admin) | `/admin/cloudnet` | `/admin/cloudnet?tab=catalogue\|proxies` | none | `tab` | catalogue is one read |
| 45 | CloudNet entry | in the catalogue | `/admin/cloudnet/:kind/:name` (kinds as the catalogue names them, confirmed in phase 12) | none | none | from the catalogue read |
| 46 | Kick (admin) | `/admin/kick` | `/admin/kick?status=` | new `/admin/kick/new`, acknowledge `/admin/kick/:id/ack` | `status` | list only |
| 47 | Kick detail | `KickDialog` result | `/admin/kick/:id` | as row 46 | none | yes, `GET /kick/{id}` |
| 48 | Audit log (admin) | `/admin/audit` | `/admin/audit?actor=<oid>&action=&surface=&cursor=` | none | the filters and cursor | list only |
| 49 | Audit entry | expandable row | `/admin/audit/:auditId` | none | none | **no**, companion |
| 50 | Global search | top bar dropdown | not a page: every hit is a link to its canonical URL | none | none | n/a |
| 51 | Sign-in, no access, not found, leave | `/auth/*`, `/no-access`, `/leave`, catch-all | unchanged | none | `reason`, the leave target in the hash | n/a |

### 7.2 Old URL to new URL (every route in `web/src/router/index.ts`)

"Kept" means the URL does not change. "Query" means a query parameter is added or renamed. "Redirect" means the
old URL redirects and keeps its query. "New" means the URL did not exist.

| Old URL | New URL | Kind |
|---|---|---|
| `/` | `/` | kept, adds `?view=` |
| `/punished` | `/punished` | kept, adds `?kind=`, `?state=`; detail `/punished/:shortId` new |
| `/ip-bans` | `/ip-bans` | kept, adds `?status=`; `/ip-bans/:id` new |
| `/players` | `/players` | kept; name lookup replaces the address with the owner URL |
| `/players/:owner` | `/players/:owner` | kept, adds `?tab=` and child routes for actions |
| `/intel/policy` | `/intel/policy` | kept; `/intel/policy/:classification` new |
| `/intel/allow-list` | `/intel/allow-list` | kept, adds `?page=`; `/intel/allow-list/:owner` new |
| `/reports` | `/reports` | kept, adds `?status=`, `?player=` |
| `/reports/:id` | `/reports/:id` | kept, adds `/resolve`, `/dismiss` child routes |
| `/appeals` | `/appeals` | kept, adds `?status=` |
| `/appeals?show=all` | `/appeals?status=all` | query renamed, redirect |
| `/appeals/:id` | `/appeals/:id` | new (detail page) |
| `/cases` | `/cases` | kept, adds `?status=`, `?assignee=`, `?priority=`, `?section=`, `?tag=`, `?owner=` |
| `/cases/:reference` | `/cases/:ref` | kept, adds `?tab=`, child routes, `#event-<id>` |
| `/guard` | `/guard` | kept; `/guard/settings` new |
| `/area-bans` | `/area-bans` | kept, adds `?status=`; `/area-bans/:id` new |
| `/admin` | `/admin/templates` | redirect (from `redesign-ux`) |
| `/admin/templates` | `/admin/templates` | kept; `/admin/templates/:id` and create and edit routes new |
| `/admin/sections` | `/admin/sections` | kept; `/admin/sections/:key` and child routes new |
| `/admin/client-rules` | `/admin/client-rules` | kept; `/admin/client-rules/:key` new, `?tab=` |
| `/admin/appeal-terms` | `/admin/appeal-terms` | kept; `/versions/:version` and `/new` new |
| `/admin/exemption-reviews` | `/admin/exemption-reviews` | kept, adds `?status=`; `/:id` new |
| `/admin/staff` | `/admin/staff` | kept, adds `?q=`; `/admin/staff/:oid` new |
| `/admin/rollback` | `/admin/rollback` | kept; `/admin/rollback/:id` and `/new` new |
| `/admin/imports` | `/admin/imports` | kept, adds `?tab=`, `?group=`; `/vanilla/new` new |
| `/admin/cloudnet` | `/admin/cloudnet` | kept, adds `?tab=`; `/:kind/:name` new |
| `/admin/kick` | `/admin/kick` | kept, adds `?status=`; `/:id` and `/new` new |
| `/admin/audit` | `/admin/audit` | kept, adds filters and cursor; `/:auditId` new |
| `/templates` | `/admin/templates` | redirect, query kept |
| `/sections` | `/admin/sections` | redirect, query kept |
| `/client-rules` | `/admin/client-rules` | redirect, query kept |
| `/appeal-conditions` | `/admin/appeal-terms` | redirect, query kept |
| `/no-access` | `/no-access` | kept |
| `/auth/callback`, `/auth/error` | same | kept |
| `/leave` | `/leave` | kept |
| `/:pathMatch(.*)*` | same, not-found view | kept; a removed or mistyped path shows the not-found view with a link back, never a blank page |
| (no route today) `/cases/:ref/timeline` | `/cases/:ref?tab=notes-audit` | redirect, for a link that used the old timeline name |

### 7.3 Companion read endpoints (`add-staff-panel-read-endpoints`, `feat(api)`)

A deep link must load one item without loading its list. These reads do not exist today and are needed:

| Endpoint | Returns | Needed by row | Phase |
|---|---|---|---|
| `GET /appeal/{identifier}` | one `AppealDTO` (identifier, punishment, appellant hash, statement, status, decision, times) | 7 | 8 |
| `GET /exemption/reviews/{id}` | one exemption review | 36 | 14 |
| `GET /area-bans/{id}` | one area ban (the list endpoint exists) | 19 | 15 |
| `GET /intel/allow-list/{owner}` | one allow-list entry by owner hash | 25 | 15 |
| `GET /admin/audit/{auditId}` | one audit entry, same fields as the list item | 49 | 12 |
| `GET /admin/staff-actor/{oid}` | one staff actor with its roles and link status (no raw Minecraft UUID in the response beyond what the staff-link response already carries) | 38, 39 | 11 |
| `GET /appeal-conditions/versions/{version}` | one terms version (optional: the versions list is small) | 34 | 13 |

Not needed (existing read by id): templates, sections, client rules, punishment by short id, warnings, IP bans,
kicks, rollback jobs, reports, cases, evidence, case protocols, guard settings, ELO and evasion by owner, exemption
status by owner, Discord tickets, staff profile and staff link of the caller.

Existing endpoints that take a secret or a raw identifier in the path, and therefore must never be the target of a
panel URL: `GET /staff-link/by-minecraft/{uuid}` (raw Minecraft UUID), `GET /punishment/{reference}/ban-id` (Ban ID reveal),
and the feed route with its token. The panel calls these as actions or not at all.

## Decision 8: security requirements for the URLs

Each rule has a requirement with scenarios in `specs/staff-panel-security`. The standard is named per rule.

1. **Redirects are same-origin and allow-listed.** Standard: CWE-601 (open redirect). The post-login return path
   uses `safeReturnTo` in `web/src/auth/authService.ts`, which only checks for a leading slash today. It is extended
   to resolve the target against the panel origin with `new URL`, to reject backslashes and control characters, and
   to accept only a route name from the router's allow-list. Old-URL redirects use the same check. Test: phase 1.
2. **The URL is never the authorization.** Standard: OWASP API Security Top 10 2023, API1 (BOLA) and API5 (BFLA);
   OWASP ASVS Level 2, V4 (access control). Every endpoint behind a deep link checks the role on each request
   (`@Secured` with the `Roles` of the endpoint). The route guard only decides what to show. Every read-by-id endpoint
   of the companion change has a 401 test (no token) and a 403 test (role below the minimum). Every phase has an
   authorization test for each new route.
3. **Short ids are lookup keys, not secrets, and stay in staff URLs.** Standard: OWASP ASVS Level 2, V2 and V3
   (authentication and session). A short id such as `#A1B2C3D4` has 32 bits, so it can be guessed. It therefore
   never grants access (rule 2 still applies) and appears only in staff URLs behind sign-in. Public and portal URLs
   carry no staff id. A signed-out visit starts sign-in before any staff data is requested.
4. **No action runs on navigation.** Standard: CWE-352 (state change without intent) and RFC 9110, section 13.1
   (conditional requests: `If-Match`, `412 Precondition Failed`). Child routes that change state (`/close`,
   `/resolve`, `/revoke`, `/lift`, `/anonymize`, `/ack`, `/disable`, `/cancel`, `/decide`) render a confirmation and
   send nothing on mount. The confirmed request carries the version read with the entity: `expectedVersion` in the
   body (the case update already does this, and answers 409 on a stale version), or `If-Match` where the endpoint
   supports an ETag. **Known gap:** endpoints without any version field (for example some admin toggles) are
   confirmed by the dialog only; each such endpoint is listed in the companion change so that a version can be
   added.
5. **No secret in an address.** Standard: CWE-598 (sensitive information in the query string) and GDPR principle 1
   (`openspec/config.yaml`). The route table has no parameter named for a secret, Ban ID, name, raw UUID, IP, Discord
   id or token; a test fails when one is added (phase 1, and each later phase). The hash fragment is not sent to the
   server. `Referrer-Policy: no-referrer` is already set by `web/server/lib/headers.ts` and tested in
   `web/server/__tests__/handlers.test.ts`; the test is extended to every route the router defines (ASVS Level 2, V14).
   Note: the existing `GET /staff-link/by-minecraft/{uuid}` takes a raw Minecraft UUID in its path. No panel route
   calls it from an address.
6. **ASVS Level 2 review per phase.** Standard: OWASP ASVS Level 2 (V2, V3, V4, V5, V7, V14). Each phase pull request
   states the items it touches. The review is one line in `tasks.md` for that phase and is checked before merge.

## Risks and trade-offs

- **The "me" section depends on the verified oid.** If `/auth/me` returns no oid, section 1 says so (decision 1).
- **Undo can race with another moderator.** The version check stops it; the snackbar then says "The case changed
  since. Reload to see it."
- **Shortcuts collide with browser or screen-reader keys.** Only chords that start with `g` are used, and they
  are off in text fields and dialogs.
- **Names in the header.** The player's name is shown in the header, not in the URL. Names are personal data
  already stored under principle 1; their display stays in the panel for staff only.
- **More PRs.** Fifteen PRs take longer than one. Each one is small, leaves the panel usable, and
  ships its URLs together with its screens.

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
