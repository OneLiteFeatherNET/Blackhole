# Tasks

Stacked, small PRs, one Conventional Commit type per PR, squash-merged. Each PR branches from the previous PR's branch
when it depends on it (the only exception to "branch from `origin/main`"); each PR is opened after the main session has
reviewed the diff and the test results.

Ownership: implementation (code, tests, migrations, client spec, commits, PRs) is done by Sonnet agents, one agent per
PR, in an isolated worktree (the global rule for writing agents). Read-only checks (fact-checking the code paths named
here, CI and log triage) go to Haiku agents. This brief said Haiku for implementation; the global rule says Sonnet for
implementation, and the global rule is followed. See the report.

Every group starts with failing tests (red, then green, then refactor). Tests follow F.I.R.S.T.: no sleeps, injected
`Clock`, fixed `TestKeys`, fresh fixtures per test, no shared static state, meaningful assertion messages, and no test
reads logs to decide pass or fail. Flyway numbers and the client spec number are the next free ones on `main` at the
moment each PR is written (main is at V26 today; the kick change takes V27 when it lands). Do not fix numbers here.

## 1. Proposal (PR 1, `docs(openspec)`)

- [x] 1.1 Proposal, design, delta specs and this task list; `openspec validate structure-case-evidence --strict` and
  `openspec validate --all --strict` pass.

## 2. Canned reasons backend (PR 2, `feat(case)`)

- [ ] 2.1 Failing tests first, in `backend/src/test/java/.../cannedreason/`: `CannedReasonServiceTest` (code format;
  `CODE_TAKEN` per kind but not across kinds; body length 1 to 4000; `PII_REJECTED` for a UUID, an IP, a Ban ID lookalike
  and a non-http(s) link target; `SYSTEM_REASON` on deactivating `BAN_ID_LEAKED`; code immutable on update; `VERSION_CONFLICT`
  on a stale `version`), `CannedReasonTextTest` (returns the body for every locale until a translation port answers), and
  `CannedReasonIntegrationTest` (Postgres: seed row present; moderator sees active only; `Idempotency-Key` repeat inserts
  nothing). Extend `PermissionMatrixIntegrationTest` and `RouteCoverageIntegrationTest` with the new routes.
- [ ] 2.2 Migration `V<next>__canned_reasons.sql` in the directory of the existing Flyway files: create `canned_reason`
  (`unique (kind, code)`), seed `CLOSING / BAN_ID_LEAKED` (system, active, body from `design.md` decision 8), add
  `punishment_case.closing_reason_code varchar(32) null`. No backfill. Check the migration against `liquibase-checker`-style
  rules: no edit of an applied migration.
- [ ] 2.3 Package `cannedreason/`: `CannedReasonKind` (ordinal `smallint`: `CLOSING` 0, `DECISION` 1, `REPLY` 2, `ALLEGATION` 3),
  `CannedReasonStore` and `JdbcCannedReasonStore`, `CannedReasonService`, `CannedReasonText` (the translation port, English
  fallback), sealed `CannedReasonDTO` (`CreateRequest`, `UpdateRequest`, `Response`, `Error`), `CannedReasonApi` and a thin
  `CannedReasonController`. Bodies pass `MessageRules` and `PiiGuard`. No hard delete.
- [ ] 2.4 Routes: `GET /canned-reasons?kind=&includeInactive=` (MODERATOR; inactive ADMIN only), `POST /canned-reasons`
  (ADMIN, `Idempotency-Key`), `PATCH /canned-reasons/{id}` (ADMIN, `version`). Problem Details per the standardisation change
  once it is on `main`; until then the existing error shape with the same codes.
- [ ] 2.5 Regenerate the client spec `client/specs/blackhole-api-<next>.yml`, bump `inputSpec` in `client/build.gradle.kts`,
  run `./gradlew :backend:test :client:compileJava`.

## 3. Leak closes the case (PR 3, `feat(case)`; depends on PR 2)

- [ ] 3.1 Failing tests first: `LeakCloseIntegrationTest` (Postgres): first mark sets `CLOSED` and the closing reason, forfeits
  a filed `IN_REVIEW` appeal to `FORFEITED_BAN_ID_LEAKED`, deletes the punishment's sessions, appends `ban_id.leak_marked`,
  `case.status` and one closing `message.added` (author `SYSTEM`, visibility `PLAYER`, the closing text); repeat mark changes
  nothing and appends nothing; a decided appeal keeps its status; the Ban ID value appears in no `case_event`, session or
  log line (extend `BanIdNoLeakTest` with the new types). `AppealStatusTest`: the forfeited status counts as neither granted
  nor denied and starts no cooldown. `ReissueReopenIntegrationTest`: a reissue reopens only a leak-closed case and leaves the
  forfeited appeal forfeited. `CaseStatusLockTest`: a status change answers `CASE_LEAKED`, assignee changes still succeed.
  `PublicLimitedClosingIntegrationTest`: a `LIMITED` login gets `closing` with the stored text and nothing else from the case;
  the conversation read stays 403. `PublicAppealFilingAfterLeakTest`: filing answers `FORFEITED`, the case is not reopened.
- [ ] 3.2 `AppealStatus.FORFEITED_BAN_ID_LEAKED` appended with the next ordinal. If `appeals.status` has a database check, widen
  it in a migration `V<next>__appeal_forfeited_status.sql`; otherwise the range is enforced in the application and no
  migration is added.
- [ ] 3.3 `JdbcCaseWriter.MARK_LEAKED` becomes the single CTE of `design.md` decision 8 (status, closing reason, appeal
  forfeit, session delete, three events, closing message, dedupe key `leak-close:<punishmentId>:<generation>`). The statement
  stays one data-modifying statement; no `@Transactional`.
- [ ] 3.4 Reissue paths (`reissueBanId`, `reissueBanIdAtGeneration`) reopen a leak-closed case and clear the closing reason in
  the same statement. `CaseService` refuses status changes while the closing reason is set (`CASE_LEAKED`). The appeal filing
  path refuses `FORFEITED_BAN_ID_LEAKED` before the reopen rule runs.
- [ ] 3.5 Public punishment answer for `LIMITED` sessions gains `closing {code, text, closedAt}` (sealed DTO change, additive);
  `PublicPunishmentDTO` and its OpenAPI annotations on the `*Api` interface only.
- [ ] 3.6 Regenerate the client spec (new field and enum value), bump `inputSpec`, run `./gradlew :backend:test :client:compileJava`.

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

- [ ] 5.1 Failing tests first: `PresentCaseFileServiceTest` (`NO_APPEAL`, `CASE_CLOSED`, `STAFF_PROFILE_REQUIRED`,
  `NOT_CONFIRMED`, `SELECTION_TOO_LONG` at 21 lines, `PII_REJECTED` names the line index only, `NOT_SHAREABLE` for an
  anticheat item, repeat key answers `repeated: true`), `CaseFileIntegrationTest` (Postgres: file and items rows, one
  `case.file_presented`, one `evidence.shared` per file or excerpt, no content in any event), `WithdrawAndReviewTest`
  (withdrawal marks summaries, revokes files and excerpts, reviewed is recorded once), `SnapshotTest` (a report resolved after
  presentation does not change the stored summary).
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
  withdrawn item 409 `ITEM_WITHDRAWN`, leaked case 409 `CASE_CLOSED`), `PlayerResponseIntegrationTest` (message with
  `caseFileItemId` and `stance`, files bound through the existing claim, `repeated` on the same key), `PublicCaseFileRouteTest`
  (only presented, not withdrawn items of the own appeal; no internal field; `LIMITED` 403), `ForeignItemIsolationTest`
  (another punishment's item and another appeal's item answer the same 404 as an unknown id).
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
- [ ] 7.3 Locales `en.json` and `de.json` keys for kinds and states (German: "Vorgefertigte Texte" for the page).

## 8. Case layout (PR 8, `feat(web)`; depends on PR 4 and PR 7)

- [ ] 8.1 Failing component tests first: `CaseLayout.test.ts` (section order, `h1` once, `h2` for sections, `h3` for items),
  `CaseItemCard.test.ts` (brought-by labels for every verification state and "You"; provenance badge; shareability badge),
  `AllegationSection.test.ts` (writes a new version; shows history count; no text in the timeline), `DecisionSection.test.ts`
  (reason from the canned `DECISION` picker, decision note labelled internal), `LeakDialog.test.ts` (states the close and the
  forfeit before the confirm button is enabled).
- [ ] 8.2 `web/src/views/CaseView.vue` rebuilt with `PageShell` sections in the order of `design.md` decision 1; components
  `web/src/components/case/CaseSummary.vue`, `AllegationSection.vue`, `EvidenceBundles.vue`, `CaseItemCard.vue`,
  `DecisionSection.vue`. Notes move to the summary column. Replaces the inline back link with `Breadcrumbs`.
- [ ] 8.3 Empty states for every section with the next step (`ResourceState` and the state components of `redesign-ux`).
- [ ] 8.4 `LeakDialog` text (closing reason and forfeit) in `BanIdPanel.vue`; the leak button calls the existing mark route.
- [ ] 8.5 Contrast and axe checks for the new states (`web/tests/contrast.test.ts` tokens).

## 9. Evidence picker and staff case file (PR 9, `feat(web)`; depends on PR 6 and PR 8)

- [ ] 9.1 Failing component tests first: `EvidencePicker.test.ts` (locked rows have no checkbox and state their reason;
  evasion hint row has no count, no date and no preview; the 20-line cap disables line 21 with its message; "Present" is
  disabled until the preview is shown and the confirmation checked; closing the panel discards the draft and writes nothing;
  the draft is not stored in browser storage), `ChatLineSelector.test.ts` (checkbox per line, `n / 20` counter, "Player N"
  preview labels, indexes sorted), `CaseFileThread.test.ts` (team side and player side pair; "No response yet"; withdrawn
  marked), `PlayerPreview.test.ts` (renders the server preview blocks, writes nothing).
- [ ] 9.2 `web/src/components/case/casefile/`: `EvidencePicker.vue` (desktop aside, mobile full-screen dialog; route state
  `?picker=open`), `PickerList.vue`, `ChatLineSelector.vue`, `CaseFileDraft.vue`, `PlayerPreview.vue`, `CaseFileList.vue`,
  `CaseFileThread.vue`. Uses `FilterToolbar` chips per data type and the server-side search.
- [ ] 9.3 Wire "Present a case file" in the Evidence section (disabled with its reason for no open appeal, closed case or
  missing display name, with a link to My profile).
- [ ] 9.4 Locales for picker labels, lock reasons and states (`en.json`, `de.json`).

## 10. Player case file and counter-evidence (PR 10, `feat(portal)`; depends on PR 6 and PR 3)

- [ ] 10.1 Failing tests first (Vitest): `CaseFileCard.test.ts` (presented item rendered from text nodes; allegation shown;
  presenter label; withdrawn shows status only), `StanceControl.test.ts` (`DISPUTE`, `ACCEPT`, `NO_COMMENT`, one selected),
  `ResponseComposer.test.ts` (up to five files; text optional with files; text required with no file for `DISPUTE`; a
  failed upload keeps the draft), `LimitedClosingNotice.test.ts` (the `closing` text in text nodes; no conversation view).
- [ ] 10.2 `portal/src/components/casefile/` (`CaseFileCard.vue`, `StanceControl.vue`, `ResponseComposer.vue`), wired into
  the existing `portal/src/views/CaseView.vue` below the Ban ID facts; `LimitedClosingNotice.vue` in the `LIMITED` view.
  Portal copy in English and German in `portal/src/locales/`. Follows `redesign-ux` section 7 for the portal.
- [ ] 10.3 Client calls go through the generated client; no new fetch wrapper.

## 11. Discord ticket on leak (PR 11, `feat(discord)`; conditional, depends on PR 3)

- [ ] 11.1 Failing tests first: `LeakCloseTicketTest` (an open ticket of a leaked case moves to `CLOSING` once; a closed or
  archived ticket is not touched; the bot's outbox carries the closing comment; no new relay label).
- [ ] 11.2 Transition in `discord/ticket` called from the leak CTE's application path (after the statement, idempotent;
  a failure is logged and counted, never failing the leak). Document the decision in the rule file in PR 12.
- [ ] 11.3 If the team decides against the Discord change (open question 5), close this PR without merging and note it.

## 12. Rules and archive (PR 12, `docs(case)`; after PRs 2 to 10)

- [ ] 12.1 Update `.claude/rules/case-timeline.md` (leak close, allegation, case file events), `evidence.md` (provenance,
  presentable kinds), `public-api.md` (case file routes, `closing`), `banid.md` (leak close in one statement). Add a
  path-scoped rule `.claude/rules/canned-reasons.md` for `cannedreason/**`. Keep each rule short (the CLAUDE.md lean rule).
- [ ] 12.2 Sync the delta specs into `openspec/specs/` only after every PR of this change is merged; then archive with
  `docs(openspec): archive structure-case-evidence` as a separate commit (archive commits are separate unless shipped with
  the implementation).

## 13. Open the pull requests

- [ ] 13.1 For each PR 1 to 12, in order, after its branch is pushed and the main session has reviewed its diff and test
  results: `gh pr create --base <previous PR branch or main> --title "<the Conventional Commit title of that PR>"`. The
  titles are: PR 1 `docs(openspec): propose a structured case with evidence from both sides`; PR 2 `feat(case): add canned
  reasons for closing, decisions, replies and case files`; PR 3 `feat(case): close the case and forfeit the appeal when a
  Ban ID is leaked`; PR 4 `feat(case): add the case model read API and the evidence picker source`; PR 5 `feat(case): present
  a case file to the player`; PR 6 `feat(case): accept player counter-evidence per case file item`; PR 7 `feat(web): add the
  canned reasons page and pickers`; PR 8 `feat(web): lay out the case by section with attributed evidence`; PR 9 `feat(web):
  add the evidence picker and the threaded case file`; PR 10 `feat(portal): show the case file and accept counter-evidence`;
  PR 11 `feat(discord): move an open ticket to closing when a Ban ID is leaked`; PR 12 `docs(case): document the case
  structure and canned reasons`. Each PR body ends with the session link line. Do not merge.
