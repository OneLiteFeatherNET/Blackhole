## 1. Storage and retention (backend)

- [ ] 1.1 Write the failing JUnit test for the `player_sighting` repository: a session is stored with a clear IP, the raw UUID, the owner hash and the outcome, and a repeated start with the same `sessionId` returns the stored row. Each test gets its own fresh fixture and no sleeps.
- [ ] 1.2 Add the Flyway migration `V<next>__player_sighting.sql` (next free version after V27, V28 today) with the columns in `design.md` decision 3, indexes on `owner_hash`, `player_uuid`, `ip`, `ip_token`, and `started_at`. Append-only; no edit to an applied migration.
- [ ] 1.3 Implement the entity, repository and `SessionService` in `intel/`; make 1.1 pass.
- [ ] 1.4 Write the failing test for the retention sweeper: a session started 100 days ago is deleted and one started 10 days ago is kept, with an injected `java.time.Clock` and no system-time reads.
- [ ] 1.5 Implement `IntelRetentionSweeper` on `blackhole.intel.retention-sweep-cron`; make 1.4 pass. Add `blackhole.intel.retention-days` and `blackhole.intel.retention-sweep-cron` to `application.yml` with `${ENV_VAR:default}` and a comment.

## 2. Offline enrichment (backend)

- [ ] 2.1 Write the failing test for the enrichment reader: a known test IP resolves to the expected ASN and country from a small fixture MMDB under `src/test/resources` (no network); an IP not in the file leaves the fields null.
- [ ] 2.2 Write the failing test that a missing ASN file leaves the enrichment null, does not throw, and logs once (asserted through a captured appender, not by reading console output).
- [ ] 2.3 Implement `GeoIpReader` over `com.maxmind.db:maxmind-db`, with hourly modification-time reload and the two `blackhole.intel.geoip.*-path` settings; make 2.1 and 2.2 pass.
- [ ] 2.4 Wire enrichment into `SessionService.start` (ASN, organisation, country, token when the salt is set); tests cover salt set and salt unset, both without the network.

## 3. Ingestion and read API (backend and client spec)

- [ ] 3.1 Write the failing controller-level test for `POST /intel/sessions` (PROXY role only, sealed `SessionDTO.StartRequest`), `PATCH /intel/sessions/{sessionId}` (end and brand) and `GET /intel/players/{owner}/sessions` (VIEWER and above, newest first, paginated, PROXY denied). Assert the response variants, not the log output.
- [ ] 3.2 Implement `IntelApi` (OpenAPI annotations), a thin `IntelController`, and the `SessionDTO` sealed interface with `StartRequest`, `UpdateRequest`, `Response`, `Error`; make 3.1 pass. Validation: owner is 128 hex characters, UUID is a valid UUID, IP is a valid address, brand at most 64 characters.
- [ ] 3.3 Publish `session.started` and `session.ended` through the domain event bus (NATS subjects `blackhole.events.session.started` and `blackhole.events.session.ended`), with the owner hash and the session id only, and test that the payload carries no IP, UUID or name.
- [ ] 3.4 Update `client/specs/blackhole-api-*.yml` to the next free version (after `0.0.44` at implementation time) and set `client/build.gradle.kts` `inputSpec` to it; run `./gradlew :client:openApiGenerate`.

## 4. Evasion correlation over both tables (backend)

- [ ] 4.1 Write the failing test that two owners, one legacy row and one new session row with the same token, raise one `evasion.detected` event (both tables read).
- [ ] 4.2 Write the failing test that a new session with the salt unset is stored with a null token and clear IP and does not raise an evasion event.
- [ ] 4.3 Implement the union read in `IpCorrelationService` and the `GET /evasion/{owner}/sightings` path; make 4.1 and 4.2 pass. Keep `POST /evasion/record` unchanged.

## 5. Session reporting (velocity)

- [ ] 5.1 Write the failing unit test for the reporter: a denied login sends one start with `DENIED`, an allowed login sends `ALLOWED`, and the backend being unavailable does not change the login result. Use a fake client and an injected executor, no real network, no sleeps.
- [ ] 5.2 Write the failing unit test that a full report queue drops the report and does not block the calling thread.
- [ ] 5.3 Implement `SessionReporter` (bounded executor, `blackhole.intel.report-timeout`, `sessionId` map keyed by connection); change `PlayerLoginListener` so the ban check runs first and one start report, with the raw UUID and the name, follows; make 5.1 and 5.2 pass.
- [ ] 5.4 Write and make pass the tests that the brand listener sends the brand as a `PATCH` for the connection's session and that `DisconnectEvent` sends the end once.
- [ ] 5.5 Keep `updateSessionInfo` for the profile unchanged; the protocol version is sent on both the session start and the profile update, and a test asserts both calls for an allowed login.

## 6. Panel (web)

- [ ] 6.1 Write the failing component test for the Sessions section: the header shows the UUID and name, and rows show time, outcome, IP, ASN organisation, country (labelled approximate), client brand and version, protocol and virtual host; an empty list shows the empty-state text; a failed request shows the error state.
- [ ] 6.2 Write the failing test that the section writes nothing to `localStorage` or `sessionStorage` (extend the existing no-personal-data test).
- [ ] 6.3 Implement the Sessions section on `PlayerDetailView`, the `api` call and the DB-IP attribution text in the footer; make 6.1 and 6.2 pass.

## 7. Deploy

- [ ] 7.1 Add the MaxMind DB volume (read-only mount) and the monthly refresh CronJob to the Helm chart, with the `blackhole.intel.geoip.*-path` values set in `application.yml` defaults and documented.

## 8. Code pull request

- [ ] 8.1 Open the code pull request titled `feat(intel): record player sessions with clear IP and offline ASN and country` from the implementation branch, based on `main` after the proposal and `amend-data-protection-principle` are merged. Its commits follow the type rules and it touches nothing under `openspec/`.
