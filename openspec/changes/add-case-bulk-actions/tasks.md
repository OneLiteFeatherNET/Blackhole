# Tasks

One code pull request, `feat(case)`, opened by the last task. Every backend group lands its own JUnit tests
written first (F.I.R.S.T.: injected `Clock`, fakes for the ports, no sleeps, no shared static state; the
SQL is covered by Testcontainers tests). Every web group lands its own Vitest tests with a fake API client,
also written first. Commits per group, each with its own Conventional Commit title that stays within the
one `feat(case)` change. Checkboxes are ticked in the follow-up PR, never in the code PR.

## 1. Backend: player name, groups and owner filter

- [ ] 1.1 Failing tests first: the newest name wins; no name gives `playerName` null and `ownerShort` the first 12 characters; no raw UUID in the JSON; one statement per page (counted); group counts (`openCount` excludes `CLOSED`, `totalCount`); a group is on exactly one page; filters apply within groups; `status=CLOSED` with no closed case has no group; malformed `owner` is 400
- [ ] 1.2 `CaseReader` and `JdbcCaseReader`: the name lateral in the list query, `version` and `owner` filter in the list, the two-level group query (`GET /case/groups`), reusing the list's filter builder
- [ ] 1.3 `CaseDTO`: `ListItem` gains `playerName`, `ownerShort`, `version`; new `GroupItem` and `GroupPage` records; `CaseService` methods for the groups and the owner filter; the thin controller route and its annotations on `CaseApi` (`@Version(ApiVersion.V1)`, `@Secured(Roles.VIEWER)`)
- [ ] 1.4 Testcontainers test over an owner with a 128-character hash and an owner with no name row, against the real `V*` migrations; verify the group query's plan has no sequential scan on `punishments` for a few thousand cases

## 2. Backend: bulk edit

- [ ] 2.1 Characterisation tests first: the existing `CaseService.update` tests stay green before the extraction (tags, assignee, version, no-op returns no event, audit once)
- [ ] 2.2 Extract `CaseChangeRules` (tag normalisation, assignee check through a port, version check, diff from `CaseMeta`) from `CaseService.update`; `update` calls it, no behaviour change
- [ ] 2.3 Failing tests first: `CaseBulkService` with fake writer, fake reader and injected `Clock`: request validation (0, 101, duplicate, no change field, bad tag, unknown assignee gives no write), per-case outcomes (`UPDATED`, `UNCHANGED`, `CONFLICT` with `currentVersion`, `NOT_FOUND`, `FAILED`), a conflict in the middle does not stop the rest, unchanged case appends no event and no audit, audit failure gives `auditWritten` false, no `onBehalfOf` field exists in the request type
- [ ] 2.4 `CaseEventPayload.CaseBulkEdited` (`case.bulk_edited`, payload version 1, `batchId`, changed fields with from and to, oids not names) and its JSON in `CaseEventPayloads`; unknown-type and version readers stay unchanged; `AuditAction.CASE_BULK_UPDATE("case.bulk_update")`
- [ ] 2.5 Failing Testcontainers test first, then `CaseWriter.bulkEdit`: one CTE per case that updates `punishment_case` guarded by `version` and appends `case.bulk_edited` with dedupe key `bulk:<batchId>:<identifier>`; a stale version writes nothing; a replay of the same batch appends nothing; state and event never diverge
- [ ] 2.6 `CaseBulkService`: validates once, reads the current rows of all listed cases in one query, writes each case in order with its own version, records the audit per changed case, publishes the domain events with `batchId`; counters `blackhole.case.bulk.audit-failures`
- [ ] 2.7 `CaseDTO.BulkRequest` (cases with `punishmentIdentifier` and `expectedVersion`, `status`, `assigneeOid`, `clearAssignee`, `priority`, `addTags`, `removeTags`) and `CaseDTO.BulkResult` (`batchId`, counts, `items`); `CaseApi` `POST /case/bulk` with `@Secured(Roles.MODERATOR)`; the thin controller maps the result
- [ ] 2.8 Failing tests first, then extend `PermissionMatrixIntegrationTest` and `RouteCoverageIntegrationTest`: `MODERATOR` and `ADMIN` allowed, `VIEWER`, `DISCORD` and `PROXY` denied on `/case/bulk`; the route rejects `onBehalfOf` in the body
- [ ] 2.9 Domain events: `case.status_changed`, `case.assignee_changed`, `case.priority_changed` and `case.tags_changed` carry `batchId` in their change map; a failing publisher does not fail the batch; no note text (captured publisher)
- [ ] 2.10 Run the backend tests for the `casetimeline` and `security` packages with `flock ~/.cache/claude-heavy.lock`, and check `MemAvailable` first

## 3. OpenAPI client spec

- [ ] 3.1 Write a new `client/specs/blackhole-api-0.0.N.yml` where N is one more than the newest file in `client/specs` at implementation time (`0.0.66` if nothing else lands first); never edit a published file such as `0.0.65`
- [ ] 3.2 Bump `inputSpec` in `client/build.gradle.kts` to the new file; `./gradlew :client:compileJava` under `flock`; check the generated model names for the new records do not clash with existing ones
- [ ] 3.3 Verify the spec and the running backend agree on `GET /case/groups`, the `owner` filter, `POST /case/bulk` and the new `CaseListItem` fields

## 4. Web: data and view memory

- [ ] 4.1 Failing Vitest first: API types and client methods for the list (`playerName`, `ownerShort`, `version`, `owner` filter), the groups route and the bulk route, using a fake client
- [ ] 4.2 Failing test first, then the view memory: one `localStorage` key with `cases` or `player`; a storage that throws falls back to `cases` without an error; nothing else is stored (no name, no owner)

## 5. Web: Player column and grouped view

- [ ] 5.1 Failing tests first: the Player column between Case and Section shows `playerName`, falls back to `ownerShort` and never a UUID; column order
- [ ] 5.2 Failing tests first: the grouped view shows name or short hash, open and total counts; expansion through the `owner` filter; keyboard activation; `aria-expanded`; a link to each case; paging over groups
- [ ] 5.3 `CasesView.vue` toggle (flat and grouped) wired to the view memory; the filters stay in the route query and apply to both views

## 6. Web: selection and bulk panel

- [ ] 6.1 Failing tests first: row selection, page selection, group selection of the loaded cases; cap at 100 with a message; selection cleared when the page, a filter or the view changes
- [ ] 6.2 Failing tests first: the bulk panel is disabled until one change is set; status, assignee (same picker as the case header), priority, tags to add and to remove; a 150-case group sends batches of 100 and 50 in order
- [ ] 6.3 Failing tests first: the result list shows each outcome; a `CONFLICT` offers a reload; the counts are announced in a live region; the list reloads after a batch
- [ ] 6.4 `CasesView.vue` selection, bulk panel and result list; component tests green

## 7. Web: timeline, strings and accessibility

- [ ] 7.1 Failing test first: `CaseEventEntry` renders `case.bulk_edited` with the changed fields (old and new values, oids shown as the existing assignee label), and an unknown type still renders as `Unknown`
- [ ] 7.2 German and English strings for the Player column, the view toggle, the group labels, the bulk panel and the result list; `i18n.keys.test.ts` parity
- [ ] 7.3 Run `npm test` and the type check in `web/` (targeted to the changed files where possible)

## 8. Documentation and verification

- [ ] 8.1 Update `.claude/rules/case-timeline.md` for the groups, the bulk route, the event `case.bulk_edited`, the audit action and the non-atomic batch decision (short, no duplicate of the design)
- [ ] 8.2 Manual check against the real stack (Docker PostgreSQL, under `flock`): group a player with two cases of different status, bulk-close one, check the timeline event, the audit entry and that an unchanged case gets nothing; record the result in the pull request
- [ ] 8.3 Check the owner hash and the raw UUID do not appear in any response of the list, the groups or the bulk route (grep the response bodies from the manual check)

## 9. Code pull request

- [ ] 9.1 Push the implementation branch from `origin/main` and open the code pull request titled `feat(case): edit cases in bulk and group them by player`, with the body from `.github/pull_request_template.md`; no file under `openspec/`; open only after the proposal PR `docs(openspec): propose add-case-bulk-actions` is merged
