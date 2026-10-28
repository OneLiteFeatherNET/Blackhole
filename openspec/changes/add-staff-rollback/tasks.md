## Conventions for every PR

- Branch from the up-to-date `origin/main`, never from the checkout. One PR per group below, stacked
  in the order given.
- Red, then green, then refactor: each group starts with the failing test named in it.
- F.I.R.S.T.: no `Thread.sleep`, no system time (inject `java.time.Clock`), no shared static state,
  fresh fixtures per test, one behaviour per test. The job runner is driven by calling its tick method
  directly, never by waiting for a schedule.
- Commit per type. No Co-Authored-By or session trailers. The migration number is the next free Flyway
  version at implementation time; use no fixed number from this document.
- Never edit an applied Flyway migration. Never use `--force` or `--no-verify`.

## PR 1: `feat(punishment): add the rollback preview and job model`

Branch `feat/staff-rollback-preview`. Backend only, no route.

- [ ] 1.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/rollback/RollbackSelectionTest.java`:
  a linked in-game row (`actor_oid` set) and a web row (`recorded_by_oid` set, `actor_oid` NULL) both
  match the selected staff actor; a proxy row with an unlinked account matches no human actor; a row
  outside the window does not match; a `SERVICE` selector is refused with `ROLLBACK_SELECTOR_INVALID`.
  Fixed `Clock`, fixtures built per test.
- [ ] 1.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/rollback/RollbackPreviewTest.java`:
  counts per kind and per section; an already lifted row is listed as "already inactive" and not as
  revocable; more than `max-items` returns `ROLLBACK_TOO_LARGE`; the preview writes no punishment
  change (assert the punishment rows are unchanged).
- [ ] 1.3 Add `backend/src/main/resources/db/migration/V<next>__staff_rollback.sql` as in `design.md`
  (`staff_rollback_job`, `staff_rollback_item`, CHECKs on state and on exactly one selector).
- [ ] 1.4 Create the package `backend/src/main/java/net/onelitefeather/blackhole/backend/rollback/` with
  the state enums, the JDBC selection reader (the selection query of decision 1), the preview service
  that returns the sealed Response or Error variant, and the job and item repositories.
- [ ] 1.5 Add `blackhole.rollback.preview-ttl` and `blackhole.rollback.max-items` to
  `backend/src/main/resources/application.yml` with env-var defaults and a comment each.
- [ ] 1.6 Run `./gradlew :backend:test --tests "*rollback*"`, then the full `./gradlew :backend:test`.

Commit: `feat(punishment): add the rollback preview and job model`.

## PR 2: `feat(punishment): execute the rollback as a resumable audited job`

Branch `feat/staff-rollback-execute`. Depends on PR 1.

- [ ] 2.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/rollback/RollbackRunnerIdempotencyTest.java`:
  running a tick twice revokes each frozen item once; an item whose slot already changed becomes
  `SKIPPED` with `ALREADY_INACTIVE` and does not fail the job.
- [ ] 2.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/rollback/RollbackResumeTest.java`:
  a lease held by a dead runner expires on the injected clock; a second runner resumes at the first
  `PENDING` item and never re-processes a `REVOKED` one.
- [ ] 2.3 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/rollback/RollbackRevokeReuseTest.java`:
  each item produces the same case event, evidence marking, ELO behaviour and `punishment.revoked`
  event as a manual revoke, with the admin as `revokedBy`; the case text carries the reason.
- [ ] 2.4 Refactor `PunishmentApplicationService.revoke` into the public method and an internal method
  taking owner, section, identifier, attribution, `rollbackJobId` and reason. The public behaviour is
  unchanged: the existing revoke tests stay green before any rollback code uses the internal method.
- [ ] 2.5 Implement the job runner: lease in `staff_rollback_job` (`locked_by`, `locked_until`), one
  batch per tick, explicit repository writes per item (no `@Transactional`), `FAILED` items retried
  on a second execute call.
- [ ] 2.6 Red then implement: `RollbackLinkSuspensionTest`: with `suspendLink` the link is removed with
  reason `ROLLBACK` before the first item runs; `actor_oid` of existing rows is unchanged.
- [ ] 2.7 Red then implement: `RollbackMarksTest`: a kick in the window is marked, not revoked; no
  ELO change, no `punishment.revoked`, no profile slot change.
- [ ] 2.8 Red then implement: `RollbackEventsTest`: `staff.rollback.started` and `.completed` carry
  counts and job id only; the payload contains no owner hash and no reason text; a failed publish
  does not fail the job.
- [ ] 2.9 Tests green: `./gradlew :backend:test --tests "*rollback*"` and the full backend suite.

Commit: `feat(punishment): execute the rollback as a resumable audited job`.

## PR 3: `feat(punishment): add the rollback routes and the client spec`

Branch `feat/staff-rollback-api`. Depends on PR 2.

- [ ] 3.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/rollback/controller/RollbackControllerTest.java`:
  one test per route. `POST /admin/rollback/preview` returns the sealed Response; `POST .../execute`
  without `Idempotency-Key` is 400; a MODERATOR token gets 403 on every route; a second execute on the
  same key returns the same job; cancel on a `COMPLETED` job is 409.
- [ ] 3.2 Add `RollbackApi` (OpenAPI annotations on the `*Api` interface) and a thin `RollbackController`
  that makes one service call per method and maps the result to an `HttpResponse`. DTOs follow the
  sealed marker-interface contract (`PreviewRequest`, `ExecuteRequest`, `Response`, `Error`).
- [ ] 3.3 Regenerate the OpenAPI client: bump `inputSpec` in `client/build.gradle.kts` to the next
  version after the one there at implementation time (0.0.44 today), add the spec file under
  `client/specs/`, run `./gradlew :client:openApiGenerate`. Generated sources are not edited by hand.
- [ ] 3.4 Confirm the spec contains no actor field in any request body (`verified-actor`), with a test
  that reads the generated spec.
- [ ] 3.5 Tests green: `./gradlew :backend:test` and `./gradlew :client:compileJava`.

Commit: `feat(punishment): add the rollback routes and the client spec`.

## PR 4: `feat(web): add the rollback page under the team administration`

Branch `feat/staff-rollback-web`. Depends on PR 3.

- [ ] 4.1 Red: `web/src/views/StaffRollbackView.test.ts` (component test): the preview button stays
  disabled until a staff actor and a window are chosen; the confirm step requires a justification;
  execute sends an `Idempotency-Key` header; progress polling stops at `COMPLETED` and at `CANCELLED`.
  No real timers: the poll interval is injected and the test advances a fake one.
- [ ] 4.2 Implement the step-based page on the staff accounts tab (`StaffAccountsView.vue` sibling),
  with the preview tables (counts per kind and per section, unlinked in-game list), the options and the
  progress view. EN and DE strings in `web/src/locales/en.json` and `de.json`.
- [ ] 4.3 Add the page to the admin tabs and the role gate (ADMIN only, the existing role-aware
  navigation).
- [ ] 4.4 Run the web unit suite and the i18n key test (`web/src/i18n.keys.test.ts`).

Commit: `feat(web): add the rollback page under the team administration`.

## Last task: open the code pull request

- [ ] 5.1 After this proposal PR is merged to `main`, open the code PR from PR 1 to PR 4 as a stack,
  with the backend PR titled `feat(punishment): add a rollback of one staff actor's punishments`
  (the Ships-as title in `proposal.md`). No file under `openspec/` in the code PRs. Tick the tasks
  only in the follow-up PR, never in the code PR.
