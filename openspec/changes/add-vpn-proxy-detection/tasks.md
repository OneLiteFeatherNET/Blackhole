## Conventions for every PR

- Branch from the up-to-date `origin/main`. `add-player-intelligence` and
  `amend-data-protection-principle` must be merged first; stop and report if they are not.
- Red, then green, then refactor: each group starts with the failing test named in it.
- F.I.R.S.T.: no `Thread.sleep`, no system time (inject `java.time.Clock`), no real network (list
  downloads are tested against an in-process HTTP stub, and no test contacts GitHub), no shared static
  state, fresh fixtures per test, one behaviour per test.
- Migration number is the next free Flyway version at implementation time. Never edit an applied
  migration. Never use `--force` or `--no-verify`.

## 0. Spike: verify the X4BNet files (no production code)

- [ ] 0.1 Download `output/vpn/ipv4.txt` and `output/datacenter/ipv4.txt` once into the scratchpad, not
  into the repository. Record the line format (CIDR per line? comments?), the line count and the
  count of lines that are not CIDR.
- [ ] 0.2 Check whether `ipv6.txt` at the root or any `output/**/ipv6.txt` exists and what it contains.
- [ ] 0.3 Update `design.md` decision 2 with the verified format before group 2 starts.

## 1. Data model and classification (backend)

- [ ] 1.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/intel/IntelClassifierTest.java`:
  a hosting ASN classifies as `HOSTING`; a prefix in a `VPN` list classifies as `VPN`; an address in no
  list is `CLEAN`; an address in both a VPN and a hosting list gets both classes recorded; an IPv6
  address is classified by ASN only when no IPv6 list is enabled.
- [ ] 1.2 Red: `IntelVerdictTest.java`: the most restrictive policy wins (`DENY` over `FLAG` over
  `ALLOW`); a class with no policy row defaults to `FLAG`.
- [ ] 1.3 Add `backend/src/main/resources/db/migration/V<next>__vpn_detection.sql` (tables per
  `design.md` migration plan; seed rows for the three policies at `FLAG`). Additive only.
- [ ] 1.4 Create the `intel/` package: `IntelClassifier.java` (pure, in-memory prefix structure),
  `IntelVerdict.java`, `IntelPolicy.java`, `IntelClass.java`, `IntelListSourceConfig.java`
  (`blackhole.intel.lists[*]`), `JdbcIntelStore.java`.
- [ ] 1.5 `application.yml`: `blackhole.intel.lists` (two X4BNet sources, `enabled: false`),
  `blackhole.intel.hosting-asns` (empty), `blackhole.intel.lists.refresh-interval` (`PT6H`),
  `blackhole.intel.login-check.timeout` (`PT1S`), `blackhole.intel.hit-retention` (`P90D`), each with an
  env-var default and a comment.
- [ ] 1.6 Tests green: `./gradlew :backend:test --tests "*intel*"`.

## 2. List refresh with atomic generation swap (backend)

- [ ] 2.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/intel/ListFetcherSsrfTest.java`:
  an `http://` URL is refused; a URL resolving to loopback, link-local, private, unique-local or metadata
  addresses is refused; a redirect to another host is refused; a body over 20 MB is refused.
- [ ] 2.2 Red: `ListParserTest.java`: valid CIDR lines are kept; comment and blank lines are ignored; an
  invalid line is counted; more than 5% invalid fails the source; an empty body fails the source.
- [ ] 2.3 Red: `ListGenerationSwapTest.java` (in-memory store fake): a failed write leaves the previous
  generation active; a successful write switches the active generation exactly once; the old generation
  is removed after the switch; an orphaned generation is removed by the sweeper.
- [ ] 2.4 Red: `ListRefreshDebounceTest.java` with a fixed `Clock`: two refreshes of one source within 10
  minutes run once; a manual refresh after that runs.
- [ ] 2.5 Implement `ListFetcher.java` (Micronaut HTTP client with the hardening above, through a
  resolver interface so the SSRF test needs no DNS), `ListParser.java`, `ListGenerationStore.java`,
  `ListRefreshJob.java` (`@Scheduled`), `ListGenerationSweeper.java` (removes orphaned generations).
- [ ] 2.6 Each replica reloads active generations at start-up and every 5 minutes. Red first:
  `ListReloadTest.java` (a changed active generation triggers a reload; an unchanged one does not).
- [ ] 2.7 Tests green: `./gradlew :backend:test --tests "*intel*"`. No test contacts a real host.

## 3. Policy, allow-list and hits (backend)

- [ ] 3.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/intel/IntelPolicyServiceTest.java`:
  changing a policy to `DENY` as MODERATOR returns the Error variant `FORBIDDEN_POLICY`; as ADMIN it
  succeeds and records the previous value.
- [ ] 3.2 Red: `IntelAllowListTest.java`: a MODERATOR can add an exemption of 30 days; a permanent one
  is refused for MODERATOR (`FORBIDDEN_DURATION`); an expired exemption no longer applies; the reason
  is required.
- [ ] 3.3 Red: `IntelHitRetentionSweeperTest.java` with a fixed `Clock`: a hit older than
  `blackhole.intel.hit-retention` is deleted; a newer one stays.
- [ ] 3.4 Implement `IntelPolicyService.java`, `IntelAllowListService.java`, `IntelHitService.java` (writes a
  hit only when the verdict is not `ALLOW` or the allow-list applied), `IntelHitRetentionSweeper.java`,
  the DTOs as sealed marker interfaces (`PolicyDTO`, `AllowListDTO`, `HitDTO`, `Error`) and the `*Api`
  interfaces with OpenAPI annotations.
- [ ] 3.5 Tests green: `./gradlew :backend:test --tests "*intel*"`.

## 4. Login check endpoint (backend)

- [ ] 4.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/intel/LoginCheckTest.java`:
  a `DENY` class returns `DENY`; a `FLAG` class returns `FLAG` and records a hit; an allow-listed owner
  returns `ALLOW` and records a hit with `allow_listed = true`; a clean address returns `ALLOW` and
  records nothing; a malformed owner hash is refused.
- [ ] 4.2 Red: `LoginCheckNoDatabaseReadTest.java`: the classification path reads only the in-memory
  structures; the test uses a store fake that fails on any read during classification.
- [ ] 4.3 Red: `IntelEventNoAddressTest.java`: `blackhole.events.intel.vpn.flagged` carries the hit id and
  class only; a captured appender asserts a publish failure is logged without the address.
- [ ] 4.4 Implement `IntelLoginCheckController.java` (thin), `IntelLoginCheckService.java`, the
  `flagged` notice with the 30-minute aggregation per owner.
- [ ] 4.5 Tests green: `./gradlew :backend:test --tests "*intel*"`.

## 5. Health and metrics (backend)

- [ ] 5.1 Red: `IntelListsHealthIndicatorTest.java` with a fixed clock: DOWN only when every enabled
  source has failed for more than two refresh intervals; a disabled source does not count.
- [ ] 5.2 Implement the gauges, counters and timer from `design.md` decision 8 and the `intel-lists`
  health indicator.
- [ ] 5.3 Tests green: `./gradlew :backend:test --tests "*intel*"`.

## 6. OpenAPI client

- [ ] 6.1 Bump `client/specs/blackhole-api-*.yml` to the next free version, add `/intel/login-check`
  (PROXY), the policy, allow-list and hit paths and schemas; set `inputSpec` in `client/build.gradle.kts`.
- [ ] 6.2 Run `./gradlew :client:openApiGenerate` and `./gradlew :client:compileJava`. Do not edit
  generated sources.

## 7. Velocity login check (velocity)

- [ ] 7.1 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/intel/VpnLoginDecisionTest.java`
  with a stub gateway: `DENY` refuses the login with the `VPN_DENY` screen; `FLAG` and `ALLOW` let the
  login through; a timeout, an error or an unknown verdict lets the login through and increments the
  fail-open counter.
- [ ] 7.2 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/intel/VpnLoginCallTimeoutTest.java`:
  the call is made with the configured timeout and the login waits no longer than that timeout
  (asserted with a stub that never answers; no sleep).
- [ ] 7.3 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/component/PunishmentScreensVpnTest.java`:
  the `VPN_DENY` screen contains no address and no account detail, and is localized with the English
  fallback.
- [ ] 7.4 Implement `velocity/.../intel/VpnLoginDecision.java`, add the `login-check` method to
  `BackendGateway` and `HttpBackendGateway`, hook into `listener/PlayerLoginListener.java` after the
  existing checks, add `Surface.VPN_DENY` to `component/PunishmentScreens.java`.
- [ ] 7.5 Tests green: `./gradlew :velocity:test`.

## 8. Panel (web)

- [ ] 8.1 Red: `web/tests/unit/intel/PolicyForm.spec.ts`: the `DENY` option is shown to ADMIN only; saving a
  change shows the previous value in the confirmation.
- [ ] 8.2 Red: `web/tests/unit/intel/AllowListForm.spec.ts`: the reason is required; the permanent option is
  hidden for MODERATOR; an expired entry is shown as expired.
- [ ] 8.3 Red: `web/tests/unit/intel/ListSourceCard.spec.ts`: the attribution text of X4BNet is rendered;
  the status maps to its label (fresh, stale, failed).
- [ ] 8.4 Red: `web/tests/unit/player/IntelHitsSection.spec.ts`: hits show class, source key and verdict;
  the owner's allow-list state is shown; no hit appears to a VIEWER without the staff role.
- [ ] 8.5 Implement the policy page (`web/src/pages/intel/policy`), the allow-list page
  (`web/src/pages/intel/allow-list`), the hits section on the player page, and the list source card.
- [ ] 8.6 Tests green: `npm --prefix web test` and `npm --prefix web run typecheck`.

## 9. Code PR

- [ ] 9.1 Open the code pull request from the implementation branch, branched from `origin/main` after
  the proposal PR is merged. It contains groups 1 to 8 only and no file under `openspec/`. Title:
  `feat(intel): flag and deny vpn and hosting connections`. The body is written from the staff point of
  view. Do not merge.
