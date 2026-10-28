## Conventions for every PR

- Branch from the up-to-date `origin/main`, never from the checkout. One PR per group below.
- Red, then green, then refactor: each group starts with the failing test named in it.
- F.I.R.S.T.: no `Thread.sleep`, no system time (inject `java.time.Clock`), no shared static state,
  fresh fixtures per test, one behaviour per test.
- Commit per type. Do not add a Co-Authored-By trailer. Migration number is the next free Flyway
  version at implementation time. Use no fixed number from this document.
- Never edit an applied Flyway migration. Never use `--force` or `--no-verify`.
- Prerequisite: `standardize-api-pagination-and-problems` merged (ETag, If-Match, Idempotency-Key). If it is
  not, stop and report; do not re-implement its rules here.

## PR 1: `feat(punishment): add the amendment record and the punishment version`

Branch `feat/amendment-model`. Backend only, no route.

- [ ] 1.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/AmendmentRulesTest.java`:
  shorten only a temporary punishment to a later, future expiry; extend only to a later expiry; make permanent;
  make temporary needs a future expiry; an empty amendment is refused; a past expiry is refused (fixed `Clock`).
- [ ] 1.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/AmendmentRoleRulesTest.java`:
  MODERATOR may shorten and change reason or template; MODERATOR is refused extend, make permanent and make
  temporary; ADMIN may do all.
- [ ] 1.3 Add `backend/src/main/resources/db/migration/V<next>__punishment_amendment.sql`: `punishments` gets
  `version BIGINT NOT NULL DEFAULT 0`; `CREATE TABLE punishment_amendment` (`id UUID PK`,
  `punishment_identifier` FK, `actor_oid UUID`, `justification VARCHAR(500)`, `before_state` and `after_state`
  JSON text, `version_after BIGINT`, `created_at BIGINT`); index `(punishment_identifier, created_at)`.
- [ ] 1.4 `PunishmentEntity.java`: `version` field; every write path that changes the row (`revoke`, the lift in
  `AppealDecisionService`, `ActivePunishmentExpirer`, `PunishmentExpirySweeper`, the template link in
  `ImportedPunishmentService`) increments it through the same `update`. Red first:
  `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/VersionIncrementTest.java`.
- [ ] 1.5 `PunishmentAmendmentRule.java` (pure): the rules of 1.1 and 1.2, returning the Error variant reason; no
  repository call.
- [ ] 1.6 Tests green: `./gradlew :backend:test --tests "*punishment*"`, then the full `./gradlew :backend:test`.

Commit: `feat(punishment): add the amendment record and the punishment version`.

## PR 2: `feat(punishment): add the amendment routes and the amendment audit`

Branch `feat/amendment-api`. Depends on PR 1.

- [ ] 2.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/AmendmentConditionalRequestTest.java`:
  `GET /punishment/{identifier}` returns `ETag: "<version>"`; a POST with a stale `If-Match` answers 412
  `PRECONDITION_FAILED` with the current `ETag` and changes nothing; a missing `If-Match` answers 428.
- [ ] 2.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/AmendmentConcurrencyTest.java`:
  two amendments with the same version give one 201 and one 412 (integration, Postgres, no sleeps).
- [ ] 2.3 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/AmendmentIdempotencyTest.java`:
  a repeat `Idempotency-Key` with the same body writes nothing and answers the original 201; a different body answers 422
  `IDEMPOTENCY_KEY_INVALID`.
- [ ] 2.4 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/AmendmentEventsNoLeakTest.java`:
  the `punishment.updated` payload carries no IP, no raw UUID and no justification text (captured publisher).
- [ ] 2.5 Create `backend/src/main/java/net/onelitefeather/blackhole/backend/punishment/amendment/` with
  `controller/AmendmentApi.java` (OpenAPI annotations; `GET /punishment/{identifier}`,
  `POST /punishment/{identifier}/amendments`, `GET /punishment/{identifier}/amendments`),
  `controller/AmendmentController.java` (thin), `dto/AmendmentDTO.java` (sealed marker: `CreateRequest`, `Response`,
  `ListItem`, `Error`), `service/AmendmentService.java` (`@Singleton`, returns the Error variant, never throws for expected
  failures), `JdbcAmendmentStore.java`.
- [ ] 2.6 The write: one conditional update `WHERE version = :expected` (`If-Match`), then the amendment row, then
  `punishment.updated` after the write (try/catch: a failure is logged without the justification and does not fail the
  request). Evict the profile cache.
- [ ] 2.7 Case: `CaseTimeline.amended(...)` (`punishment.amended`, dedupe `amended:<amendmentId>`) with payload
  `Amended(before, after, justification)` in `CaseEventPayload.java` and `CaseDrafts` (live flow and reconciler). Red first:
  `backend/src/test/java/net/onelitefeather/blackhole/backend/casetimeline/AmendedEventTest.java`. A recording failure never
  fails the amendment.
- [ ] 2.8 Client spec: next free `client/specs/blackhole-api-*.yml`; the three routes, `version` on the punishment schema,
  the amendment schemas; set `inputSpec` in `client/build.gradle.kts`. Run `./gradlew :client:openApiGenerate` and
  `./gradlew :client:compileJava`. Do not edit generated sources.
- [ ] 2.9 Update the D17 table of `standardize-api-pagination-and-problems` in that change folder to list the punishment
  (only if that change is still open; otherwise note it in the PR body).
- [ ] 2.10 Tests green: `./gradlew :backend:test` and `./gradlew :client:compileJava`.

Commit: `feat(punishment): add the amendment routes and the amendment audit`.

## PR 3: `feat(elo): reset the score when a permanent punishment is made temporary`

Branch `feat/amendment-elo`. Depends on PR 2.

- [ ] 3.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/AmendmentEloTest.java`:
  a permanent punishment made temporary by an ADMIN resets the score to the pardon score once (`PARDON_RESET`, the appeal's
  metadata); a shortening of a temporary punishment changes no score and writes no ELO event; an extension and a reason
  change change no score.
- [ ] 3.2 `AmendmentService.java`: call `EloScoring.resetToPardon` only for the permanent-to-temporary change, with the
  same metadata keys as `AppealDecisionService.applyDecision`.
- [ ] 3.3 Tests green: `./gradlew :backend:test --tests "*amendment*" --tests "*elo*"`.

Commit: `feat(elo): reset the score when a permanent punishment is made temporary`.

## PR 4: `feat(velocity): apply amended punishments and add the amend command`

Branch `feat/amendment-velocity`. Depends on PR 2 (client bump merged).

- [ ] 4.1 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/command/AmendCommandParsingTest.java`:
  `/blackhole <user> amend duration <1d|permanent> <justification>` and `/blackhole <user> amend reason <template>
  <justification>` parse; a justification under 10 characters is refused before the call.
- [ ] 4.2 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/command/AmendCommandFlowTest.java`: the command
  reads the `ETag` first, then posts with `If-Match`; a 412 shows the "changed by someone else" line and nothing is posted
  twice (stub gateway, no server).
- [ ] 4.3 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/enforcement/AmendedBanReenforcementTest.java`:
  for an online player under a network ban, a permanent or extended amendment disconnects with the updated screen; a
  shortening does not; a mute amendment changes nothing until the next message.
- [ ] 4.4 `velocity/src/main/java/net/onelitefeather/blackhole/velocity/command/AmendCommand.java` (Cloud, `requiredSender =
  Player.class`, `@Permission("blackhole.amend")`), on the async pool. `velocity/.../punish/BackendGateway.java` and
  `HttpBackendGateway.java` gain `punishmentWithEtag(identifier)` and `amend(identifier, ifMatch, body, idempotencyKey)`.
- [ ] 4.5 `velocity/.../enforcement/EnforcementActions.java` or a new `AmendmentHandler.java`: on `punishment.updated`, call
  the existing `punishment(payload, true)` mirror path (already wired) and, for an online player affected by a ban
  amendment, the existing new-ban enforcement with the amended payload. Red first: the test of 4.3.
- [ ] 4.6 Tests green: `./gradlew :velocity:test`.

Commit: `feat(velocity): apply amended punishments and add the amend command`.
Body notes: the amend command needs the client of PR 2; the plugin's release note says so.

## PR 5: `feat(web): add the amend dialog and the amendment history`

Branch `feat/amendment-web`. Depends on PR 2 (client regenerated).

- [ ] 5.1 Red: `web/tests/unit/amendment/AmendDialog.spec.ts`: before and after are shown; submit disabled under 10 characters
  of justification; ADMIN-only options are hidden for MODERATOR; the request carries `If-Match` from the read.
- [ ] 5.2 Red: `web/tests/unit/amendment/AmendmentConflict.spec.ts`: a 412 shows the conflict message and reloads the
  punishment (fake timers, no network).
- [ ] 5.3 Create `web/src/components/amendment/AmendDialog.vue` (`MdDialog`), `web/src/components/amendment/AmendmentList.vue`
  (cursor pagination, `Link` next).
- [ ] 5.4 Add the "Amend" action to the active punishment on the player page and to the case page actions.
- [ ] 5.5 Tests green: `npm --prefix web test` and `npm --prefix web run typecheck`.

Commit: `feat(web): add the amend dialog and the amendment history`.

## PR 6: `docs(openspec): propose amending a punishment`

Branch `docs/add-punishment-amendment`. This PR.

- [ ] 6.1 Add `.claude/rules/amendment.md` with `paths:` frontmatter for `backend/src/main/java/**/punishment/amendment/**/*.java`
  and `velocity/src/main/java/**/AmendCommand.java`: the rules of `design.md` decisions 1, 2 and 5 in short form (`If-Match`
  required, justification mandatory, no ELO change except the pardon reset). Under 60 lines.
- [ ] 6.2 Add one line to `CLAUDE.md` under "Backend architecture" pointing to `.claude/rules/amendment.md`. Keep `CLAUDE.md`
  under 200 lines.
- [ ] 6.3 Run `openspec validate --all --strict`.
- [ ] 6.4 Open the code pull requests in order (PR 1 first) once the proposal PR is merged and the prerequisite is merged. Each
  PR title is its commit title. Use `gh pr create --base main`. Do not merge.
