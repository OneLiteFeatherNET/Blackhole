## Conventions for every PR

- Branch from the up-to-date `origin/main`. `add-join-guard` code is on `main` (V38, V40, `guard/`). Stop and report
  if it is not.
- Red, then green, then refactor: each group starts with the failing test named in it.
- F.I.R.S.T.: no `Thread.sleep`, no system time (inject `java.time.Clock`, and the zone for the night window), no real
  database in unit tests (use the in-memory fakes of `backend/src/test/.../guard/`), no shared static state, a fresh
  fixture per test, one behaviour per test, and every assertion has a message.
- Migration number is the next free Flyway version at implementation time. Never edit an applied migration.
- Never use `--force` or `--no-verify`.

## 0. Prerequisite check (no code)

- [ ] 0.1 Confirm `add-join-guard` is on `main` (`GuardSettings`, `UnattendedEvaluator`, `GuardTickJob`, V38 and V40), and
  note that its `join-guard` capability is still unarchived, so the ADDED requirements of this change are synced later.
- [ ] 0.2 Confirm the next free Flyway version (V41 on `main` at proposal time) and the newest client spec on `main`
  (0.0.64 at proposal time). Use the next free ones in tasks 1.4 and 4.1.

## 1. Migration, rules and stored settings (backend)

- [ ] 1.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/guard/GuardSettingsRulesTest.java`: the unattended
  level `OFF` is refused; `STRICT` and `LOCKDOWN` are accepted; absence threshold 4 and 241 minutes are refused, 5 and 240
  are accepted; night window start equal to end is refused; `7:00` (not `HH:mm`) is refused; time zone `+01:00`, `UTC`
  alone and `Mars/Olympus` are refused; `Europe/Berlin` is accepted; a refusal names the bound in its message.
- [ ] 1.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/guard/NightWindowScheduleTest.java` with a fixed
  `Clock` and zone: a disabled window gives null; at 00:30 with window 01:00-07:00 the next window starts at 01:00 that day;
  at 03:00 the current window (start in the past) is returned; a window `23:00-02:00` at 22:00 starts at 23:00 and at 01:00
  returns the window that began the day before.
- [ ] 1.3 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/guard/JdbcGuardSettingsStoreTest.java` against the
  test PostgreSQL the other JDBC store tests use: a missing row is seeded from the configured values; a second read does not
  overwrite a changed value; two stores seeding at once leave one row (`ON CONFLICT DO NOTHING`).
- [ ] 1.4 Add `backend/src/main/resources/db/migration/V41__join_guard_settings.sql` (or the next free number): table
  `join_guard_settings` with `CHECK (id = 1)`, the CHECK constraints for level, minutes (5 to 240), minute of day (0 to 1439)
  and the end not equal to the start; no seed row. Additive only.
- [ ] 1.5 Implement `GuardSettingsRules.java` (pure), `NightWindowSchedule.java` (pure, next window from the injected clock),
  `UnattendedSettings.java` (record, the stored values), `JdbcGuardSettingsStore.java` (read, seed on missing, write), and seed
  values from `GuardSettingsFactory` (the existing `blackhole.guard.unattended.*` keys). Keep `GuardSettings` for the presence
  TTL and the seed only.
- [ ] 1.6 Tests green: `./gradlew :backend:test --tests "*guard*"`.

## 2. Evaluator and tick read the stored settings (backend)

- [ ] 2.1 Red: change `UnattendedEvaluatorTest`: `automaticLevel` takes the settings as an argument; with the same inputs, an
  absence threshold of 5 minutes gives the unattended level after 6 minutes of absence where the old 20 minutes gave `OFF`;
  with the night window disabled, 02:00 and one minute of absence gives `OFF`.
- [ ] 2.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/guard/GuardTickSettingsTest.java` with in-memory
  fakes: a setting saved between two ticks is applied by the second tick (one tick, no wait for the snapshot poll); the tick
  reloads `GuardStateCache` so the effective level is updated in the same tick; a failing settings read keeps the last
  level and logs once.
- [ ] 2.3 Implement: `UnattendedEvaluator` takes `UnattendedSettings`; `GuardTickJob` reads the settings, reconciles, then
  reloads the cache; `GuardTransitionService.reconcileAutomatic` takes the settings.
- [ ] 2.4 Tests green: `./gradlew :backend:test --tests "*guard*"`.

## 3. Routes, audit and event (backend)

- [ ] 3.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/guard/GuardSettingsServiceTest.java`: a read seeds
  and returns the response with the next window; a valid save writes one row, one audit entry and one
  `guard.settings_changed` event; an invalid save (`OFF`, threshold 4) returns the `VALIDATION` error and writes nothing,
  publishes nothing and audits nothing; a save with the same values audits but publishes nothing.
- [ ] 3.2 Red: `GuardSettingsControllerTest.java`: each route makes exactly one service call and maps the service result to
  the status (the `Error` variant maps to its status); `PUT /guard/settings` is refused for MODERATOR, PROXY and DISCORD;
  `GET /guard/settings` is allowed for VIEWER.
- [ ] 3.3 Red: `GuardSettingsAlertPayloadTest.java` in the style of `GuardAlertPayloadTest`: the payload of
  `guard.settings_changed` contains only the whitelisted keys of `design.md` decision 6; a fixture with an IP, an owner hash
  and a player name yields none of them in the payload.
- [ ] 3.4 Implement `GuardSettingsDTO.java` (sealed: `UpdateRequest`, `Response`, `Error`), `GuardSettingsService.java`,
  `GuardSettingsApi.java` (OpenAPI annotations), `GuardSettingsController.java` (thin), `AuditAction.GUARD_SETTINGS_SET`
  (`guard.settings.set`), and the event payload record and publish call.
- [ ] 3.5 Add `GET /guard/settings` (VIEWER) and `PUT /guard/settings` (ADMIN) to the role matrix of
  `backend/src/test/java/net/onelitefeather/blackhole/backend/security/RouteCoverageIntegrationTest.java`.
- [ ] 3.6 Tests green: `./gradlew :backend:test --tests "*guard*"`. Run the full backend suite before the PR
  (`./gradlew :backend:test`, which needs Docker).

## 4. OpenAPI client (client)

- [ ] 4.1 Copy the newest spec to `client/specs/blackhole-api-0.0.65.yml` (or the next free version), add the two routes
  (`GET /guard/settings`, `PUT /guard/settings`), their schemas and the `operationId`s (`getJoinGuardSettings`,
  `updateJoinGuardSettings`), and set `inputSpec` in `client/build.gradle.kts`. The web test in 5.1 is the red step: it fails
  until the generated operations exist.
- [ ] 4.2 Run `./gradlew :client:openApiGenerate` and `./gradlew :client:compileJava`. Do not edit generated sources.

## 5. Web panel (web)

- [ ] 5.1 Red: `web/src/api/blackhole.guard.test.ts`: `joinGuardSettings()` maps the `GET /guard/settings` response to the
  settings type; `setJoinGuardSettings(body)` sends the `PUT` body unchanged and maps a `400` to the error result.
- [ ] 5.2 Red: `web/src/components/guard/guardSettings.test.ts`: the client bounds (5 to 240, start not equal to end) match the
  server numbers; the next-window formatter renders the epoch values in a given zone (fixed zone passed in, not the system
  zone); a disabled window renders no times; the zone list falls back to `Europe/Berlin` when `Intl.supportedValuesOf` is
  missing.
- [ ] 5.3 Red: `web/src/views/JoinGuardView.test.ts`: an admin sees the form and the save action; a moderator and a viewer see
  the values and no input control; choosing `LOCKDOWN` as the unattended level opens the confirmation dialog, and another
  change does not; a `400` from the server is shown in the form.
- [ ] 5.4 Red: `web/src/i18n.keys.test.ts` already checks the key sets: add the new keys to both locale files in the same test
  run, so the test fails before the keys exist.
- [ ] 5.5 Implement `web/src/components/guard/GuardSettingsForm.vue`, `web/src/components/guard/guardSettings.ts` (bounds,
  formatter, zone list), the two API methods in `web/src/api/blackhole.ts`, the section in `JoinGuardView.vue`, and the German
  and English texts in `web/src/locales/de.json` and `en.json`.
- [ ] 5.6 Tests green: `npm --prefix web run test`, then the type check and lint scripts of `web/package.json`.

## 6. Ship

- [ ] 6.1 Open the code pull request `feat(guard): adjust the unattended join guard in the panel` from a branch off the
  up-to-date `origin/main` that already contains the merged proposal. It carries the implementation only: no file under
  `openspec/`, no ticked task boxes in this change. The body follows `.github/pull_request_template.md`.
