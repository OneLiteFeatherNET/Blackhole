## Conventions for every PR

- Branch from the up-to-date `origin/main`, never from the checkout. One PR per group below.
- Red, then green, then refactor: each group starts with the failing test named in it.
- F.I.R.S.T.: no `Thread.sleep`, no system time (inject `java.time.Clock`), no shared static state,
  fresh fixtures per test, one behaviour per test.
- Commit per type. Do not add a Co-Authored-By trailer. Migration number is the next free Flyway
  version at implementation time, after the `add-kick` migration. Use no fixed number from this document.
- Never edit an applied Flyway migration. Never use `--force` or `--no-verify`.
- Prerequisite: `add-kick` archived (see `proposal.md`, Prerequisite).

## PR 1: `feat(punishment): add the warning kind and warning template step`

Branch `feat/warning-model`. Backend only, no route.

- [ ] 1.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/StepSelectorWarningTest.java`:
  a warning in the group counts towards the next step (one warning, steps `WARN` then `KICK`, selects index 1).
- [ ] 1.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/WarnWindowCountTest.java`:
  with a template `warnWindow` of P30D, a warning 31 days old does not count, a warning 29 days old does, and
  a kick 31 days old still counts within the escalation window (fixed `Clock`).
- [ ] 1.3 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/TemplateValidatorWarnTest.java`:
  `WARN` without duration is valid; `WARN` with a duration is refused (400); `PUNISH` without duration is refused.
- [ ] 1.4 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/ActiveReadExcludesWarningTest.java`:
  a warning is not in `JdbcActivePunishmentReader`, not expired by `ActivePunishmentExpirer`, not counted by
  `hasActivePermanent`.
- [ ] 1.5 Add `backend/src/main/resources/db/migration/V<next>__warnings.sql` (next free version, after `add-kick`):
  replace the CHECK on `punishments.kind` with `('PUNISHMENT','KICK','WARN')`; `ADD COLUMN acknowledged_at BIGINT NULL`;
  index `(owner, kind, acknowledged_at)`; replace the CHECK on the template step `kind` with
  `('PUNISH','KICK','WARN')`; `ADD COLUMN warn_window VARCHAR(32) NULL` on the template table. Read the
  constraint names from the `add-kick` migration; do not edit it.
- [ ] 1.6 Add `WARN` to `PunishmentKind.java` and `StepKind.java`; add `acknowledgedAt` to `PunishmentEntity.java`;
  add `warnWindow` (nullable `Duration`) to the template entity and `PunishmentTemplateService` input and output.
- [ ] 1.7 `TemplateValidator.java`: the rules of 1.3; a `warnWindow` must parse as a positive ISO-8601 duration.
- [ ] 1.8 `PunishmentRepository.java`: add `countWarningsByOwnerAndEscalationGroupAndLiftedFalseAndCreatedAtGreaterThanEquals`;
  `PunishmentApplicationService.plan` sums non-warning and warning counts as described in `design.md` decision 3.
- [ ] 1.9 Tests green: `./gradlew :backend:test --tests "*punishment*"`, then the full `./gradlew :backend:test`.

Commit: `feat(punishment): add the warning kind and warning template step`.

## PR 2: `feat(punishment): add the warning routes, acknowledgement and NATS events`

Branch `feat/warning-api`. Depends on PR 1.

- [ ] 2.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/warning/WarningAcknowledgementTest.java`:
  a first acknowledgement sets `acknowledged_at`; a repeat keeps the first value and answers 204; a PROXY call
  for another owner answers 404 and changes nothing.
- [ ] 2.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/warning/WarningPendingReadTest.java`:
  the pending read returns only unacknowledged warnings of the owner, oldest first, with the cursor envelope and
  `limit` defaulted.
- [ ] 2.3 Create the package `backend/src/main/java/net/onelitefeather/blackhole/backend/warning/` mirroring `kick/`:
  `controller/WarningApi.java` (OpenAPI annotations, `GET /warn/{id}`, `GET /warn/pending`,
  `POST /warn/{id}/acknowledge`), `controller/WarningController.java` (thin), `dto/WarningDTO.java` (sealed
  marker: `Response`, `Pending`, `Acknowledge`, `Error`), `service/WarningService.java` (`@Singleton`, returns the
  `Error` variant), `WarningStore.java` and `JdbcWarningStore.java`.
- [ ] 2.4 Red then implement `backend/src/test/java/net/onelitefeather/blackhole/backend/warning/WarningEventsNoLeakTest.java`:
  the `punishment.warned` payload carries the owner hash and the warning id, and no reason text, name, UUID or IP
  (scan of the published payload, like `KickEventsNoLeakTest`).
- [ ] 2.5 Events: publish `punishment.warned` after the write in `warning/` (or in the apply path for `WARN` steps),
  inside a try/catch; a failure is logged without the reason text and does not fail the request. Add the subject to
  `events/EventSubjects` (the `punishment.*` wildcard already covers it; check the consumers in `EventConsumers`).
- [ ] 2.6 Case: `CaseTimeline.warned(...)` (`punishment.warned`, dedupe `warned:<id>`) and
  `CaseTimeline.acknowledged(...)` (`punishment.acknowledged`, dedupe `acknowledged:<id>`) with payloads in
  `CaseEventPayload.java` and `CaseDrafts` (live flow and reconciler). Red first:
  `backend/src/test/java/net/onelitefeather/blackhole/backend/casetimeline/WarnedEventTest.java`. A recording
  failure never fails the write.
- [ ] 2.7 Client spec: next free `client/specs/blackhole-api-*.yml`; add `WARN` to the punishment and template step kind
  enums, the warn routes and schemas, `acknowledged_at` on the punishment schema; set `inputSpec` in
  `client/build.gradle.kts`. Run `./gradlew :client:openApiGenerate` and `./gradlew :client:compileJava`. Do not edit
  generated sources.
- [ ] 2.8 `application.yml`: nothing new for the warning path; add a comment that warnings use the escalation window
  unless a template sets `warnWindow` (documentation only, per the env-var convention).
- [ ] 2.9 Tests green: `./gradlew :backend:test` and `./gradlew :client:compileJava`.

Commit: `feat(punishment): add the warning routes, acknowledgement and NATS events`.
Body notes: `BREAKING` for the generated client (enum values), see `design.md` decision 10.

## PR 3: `feat(template): apply warning steps with warning window and ELO cost`

Branch `feat/warning-template`. Depends on PR 2.

- [ ] 3.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/WarningTemplateStepTest.java`:
  applying a template whose step 1 is `WARN` (`eloCost` 5) creates a warning row, not in the active slot, changes the
  score by 5 once, and a second application within the window selects step 2 (`KICK`).
- [ ] 3.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/WarningWithoutEloCostTest.java`:
  a `WARN` step with `eloCost` 0 changes no score and records no ELO event.
- [ ] 3.3 `PunishmentApplicationService.java`: a `WARN` step goes to the warning path (no `placeAndRecord`, no slot),
  scope and target as for kicks, ELO through the existing template path.
- [ ] 3.4 Tests green: `./gradlew :backend:test --tests "*punishment*" --tests "*warning*"`.

Commit: `feat(template): apply warning steps with warning window and ELO cost`.

## PR 4: `feat(velocity): add the warn command, warning delivery and acknowledgement`

Branch `feat/warning-velocity`. Depends on PR 2 (client bump merged) and on the NATS subscriber of
`switch-event-bus-to-nats` being on `main`; if it is not, stop and report.

- [ ] 4.1 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/command/WarnCommandParsingTest.java`:
  `/warn <player> <template>` parses both arguments; a missing template is refused; the permission is `blackhole.warn`.
- [ ] 4.2 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/enforcement/WarningDeliveryTest.java`:
  a `punishment.warned` event for a connected owner shows the warning text once (stub player facade, no server);
  an owner not connected here does nothing; a failed read of the text shows the default English text.
- [ ] 4.3 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/enforcement/PendingWarningsOnLoginTest.java`:
  on login every pending warning is shown once; a failed pending read does not refuse the login and shows nothing.
- [ ] 4.4 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/command/WarningAckCommandTest.java`:
  `/blackhole warning ack <id>` acknowledges only for the owner of the warning (owner hash checked before the call).
- [ ] 4.5 `velocity/src/main/java/net/onelitefeather/blackhole/velocity/command/WarnCommand.java` (Cloud,
  `requiredSender = Player.class`, `@Permission("blackhole.warn")`, template suggestions as `/punish`), on the async
  pool.
- [ ] 4.6 `velocity/src/main/java/net/onelitefeather/blackhole/velocity/enforcement/WarningHandler.java`: handles
  `punishment.warned` and the login read; `velocity/.../punish/BackendGateway.java` and `HttpBackendGateway.java` gain
  `warning(id)`, `pendingWarnings(owner)` and `acknowledgeWarning(id, owner)`.
- [ ] 4.7 `velocity/.../component/WarningMessages.java`: English only, a clickable Acknowledge line that runs
  `/blackhole warning ack <id>`. Red first: `WarningMessagesTest.java` (English for a German client).
- [ ] 4.8 Subscribe to `blackhole.events.punishment.warned` in the NATS subscriber; the `punishment.*` cache path ignores
  the `warned` suffix (a warning never evicts the punishment cache). Red first: `WarningSubscriptionCacheTest.java`.
- [ ] 4.9 Tests green: `./gradlew :velocity:test`.

Commit: `feat(velocity): add the warn command, warning delivery and acknowledgement`.
Body notes: `BREAKING` for the plugin: it needs the warning-aware backend and client.

## PR 5: `feat(web): add the warn action and the warning badge`

Branch `feat/warning-web`. Depends on PR 2 (client regenerated).

- [ ] 5.1 Red: `web/tests/unit/warning/WarnDialog.spec.ts`: the template is required; submit calls the template apply route
  (not a separate warn route); the step kind result is shown.
- [ ] 5.2 Red: `web/tests/unit/history/WarningBadge.spec.ts`: warning rows get a "Warning" badge and are not counted in the
  active counter; an acknowledged warning shows its time.
- [ ] 5.3 Create `web/src/components/warning/WarnDialog.vue` (uses `MdDialog`, reuses the template apply client).
- [ ] 5.4 Add the "Warn" action to the `RowMenu` of the player row, the player page header and the case page actions.
- [ ] 5.5 History: the warning badge and the acknowledged time; no filter change unless the history is filtered by kind.
- [ ] 5.6 Tests green: `npm --prefix web test` and `npm --prefix web run typecheck`.

Commit: `feat(web): add the warn action and the warning badge`.

## PR 6: `feat(appeal): allow appeals of warnings`

Branch `feat/warning-appeal`. Depends on PR 1 and PR 2.

- [ ] 6.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/appeal/WarningAppealEligibilityTest.java`:
  a warning is appealable after `min-days-manual`, is `STANDARD`, and an appeal before it is `INELIGIBLE`.
- [ ] 6.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/appeal/WarningAppealDecisionTest.java`:
  a full lift lifts the warning without a slot, refunds `eloCost` once (`APPEAL_REFUND`), and a later escalation no
  longer counts it; a duration reduction is 400; a denial changes nothing.
- [ ] 6.3 `AppealDecisionService.java` and `AppealEligibilityService.java`: the warning branch of `design.md` decision 8.
- [ ] 6.4 Tests green: `./gradlew :backend:test --tests "*appeal*"`.

Commit: `feat(appeal): allow appeals of warnings`.

## PR 7: `docs(openspec): propose warnings`

Branch `docs/add-warnings`. This PR.

- [ ] 7.1 Add `.claude/rules/warning.md` with `paths:` frontmatter for `backend/src/main/java/**/warning/**/*.java` and
  `velocity/src/main/java/**/WarningHandler.java`: never active, never on the mirror, no reason text in events, the
  acknowledgement is PROXY only. Keep it under 60 lines.
- [ ] 7.2 Add one line to `CLAUDE.md` under "Backend architecture" pointing to `.claude/rules/warning.md`. Keep `CLAUDE.md`
  under 200 lines.
- [ ] 7.3 Run `openspec validate --all --strict`.
- [ ] 7.4 Open the code pull requests in order (PR 1 first) once the proposal PR is merged and `add-kick` is archived. Each PR
  title is its commit title, as listed in `proposal.md` (`Ships as` names the lead title). Use `gh pr create --base main`.
  Do not merge.
