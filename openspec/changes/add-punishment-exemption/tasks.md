## Conventions for every PR

- Branch from the up-to-date `origin/main`, never from the checkout. One PR per group below, stacked
  in the order given.
- Red, then green, then refactor: each group starts with the failing test named in it.
- F.I.R.S.T.: no `Thread.sleep`, no system time (inject `java.time.Clock`), no shared static state,
  fresh fixtures per test, one behaviour per test.
- Commit per type. No Co-Authored-By or session trailers. The migration number is the next free Flyway
  version at implementation time; use no fixed number from this document.
- Never edit an applied Flyway migration. Never use `--force` or `--no-verify`.

## PR 1: `feat(punishment): add the exemption projection and the rank rule`

Branch `feat/punishment-exemption-guard`. Backend only, no route.

- [ ] 1.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/exemption/ExemptionRankTest.java`:
  a MODERATOR actor is refused against a MODERATOR-ranked and an ADMIN-ranked exempt target; an ADMIN
  actor is allowed against a MODERATOR-ranked target and refused against an ADMIN-ranked one; an
  unlinked in-game actor is refused against any exempt target; a target with no recorded role ranks
  MODERATOR; a non-exempt target is always allowed (the existing behaviour).
- [ ] 1.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/exemption/OwnerParityTest.java`:
  the owner computed from a Minecraft UUID by the backend equals the owner computed by the proxy's function
  for the same UUID, from one shared fixture (no proxy code is loaded: the expected hash is a constant
  fixture).
- [ ] 1.3 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/exemption/PlayerExemptionProjectionTest.java`:
  a `staff-link.created` upserts a `LINKED_STAFF` row; `staff-link.removed` deletes it; a permission report
  replaces the `PERMISSION` row; a report with no flag deletes it; the projection holds no raw UUID.
- [ ] 1.4 Add `backend/src/main/resources/db/migration/V<next>__punishment_exemption.sql` as in `design.md`
  (`player_exemption`, `exemption_review`, `staff_actor.last_role`).
- [ ] 1.5 Create the package `backend/src/main/java/net/onelitefeather/blackhole/backend/exemption/` with the
  projection repository, the `ExemptionGuard` (returns an allowed result or the `TARGET_EXEMPT` Error
  variant, never throws for the refusal), and the rank enum (`VIEWER`, `MODERATOR`, `ADMIN`).
- [ ] 1.6 Write `staff_actor.last_role` from the token's highest role on each write (`StaffActorService`),
  with a test that a body field cannot change it.
- [ ] 1.7 Add `blackhole.exemption.enabled` (default true) to `backend/src/main/resources/application.yml`
  with a comment that it is an emergency switch only.
- [ ] 1.8 Tests green: `./gradlew :backend:test --tests "*exemption*"`, then the full `./gradlew :backend:test`.

Commit: `feat(punishment): add the exemption projection and the rank rule`.

## PR 2: `feat(punishment): refuse exempt targets in punishment writes`

Branch `feat/punishment-exemption-writes`. Depends on PR 1.

- [ ] 2.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/exemption/ApplyExemptionTest.java`:
  applying a ban or mute, a template and a kick to an exempt target by a lower-ranked actor returns
  `TARGET_EXEMPT` and writes no punishment, no case event and no ELO event (assert the repositories are
  unchanged).
- [ ] 2.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/exemption/OverrideTest.java`:
  an ADMIN override with a justification writes the punishment and records `exemptionOverride` and publishes
  `punishment.exemption_overridden`; an override without a justification is 400; a MODERATOR override is
  refused.
- [ ] 2.3 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/exemption/ProxyWriteTest.java`:
  a proxy write with `onBehalfOf` of a linked, exempt staff member is refused; the override is not accepted
  on a proxy-delegated write.
- [ ] 2.4 Wire `ExemptionGuard` into `PunishmentApplicationService.applyForRule`, the template apply path and
  the kick service, before any write. The guard is skipped for a non-exempt target with no extra query.
- [ ] 2.5 Add the optional `exemptionOverride` field to the apply, punish and kick request DTOs and the
  `TARGET_EXEMPT` error variant (sealed DTO contract).
- [ ] 2.6 Tests green: `./gradlew :backend:test --tests "*exemption*" --tests "*punishment*" --tests "*kick*"`.

Commit: `feat(punishment): refuse exempt targets in punishment writes`.

## PR 3: `feat(elo): turn automatic punishments of exempt players into review items`

Branch `feat/punishment-exemption-elo`. Depends on PR 2.

- [ ] 3.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/exemption/ExemptEloThresholdTest.java`
  a soft crossing for an exempt target changes the score, publishes
  `elo.threshold_crossed` with `exempt: true`, creates one `OPEN` review and applies no punishment; a
  non-exempt target keeps the existing automatic punishment (regression test on the unchanged path).
- [ ] 3.2 Red: `ExemptionReviewDecisionTest`: an ADMIN apply creates the punishment through the normal path
  with the override recorded and sets the review `APPLIED`; a dismiss sets `DISMISSED` with a justification
  and writes nothing else; a second decision on the same review is 409.
- [ ] 3.3 Implement the review creation in the ELO threshold path (`elo/service/EloService.java`) through
  the exemption service, and the decision service with its routes.
- [ ] 3.4 Tests green: `./gradlew :backend:test --tests "*elo*" --tests "*exemption*"`.

Commit: `feat(elo): turn automatic punishments of exempt players into review items`.

## PR 4: `feat(punishment): add the exemption read and review routes and the client spec`

Branch `feat/punishment-exemption-api`. Depends on PR 3.

- [ ] 4.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/exemption/controller/ExemptionControllerTest.java`:
  `GET /exemption/{owner}` returns the badge data (`LINKED_STAFF`, `PERMISSION`, sections) and 404 for an
  unknown owner; `POST /exemption/report` accepts only the `PROXY` role (403 otherwise) and an owner that is
  a 128-character hash; review list and decision routes are ADMIN-only.
- [ ] 4.2 Add `ExemptionApi` (OpenAPI annotations on the `*Api` interface) and thin controllers, one service
  call per method.
- [ ] 4.3 Regenerate the OpenAPI client: bump `inputSpec` in `client/build.gradle.kts` to the next free
  version after the one there at implementation time (0.0.44 today), add the spec under `client/specs/`,
  run `./gradlew :client:openApiGenerate`.
- [ ] 4.4 Tests green: `./gradlew :backend:test` and `./gradlew :client:compileJava`.

Commit: `feat(punishment): add the exemption read and review routes and the client spec`.

## PR 5: `feat(velocity): report exemption flags and show the exemption note`

Branch `feat/punishment-exemption-velocity`. Depends on PR 4 and on the regenerated client.

- [ ] 5.1 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/exemption/ExemptionFlagsTest.java`:
  the flags are read from `blackhole.exempt` and `blackhole.exempt.<section>` through a fake permission
  subject (no LuckPerms, no server); no flag means an empty report.
- [ ] 5.2 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/exemption/ExemptionRefusalMessageTest.java`:
  a `TARGET_EXEMPT` answer produces the refusal message with the exemption note, in the existing message
  style; the message contains no owner hash.
- [ ] 5.3 Implement the report in the login listener and in the enforcement request path, the pre-check in
  the staff commands, and the badge in the staff messages (`Permissions`-style view, no decision taken on the
  proxy).
- [ ] 5.4 Tests green: `./gradlew :velocity:test`.

Commit: `feat(velocity): report exemption flags and show the exemption note`.

## PR 6: `feat(web): show the exemption badge, the override and the review queue`

Branch `feat/punishment-exemption-web`. Depends on PR 4.

- [ ] 6.1 Red: `web/src/components/ExemptionBadge.test.ts`: the badge shows "Linked staff" or "Exempt" with
  the role tooltip; no owner hash in the DOM.
- [ ] 6.2 Red: `web/src/components/ExemptionOverride.test.ts`: the override field is shown only for ADMIN,
  requires a justification, and sends `exemptionOverride` only when filled.
- [ ] 6.3 Red: `web/src/views/ExemptionReviewsView.test.ts`: apply and dismiss actions, the justification,
  the disabled state after a decision.
- [ ] 6.4 Implement the badge on the player page and the case page, the override in the apply, punish and
  kick dialogs, and the review queue entry. EN and DE strings in `web/src/locales/en.json` and `de.json`.
- [ ] 6.5 Run the web unit suite and `web/src/i18n.keys.test.ts`.

Commit: `feat(web): show the exemption badge, the override and the review queue`.

## Last task: open the code pull request

- [ ] 7.1 After this proposal PR is merged to `main`, open the code PRs from PR 1 to PR 6 as a stack, with
  the backend PR titled `feat(punishment): add rank protection and punishment exemption` (the Ships-as
  title in `proposal.md`). No file under `openspec/` in the code PRs. Tick the tasks only in the follow-up
  PR, never in the code PR.
