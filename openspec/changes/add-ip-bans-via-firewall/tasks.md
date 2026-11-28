## Conventions for every PR

- Branch from the up-to-date `origin/main`. Prerequisites `amend-data-protection-principle`,
  `add-player-intelligence`, `add-vpn-proxy-detection` and `add-join-guard` must be merged first; stop
  and report if they are not.
- Red, then green, then refactor: each group starts with the failing test named in it.
- F.I.R.S.T.: no `Thread.sleep`, no system time (inject `java.time.Clock`), no real network (the feed
  is tested with in-process HTTP calls and in-memory fakes), no shared static state, fresh fixtures
  per test, one behaviour per test.
- Migration number is the next free Flyway version at implementation time. Never edit an applied
  migration. Never use `--force` or `--no-verify`.
- Group 3 does not wait for group 0. The comments switch is built in; group 0 decides only its default and the operator note.

## 0. Spike: verify the OPNsense URL Table behaviour (no production code)

- [ ] 0.1 On a lab OPNsense (not production), create two URL Table (IPs) aliases, each pointing at a
  local stub HTTP server that serves a fixed text file. Record the firewall version and, for each
  check, the exact file served and the alias contents shown by the firewall afterwards:
  - `#` comment lines: ignored, or does the load fail or skip entries?
  - basic auth in the URL (`https://user:pass@host/...`): loads or refused?
  - the table entry limit (pf table-entries) against the expected size of each feed, using the largest
    ASN networks in the GeoIP database as the estimate for `/networks`;
  - the refresh at a one-minute cron job "Update and reload firewall aliases": does a change appear
    within about two minutes?
  - IPv6 bare addresses in `/ips`, and IPv4 and IPv6 CIDRs in `/networks`: does `URL Table (IPs)` load
    them, or is another type needed?
  - an empty body: does the table empty (intended) or keep its content?
  - HTTP 503 and 404 from the stub: does the table keep its last content?
  - a manual entry added to the table: overwritten on the next refresh?
- [ ] 0.2 Record the results in the PR description, with the file served for each check.
- [ ] 0.3 Record the comments default and the operator note in `design.md` ("Spike results") and in the operator note (task 3.11). No finding gates group 3. A finding about the table on a 503 or an empty body is written into the operator note.

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

## 3. Firewall feeds (backend)

Does not wait for group 0. Group 0 decides only the comments default and the operator note.

- [ ] 3.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/firewall/FeedTokenRegistryTest.java`:
  a configured client's hash file is read at start-up; a token matches its hash; an unknown token
  matches nothing; with two hash files for one client (`<name>` and `<name>.next`) both tokens match;
  a hash file that is missing or malformed is a start-up error outside `dev` and `test`.
- [ ] 3.2 Red: `FeedTokenGenerationTest.java`: a generated token is at least 32 bytes from a seeded
  `SecureRandom` and encodes as base64url without padding; the stored value is the SHA-256 hex of the
  token string.
- [ ] 3.3 Red: `FeedAccessDenialTest.java`: an unknown token, an unknown path and a source address
  outside `blackhole.firewall.feed.allowed-sources` each return 404 with an empty body, and the three
  responses are identical. An IP and a CIDR in the allow-list both pass; a host name is refused at
  start-up.
- [ ] 3.4 Red: `FeedRenderTest.java` (pure): the `/ips` body has one bare address per line, IPv4 before
  IPv6, sorted; the `/networks` body is sorted and holds each network once; a comment line has the form
  `# id=... kind=... expires=...` and carries no player name, owner hash or reason; `?comments=false`
  produces no `#` line.
- [ ] 3.5 Red: `FeedContentTest.java`: a DENY class contributes its ranges to `/networks`, an ALLOW or FLAG
  class contributes nothing; a configured hosting ASN contributes its networks only while the HOSTING policy is `DENY`, and nothing under `FLAG`; an active ASN area
  ban contributes its networks; a country area ban contributes nothing; an expired or lifted ban is
  absent; a row that overlaps a protected network is dropped and `blackhole.ipban.feed.refused`
  increments.
- [ ] 3.6 Red: `FeedCacheTest.java` with a fixed `Clock` and a counting repository fake: a second request
  reads no database row; a change event rebuilds the feed; a failed rebuild keeps the last good feed;
  before the first successful build the route answers 503 with no body; concurrent first requests
  trigger one build.
- [ ] 3.7 Red: `FeedEtagTest.java`: the same content gives the same ETag; a request with a matching
  `If-None-Match` gets `304` and no body; changed content gives `200` with a new ETag; the comment and
  comment-free modes have different ETags; `Cache-Control` is `no-cache`.
- [ ] 3.8 Red: `FeedLoggingTest.java`: with a captured appender and a request with a valid token, neither
  the access log nor the metric tags contain the token or the raw path; the route template
  `/firewall/feed/{token}/{feed}` is what appears.
- [ ] 3.9 Implement `backend/src/main/java/net/onelitefeather/blackhole/backend/firewall/`:
  `FeedTokenRegistry.java` (reads `blackhole.firewall.feed.clients` and the hash files),
  `FeedContentSource.java` (ban rows, VPN lists and policies, hosting and area-ban networks, through
  ports), `FeedRenderer.java` (sorting, comments, protected-network check), `FeedCache.java`
  (single-flight build, invalidation on events, scheduled rebuild), `FeedController.java` with
  `FeedApi.java` (excluded from the published OpenAPI document), `FeedMetrics.java`.
- [ ] 3.10 `application.yml`: `blackhole.firewall.feed.clients` (`BLACKHOLE_FIREWALL_FEED_CLIENTS`,
  empty), `.secrets-dir` (`BLACKHOLE_FIREWALL_FEED_SECRETS_DIR`), `.allowed-sources`
  (`BLACKHOLE_FIREWALL_FEED_ALLOWED_SOURCES`, empty means any peer), `.vpn-classes`
  (`VPN,HOSTING,PROXY`), `.rebuild-interval` (`PT60S`), with env-var defaults and comments.
- [ ] 3.11 Operator note in `docs/`: the two URL Table (IPs) aliases, the cron job at one minute, the
  block rule, token rotation with `<name>.next`, the comment query, the direct-ingress rule, and that
  manual entries are overwritten on refresh.
- [ ] 3.12 Tests green: `./gradlew :backend:test --tests "*firewall*"`. No test contacts a real host.

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
  schemas and the `alsoBanLastIp` field; do not add the feed routes; set `inputSpec` in
  `client/build.gradle.kts`.
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
- [ ] 9.2 Red: `web/tests/unit/player/AlsoBanLastIpOption.spec.ts`: the option shows the account count
  on that address and the "no address on record" hint.
- [ ] 9.3 Implement the IP bans page (`web/src/pages/ipbans/`), the "Ban IP" action on the player page
  and the option in the ban dialog. No address appears in any link or log line.
- [ ] 9.4 Tests green: `npm --prefix web test` and `npm --prefix web run typecheck`.

## 10. Code PR

- [ ] 10.1 Open the code pull request from the implementation branch, branched from `origin/main`
  after the proposal PR is merged. It contains groups 1 to 9 only and no file under `openspec/`.
  Title: `feat(ipban): block banned IP addresses at the firewall and on the proxy`. The body is
  written from the staff point of view (`design.md` is the source of the rules). Do not merge.
