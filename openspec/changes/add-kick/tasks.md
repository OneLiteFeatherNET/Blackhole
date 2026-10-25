## Conventions for every PR

- Branch from the up-to-date `origin/main`, never from the checkout. One PR per group below.
- Red, then green, then refactor: each group starts with the failing test named in it.
- F.I.R.S.T.: no `Thread.sleep`, no system time (inject `java.time.Clock`), no shared static state,
  fresh fixtures per test, one behaviour per test.
- Commit per type. Do not add a Co-Authored-By trailer. Migration number is the next free Flyway
  version at implementation time (V27 or higher; V25 and V26 are taken). Use no fixed number from this
  document.
- Never edit an applied Flyway migration. Never use `--force` or `--no-verify`.

## PR 1: `feat(punishment): add the kick punishment kind and template step kind`

Branch `feat/kick-model`. Backend only, no route.

- [ ] 1.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/StepSelectorKickTest.java`
  asserts that a kick in the group counts towards the next step (`StepSelector.select` over two
  steps, count 1, selects index 1). Fails until the count query includes kicks (task 1.6).
- [ ] 1.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/TemplateValidatorKickTest.java`:
  `KICK` without duration is valid; `KICK` with duration is refused (400); `PUNISH` without duration
  is refused.
- [ ] 1.3 Add `backend/src/main/resources/db/migration/V<next>__kick.sql` (next free version, see the
  conventions above; never V25 or V26):
  `punishments` gets `kind VARCHAR(16) NOT NULL DEFAULT 'PUNISHMENT'`, `kick_scope VARCHAR(16)`,
  `kick_target VARCHAR(255)`, index on `(kind, owner)`, CHECK on `kind` in (`PUNISHMENT`, `KICK`);
  the template step table from `V5__template_steps_and_active_punishments.sql` gets
  `kind VARCHAR(16) NOT NULL DEFAULT 'PUNISH'` and `duration` becomes nullable; `CREATE TABLE kick_action`
  (`id UUID PK`, `scope VARCHAR(16)`, `target VARCHAR(255)`, `reason VARCHAR(200)`, `actor_oid UUID NULL`,
  `created_at BIGINT`, `deadline_at BIGINT`); `CREATE TABLE kick_delivery` (`subject_id UUID`,
  `proxy_id VARCHAR(64)`, `found BOOLEAN`, `moved BOOLEAN`, `acked_at BIGINT`,
  PRIMARY KEY (`subject_id`, `proxy_id`)).
- [ ] 1.4 Add the `kind`, `kickScope`, `kickTarget` fields to
  `backend/src/main/java/net/onelitefeather/blackhole/backend/punishment/PunishmentEntity.java`
  (defaults `PUNISHMENT`, null), and a `kind` to `TemplateStep.java` (default `PUNISH`, duration
  nullable; keep the 2-arg constructor for existing callers).
- [ ] 1.5 Add `backend/src/main/java/net/onelitefeather/blackhole/backend/punishment/PunishmentKind.java`
  (`PUNISHMENT`, `KICK`) and `StepKind.java` (`PUNISH`, `KICK`).
- [ ] 1.6 Escalation: in `PunishmentRepository.java` keep
  `countByOwnerAndEscalationGroupAndLiftedFalseAndCreatedAtGreaterThanEquals` as is (kicks count).
  Add `kind = PUNISHMENT` to every active-state read: `JdbcActivePunishmentReader.java`,
  `ActivePunishmentExpirer.java`, `PunishmentExpirySweeper.java`, `PunishmentApplicationService.hasActivePermanent`.
  Red test first: `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/ActiveReadExcludesKickTest.java`.
- [ ] 1.7 `TemplateValidator.java`: apply the rules of 1.2.
- [ ] 1.8 Tests green: `./gradlew :backend:test --tests "*punishment*"`. Run the full
  `./gradlew :backend:test` before opening the PR.

Commit: `feat(punishment): add the kick punishment kind and template step kind`.

## PR 2: `feat(punishment): add the kick routes, delivery acknowledgement and NATS events`

Branch `feat/kick-api`. Depends on PR 1.

- [ ] 2.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/kick/KickStatusTest.java`:
  a fixed `Clock`; `PENDING` before `deadlineAt`; `NOT_ONLINE` at and after `deadlineAt` with no
  positive ack; `DELIVERED` on a positive ack even before the deadline; `NOT_ON_TARGET` when only
  `found=true, moved=false` is stored.
- [ ] 2.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/kick/KickRequestValidatorTest.java`:
  reason 1..200; UUID, IP and Ban ID refused (reuse `PiiGuard`); scope SERVER/SECTION without target
  is 422 `KICK_TARGET_INVALID`; missing `Idempotency-Key` refused.
- [ ] 2.3 Create the package `backend/src/main/java/net/onelitefeather/blackhole/backend/kick/` with:
  `controller/KickApi.java` (OpenAPI annotations, routes `POST /kick`, `POST /kick/mass`,
  `GET /kick/{id}`, `POST /kick/{id}/deliveries`), `controller/KickController.java` (thin, one service
  call per method), `dto/KickDTO.java` (sealed marker interface: `CreateRequest`, `MassCreateRequest`,
  `Accepted`, `Response`, `Ack`, `Error`), `service/KickService.java` (`@Singleton`, returns the
  `Error` variant, never throws for expected failures), `KickStatus.java`, `KickValidator.java`,
  `KickDeadline.java` (pure function of `Clock`, `blackhole.kick.ack-timeout`).
- [ ] 2.4 Red then implement `backend/src/test/java/net/onelitefeather/blackhole/backend/kick/KickDeliveryIdempotencyTest.java`:
  the same proxy acknowledging twice stores one `kick_delivery` row (JDBC, the existing test support).
- [ ] 2.5 Red then implement `backend/src/test/java/net/onelitefeather/blackhole/backend/kick/KickNotActiveIntegrationTest.java`:
  a kick is not in active lists, not enforced on login, counted for escalation, and a repeated
  `Idempotency-Key` writes nothing; a kick of a player already permanently banned in the target
  section is accepted with a `warnings` entry (202, not 409) and leaves the ban unchanged.
- [ ] 2.6 Red then implement `backend/src/test/java/net/onelitefeather/blackhole/backend/kick/MassKickWritesNoPunishmentTest.java`:
  `POST /kick/mass` writes one `kick_action` and no `punishments` row, no `case_event`, no ELO event.
- [ ] 2.7 Events: add `punishment.kick` and `kick.mass` to the subject builder used by
  `backend/src/main/java/net/onelitefeather/blackhole/backend/events/` (follow the existing
  `DomainEvent` pattern); payload records `KickEvent` and `MassKickEvent` contain no reason text.
  Red first: `backend/src/test/java/net/onelitefeather/blackhole/backend/kick/KickEventsNoLeakTest.java`
  (scans published payloads for UUIDs, IPs and reason text; a captured appender asserts a publish
  failure is logged without the reason).
- [ ] 2.8 `CaseTimeline.kicked(...)` in `backend/src/main/java/net/onelitefeather/blackhole/backend/casetimeline/CaseTimeline.java`
  with dedupe key `kicked:<id>`; add `Kicked(scope, target, eloCost)` to `CaseEventPayload.java`
  and to `CaseDrafts` (both the live flow and the reconciler). Red first:
  `backend/src/test/java/net/onelitefeather/blackhole/backend/casetimeline/KickedEventTest.java`.
- [ ] 2.9 Client spec: bump to the next free `client/specs/blackhole-api-*.yml`, add `KICK` to the
  punishment and template step kind enums, add the kick paths and schemas, and set `inputSpec` in
  `client/build.gradle.kts`. Run `./gradlew :client:openApiGenerate` and `./gradlew :client:compileJava`.
  Do not edit generated sources.
- [ ] 2.10 `application.yml`: add `blackhole.kick.ack-timeout: ${BLACKHOLE_KICK_ACK_TIMEOUT:PT5S}` with
  a comment (per the env-var convention).
- [ ] 2.11 Tests green: `./gradlew :backend:test` and `./gradlew :client:compileJava`.

Commit: `feat(punishment): add the kick routes, delivery acknowledgement and NATS events`.
Body notes: `BREAKING` for the generated client (enum values), see `design.md` decision 9.

## PR 3: `feat(template): apply kick steps with ELO cost and escalation`

Branch `feat/kick-template`. Depends on PR 2.

- [ ] 3.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/KickTemplateStepTest.java`:
  applying a template whose step 1 is `KICK` (`eloCost` 10) creates a KICK row, changes the score by
  10 once, and a second application within the window selects step 2 (`PUNISH P1D`).
- [ ] 3.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/KickWithoutTemplateCostsNoEloTest.java`:
  a kick with no template and a mass kick change no score.
- [ ] 3.3 `PunishmentApplicationService.java`: a `KICK` step goes to the kick path (no
  `placeAndRecord`), scope from the request (default `NETWORK`), ELO through the existing template path.
- [ ] 3.4 Tests green: `./gradlew :backend:test --tests "*punishment*" --tests "*kick*"`.

Commit: `feat(template): apply kick steps with ELO cost and escalation`.

## PR 4: `feat(velocity): add the kick and kickall commands and the kick handler`

Branch `feat/kick-velocity`. Depends on PR 2 (client bump merged) and on the NATS subscriber of
`switch-event-bus-to-nats` being on `main`; if it is not, stop and report.

- [ ] 4.1 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/command/KickCommandParsingTest.java`:
  `/kick <user> [server|section] <reason>` parses target and reason; missing reason is refused.
- [ ] 4.2 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/enforcement/KickHandlerDecisionTest.java`
  with a stub proxy facade (no real server): not connected gives `found=false`; NETWORK disconnects
  with the kick screen; a covered server moves to the fallback; an uncovered server gives
  `NOT_ON_TARGET`; fallback equal to the target disconnects.
- [ ] 4.3 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/enforcement/FallbackMoveTest.java`:
  the server punishment path and the kick path use the same fallback rule (`moveToFallback`).
- [ ] 4.4 Extract the fallback part of `velocity/src/main/java/net/onelitefeather/blackhole/velocity/enforcement/EnforcementActions.java`
  (`moveOffCoveredServer`, around lines 89-110) into `moveToFallback(player, screenSupplier)`. This is a
  refactor inside `feat(velocity)`; the existing enforcement tests must stay green before the new code.
- [ ] 4.5 `velocity/src/main/java/net/onelitefeather/blackhole/velocity/enforcement/KickHandler.java`:
  handles `punishment.kick` and `kick.mass`, calls `BackendGateway` to read the reason, acts, then
  acknowledges. Add `kick(...)`, `kickStatus(...)`, `acknowledgeKick(...)` to
  `velocity/src/main/java/net/onelitefeather/blackhole/velocity/punish/BackendGateway.java` and
  implement them in `HttpBackendGateway.java`.
- [ ] 4.6 `velocity/src/main/java/net/onelitefeather/blackhole/velocity/command/KickCommand.java` and
  `KickAllCommand.java` (Cloud, `requiredSender = Player.class`, permissions `blackhole.kick` and
  `blackhole.kick.mass`, suggestions for players and targets). Calls run on the async pool; the
  result is polled with a delay, never on the server thread. Red first: `KickCommandAsyncTest.java`
  (no blocking call on the calling thread; the poll stops when settled).
- [ ] 4.7 `velocity/src/main/java/net/onelitefeather/blackhole/velocity/component/PunishmentScreens.java`:
  add `Surface.KICK`, English only. Red first: `PunishmentScreensKickTest.java` (German client gets English).
- [ ] 4.8 Subscribe to `blackhole.events.punishment.kick` and `blackhole.events.kick.mass` in the NATS
  subscriber (the existing class from `switch-event-bus-to-nats`); the `punishment.*` cache path
  ignores the `kick` suffix. Red first: `KickSubscriptionCacheTest.java`.
- [ ] 4.9 Tests green: `./gradlew :velocity:test`.

Commit: `feat(velocity): add the kick and kickall commands and the kick handler`.
Body notes: `BREAKING` for the plugin: it needs the kick-aware backend and client.

## PR 5: `feat(web): add the kick dialog and the mass kick in the admin area`

Branch `feat/kick-web`. Depends on PR 2 (client regenerated).

- [ ] 5.1 Red: `web/tests/unit/kick/KickDialog.spec.ts`: reason validation (empty and IP refused);
  submit sends `Idempotency-Key`; the polling stops at a settled status (fake timers, no real waits).
- [ ] 5.2 Red: `web/tests/unit/kick/KickStatusChip.spec.ts`: each status maps to its label
  (`Delivered`, `Not on that server`, `Not online`, `No proxy answered`).
- [ ] 5.3 Red: `web/tests/unit/admin/MassKickForm.spec.ts`: submit disabled until the target name is
  typed exactly; no second approval step is shown; the form is not rendered for MODERATOR.
- [ ] 5.4 Create `web/src/components/kick/KickDialog.vue` (uses `MdDialog`), `web/src/components/kick/KickStatusChip.vue`,
  `web/src/components/kick/KickStatusPoller.ts` (pure, injected timer); `web/src/pages/admin/MassKickForm.vue`
  (in the admin area route of commit d23d7ab).
- [ ] 5.5 Add the "Kick" action to the `RowMenu` of the player row and to the player page header
  (`web/src/pages/players/...`), and to the case page actions (`web/src/pages/cases/...`). Use
  `FilterToolbar` only if the history gets a kind filter; otherwise no filter change.
- [ ] 5.6 History: kick rows get a "Kick" badge and do not count in the active counter. Red first:
  `web/tests/unit/history/KickBadge.spec.ts`.
- [ ] 5.7 Tests green: `npm --prefix web test` and the type check `npm --prefix web run typecheck` (or the
  project's existing script).

Commit: `feat(web): add the kick dialog and the mass kick in the admin area`.

## PR 6: `docs(openspec): propose kicking players from the network and from servers`

Branch `docs/add-kick`. This PR.

- [ ] 6.1 Add `.claude/rules/kick.md` with `paths:` frontmatter for `backend/src/main/java/**/kick/**/*.java`
  and `velocity/src/main/java/**/enforcement/KickHandler.java`: the rules of `design.md` decisions 1,
  3 and 5 in short form (kick is never active; mass kick writes no punishment; event payloads carry no
  reason text). Keep it under 60 lines.
- [ ] 6.2 Add one line to `CLAUDE.md` under "Backend architecture" pointing to `.claude/rules/kick.md`.
  Keep `CLAUDE.md` under 200 lines.
- [ ] 6.3 Run `openspec validate --all --strict`.
- [ ] 6.4 Open the pull requests in order (PR 1 first). Each PR title is its commit title, and each body
  ends with `https://claude.ai/code/session_01TFcvDovaYNHZewdbM2f8Ru`. Use `gh pr create --base main`.
  Run `gh pr checks --watch` on each. Do not merge.
