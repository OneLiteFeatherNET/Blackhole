## 1. Linked accounts (backend)

- [ ] 1.1 Write the failing JUnit test: owner B shares the clear IP of owner A inside the detection window, so A's linked list holds B with reason `SHARED_ADDRESS`; the test uses a fresh fixture, an injected `java.time.Clock` and no network.
- [ ] 1.2 Write the failing JUnit test: a legacy token row and a session row with the same token link the two owners, and an owner with the same IP outside the window is not listed.
- [ ] 1.3 Write the failing JUnit test: a salt-less deployment still links owners by the clear IP of their sessions.
- [ ] 1.4 Write the failing JUnit test: an owner that shares an address and a fingerprint is listed once with both reasons, and the target is never listed.
- [ ] 1.5 Write the failing JUnit test for ban status: a linked owner with an active network ban shows `NETWORK`, a lifted ban shows nothing, and a kick is not listed.
- [ ] 1.6 Write the failing JUnit test for the limit: 25 linked owners give 20 entries newest first and `truncated` true.
- [ ] 1.7 Implement `AltLookupService` in `evasion/service/` over the session and legacy tables, `ClientFingerprintService.linksOf`, the name history of `add-name-history` and the profile slot reader; make 1.1 to 1.6 pass.

## 2. Route and client spec (backend)

- [ ] 2.1 Write the failing controller-level test for `GET /evasion/{owner}/alts`: VIEWER and above and PROXY allowed, a malformed owner is a validation error, an unknown owner is 200 with an empty list.
- [ ] 2.2 Add the route to `EvasionApi` with OpenAPI annotations, the sealed `AltsDTO` (`Response`, `Linked`, `Error`) and the thin controller method; make 2.1 pass.
- [ ] 2.3 Update `client/specs/blackhole-api-*.yml` to the next free version and `client/build.gradle.kts` `inputSpec`; run `./gradlew :client:openApiGenerate`.

## 3. Velocity command

- [ ] 3.1 Write the failing unit test for target resolution: an online player is resolved without a network call, a current name through the live lookup, and an old name through the old-name route; use a fake client.
- [ ] 3.2 Write the failing unit test for the timeout: a backend future that does not complete within the configured timeout gives the timeout message, and the test uses an injected timeout of zero and a future the test controls, with no sleeps.
- [ ] 3.3 Write the failing unit test for the output: each entry names the account by its UUID and name, its reasons in words, its last seen time and its active punishments or "none", and no entry contains an IP address, a token or an owner hash (assert the component text).
- [ ] 3.4 Write the failing unit test that each entry's click event is `suggestCommand` for `/blackhole <name> info` and never `runCommand`.
- [ ] 3.5 Write the failing unit test that a player without `blackhole.alts` is refused and that the console is refused.
- [ ] 3.6 Implement `AltsCommand` with `@Permission("blackhole.alts")`, `requiredSender = Player.class`, the async call with `blackhole.alts.timeout`, and the messages in `blackhole_en.properties` and `blackhole_de.properties`; make 3.1 to 3.5 pass.

## 4. Code pull request

- [ ] 4.1 Open the code pull request titled `feat(velocity): add the in-game alt lookup command` from the implementation branch, based on `main` after `add-player-intelligence`, `add-name-history` and `amend-data-protection-principle` are merged. Its commits follow the type rules and it touches nothing under `openspec/`.
