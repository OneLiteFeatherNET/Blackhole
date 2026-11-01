# Design

This design sits on the merged case timeline (`backend/.../casetimeline/`): `CaseService.update` is the
single edit, `JdbcCaseWriter.update` writes its state and its per-field events in one statement, and
`JdbcCaseReader.list` is the case list. Read `.claude/rules/case-timeline.md` first.

## Context

- The case list pages over case rows, joins `staff_actor` for the assignee name and counts with
  `count(*) OVER ()`. It has no player, because a case row does not hold one. The player is reached
  through `punishments.owner`, a hash of the player, and the name is in `player_name_history`
  (`owner_hash`, newest `last_seen` wins, rows deleted by `NameHistorySweeper` after
  `blackhole.intel.name-retention-days`, and by erasure).
- The web keeps names only in memory (`stores/lookup.ts`), on purpose. The Player column therefore
  cannot be built in the browser from the hash; the server must return the name.
- The single update checks a version, validates tags and assignee, appends one event per changed
  field in a CTE and records one audit entry. `@Transactional` is unusable in this codebase, so none
  of this relies on it.

## Decisions

### 1. Player column: name from the name history, short hash as the fallback

The list query adds one lateral join per row to `player_name_history`:
`SELECT n.name FROM player_name_history n WHERE n.owner_hash = p.owner ORDER BY n.last_seen DESC,
n.name_lower LIMIT 1`. The primary key `(owner_hash, name_lower)` serves the lookup. The response
carries `playerName` (nullable) and `ownerShort` (the first 12 characters of the owner, the existing
`OWNER_PREFIX`). The raw UUID in `player_name_history.player_uuid` is never selected.

One statement per page. There is no per-row lookup, so the number of queries does not grow with the
page size. A test counts the statements a page issues and asserts one.

Why the name is in clear text here: principle 1 of `openspec/config.yaml` allows names where a feature
needs them, with a retention. This join uses the existing retention. A name that the sweeper or an
erasure has removed reads as `null` and the column shows the short hash, which is the behaviour of
the other lists.

`owner_hash` is `char(128)` and `punishments.owner` is `varchar(255)`. The join compares them as
Postgres does for a blank-padded `char`. Implementation checks this with the Testcontainers test
over an owner with a 128-character hash.

### 2. Grouping is a server-side query, paged over players

`GET /case/groups` runs one statement, two levels deep so the name join happens after the grouping:

```
SELECT g.owner, g.total, g.open, g.last_activity, n.name, count(*) OVER ()
FROM (
  SELECT p.owner,
         count(*)                                    AS total,
         count(*) FILTER (WHERE c.status <> 'CLOSED') AS open,
         max(COALESCE(act.last_at, c.created_at))     AS last_activity
  FROM punishment_case c
  JOIN punishments p ON p.identifier = c.punishment_identifier
  LEFT JOIN staff_actor a ON a.oid = c.assignee_oid
  LEFT JOIN LATERAL (SELECT max(e.occurred_at) AS last_at FROM case_event e
                     WHERE e.punishment_identifier = c.punishment_identifier) act ON TRUE
  WHERE <the same filters as the list>
  GROUP BY p.owner
) g
LEFT JOIN LATERAL (SELECT n.name FROM player_name_history n WHERE n.owner_hash = g.owner
                   ORDER BY n.last_seen DESC, n.name_lower LIMIT 1) n ON TRUE
ORDER BY g.last_activity DESC, g.owner
LIMIT ? OFFSET ?
```

- **Filters apply within groups.** The `WHERE` clause is the list's clause (status, assignee, tag,
  priority, section, punishment state), built by the same code. A group exists only if it has a
  matching case, and its counts are over matching cases. With `status=CLOSED`, the open count is zero.
- **Open** means status is not `CLOSED` (`OPEN`, `IN_REVIEW`, `WAITING`).
- **Paging is over owners**, so a player's cases are never split across two pages, and a page of
  groups has a stable total. Offset paging is acceptable here: the table is the set of cases of one
  network (a few thousand rows at most), and the sort is stable by `owner`.
- **Expansion** is the list route with `owner=<hash>` and the same filters, so a group shows exactly
  the cases the group counted. The owner value is validated like the owner in the player routes
  (a hash of the expected length and alphabet). A `GET /case` without `owner` is unchanged.

Why not group in the browser: a page of cases would then hold some of a player's cases and the
counts would be wrong on every page. The server must count across the whole filtered set.

### 3. Bulk edit is not atomic across cases; each case is atomic

The batch is **not** one database transaction. The reasons:

- **Partial failure must not hide.** The request says which case was changed and which was not. If the
  batch were one transaction, a single stale version would undo ninety-nine correct changes, and the
  moderator would learn only "failed".
- **The single update is already atomic per case.** Its state change and its event are one CTE with
  the version guard in its `WHERE`. A bulk edit reuses that property per case, so each case is either
  fully changed with its event or not changed at all.
- **`@Transactional` does not work here** (the bean ambiguity in `.claude/rules` and the memory of
  three reverted attempts). An explicit JDBC transaction across many statements would work, but
  would hold row locks on up to 100 cases for the length of the batch, while the Blocking executor
  waits for the moderator's browser. It is not worth it for a result the user does not need as one
  unit.

What works instead:

1. The request is validated once, before any write (the change, the tags, the assignee, the list size,
   no duplicate identifier). A request-level error changes nothing.
2. The current rows of all listed cases are read in one query (`punishment_case` with
   `punishment_identifier = ANY(?)`), not one read per case.
3. Each case is then written in order by the same CTE shape as the single update, guarded by its own
   `expectedVersion`. A zero-row result is a conflict for that case only.
4. Each result is recorded. A failure of a case does not stop the loop; the answer lists every case.

Consequences: after a crash or a timeout the moderator may see some cases changed and some not. The
answer is the record. A retry of the same request is safe: a changed case now has a newer version, so
the retry answers `CONFLICT` with its `currentVersion` and writes nothing. No event is duplicated.

If an all-or-nothing batch is wanted later, it is a new option (`atomic: true`), built on one
connection with `setAutoCommit(false)` owned by a `@Singleton` writer, not on `@Transactional`. It is
not part of this change.

### 4. The limit is 100 case identifiers per request

`MAX_BULK = 100`, the same as `MAX_LIST_SIZE`, so the page the moderator sees can be selected in full.
The batch reads one query and writes up to 100 statements on the Blocking executor, which is a few
hundred milliseconds in the usual case and keeps the request inside the timeout of the admin UI. A
request with more than 100 identifiers, zero identifiers or a duplicate identifier is `400` and
changes nothing. A player group with more than 100 cases is handled by the web (decision 8).

### 5. The bulk change is validated by the single update's rules, extracted

The rules of the single update move into a `CaseChangeRules` class with no I/O: tag normalisation,
the assignee check (a known human staff member, through a read), the version check and the diff from
the current `CaseMeta`. `CaseService.update` calls it, and its behaviour does not change; its tests
stay as they are. `CaseBulkService` calls the same class per case. This is the "exactly the same
rules" requirement: the code is shared, not copied.

The bulk request differs in one place that is a request shape, not a rule. The single update
replaces the tag set. The bulk request has `addTags` and `removeTags`, because a bulk edit of a
player's cases should not wipe tags that differ per case. The result of both is validated by the
same tag rule, including the maximum of `CaseTags.MAX`, after the change is applied to each case.

### 6. Timeline: one `case.bulk_edited` event per changed case

A changed case gets exactly one timeline event, `case.bulk_edited`, payload version 1:

```json
{ "batchId": "<uuid>",
  "changes": [
    { "field": "STATUS",   "from": "OPEN", "to": "CLOSED" },
    { "field": "ASSIGNEE", "from": null,   "to": "<oid>" },
    { "field": "TAGS",     "from": ["a"],  "to": ["a", "b"] } ] }
```

Only changed fields are listed. Assignee values are oids (no names); tags are the sorted lists. The
event is written in the same CTE as the state change, so the state and its one event are atomic per
case, like the single update. The dedupe key is `bulk:<batchId>:<punishmentIdentifier>` to make a
replay of a stored batch a no-op in any future recovery path.

The timeline keeps one event per case for this action, as the task asks. The single update still
writes one event per field; a case shows both shapes when both kinds of edit were used. This is
accepted: the change of the case's history is what the timeline is for, and the `changes` list
carries every field.

The web timeline renders `case.bulk_edited` with its own entry (type label, the changed fields). The
type is text, so a reader that does not know it shows it as `Unknown` (the existing rule).

### 7. Domain events keep their per-field types

`CaseChangeNotifier` publishes one `case.<field>_changed` per changed field, as the single update
does, now with `batchId` in the change map. Consumers that listen to `case.status_changed` keep
working. A new domain event for the batch is not added; the batch id is enough for a consumer that
wants to group them. Note text never appears, as before.

### 8. Audit: one entry per changed case, new action

`AuditAction.CASE_BULK_UPDATE("case.bulk_update")` is written once per changed case through
`StaffAuditRecorder.record(actor.attribution(), actor, ..., AuditTarget.ofCase(shortId))`. The
attribution is the human moderator (`source` = `recordedBy` = the actor), so `onBehalfOf` is never set
on this route. `staff_audit.action` is text with no check constraint, so no migration is needed.

Audit is written after the state change, as the single update does. If the audit write throws, the
case is changed and the item reports `changed: true` and `auditWritten: false`; the failure is logged
and counted in `blackhole.case.bulk.audit-failures`. The batch never reports a changed case as
failed, and never reports an unchanged case as changed.

### 9. Roles: MODERATOR only, no delegation

`@Secured(Roles.MODERATOR)` (ADMIN inherits MODERATOR through the role hierarchy in `Roles`). The `DISCORD` role that the single
update accepts is not granted, and the request has no `onBehalfOf` field, so `StaffDelegation` is not
called. `PROXY` is denied, as on every case route. The Discord surfaces stay on the single update.

### 10. The response: 200 with one result per case

Request-level failures are `400` (shape, size, duplicates, no change field, tag rule) or `422`
(unknown assignee), and change nothing. Otherwise the answer is `200`:

```
CaseBulkResult {
  batchId, updated, unchanged, conflicts, notFound, failed,
  items: [ { punishmentIdentifier, outcome, changed, version, currentVersion, auditWritten, message } ]
}
```

`outcome` is one of `UPDATED`, `UNCHANGED`, `CONFLICT` (stale version; `currentVersion` says where the
case is now), `NOT_FOUND`, `FAILED` (storage error before the write; `changed` is false). `207 Multi-Status`
was considered and rejected: the admin UI and the generated client handle one shape better, and the
per-item outcome carries the same information.

An identifier that is not a full punishment identifier is `NOT_FOUND`, not an ambiguous short-id
`409`. The bulk route takes identifiers only, so one entry is one case.

### 11. Web: view memory, selection and chunking

- **View memory**: one `localStorage` key, `blackhole.cases.view`, value `cases` or `player`, read and
  written in a try/catch. A blocked or private storage falls back to `cases`. The key holds no name and
  no owner hash. The filters stay in the route query, as today.
- **Selection** lives in the page. In the flat view, a checkbox per row and a "select page" control.
  In the player view, a "select all cases of this player" control on each group. Selection is capped at
  100; the control says so and disables further selection.
- **Groups larger than the page** are expanded page by page, so "select all of this player" selects the
  cases loaded so far and says how many of the total are selected. Selecting more than 100 cases of one
  player is done in two bulk actions; the web sends the batches one after the other, and shows the
  results of each. This keeps every request within the server limit.
- **Selection is cleared** when a filter, the page or the view changes.
- **The bulk panel** offers status, assignee (the same picker as the case header), priority, and the
  tag add and remove fields. Apply is disabled until one change field is set. The result is a list of
  the cases with their outcomes; `CONFLICT` items offer a reload of the list.

### 12. Population and counts

The list and the groups count punishments that have a case row, as the list does today. A punishment
that has never been opened as a case is not listed and not counted. The alternative (counting every
punishment as an open case) would change the list's meaning and is listed in the open questions.

## Migrations

None. `case_event.type` and its payload are text and JSON; `staff_audit.action` is text without a check
constraint (`V29__staff_audit.sql`). The group and list queries use `punishments (owner, ...)` and
`case_event (punishment_identifier, ...)`, which exist. If the group query shows a sequential scan on a
production-sized table during implementation, an index is added as a new migration at the next free
version (V41 is the newest today, so V42), appended and never edited.

## Testing approach (F.I.R.S.T.)

- Unit tests with an injected `Clock` and fake ports: `CaseChangeRules`, `CaseBulkService` (per-case
  outcomes, conflict in the middle of a batch does not stop the rest, unchanged case appends nothing,
  audit failure reports `auditWritten: false`), the request validation, and the DTO shapes.
- Testcontainers integration test for the reader and writer SQL: the group query with each filter, the
  name fallback (no name row, expired name, 128-character owner hash), one statement per page, the
  bulk CTE (state and event atomic, stale version writes nothing), and a replay of the same batch.
- Permission tests: `MODERATOR` allowed, `VIEWER` denied, `DISCORD` denied, `PROXY` denied, `ADMIN` allowed.
- Web: Vitest with the fake API client; the view memory with a storage that throws; selection cap;
  chunking of a 150-case player into two requests; keyboard and screen-reader labels; de/en parity.
- No sleeps, no real time, no shared static state, no network.

## Open questions

1. **Population**: should punishments without a case row be counted as open cases? This changes the
   meaning of the list and the counts; this change keeps today's population.
2. **Selection across pages**: the proposal keeps selection per page. Cross-page selection needs a
   server-side selection or a larger limit.
3. **Audit correlation**: the audit entry has no field for the batch id. Should the audit target carry
   it, or is the timeline's `batchId` enough?
4. **Limit**: 100 is a guess from the page size. A player with hundreds of cases makes the web send
   several batches. Is that acceptable, or should the limit be raised after a measurement?
5. **Archive order**: `add-punishment-case-timeline` is merged in code but not archived; its tasks 4 and
   5 are open. This change's deltas name its rules, so it should be archived first or in the same window.
6. **Auth model**: `CLAUDE.md` says there is no auth layer, `openspec/config.yaml` principle 2 says every
   route is guarded by `@Secured` with Entra ID, and the merged `CaseController` uses `@Secured`. This
   proposal follows the code (`@Secured(Roles.MODERATOR)`). The conflict is for the maintainer to settle.
7. **Discord bulk**: out of scope. If the bot should bulk-edit later, it needs its own `onBehalfOf`
   design (one attribution for the batch, checked per case).
