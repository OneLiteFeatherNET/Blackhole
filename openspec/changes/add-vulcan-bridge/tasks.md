# Tasks

Prerequisite: `add-integration-evidence` (PR #556) is merged, and its ingestion route `POST /integration/detections` with
the `vulcan/DETECTION` v1 schema is deployed to a test environment. Groups 1 to 6 run without the backend (fake HTTP).
Group 8 needs it.

Every test is written before the production code it covers (red, green, refactor) and follows F.I.R.S.T.: no sleeps, an
injected `Clock` and scheduler, a fake HTTP sender, a fresh fixture per test, and assertions instead of log reading.

## 1. Module skeleton and build

- [ ] 1.1 Add a failing build check first: `./gradlew :bridge-paper:build` on an empty module fails until the module exists.
- [ ] 1.2 Add `bridge-paper` to `settings.gradle.kts` with `paper-api` compile-only (version pinned after the two-week age check), the shadow plugin, Java 21, JUnit 5, and Gson shaded and relocated.
- [ ] 1.3 Add `paper-plugin.yml` or `plugin.yml` with `folia-supported: true` and the `blackhole.bridge.status` permission (default `op`), and a test that parses it.
- [ ] 1.4 Add a `release-please-config.json` package entry for `bridge-paper` with its own changelog and tag prefix.

## 2. Configuration and credentials (tests first)

- [ ] 2.1 Test: a missing config file is written with defaults and an empty secret.
- [ ] 2.2 Test: the environment variable overrides the config key for the secret.
- [ ] 2.3 Test: a missing secret leaves the bridge idle with one WARN and no secret text in it.
- [ ] 2.4 Test: the secret wrapper prints `***` in `toString()` and in the status output.
- [ ] 2.5 Test: the token is refreshed at 80% of its lifetime, with a fake clock and a fake token endpoint.
- [ ] 2.6 Test: a `server-name` or service name that fails the pattern falls back to the configured name.
- [ ] 2.7 Implement configuration, secret handling and token cache to make 2.1 to 2.6 pass.

## 3. Queue and delivery (tests first)

- [ ] 3.1 Test: the queue drops the oldest detection and counts `queue_full` when the 5 000 bound is reached.
- [ ] 3.2 Test: the queue drops the oldest detection when the 8 MiB bound is reached.
- [ ] 3.3 Test: an envelope over 64 KiB is dropped and counted as `too_large`.
- [ ] 3.4 Test: 120 queued detections are sent in requests of at most 50 items, with a fake sender.
- [ ] 3.5 Test: a batch is flushed by the timer after 2 seconds, using a fake scheduler.
- [ ] 3.6 Test: a network failure retries the same batch with the same `detectionId`s and with backoff capped at 60 seconds, using an injected jitter source.
- [ ] 3.7 Test: a 401 refreshes the token once and retries; a 400 is not retried.
- [ ] 3.8 Test: `ACCEPTED` and `DUPLICATE` items are removed; `REJECTED` items are removed and counted by reason code, and the logged line contains no owner or detail.
- [ ] 3.9 Test: the shutdown flush sends queued items within the 2 second deadline and counts the rest as `shutdown`.
- [ ] 3.10 Implement the bounded queue, batching, sender, backoff and shutdown flush to make 3.1 to 3.9 pass.

## 4. Envelope mapping

- [ ] 4.1 Test: each envelope gets a fresh UUID `detectionId`, kept across retries (uses 3.6's fixture).
- [ ] 4.2 Test: the envelope has `kind` `DETECTION`, `schemaVersion` 1, a server-clock `occurredAt`, and a summary of at most 200 characters.
- [ ] 4.3 Test: the envelope contains no IP, world, coordinates, inventory or player name field (checked against the JSON keys).
- [ ] 4.4 Implement the mapping to make 4.1 to 4.3 pass.

## 5. Vulcan adapter and version independence (tests first)

- [ ] 5.1 Create test doubles in the test sourceset: `FakeFlagEventV1` (full getter set) and `FakeFlagEventV2` (a renamed getter, one absent getter, the legacy package variant). Label each as a double modelled on changelog names, not Vulcan's real signatures.
- [ ] 5.2 Test: the probe finds the flag class and every getter in V1 and lists all features as available.
- [ ] 5.3 Test: V2 with the VL getter absent sends `violationLevel` as null and still delivers the flag.
- [ ] 5.4 Test: the same flag fed to V1 and V2 yields envelopes with the same `check`, `summary` and `occurredAt` source.
- [ ] 5.5 Test: a flag without a check name is dropped and counted as `unmapped`.
- [ ] 5.6 Test: with no flag class under any known name, the adapter logs one WARN and queues nothing (uses an empty package fixture).
- [ ] 5.7 Test: a getter that throws disables the integration once, logs one ERROR with the exception class name, and the handler never throws.
- [ ] 5.8 Test: the INFO line lists available and missing features exactly once per enable.
- [ ] 5.9 Implement the runtime probe with cached method handles, the candidate-name tables, the mapping and the guard to make 5.2 to 5.8 pass.

## 6. Paper wiring and observability (tests first)

- [ ] 6.1 Test: the listener copies fields and returns; the handler runs on a thread that never calls the network (asserted with a fake sender that records its thread).
- [ ] 6.2 Test: ping and TPS come from the main-thread snapshot; a stale snapshot (older than 5 seconds) yields null.
- [ ] 6.3 Test: on Folia (detected through a fake capability check) TPS is null and ping still comes from the player.
- [ ] 6.4 Test: `/blackhole-bridge status` is refused without the permission and prints the counters and features without the secret.
- [ ] 6.5 Test: counters `queued`, `sent`, `accepted`, `duplicate`, `rejected`, `dropped`, `token_refresh` are incremented by the paths in groups 3 and 5, asserted through the counter object, not the log.
- [ ] 6.6 Implement the listener, the snapshot task, the command and the counters to make 6.1 to 6.5 pass.

## 7. Operator documentation and distribution

- [ ] 7.1 Write `bridge-paper/README.md`: supported platforms (Paper and forks; Folia experimental with TPS null; Spigot not supported), Vulcan and PacketEvents requirements, the Vulcan `enable-api` setting, the config keys, and the CloudNet template checklist (jar, environment variable, service name, registration per task or per instance).
- [ ] 7.2 Record in the README the real-jar result from task 8.2 (or the manual check result).
- [ ] 7.3 Add a CI step that runs `./gradlew :bridge-paper:build` and uploads the shaded jar as an artifact.

## 8. Verification

- [ ] 8.1 Run the whole `bridge-paper` unit suite (`./gradlew :bridge-paper:test`) under `flock ~/.cache/claude-heavy.lock`. It must pass in random method order.
- [ ] 8.2 If a Vulcan release can be downloaded in CI under its licence without a login (open question 3), run groups 5 and 6 against two real releases as fixtures and name the versions in the README. Otherwise run both shapes by hand on a Paper test server with Vulcan and PacketEvents installed and record the result in the README.
- [ ] 8.3 End to end, after `add-integration-evidence` ingestion is deployed to the test environment: a registered test bridge sends a real flag; the card shows it; a repeated batch answers `DUPLICATE`; a disabled registration answers 403 and the bridge logs one WARN with the code.

## 9. Code pull request

- [ ] 9.1 Open the code pull request titled `feat(vulcan): deliver vulcan detections to blackhole`, branched from the remote main branch that already contains this proposal. The PR contains the `bridge-paper` module and its docs and no file under `openspec/`.
