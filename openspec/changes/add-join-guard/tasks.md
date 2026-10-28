## Conventions for every PR

- Branch from the up-to-date `origin/main`. `add-vpn-proxy-detection` and `add-player-intelligence` must be merged
  first. Stop and report if they are not.
- Red, then green, then refactor: each group starts with the failing test named in it.
- F.I.R.S.T.: no `Thread.sleep`, no system time (inject `java.time.Clock`, and the zone for the night window), no real
  Dragonfly or database in unit tests (use in-memory fakes), no shared static state, a fresh fixture per test, one
  behaviour per test, and every assertion has a message.
- Migration number is the next free Flyway version at implementation time. Never edit an applied migration.
- Never use `--force` or `--no-verify`.

## 0. Prerequisite check (no code)

- [ ] 0.1 Confirm `add-vpn-proxy-detection` and `add-player-intelligence` are on `main`, and that `add-player-intelligence`
  provides ASN, organisation, country and playtime per owner. Stop if not.
- [ ] 0.2 Confirm the Velocity login check in `add-vpn-proxy-detection` runs after the ban checks, so the guard steps can
  be added after its ban checks without a second call.

## 1. Data model and level rules (backend)

- [ ] 1.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/guard/GuardLevelTest.java`: the effective level is
  the higher of manual and automatic (`OFF`, `STRICT`, `LOCKDOWN`); manual `LOCKDOWN` with automatic `OFF` is `LOCKDOWN`;
  manual `OFF` with automatic `STRICT` is `STRICT`.
- [ ] 1.2 Red: `GuardLevelRulesTest.java`: a `STRICT` level treats `VPN`, `HOSTING` and `PROXY` as refusals and `CLEAN` as
  allowed; a `LOCKDOWN` level refuses an unknown owner and allows a known owner; an unattended level of `OFF` is refused
  at configuration load.
- [ ] 1.3 Add `backend/src/main/resources/db/migration/V<next>__join_guard.sql`: `area_ban` (with the partial unique index
  on `(kind, value) WHERE lifted_at IS NULL`), `join_guard_state` (one seeded row, all levels `OFF`), `join_guard_change`.
  Additive only.
- [ ] 1.4 Create `guard/` (`GuardLevel.java`, `GuardLevelRules.java` (pure), `GuardState.java`, `JdbcGuardStateStore.java`,
  `JdbcGuardChangeStore.java`) and `application.yml` entries `blackhole.guard.*` with env-var defaults and a comment each:
  `unattended.absence-threshold`, `unattended.night-window`, `unattended.zone`, `unattended.level`, `lockdown.min-playtime`,
  `presence.ttl`, `summary-interval`, `burst.enabled`, `burst.threshold`, `burst.window`, `burst.ban-duration`.
- [ ] 1.5 Tests green: `./gradlew :backend:test --tests "*guard*"`.

## 2. Staff presence (backend)

- [ ] 2.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/guard/PresenceStoreTest.java` (in-memory hash fake):
  a heartbeat with count 0 makes staff absent; a count above 0 makes staff present; an entry older than the TTL is ignored
  (fixed `Clock`); two proxies with one stale and one fresh entry count only the fresh one.
- [ ] 2.2 Red: `GuardHeartbeatTest.java`: a heartbeat with a missing proxy id is refused; a heartbeat with a count above 0
  updates `guard:last-staff-seen`; a heartbeat with count 0 does not.
- [ ] 2.3 Implement `PresenceStore.java` (Dragonfly hash `guard:presence`, `guard:last-staff-seen`), `GuardPresenceService.java`,
  and the heartbeat response that carries the effective level.
- [ ] 2.4 Tests green: `./gradlew :backend:test --tests "*guard*"`.

## 3. Unattended evaluator and transitions (backend)

- [ ] 3.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/guard/UnattendedEvaluatorTest.java` with a fixed `Clock`:
  staff present gives automatic `OFF`; staff absent for 19 minutes gives `OFF`; staff absent for 20 minutes gives `STRICT`;
  staff returning clears the automatic level at once.
- [ ] 3.2 Red: `UnattendedNightWindowTest.java` with a fixed `Clock` and zone: at 01:00 local time with nobody present and
  last seen 2 minutes ago gives `STRICT`; at 03:00 with staff present gives `OFF`; at 07:00 exactly is outside the window;
  a window that crosses midnight (for example `23:00-02:00`) is handled.
- [ ] 3.3 Red: `UnattendedLevelOverrideTest.java`: an unattended level of `LOCKDOWN` set by configuration is used; a missing
  last-staff-seen value counts as long absent.
- [ ] 3.4 Red: `GuardTransitionTest.java` with an in-memory state fake that implements the conditional update: two replicas
  evaluate the same transition, exactly one update succeeds, exactly one change event and one history row are produced.
- [ ] 3.5 Implement `UnattendedEvaluator.java` (pure), `GuardTickJob.java` (`@Scheduled` every 30 seconds, injected `Clock`),
  `GuardTransitionService.java` (conditional update, publish on success only).
- [ ] 3.6 Tests green: `./gradlew :backend:test --tests "*guard*"`.

## 4. Area bans (backend)

- [ ] 4.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/areaban/AreaBanServiceTest.java`: a MODERATOR creating
  an ASN ban of 7 days succeeds; a MODERATOR creating a permanent ASN ban gets the Error variant `FORBIDDEN_DURATION`; a
  MODERATOR creating a country ban gets `FORBIDDEN_KIND`; an ADMIN creating a country ban succeeds; a second active ban on the
  same area gets `ALREADY_ACTIVE`; a reason of 0 or 201 characters gets `INVALID_REASON`.
- [ ] 4.2 Red: `AreaBanNormalizationTest.java`: `AS16276`, `as16276` and `16276` normalise to `16276`; `de` normalises to `DE`;
  a non-numeric ASN and a three-letter country code are refused.
- [ ] 4.3 Red: `AreaBanLiftTest.java`: lifting needs a reason; a lifted ban no longer matches; an expired ban no longer matches
  (fixed `Clock`); lifting an unknown id gets `NOT_FOUND`.
- [ ] 4.4 Implement `AreaBanService.java`, `AreaBanStore.java` (JDBC), the DTOs as a sealed marker interface (`AreaBanDTO` with
  `CreateRequest`, `Response`, `Error`), `AreaBanApi.java` (OpenAPI annotations) and the thin `AreaBanController.java`.
- [ ] 4.5 Tests green: `./gradlew :backend:test --tests "*areaban*"`.

## 5. Login check integration (backend)

- [ ] 5.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/guard/LoginCheckGuardOrderTest.java`: a linked staff owner
  gets `ALLOW` even under `LOCKDOWN` from an area-banned ASN; an area ban gets `DENY` with `denialReason = AREA_BAN` even for an
  allow-listed owner; an allow-listed owner under `STRICT` from hosting gets the VPN policy result, not `GUARD_STRICT`; a hosting
  address under `STRICT` gets `DENY` with `GUARD_STRICT`; an unknown owner under `LOCKDOWN` from a clean address gets `DENY` with
  `GUARD_LOCKDOWN`; a known owner under `LOCKDOWN` from a clean address gets `ALLOW`; under `OFF` the VPN policy result is unchanged.
- [ ] 5.2 Red: `LoginCheckNoDatabaseReadTest.java`: the refusal path reads only in-memory structures and the Dragonfly fake; a
  store fake that throws on any database read during the check must not fail the check.
- [ ] 5.3 Implement the guard and area-ban steps inside the login check service from `add-vpn-proxy-detection` (`intel/`), in the
  order of `design.md` decision 2, and the `denialReason` field.
- [ ] 5.4 Tests green: `./gradlew :backend:test --tests "*guard*"`.

## 6. Burst rule and summaries (backend)

- [ ] 6.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/guard/BurstRuleTest.java` with a fixed `Clock`: with the
  rule disabled, 100 refusals create no area ban; with the rule enabled, 10 refusals within 5 minutes create an `AUTO_BURST` ban with
  an expiry of 2 hours; 10 refusals spread over 6 minutes do not; refusals by area bans and by the VPN policy are not counted.
- [ ] 6.2 Red: `BurstRuleConflictTest.java`: a second insert for an ASN that already has an active ban is a no-op and publishes nothing.
- [ ] 6.3 Red: `GuardSummaryJobTest.java` with a fixed `Clock`: refusals across one window produce one summary with the top ASNs in
  order; an empty window publishes nothing; the counters are cleared after publishing.
- [ ] 6.4 Implement `BurstRule.java`, `RefusalCounter.java` (Dragonfly sorted set and hash), `GuardSummaryJob.java` (`@Scheduled`).
- [ ] 6.5 Tests green: `./gradlew :backend:test --tests "*guard*"`.

## 7. Alerting and privacy (backend)

- [ ] 7.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/guard/GuardEventPayloadTest.java`: the payload of
  `guard.level_changed`, `area_ban.created`, `area_ban.lifted` and `guard.denials.summary` contains only the whitelisted keys of
  `design.md` decision 11; a fixture with an IP, an owner hash and a player name yields no such values in any payload.
- [ ] 7.2 Red: `GuardLogPrivacyTest.java`: a captured appender records every log line emitted while a refusal, a level change and
  a burst ban are processed; no line contains the fixture IP or the owner hash.
- [ ] 7.3 Implement the event payload records and the publish calls for the four events.
- [ ] 7.4 Tests green: `./gradlew :backend:test --tests "*guard*"`.

## 8. Controllers, health and metrics (backend)

- [ ] 8.1 Red: `GuardControllerTest.java`: each route makes exactly one service call and maps the service result to the response
  (the Error variant maps to its HTTP status); `POST /guard/level` is refused for role PROXY.
- [ ] 8.2 Implement `GuardApi.java` (OpenAPI annotations), `GuardController.java` (thin), the DTOs (`GuardStateDTO`, `LevelDTO` with
  `CreateRequest`, `Response`, `Error`; `PresenceDTO`), and `GET /guard/state`, `POST /guard/level`, `POST /guard/presence`.
- [ ] 8.3 Red then implement `GuardMetricsTest.java`: `blackhole.guard.level` gauge, `blackhole.guard.refusals{reason}` counter,
  `blackhole.guard.bursts` counter, `blackhole.guard.summary.published` counter.
- [ ] 8.4 Tests green: `./gradlew :backend:test --tests "*guard*" --tests "*areaban*"`.

## 9. OpenAPI client

- [ ] 9.1 Bump `client/specs/blackhole-api-*.yml` to the next free version: `/guard/state`, `/guard/level`, `/guard/presence`
  (PROXY), `/area-bans`, `/area-bans/{id}/lift`, the additive `denialReason` on the login-check response; set `inputSpec` in
  `client/build.gradle.kts`.
- [ ] 9.2 Run `./gradlew :client:openApiGenerate` and `./gradlew :client:compileJava`. Do not edit generated sources.

## 10. Velocity: heartbeat, login decision, screens (velocity)

- [ ] 10.1 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/guard/GuardHeartbeatCounterTest.java`: the count is the
  number of online players with `blackhole.staff`; a player who leaves is not counted (stub player list, no real proxy).
- [ ] 10.2 Red: `GuardLoginDecisionTest.java` with a stub gateway: `AREA_BAN` denial shows the `AREA_BAN_DENY` screen; `GUARD_STRICT`
  shows `GUARD_STRICT_DENY`; `GUARD_LOCKDOWN` shows `GUARD_LOCKDOWN_DENY`; a `DENY` without `denialReason` shows `VPN_DENY`;
  `ALLOW` and `FLAG` join without any visible change.
- [ ] 10.3 Red: `GuardFailModeTest.java` with a stub gateway that times out: cached `OFF` and `STRICT` allow the login and increment
  `failopen`; cached `LOCKDOWN` refuses a non-staff player with `GUARD_LOCKDOWN_DENY` and increments `failclosed`; cached `LOCKDOWN`
  lets a player with `blackhole.staff` through; a fresh proxy with no cache allows the login.
- [ ] 10.4 Red: `GuardCommandParserTest.java`: `/guard` with no argument shows the status; `/guard strict` sets `STRICT`; `/guard other`
  shows usage; `/areaban asn 16276 2h reason text` parses the duration and reason; `/areaban asn 16276` without a reason is refused;
  `/areaban country DE` without `blackhole.areaban.country` permission is refused.
- [ ] 10.5 Implement `GuardHeartbeatTask.java` (every `blackhole.guard.heartbeat-interval`, default `PT30S`), the level cache,
  `GuardLoginDecision.java`, `GuardCommand.java`, `AreaBanCommand.java`, the three screens in `PunishmentScreens` with English
  fallback, and the `failopen` and `failclosed` counters.
- [ ] 10.6 Tests green: `./gradlew :velocity:test --tests "*guard*"`.

## 11. Web: join guard, area bans, dashboard (web)

- [ ] 11.1 Red: component test for the join guard page: the effective level and its source (manual, unattended, cleared) are shown;
  the level selector hides `LOCKDOWN` for a user without the MODERATOR role; the history list renders the last 100 changes with
  no IP address in any row.
- [ ] 11.2 Red: component test for the area bans page: ASN and country forms; the country option is hidden for MODERATOR; the
  permanent option is hidden for MODERATOR; the lift dialog requires a reason.
- [ ] 11.3 Red: dashboard tile test: shows the effective level and the active area ban count.
- [ ] 11.4 Implement the pages, the tile and the API calls, using the Material Design 3 tokens.
- [ ] 11.5 Tests green in the web workspace.

## 12. Discord (discord)

- [ ] 12.1 Red: `discord/src/test/java/net/onelitefeather/blackhole/discord/notify/GuardNotificationRenderTest.java`: each of the four
  events renders a message with ASN, organisation, country, level and counts; no rendered message contains an IP address, an owner
  hash or a player name.
- [ ] 12.2 Red: `GuardRoutingKeyTest.java`: `NotificationEvent.ROUTING_KEYS` includes `guard.level_changed`, `area_ban.created`,
  `area_ban.lifted` and `guard.denials.summary`; an unknown key is still ignored.
- [ ] 12.3 Red: `GuardCommandGateTest.java`: `/guard` from a user without the staff Discord role gets an ephemeral refusal and no
  backend call; with the role it calls `POST /guard/level` with `source = DISCORD`.
- [ ] 12.4 Implement the routing keys, the renderer (rendered from the payload, no backend read for these events) and the `/guard`
  command.
- [ ] 12.5 Tests green: `./gradlew :discord:test --tests "*guard*"`.

## 13. Operator note (docs)

- [ ] 13.1 Write `docs/join-guard.md`: the levels, the unattended triggers and their defaults, the night window and zone, the
  burst rule and why it is off, the fail mode table, the deploy order (Velocity first) and the rollback steps from
  `design.md` migration plan.

## 14. Open the code pull request

- [ ] 14.1 Push the implementation branch (no file under `openspec/`) and open the pull request with the title
  `feat(guard): add area bans and an unattended join guard`. Fill the sections of `.github/pull_request_template.md` with a short
  story: a small team asleep while a wave joins, and what the network does until someone returns.
