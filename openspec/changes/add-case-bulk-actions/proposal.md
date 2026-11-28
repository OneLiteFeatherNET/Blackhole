# Proposal

**Ships as:** `feat(case)`. One code PR, titled `feat(case): edit cases in bulk and group them by player`.
The proposal PR is `docs(openspec): propose add-case-bulk-actions`. No other type is in this change.

## Why

The case list (`/cases`) shows one row per punishment case and lets a moderator filter it by status,
"mine" and tag. Two things are missing for the day-to-day work on a busy network:

- **A player has many cases.** A player who was banned, appealed and was banned again has several
  rows with no visible connection, and the list does not say whose case a row is. The list shows no
  player at all, only the case short id and the section. A moderator who wants to look at everything
  about one player has to open each case, or go to the player page.
- **Routine changes are one case at a time.** Closing ten cases of one player after an appeal round,
  or assigning a set of cases to a colleague before a shift, means ten header edits with ten
  conflict checks. Every edit is correct on its own, but the work is tedious and easy to abandon
  half-way without knowing it.

This change adds a **Player** column to the case list, a **group by player** view that shows each
player's open and total cases with the cases expandable underneath, and **bulk edit**: select cases
(or every case of one player group) and set status, assignee or priority, or add and remove tags.
A bulk edit applies the same rules to each case as the single edit does, reports the outcome per
case, and writes the same kind of timeline event and audit entry per case.

## What Changes

- **Player column** in the case list: the player's display name, taken from the player name history
  (the newest name of the owner). When no name is known (never logged in again, or the name was
  removed by retention or erasure) the column shows a short owner hash, as the other lists do. The
  raw player UUID is never returned. The name is resolved on the server in the same list query
  (one statement per page, no per-row lookup).
- **Group by player** toggle on the Cases page. The grouping is server-side (`GET /case/groups`):
  one row per owner with the count of open cases (status not `CLOSED`), the total count and the
  last activity. Paging is over players, so one player's cases never split across pages. Expanding
  a group loads that player's cases (`GET /case?owner=`), paged within the group.
- **Filters apply within groups**: the status, "mine", tag, priority, section and punishment-state
  filters narrow both the groups and the cases in them. Counts are for the cases that match the filters.
- **The chosen view is remembered per viewer** in the browser (`localStorage`, one key with the
  value `cases` or `player`). It holds no name and no owner hash.
- **Bulk edit** with **`POST /case/bulk`**: a body with at most **100 case identifiers**, each with
  its `expectedVersion`, and one shared change (status, assignee or clear, priority, tags to add,
  tags to remove). The route is `MODERATOR` only. Per case, it applies exactly the rules of the
  single update (version guard, same tag and assignee validation, no event and no audit when nothing
  changes). The answer lists one result per case; a conflict or a failure of one case never hides the
  results of the others.
- **One timeline event and one audit entry per changed case.** The timeline gets one new event
  `case.bulk_edited` that names the batch and lists the changed fields with old and new values. The
  audit log gets one entry per changed case with the new action `case.bulk_update`. Domain events
  keep the existing per-field `case.*` types, so consumers do not change.
- **Not atomic across cases, atomic within a case.** Each case is written by one statement (state and
  event together), as the single update already does. A batch is not one database transaction. The
  reasons are in `design.md` (decision 3).

Not breaking: all routes and fields are additive. `CaseListItem` gains `playerName`, `ownerShort`
and `version`; the existing fields are unchanged. The single update, its routes and its Discord
delegation are unchanged. The Velocity plugin is unchanged.

## Out of scope

- **Lifting, revoking, extending or otherwise changing punishments in bulk.** A punishment keeps its
  own flow, its own audit and its own appeal rules. A bulk edit touches only the case row and its
  timeline.
- **Discord and Velocity surfaces.** The bulk route takes no `onBehalfOf`, is not granted to the
  `DISCORD` role and is not called by the bot or the proxy. The Discord bot keeps the single update.
- **Cross-page selection** in the web UI: a selection lives on one page of the list (see open questions).
- **Bulk notes**, bulk evidence and bulk appeal decisions.
- **Player-facing views.** The Player column and the groups are staff-only.
- **Changing the case list population**: the list still shows cases that have a case row, as it does
  today. Punishments without a case row are not added (see open questions).
- **Renaming or re-deriving player names**: the name comes from the existing name history and its
  retention; this change does not add a name source.
- **Retention changes.** The name history keeps its retention; this change adds no new store of
  personal data.

## Capabilities

### New Capabilities

- `case-player-grouping`: the Player column, the server-side grouping by owner with open and total
  counts, the expansion of a group, and the per-viewer view memory in the Cases page.
- `case-bulk-edit`: the bulk route, its limit, the per-case rules and results, the per-case timeline
  event and audit entry, and the selection and bulk panel in the Cases page.

### Modified Capabilities

None. Both capabilities are new. They build on the `punishment-case` capability of
`add-punishment-case-timeline`, which is not yet archived. Their deltas reference that change's
rules by name only and do not edit its spec.

## Impact

- **Backend** (`casetimeline/`): `CaseReader` and `JdbcCaseReader` gain the owner filter, the name
  join, the group query and a batch read of case rows. `CaseDTO` gains the bulk request and result
  records and the group page. A new `CaseBulkService` holds the bulk rules and reuses the single
  update's validation (extracted, not copied). `CaseWriter` gets one statement for a bulk change.
  `CaseEventPayload` gets `case.bulk_edited`. `AuditAction` gets `CASE_BULK_UPDATE`. `CaseController`
  and `CaseApi` get the new routes.
- **Migrations**: none. The case event type and payload are text and JSON, the audit action is text
  with no check constraint (`V29__staff_audit.sql`), and the new query uses the existing indexes on
  `punishments` and `case_event`. If implementation shows a missing index, a new migration is
  appended as the next free version (V41 is the newest today; V42 when this is implemented, if
  needed).
- **OpenAPI client**: a new spec file `client/specs/blackhole-api-0.0.N.yml` where N is one more than
  the newest file in `client/specs` at implementation time (`0.0.65` today, so `0.0.66`). The
  published `0.0.65` file is never edited. `inputSpec` in `client/build.gradle.kts` is bumped to the
  new file.
- **Web** (`web/src/views/CasesView.vue` and `components/case/`): the Player column, the view toggle,
  the group rows with expansion, row selection, the bulk panel, the per-case result list. German and
  English strings. The view memory is a single `localStorage` value.
- **Depends on**: the merged case timeline (`casetimeline/`, routes `/case`), the name history
  (`intel/names`, `player_name_history`) and the staff audit log. No dependency on the portal or the
  Discord change.
