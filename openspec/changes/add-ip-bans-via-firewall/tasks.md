## Conventions for every PR

- Branch from the up-to-date `origin/main`. Prerequisites `amend-data-protection-principle` and
  `add-player-intelligence` must be merged first; stop and report if they are not.
- Red, then green, then refactor: each group starts with the failing test named in it.
- F.I.R.S.T.: no `Thread.sleep`, no system time (inject `java.time.Clock`), no real network (the
  OPNsense client is tested against an in-process HTTP stub), no shared static state, fresh fixtures
  per test, one behaviour per test.
- Migration number is the next free Flyway version at implementation time. Never edit an applied
  migration. Never use `--force` or `--no-verify`.
- The OPNsense request body is verified in group 0 before any code depends on it.

## 0. Spike: verify the OPNsense alias API (no production code)

- [ ] 0.1 Against a lab OPNsense (not production, not a running server of the operator), create a
  Network alias, then verify with `curl` and recorded responses: `POST /api/firewall/alias_util/add/<alias>`
  with `{"address": "203.0.113.7/32"}` and with `{"address": "203.0.113.7"}`, read-back with
  `GET /api/firewall/alias_util/list/<alias>`, `POST /api/firewall/alias_util/delete/<alias>`, then
  `POST /api/firewall/alias/reconfigure`. Record the exact bodies and responses in the PR description.
- [ ] 0.2 Check whether a Network alias accepts an IPv6 `/128` and a `/48`, and whether a bare address
  without `/32` is stored as `/32`.
- [ ] 0.3 Update `design.md` decision 2 and `firewall-alias-sync` scenarios with the verified bodies
  before group 3 starts. If the spike disagrees with the spec, change the spec in this proposal PR
  first.

## 1. Data model and validation (backend)

- [ ] 1.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/ipban/IpBanNetworkTest.java`:
  a single IPv4 becomes `/32`, a single IPv6 `/128`, a CIDR with host bits is normalised with a warning,
  an IPv4 `/15` is refused (`IPBAN_RANGE_TOO_WIDE`), an IPv6 `/31` is refused.
- [ ] 1.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/ipban/IpBanProtectedNetworkTest.java`:
  loopback, link-local, RFC 1918 and unique-local addresses are refused (`IPBAN_PROTECTED`); an address
  inside an operator-added protected range is refused; a public address passes.
- [ ] 1.3 Add `backend/src/main/resources/db/migration/V<next>__ip_bans.sql` (table and partial unique
  index per `design.md` decision 1, plus `import_key` unique). Additive only.
- [ ] 1.4 Add `backend/src/main/java/net/onelitefeather/blackhole/backend/ipban/IpBanEntity.java`,
  `IpBanNetwork.java` (parsing and normalisation, pure), `IpBanValidator.java`, `IpBanSource.java`.
- [ ] 1.5 Red: `IpBanActiveRuleTest.java`: a row with `expires_at` equal to `now` is inactive; a
  lifted row is inactive; a row without expiry is active. Use a fixed `Clock`.
- [ ] 1.6 Tests green: `./gradlew :backend:test --tests "*ipban*"`.

## 2. IP ban service and routes (backend)

- [ ] 2.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/ipban/IpBanServiceTest.java`
  (fresh repository fake per test): a MODERATOR creates a 7-day ban; a MODERATOR asks for a permanent
  ban and gets the Error variant `FORBIDDEN_DURATION`; an ADMIN lifts a ban with a reason; lifting
  without a reason is refused; a second active ban for the same range returns the Error variant
  `ALREADY_BANNED`.
- [ ] 2.2 Red: `IpBanOverlapWarningTest.java`: a narrower ban inside an active wide range is accepted
  with a `warnings` entry.
- [ ] 2.3 Create the `ipban/` package: `controller/IpBanApi.java` (OpenAPI annotations: `POST /ipban`,
  `GET /ipban`, `GET /ipban/{id}`, `POST /ipban/{id}/lift`, `GET /ipban/active` with role PROXY),
  `controller/IpBanController.java` (thin: one service call per method, response mapping),
  `dto/IpBanDTO.java` (sealed marker interface: `CreateRequest`, `LiftRequest`, `Response`, `ActiveList`,
  `Error`), `service/IpBanService.java` (`@Singleton`, returns the Error variant, never throws for
  expected failures), `JdbcIpBanStore.java`.
- [ ] 2.4 Publish `ipban.created`, `ipban.lifted`, `ipban.changed` through the existing domain event
  pattern in `events/`. Red first: `IpBanEventsNoAddressTest.java` (payloads carry no address; a
  captured appender asserts a publish failure is logged without the address).
- [ ] 2.5 Red then implement `IpBanActiveEndpointTest.java`: `GET /ipban/active` returns only active
  rows; a lifted row and an expired row are absent.
- [ ] 2.6 `application.yml`: `blackhole.ipban.moderator-max-duration` (`P30D`),
  `blackhole.ipban.protected-networks` (list, empty by default plus the built-in ranges), each with an
  env-var default and a comment (per the env-var convention).
- [ ] 2.7 Tests green: `./gradlew :backend:test --tests "*ipban*"`.

## 3. Firewall adapter and reconciler (backend)

- [ ] 3.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/firewall/OpnsenseAliasClientTest.java`
  against an in-process HTTP stub: sends the verified body from task 0.1; sends the key and secret;
  times out after the configured timeout; does not log the secret; a 401 is reported as an
  authentication error, not retried.
- [ ] 3.2 Red: `AliasReconcileDiffTest.java` (pure function): missing entries are added, extra entries
  deleted, an identical set produces no write and no reconfigure, a read-back mismatch fails the run.
- [ ] 3.3 Red: `AliasReconcilerBackoffTest.java` with a fixed `Clock`: after N consecutive errors the
  next attempt waits 30 s, doubling, capped at 15 min; a success resets the backoff.
- [ ] 3.4 Red: `ProtectedNetworkAdapterGuardTest.java`: the adapter refuses to write a protected network
  even when the database holds one; `blackhole.ipban.firewall.refused` increments.
- [ ] 3.5 Implement `backend/src/main/java/net/onelitefeather/blackhole/backend/firewall/`:
  `OpnsenseAliasClient.java` (Micronaut HTTP client, base URL and credentials from config),
  `AliasReconciler.java` (desired set from `IpBanStore`, diff, write, reconfigure, read back),
  `AliasReconcileScheduler.java` (start-up run, interval, debounced `ipban.changed` listener),
  `FirewallMetrics.java` (Micrometer gauges and counters from `design.md` decision 8).
- [ ] 3.6 Implement the backoff and the `FirewallHealthIndicator` (`ipban-firewall`): UP within three
  intervals of a success, DOWN otherwise. Test: `FirewallHealthIndicatorTest.java` with a fixed clock.
- [ ] 3.7 `application.yml`: `blackhole.ipban.firewall.enabled` (default `false`), `.alias`,
  `.reconcile-interval` (`PT5M`), `.base-url`, `.api-key`, `.api-secret` from
  `BLACKHOLE_OPNSENSE_*` with empty defaults and comments.
- [ ] 3.8 Tests green: `./gradlew :backend:test --tests "*firewall*"`. No test contacts a real host.

## 4. "Also ban last IP" and the player command support (backend)

- [ ] 4.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/punishment/AlsoBanLastIpTest.java`:
  a network ban with the flag and a last address creates one `PUNISHMENT` ip ban with the punishment's
  expiry and case; a permanent punishment gives a permanent ip ban; no address on record gives the
  warning `NO_IP_ON_RECORD` and the player ban still applies; a mute with the flag is refused (400).
- [ ] 4.2 Red: `LastIpPortTest.java`: the port reads the last sighting from the `add-player-intelligence`
  package through an interface, faked in the test (no database).
- [ ] 4.3 Add the `alsoBanLastIp` field to the punishment apply request DTO and the option handling in
  `PunishmentApplicationService`, calling `IpBanService` through its interface.
- [ ] 4.4 Tests green: `./gradlew :backend:test --tests "*punishment*" --tests "*ipban*"`.

## 5. Vanilla import of `banned-ips.json` (backend)

- [ ] 5.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/imports/VanillaIpImportTest.java`:
  a valid entry becomes an `IMPORT` ip ban with its `created` time; a permanent entry has no expiry;
  an expired entry is stored lifted and creates no active ban; a malformed address is counted invalid
  and does not stop the file; a second import of the same file creates nothing (`import_key`).
- [ ] 5.2 Red: `VanillaIpImportDryRunTest.java`: `dryRun=true` computes the counts and writes nothing.
- [ ] 5.3 Red: `VanillaIpImportNoEloTest.java`: no ELO event and no escalation step for an IP import.
- [ ] 5.4 Replace the skip in `imports/service/VanillaImportService.java` (the `ipsSkipped` branch) with
  the import; extend `VanillaImportDTO.Result` with `ipsImported`, `ipsInvalid`, `ipsSkipped`.
- [ ] 5.5 Tests green: `./gradlew :backend:test --tests "*import*"`.

## 6. Retention (backend)

- [ ] 6.1 Red: `IpBanRetentionSweeperTest.java` with a fixed `Clock`: a lifted row older than
  `blackhole.ipban.retention` is purged; a lifted row inside the window stays; an active row stays.
- [ ] 6.2 Implement `IpBanRetentionSweeper.java` (`@Scheduled`, following `IpCorrelationRetentionSweeper`).
- [ ] 6.3 Tests green: `./gradlew :backend:test --tests "*ipban*"`.

## 7. OpenAPI client

- [ ] 7.1 Bump `client/specs/blackhole-api-*.yml` to the next free version, add the `/ipban` paths,
  schemas and the `alsoBanLastIp` field; set `inputSpec` in `client/build.gradle.kts`.
- [ ] 7.2 Run `./gradlew :client:openApiGenerate` and `./gradlew :client:compileJava`. Do not edit
  generated sources.

## 8. Velocity: IP ban list, login denial and `/ipban` (velocity)

- [ ] 8.1 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/enforcement/IpBanCacheTest.java`:
  a single address and a CIDR both match; an IPv6 address matches its `/48`; an expired entry does not
  match; an empty cache matches nothing.
- [ ] 8.2 Red: `IpBanLoginDecisionTest.java` with a stub cache: a match denies with the ban reason; no
  match allows; cache never loaded allows and increments `unloaded`.
- [ ] 8.3 Red: `IpBanRefreshTest.java`: a failed refresh keeps the last list; refresh runs on the
  scheduler, never on the login thread (assert the executor, no sleep).
- [ ] 8.4 Implement `velocity/.../enforcement/IpBanCache.java`, `IpBanLoginDecision.java` and the refresh
  on the NATS subscription and `blackhole.ipban.proxy.poll` (default `PT60S`). Hook into
  `listener/PlayerLoginListener.java` after the existing ban check; the screen uses the disconnect
  surface of `component/PunishmentScreens.java`.
- [ ] 8.5 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/command/IpBanCommandParsingTest.java`:
  `/ipban <player|ip> <template>` parses both forms; a player with no address on record is refused with
  a message; the permission is `blackhole.ipban`.
- [ ] 8.6 Implement `velocity/.../command/IpBanCommand.java` (Cloud, `requiredSender = Player.class`,
  async calls, no blocking on the server thread).
- [ ] 8.7 Tests green: `./gradlew :velocity:test`.

## 9. Panel (web)

- [ ] 9.1 Red: `web/tests/unit/ipban/IpBanForm.spec.ts`: an IPv4, an IPv6 and a CIDR validate; a
  protected address is refused in the form with the server's message; the expiry is required for
  MODERATOR above the limit.
- [ ] 9.2 Red: `web/tests/unit/ipban/AliasStatusTile.spec.ts`: each status (in sync, pending, DOWN) maps
  to its label and colour token.
- [ ] 9.3 Red: `web/tests/unit/player/AlsoBanLastIpOption.spec.ts`: the option shows the account count
  on that address and the "no address on record" hint.
- [ ] 9.4 Implement the IP bans page (`web/src/pages/ipbans/`), the "Ban IP" action on the player page,
  the option in the ban dialog, and the status tile on the dashboard. Admin-only firewall status fields
  shown to ADMIN and VIEWER; no address appears in any link or log line.
- [ ] 9.5 Tests green: `npm --prefix web test` and `npm --prefix web run typecheck`.

## 10. Code PR

- [ ] 10.1 Open the code pull request from the implementation branch, branched from `origin/main`
  after the proposal PR is merged. It contains groups 1 to 9 only and no file under `openspec/`.
  Title: `feat(ipban): block banned IP addresses at the firewall and on the proxy`. The body is
  written from the staff point of view (`design.md` is the source of the rules). Do not merge.
