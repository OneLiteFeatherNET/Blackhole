# Tasks

Stacked, small PRs, one Conventional Commit type per PR, squash-merged. Each PR branches from the previous PR's branch
when it depends on it (the only exception to "branch from `origin/main`"); each PR is opened after the main session has
reviewed the diff and the test results.

Ownership (user rule, 2026-10-26): ALL implementation (code, tests, migrations, client spec, commits, PRs) is done by
Haiku agents (`model: "haiku"`), one agent per PR, in an isolated worktree. Read-only checks (fact-checking code paths,
CI and log triage) also go to Haiku agents. The main session reviews every diff and test result before a PR is opened.

Every group starts with failing tests (red, then green, then refactor). Tests follow F.I.R.S.T.: no sleeps, injected
`Clock`, fixed `TestKeys`, fresh fixtures per test, no shared static state, meaningful assertion messages, and no test
reads logs to decide pass or fail. Flyway numbers and the client spec number are the next free ones on `main` at the
moment each PR is written (main is at V26 today; the kick change takes V27 when it lands). Do not fix numbers here.

Dependency: `add-i18n-platform` (PR #388, branch `docs/i18n-platform`) provides the catalog and the storage keys
`canned.<kind>.<id>`. This change only reads them through the `CannedReasonText` port. Until #388 is on `main`, the port
returns the English body.

## 1. Proposal (PR 1, `docs(openspec)`)

- [x] 1.1 Proposal, design, delta specs and this task list; `openspec validate structure-case-evidence --strict` and
  `openspec validate --all --strict` pass.
- [ ] 1.2 Decisions of 2026-10-26 recorded in `design.md`; open questions closed; one follow-up commit on PR #389.

## 2. Canned reasons backend (PR 2, `feat(case)`)

- [ ] 2.1 Failing tests first, in `backend/src/test/java/.../cannedreason/`: `CannedReasonServiceTest` (code format;
  `CODE_TAKEN` per kind but not across kinds; body length 1 to 4000; `PII_REJECTED` for a UUID, an IP, a Ban ID lookalike
  and a non-http(s) link target; `SYSTEM_REASON` on deactivating `BAN_ID_LEAKED`; code immutable on update; `VERSION_CONFLICT`
  on a stale `version`), `CannedReasonTextTest` (returns the English body while the catalog has no value for
  `canned.<kind>.<id>` in the viewer's locale; returns the catalog value when one exists; never returns an empty string),
  and `CannedReasonIntegrationTest` (Postgres: seed row present; moderator sees active only; `Idempotency-Key` repeat inserts
  nothing). Extend `PermissionMatrixIntegrationTest` and `RouteCoverageIntegrationTest` with the new routes.
- [ ] 2.2 Migration `V<next>__canned_reasons.sql` in the directory of the existing Flyway files: create `canned_reason`
  (`unique (kind, code)`), seed `CLOSING / BAN_ID_LEAKED` (system, active, body from `design.md` decision 8), add
  `punishment_case.closing_reason_code varchar(32) null`. No backfill. Never edit an applied migration.
- [ ] 2.3 Package `cannedreason/`: `CannedReasonKind` (ordinal `smallint`: `CLOSING` 0, `DECISION` 1, `REPLY` 2, `ALLEGATION` 3),
  `CannedReasonStore` and `JdbcCannedReasonStore`, `CannedReasonService`, `CannedReasonText` (the port: catalog key
  `canned.<kind>.<id>` from #388, English body as fallback), sealed `CannedReasonDTO` (`CreateRequest`, `UpdateRequest`,
  `Response`, `Error`), `CannedReasonApi` and a thin `CannedReasonController`. Bodies pass `MessageRules` and `PiiGuard`.
  No hard delete.
- [ ] 2.4 Routes: `GET /canned-reasons?kind=&includeInactive=` (MODERATOR; inactive ADMIN only), `POST /canned-reasons`
  (ADMIN, `Idempotency-Key`), `PATCH /canned-reasons/{id}` (ADMIN, `version`). Problem Details per the standardisation change
  once it is on `main`; until then the existing error shape with the same codes.
- [ ] 2.5 Regenerate the client spec `client/specs/blackhole-api-<next>.yml`, bump `inputSpec` in `client/build.gradle.kts`,
  run `./gradlew :backend:test :client:compileJava`.

## 3. Leak freezes the appeal and closes the case once settled (PR 3, `feat(case)`; depends on PR 2)

Rules this group implements (decision 8 of `design.md`): a leak freezes an open appeal; with no open appeal
the case closes at once with the closing comment; a frozen appeal closes the case when the team decides it; a reissue
resets the leak and leaves its trace in the timeline. No new appeal status.

- [ ] 3.1 Failing tests first: `LeakFreezeIntegrationTest` (Postgres): a leak with an open appeal sets the leak state, leaves
  the case status unchanged, deletes the punishment's sessions, appends `ban_id.leak_marked`, and shows the appeal as `FROZEN`
  on the portal; a leak with no open appeal sets the case `CLOSED` with closing reason `BAN_ID_LEAKED`, appends one closing
  comment (author `SYSTEM`, visibility `PLAYER`) and `case.status`; a repeat mark changes nothing; a decision on the frozen appeal
  closes the case once with the comment and a repeat decision appends nothing; the Ban ID value appears in no `case_event`,
  session or log line (extend `BanIdNoLeakTest`). `ReissueResetIntegrationTest`: a reissue clears the leak, leaves an undecided
  frozen appeal open, restores an unfiled pre-filing right, reopens a leak-closed case to `OPEN`, clears the closing reason,
  appends `message.deleted` for the closing comment (so the player no longer sees it), keeps every earlier event in the timeline,
  and leaves a decided appeal's decision unchanged. `CaseStatusLockTest`: a status change while the leak is set answers
  `CASE_LEAKED`; assignee, tags and priority still change. `FrozenAppealTest`: a player response (`FROZEN`) and a shared item
  (`FROZEN`) are refused while frozen. `PublicLimitedClosingIntegrationTest`: the `closing` field is absent while frozen and
  present once closed, and the conversation read stays 403. `PublicAppealFilingAfterLeakTest`: before filing, filing answers
  `FORFEITED`; after a reissue, the restored right allows filing again.
- [ ] 3.2 No `AppealStatus` value is added. The "leak before filing" state stays the derived `FORFEITED` of `add-appeal-portal`,
  and the "frozen" state stays the derived `FROZEN`. Test that `AppealStatus` is unchanged.
- [ ] 3.3 `JdbcCaseWriter.MARK_LEAKED` becomes one data-modifying CTE: set `ban_id_leaked_at` and the reason (guard
  `ban_id_leaked_at IS NULL`); delete the punishment's sessions; append `ban_id.leak_marked`. When no appeal is open
  (`NOT EXISTS` on undecided appeals), the same statement also sets `status = 'CLOSED'` and `closing_reason_code`, appends
  `case.status`, and appends the closing `message.added` (dedupe `leak-close:<punishmentId>:<generation>`). Otherwise the status
  stays as it is. No `@Transactional`.
- [ ] 3.4 `CaseChanges.closeLeakedCase` (one CTE, guard: leaked, not closed, no undecided appeal, dedupe as in 3.3) runs after the
  appeal decision is recorded, through the same path as the other lifecycle recordings: a failure is logged and counted in
  `blackhole.case.record.failures`, never fails the decision. `CaseReconciler` also finishes leaked cases that have no undecided
  appeal and are not closed.
- [ ] 3.5 Reissue (`reissueBanId`, `reissueBanIdAtGeneration`): the same statement clears the leak state; a case closed by the
  leak is reopened to `OPEN` with the closing reason cleared; the closing comment is withdrawn with `message.deleted:<id>`
  (the event and the message stay in the timeline); `case.status` is appended. A frozen appeal needs no write (its `FROZEN`
  state is derived); a decided appeal is not touched.
- [ ] 3.6 `CaseService`: a status change while `ban_id_leaked_at` is set answers `409 CASE_LEAKED`. Filing and presentations
  answer `409 FROZEN` while the appeal is frozen; the appeal path refuses `FORFEITED` before filing.
- [ ] 3.7 Public punishment answer for `LIMITED` sessions gains `closing {code, text, closedAt}` only once the case is closed
  (sealed DTO change, additive); OpenAPI annotations on the `*Api` interface only.
- [ ] 3.8 Regenerate the client spec (new field), bump `inputSpec`, run `./gradlew :backend:test :client:compileJava`.

## 4. Case model read API (PR 4, `feat(case)`; depends on PR 2)

- [ ] 4.1 Failing tests first: `CaseItemReaderTest` (provenance and verification table, all four verification states, "You",
  "Unverified staff", "Unnamed staff member", "Staff member (name removed)", "Player", "System"; pure unit with fakes),
  `CaseItemRoutesIntegrationTest` (bundles split, cursor stable), `CaseAllegationServiceTest` (version increments, repeat with
  `Idempotency-Key` answers `repeated: true`, payload has no text, `PII_REJECTED` for a Ban ID lookalike),
  `ShareableKindTest` (one assertion per row of the decision 3 table; the enum has exactly the shareable kinds),
  `CaseFileRendererTest` (no branch for anticheat, replay, link, client-rule hit, evasion hint, mass kick), `SummaryWhitelistTest`
  (punishment, report and kick summaries carry only the whitelisted fields; reporter hash and description never appear).
- [ ] 4.2 `casetimeline/CaseItemReader` (+ JDBC), `CaseItemApi`/`CaseItemController`: `GET /case/{ref}/items?bundle=&cursor=&limit=`,
  `GET /case/{ref}/allegation`, `POST /case/{ref}/allegation` (`Idempotency-Key`). Sealed `CaseItemDTO` and `AllegationDTO`.
  The allegation is one `allegation.set` event through `CaseEventRepository.append`.
- [ ] 4.3 Package `casefile/`: `ShareableKind`, `CaseFileSourceReader` (port; JDBC implementation reads punishment history,
  reports and kick summaries; it does not depend on `EvidenceService` or the blob store), `CaseFileRenderer`,
  `SummaryWhitelist`.
- [ ] 4.4 Routes: `GET /case/{ref}/case-file/candidates?kind=&q=&cursor=` (MODERATOR; cursor 50, max 200, each row with
  `shareability` and `lockReason`), `POST /case/{ref}/case-file/preview` (MODERATOR; writes nothing; `PiiGuard` on the draft).
- [ ] 4.5 Extend `PermissionMatrixIntegrationTest` and `RouteCoverageIntegrationTest` for every new route.
- [ ] 4.6 Regenerate the client spec, bump `inputSpec`, run `./gradlew :backend:test :client:compileJava`.

## 5. Case file presentation (PR 5, `feat(case)`; depends on PR 4)

- [ ] 5.1 Failing tests first: `PresentCaseFileServiceTest` (`NO_APPEAL`, `CASE_CLOSED`, `FROZEN` while frozen,
  `STAFF_PROFILE_REQUIRED`, `NOT_CONFIRMED`, `SELECTION_TOO_LONG` at 21 lines, `PII_REJECTED` names the line index only,
  `NOT_SHAREABLE` for an anticheat item, repeat key answers `repeated: true`), `CaseFileIntegrationTest` (Postgres: file and
  items rows, one `case.file_presented`, one `evidence.shared` per file or excerpt, no content in any event),
  `WithdrawAndReviewTest` (withdrawal marks summaries, revokes files and excerpts, reviewed is recorded once), `SnapshotTest`
  (a report resolved after presentation does not change the stored summary, and no refresh path exists).
- [ ] 5.2 Migration `V<next>__case_file.sql`: `case_file`, `case_file_item` (cascade, `dedupe_key` unique, indexes on
  `punishment_identifier` and `appeal_id`), and the partial expression index on `case_event ((payload->>'caseFileItemId'))`
  where `type = 'message.added'`. No existing rows change.
- [ ] 5.3 `casefile/`: `JdbcCaseFileStore` (presentation as one CTE: items, the file-or-excerpt share through
  `EvidenceShareService`, events, dedupe), `CaseFileService`, sealed `CaseFileDTO` (`CreateRequest`, `Response`, `Error`),
  `CaseFileApi`, thin `CaseFileController`.
- [ ] 5.4 Routes: `POST /case/{ref}/case-files` (MODERATOR, `Idempotency-Key`), `GET /case/{ref}/case-files?cursor=` (VIEWER),
  `GET /case/{ref}/case-files/{id}` (VIEWER, items with their threads), `POST .../items/{itemId}/withdraw` (MODERATOR,
  `Idempotency-Key`), `POST .../items/{itemId}/review` (MODERATOR).
- [ ] 5.5 `POST /case/{ref}/messages` gains optional `caseFileItemId` and `cannedReasonCode` (additive; payload keys added,
  `CaseConversationService` checks the item belongs to the case).
- [ ] 5.6 Regenerate the client spec, bump `inputSpec`, run `./gradlew :backend:test :client:compileJava`.

## 6. Counter-evidence backend (PR 6, `feat(case)`; depends on PR 5)

- [ ] 6.1 Failing tests first: `PlayerResponseServiceTest` (stance values, text rules, five files at most, foreign item 404,
  withdrawn item 409 `ITEM_WITHDRAWN`, `FROZEN` while the appeal is frozen, `CASE_CLOSED` once closed), `PlayerResponseIntegrationTest`
  (message with `caseFileItemId` and `stance`, files bound through the existing claim, `repeated` on the same key),
  `PublicCaseFileRouteTest` (only presented, not withdrawn items of the own appeal; no internal field; `LIMITED` 403),
  `ForeignItemIsolationTest` (another punishment's item and another appeal's item answer the same 404 as an unknown id).
- [ ] 6.2 `publicapi/`: `PublicCaseFileApi` and thin controller: `GET /public/case-files?cursor=`,
  `POST /public/case-files/{id}/items/{itemId}/responses` (`Idempotency-Key`). `casefile/PlayerCaseFileService` uses the
  `ChatFiles` door for staging and binding; no direct evidence access.
- [ ] 6.3 Thread read on the staff side: `CaseFileService.thread(itemId)` queries `case_event` on the partial index.
- [ ] 6.4 Regenerate the client spec, bump `inputSpec`, run `./gradlew :backend:test :client:compileJava`.

## 7. Canned reasons admin page and pickers (PR 7, `feat(web)`; depends on PR 2)

- [ ] 7.1 Failing component tests first: `CannedReasonsPage.test.ts` (filter per kind; system reason's deactivate action
  disabled with its reason; edit dialog shows a live Markdown preview and count; moderators do not reach the route, the
  router guard refuses), `CannedReasonPicker.test.ts` (lists active reasons of one kind; inserts text into the composer
  without sending; `REPLY` only), `CannedReasonsApi` contract test against the generated client.
- [ ] 7.2 `web/src/views/admin/CannedReasonsView.vue` (route `/admin/canned-reasons`, `meta.admin`, Configuration group in
  `nav/navigation.ts`), components `web/src/components/canned/CannedReasonRow.vue`, `CannedReasonDialog.vue`,
  `CannedReasonPicker.vue`. Built from `PageShell`, `FilterToolbar`, `DataTable` and `MdDialog` of `redesign-ux`.
- [ ] 7.3 Locale keys for kinds and states in `en.json` and `de.json` (German: "Vorgefertigte Texte" for the page).
  Translations of canned texts themselves are edited in the #388 catalog editor, not here.

## 8. Case layout (PR 8, `feat(web)`; depends on PR 4 and PR 7)

- [ ] 8.1 Failing component tests first: `CaseLayout.test.ts` (section order, `h1` once, `h2` for sections, `h3` for items),
  `CaseItemCard.test.ts` (brought-by labels for every verification state and "You"; provenance badge; shareability badge),
  `AllegationSection.test.ts` (writes a new version; shows history count; no text in the timeline), `DecisionSection.test.ts`
  (reason from the canned `DECISION` picker, decision note labelled internal), `LeakDialog.test.ts` (states that the leak freezes
  an open appeal and closes the case at once only when no appeal is open, and that a reissue resets it; the confirm button
  is enabled only after that text is shown).
- [ ] 8.2 `web/src/views/CaseView.vue` rebuilt with `PageShell` sections in the order of `design.md` decision 1; components
  `web/src/components/case/CaseSummary.vue`, `AllegationSection.vue`, `EvidenceBundles.vue`, `CaseItemCard.vue`,
  `DecisionSection.vue`. Notes move to the summary column. Replaces the inline back link with `Breadcrumbs`.
- [ ] 8.3 Empty states for every section with the next step (`ResourceState` and the state components of `redesign-ux`).
- [ ] 8.4 `LeakDialog` in `BanIdPanel.vue` with the text of 8.1; the leak button calls the existing mark route.
- [ ] 8.5 Contrast and axe checks for the new states (`web/tests/contrast.test.ts` tokens).

## 9. Evidence picker and staff case file (PR 9, `feat(web)`; depends on PR 6 and PR 8)

- [ ] 9.1 Failing component tests first: `EvidencePicker.test.ts` (locked rows have no checkbox and state their reason;
  evasion hint row has no count, no date and no preview; the 20-line cap disables line 21 with its message; "Present" is
  disabled until the preview is shown and the confirmation checked; "Present" is disabled with the reason `FROZEN` while
  the appeal is frozen; closing the panel discards the draft and writes nothing; the draft is not stored in browser storage),
  `ChatLineSelector.test.ts` (checkbox per line, `n / 20` counter, "Player N" preview labels, indexes sorted),
  `CaseFileThread.test.ts` (team side and player side pair; "No response yet"; withdrawn marked), `PlayerPreview.test.ts`
  (renders the server preview blocks, writes nothing).
- [ ] 9.2 `web/src/components/case/casefile/`: `EvidencePicker.vue` (desktop aside, mobile full-screen dialog; route state
  `?picker=open`), `PickerList.vue`, `ChatLineSelector.vue`, `CaseFileDraft.vue`, `PlayerPreview.vue`, `CaseFileList.vue`,
  `CaseFileThread.vue`. Uses `FilterToolbar` chips per data type and the server-side search.
- [ ] 9.3 Wire "Present a case file" in the Evidence section (disabled with its reason for no open appeal, frozen appeal,
  closed case or missing display name, with a link to My profile).
- [ ] 9.4 Locale keys for picker labels, lock reasons and states (`en.json`, `de.json`).

## 10. Player case file and counter-evidence (PR 10, `feat(portal)`; depends on PR 6 and PR 3)

- [ ] 10.1 Failing tests first (Vitest): `CaseFileCard.test.ts` (presented item rendered from text nodes; allegation shown;
  presenter label; withdrawn shows status only), `StanceControl.test.ts` (`DISPUTE`, `ACCEPT`, `NO_COMMENT`, one selected),
  `ResponseComposer.test.ts` (up to five files; text optional with files; text required with no file for `DISPUTE`; a
  failed upload keeps the draft; disabled with the `FROZEN` notice while frozen), `LimitedClosingNotice.test.ts` (the
  `closing` text in text nodes, shown only when present; a frozen appeal shows its frozen notice instead; no conversation view).
- [ ] 10.2 `portal/src/components/casefile/` (`CaseFileCard.vue`, `StanceControl.vue`, `ResponseComposer.vue`), wired into
  the existing `portal/src/views/CaseView.vue` below the Ban ID facts; `LimitedClosingNotice.vue` in the `LIMITED` view.
  Portal copy in English and German in `portal/src/locales/`. Follows `redesign-ux` section 7 for the portal.
- [ ] 10.3 Client calls go through the generated client; no new fetch wrapper.

## 11. Discord ticket on case close (PR 11, `feat(discord)`; conditional, depends on PR 3)

- [ ] 11.1 Failing tests first: `CaseCloseTicketTest` (an open ticket of a case that moves to `CLOSED` moves to `CLOSING` once;
  a leak that only freezes the appeal leaves the ticket open; a closed or archived ticket is not touched; the bot's outbox
  carries the closing comment; no new relay label; presentations and responses are not relayed).
- [ ] 11.2 Transition in `discord/ticket`, called from the case-close path of PR 3 (after the statement, idempotent; a failure is
  logged and counted, never failing the close). Documented in the rule file in PR 12.
- [ ] 11.3 No Discord relay of presentations or responses in v1 (decided 2026-10-26); this PR only moves the ticket state.

## 12. Rules and archive (PR 12, `docs(case)`; after PRs 2 to 11)

- [ ] 12.1 Update `.claude/rules/case-timeline.md` (freeze and close, reissue reset, allegation, case file events), `evidence.md`
  (provenance, presentable kinds), `public-api.md` (case file routes, `closing`), `banid.md` (leak freeze and reset). Add a
  path-scoped rule `.claude/rules/canned-reasons.md` for `cannedreason/**`. Keep each rule short (the CLAUDE.md lean rule).
- [ ] 12.2 Sync the delta specs into `openspec/specs/` only after every PR of this change is merged; then archive with
  `docs(openspec): archive structure-case-evidence` as a separate commit (archive commits are separate unless shipped with
  the implementation).

## 13. Open the pull requests

- [ ] 13.1 For each PR 1 to 12, in order, after its branch is pushed and the main session has reviewed its diff and test
  results: `gh pr create --base <previous PR branch or main> --title "<the Conventional Commit title of that PR>"`. The
  titles are: PR 1 `docs(openspec): propose a structured case with evidence from both sides`; PR 2 `feat(case): add canned
  reasons for closing, decisions, replies and case files`; PR 3 `feat(case): freeze the appeal of a leaked Ban ID and close
  the case once settled`; PR 4 `feat(case): add the case model read API and the evidence picker source`; PR 5 `feat(case):
  present a case file to the player`; PR 6 `feat(case): accept player counter-evidence per case file item`; PR 7 `feat(web):
  add the canned reasons page and pickers`; PR 8 `feat(web): lay out the case by section with attributed evidence`; PR 9
  `feat(web): add the evidence picker and the threaded case file`; PR 10 `feat(portal): show the case file and accept
  counter-evidence`; PR 11 `feat(discord): move an open ticket to closing when a case closes`; PR 12 `docs(case): document the
  case structure and canned reasons`. Each PR body ends with the session link line. Do not merge.
