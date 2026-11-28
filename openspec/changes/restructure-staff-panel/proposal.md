# Proposal

**Ships as:** `feat(web)`. Every code PR of this change is `feat(web)`; the titles are in `tasks.md`.
The proposal PR is `docs(openspec): propose restructure-staff-panel`.

The one backend need (a single appeal read and a few inbox fields) is a different type and scope, so it is
not part of this change. It is proposed separately as `add-staff-panel-read-endpoints` (`feat(api)`). The web
phases that need it wait for that change; the others do not.

## Why

A moderator opens the panel to do one job: answer an appeal, close a case, resolve a report. Today the panel
makes that a hunt:

- **The inbox is a list of numbers.** It shows four sections with counts and bare identifiers. Nothing says
  which item is urgent, which one is already mine, or which one someone else is working on. Rows have no
  affordance, so they look like labels.
- **Reports and appeals are second-class.** A report cannot be resolved from its page. An appeal has no
  page of its own: it is a row in a list with a dialog, so it has no address to send to a colleague.
- **Pages are not addressable.** A player's page is titled with a hash. Tabs and filters live in component
  state, so a link to "the open conversation of this case" does not exist.
- **The panel does not say where things are.** Six admin items share one icon. Disabled buttons do not say
  why they are disabled. Closing a case is one click with no confirmation and no way back. The search keeps
  its suggestions when the page changes.
- **The moderator clicks through everything.** Going from a case list to a case, then to its player, then to
  the appeal of that player is four page loads and three trips back.

The maintainer's request (translated): the panel lacks structure; a moderator should not click themselves to
death but reach what they need at once; every case, appeal and similar entity needs a unique URL; and it
must be clear what state something is in and where something happens.

## What Changes

- **Inbox as a work queue.** The inbox shows, in this order: assigned to me, unassigned and urgent, appeals
  waiting for a decision, appeal chats waiting for a staff answer, and open reports. A later web PR adds
  "changed by others" once the backend fields exist. Each section has a count
  and a link to the full filtered list. Every row is a link to the entity's own URL. Roles decide what a row
  offers: a viewer reads, a moderator acts. (Decision 1 in `design.md`.)
- **A navigation bar on phones, a rail from medium width, no drawer.** Destinations are grouped by job:
  Today, Work (cases, appeals, reports), Players, Protection, and Administration (admin only, behind the
  workspace switcher kept from `redesign-ux`). Each destination has its own icon, badges show open counts,
  and the top destinations have keyboard shortcuts. The global search is scoped by type and loses its
  suggestions on navigation. (Decision 2.)
- **Unique, stable URLs for every entity and every view, not only the four main ones.** Maintainer decision
  (2026-11-02): every case, appeal, report, player, punishment, warning, IP and area ban, allow-list entry,
  template, section, client rule, appeal term version, exemption review, staff member, rollback job, kick,
  audit entry and evidence item has its own address. Create, edit and action states are routes, so Back closes
  a dialog and a link opens the same state. Tabs, filters, sort and page are in the address. Items inside a page
  are reached by `#event-<id>`. Old addresses redirect. No Ban ID, raw UUID, name, IP or secret is ever in an
  address. (Decisions 3 and 7; the full inventory and URL table are in `design.md`.)
- **One entity header, one timeline, cross-links.** Case, appeal, report and player pages share a header that
  shows the type, a short id, a state pill, the player, the assignee, the primary action and the breadcrumb.
  Each page shows what happened on it, and links to the case, appeal, report, player and punishment that
  belong to it. Closing and reopening a case can be undone for ten seconds, and reopening returns the case to
  the status it had before it was closed. (Decision 4.)
- **List-detail on expanded widths.** Cases, appeals and reports keep the list on the left and open the item
  on the right. The URL names the open item, so Back, reload and sharing work. Compact widths show one pane.
  (Decision 5.)
- **Phased delivery.** Fifteen small code PRs, each usable on its own and each shipping its URLs with its
  screens, in the order of `tasks.md`. (Decision 6.)

## Out of scope

- **The backend read endpoints.** Seven reads that load one item without its list (appeal, exemption review,
  area ban, allow-list entry, audit entry, staff member, one terms version) and the inbox fields (assignee,
  priority, status, kind, last actor) are the separate change `add-staff-panel-read-endpoints` (`feat(api)`),
  listed in `design.md` 7.3. The phases that need them wait; the others use existing reads.
- **Bulk actions on cases.** They are `add-case-bulk-actions`. This change only uses its docked toolbar pattern
  as a reference.
- **The case page tabs and messenger.** They are `add-case-workspace-tabs`. This change reuses that tab
  query (`?tab=`) and does not redo the layout.
- **The player portal and Discord.** Nothing changes there.
- **A new design system or new colours.** The existing tokens and `PageShell` / `FilterToolbar` / `DataTable`
  components from `redesign-ux` are used.
- **Remembering filters across visits.** Filters live in the URL only. No `localStorage` or server-side
  preferences are added for them. See the open question in `design.md`.
- **Per-user notifications** and a feed of other staff's changes beyond the inbox section. The timeline of each
  entity is the only history shown.

## Capabilities

### New Capabilities

- `staff-navigation`: destinations grouped by job, the navigation bar and rail per window size, badges,
  icons, workspace switcher, keyboard shortcuts and the scope of the global search.
- `staff-inbox`: the work-queue sections, their content per role, counts, empty states and row actions.
- `staff-deep-links`: the URL of every entity and view state, the query parameters, the redirects of old
  URLs and the player lookup that keeps names out of the URL.
- `staff-entity-header`: the header of case, appeal, report and player pages, the breadcrumb, the activity
  shown per entity, cross-links, and confirmation and undo for state changes.
- `staff-list-detail`: the list-detail layout for cases, appeals and reports, its URL, its keyboard
  movement and its behaviour per window size.
- `staff-panel-security`: redirect allow-list, authorization per request, short ids behind sign-in,
  no action on navigation, no secret in an address, referrer policy and the ASVS Level 2 review (design decision 8).

### Modified Capabilities

None. The `redesign-ux` change is not edited. This change supersedes parts of its navigation and inbox
decisions as listed in `design.md` ("What this supersedes").

## Impact

- **Web only**: `web/src/router/index.ts` (about 50 routes after this change), `web/src/nav/` (groups, icons, badges), `web/src/App.vue` (shell),
  `web/src/views/` (inbox, appeals, reports, case, player), new components for the header, the list-detail
  layout, the appeal detail page and the shortcut handler, German and English strings, and tests.
- **Backend, API, migrations, generated client, Velocity plugin**: none in this change.
- **Depends on**:
  - `add-case-workspace-tabs` for the case tab query.
  - `add-staff-panel-read-endpoints` for phases 8, 11, 12, 14 and 15 (listed in `design.md` 7.3), and for the
    "changed by others" inbox section, which is a follow-up after the fifteen phases.

## Breaking

None. No route is removed. Every changed URL redirects with its query. The generated client spec and the
Velocity plugin are not changed.
