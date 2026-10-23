## 1. Sections: target kinds (PR 1, `feat(sections)`)

- [x] 1.1 Add `ServerTargetKind`, carry `targetKind` through `SectionSpec`, `SectionEntity`, `SectionDTO` (create/update optional, response required) and extend `ServerPatterns.validate` with the CloudNet rules; verify with JUnit tests per kind (valid, wildcard, empty, >50, wrong enforcement, case-insensitive duplicates)
- [x] 1.2 Append Flyway `V11__section_target_kind.sql` (`target_kind varchar(24) NOT NULL DEFAULT 'PATTERN'`, next free version); verify a migration test that pre-existing rows read as `PATTERN`
- [x] 1.3 Section list in the shared cache and `SectionChangeNotifier` carry the new field; verify a cache round-trip test and that an entry without `targetKind` deserialises as `PATTERN`
- [x] 1.4 Publish `client/specs/blackhole-api-0.0.17.yml`, bump `inputSpec` in `client/build.gradle.kts`, regenerate; verify `./gradlew :client:openApiGenerate :backend:test`

## 2. Velocity: enforcement (PR 2, `feat(velocity)`)

- [x] 2.1 Add `compileOnly` `eu.cloudnetservice.cloudnet:driver-api:4.0.0-RC17` (Maven Central) to the version catalogue and `velocity/build.gradle.kts`, not shaded; verify the shadow jar contains no `eu/cloudnetservice` classes
- [x] 2.2 Add `CloudNetView`, `ServerFacts`, `NoCloudNetView`, `DriverCloudNetView` (only class importing the driver; cache fed by `CloudServiceLifecycleChangeEvent`/`CloudServiceUpdateEvent`, one `serviceByName` lookup on a miss), detection and `cloudnet.integration` config; verify unit tests with `FakeCloudNetView` and a test that only `DriverCloudNetView` imports `eu.cloudnetservice`
- [x] 2.3 Add `ServerTargets.covers` and use it in `Enforcement.serverBan`/`coversServer`, `ServerConnectListener` and `PunishmentSyncHandler` (move-off, fallback-also-covered disconnects); `SectionInfo` reads `targetKind` (missing -> `PATTERN`); verify tests for each kind, case, unknown server (warn once), no CloudNet, service restart, fallback covered
- [x] 2.4 Deny screen: `punishment.screen.target.{task,group,service}` in `blackhole_en.properties` and `blackhole_de.properties`; verify `PunishmentScreensTest` and `I18nBundlesTest`

## 3. Catalogue (PR 3, `feat(cloudnet)`)

- [x] 3.1 Backend `cloudnet/` package: `CatalogueApi`, thin `CatalogueController` (`PUT` `Roles.PROXY`, `GET` `Roles.VIEWER`), `CatalogueService`, sealed `CatalogueDTO`, shared-cache storage with TTL 15 min (`CacheKeys`), merge, bounds, 503/`available:false` on cache outage; verify JUnit tests with a fake cache and injected `Clock`
- [x] 3.2 Add the two routes to `RouteCoverageIntegrationTest`/`PermissionMatrixIntegrationTest` and amend the matrix in the test; verify the tests pass and fail without the rule
- [x] 3.3 Publish `client/specs/blackhole-api-0.0.18.yml`, bump `inputSpec`, regenerate; verify `./gradlew :client:openApiGenerate`
- [x] 3.4 Velocity `CatalogueReporter` (full snapshot at start, debounced on events, heartbeat, failure never affects enforcement; config `cloudnet.report-debounce`, `cloudnet.heartbeat`, optional `cloudnet.proxy-id`); verify tests with injected `Clock` and a hand-driven scheduler (no sleeps)

## 4. Web (PR 4, `feat(web)`)

- [x] 4.1 API types/client for `targetKind` and `GET /cloudnet/catalogue`; target selector, picker with free-text fallback, not-in-catalogue marker and service warning in `SectionsView`; list shows the kind; en/de strings; verify component tests (picker, no catalogue, failed catalogue, warning) and `i18n.keys.test.ts`

## 5. Documentation (PR 5, `docs(sections)`)

- [x] 5.1 README section on CloudNet sections (target kinds, requirements: proxy as CloudNet service with Bridge, `cloudnet.integration`, rollout order, fail-open behaviour, service-name volatility), `.claude/rules` note for the new package and the permission matrix; verify the docs match config keys and routes in code

## 6. Verification and wrap-up

- [ ] 6.1 Update `.claude/PROGRESS.md` per PR as it lands
- [ ] 6.2 Manual check against a real CloudNet 4.0.0-RC node (not automated): Velocity plugin classloader sees the driver; Bridge server names equal service names; task, group and service bans deny and move off to the fallback; stop/start of a service keeps a service ban on the new instance; plugin on a non-CloudNet proxy starts with one warning; catalogue appears in the UI picker and expires after the proxy stops; record the result in PR 2/3
- [ ] 6.3 `openspec validate --all --strict`; archive with `docs(openspec): archive integrate-cloudnet-sections` after the PRs merge (archive `staff-temp-bans-elo-escalation` first)
- [x] 6.4 Open the pull requests as small stacked PRs against `main` (each squash-merged, title is the Conventional Commit): `feat(sections): target cloudnet tasks, groups and services`, `feat(velocity): enforce cloudnet sections through the bridge api`, `feat(cloudnet): report the cloudnet catalogue to the backend`, `feat(web): pick cloudnet targets in the section editor`, `docs(sections): document the cloudnet integration`
