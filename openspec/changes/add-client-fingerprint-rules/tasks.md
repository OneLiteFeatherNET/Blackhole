# Tasks

Seven small stacked PRs, each squash-merged; the PR title is the Conventional Commit. Every group
starts with failing tests (F.I.R.S.T.: injected `Clock`, a manual scheduler the test advances,
in-memory fakes behind `BackendGateway` and `CloudNetView`, no sleeps, no network, test keys from
`TestKeys`, self-signed JWTs, Testcontainers for SQL). Every backend group lands its own JUnit tests
and every UI group its own Vitest tests. A bug found while implementing is reproduced by a failing
test first. Migration numbers are planned (V19 first) and are "next free" when another change lands
first; client spec bumps use the next free number at implementation time.

## 1. Rule model and API (PR 1, `feat(client-rules): add the client rule model and api`)

- [ ] 1.1 Failing tests first: rule validation table (key format, kind-specific fields, pattern charset and length, 1 to 32 patterns, protocol range, negate allowed kinds, no regex), scope validation (section exists and is SERVER, target kind names, unknown section rejected), action validation (template exists, deny message length, spoofable acknowledgement required for BRAND/MOD/CHANNEL + TEMPLATE and cleared on kind/action change), duplicate key 409, deleting a template referenced by a rule answers 409
- [ ] 1.2 Migration `V19__client_rule.sql` (`client_rule`, `client_rule_pattern`, `client_rule_server`, `client_rule_section`; checks, cascade, FK to templates `RESTRICT`); migration test on Testcontainers
- [ ] 1.3 `clientrule/` package: `ClientRuleApi` (OpenAPI), thin `ClientRuleController` (`@Version(ApiVersion.V1)`), `ClientRuleService` (answers the `Error` variant), sealed `ClientRuleDTO` (`CreateRequest`, `UpdateRequest`, `Response`, `Error`); write is one CTE statement, no `@Transactional`; `recorded_by_oid` from `ActorResolver`
- [ ] 1.4 Change notification to proxies (same shape as the section notifier) and shared-cache mirror of the enabled rules for `PROXY` reads; test that a write evicts and notifies
- [ ] 1.5 Catalogue: `PUT/GET /client-rule/catalogue` over the shared cache with TTL `blackhole.clientrules.catalogue.ttl` (`PT24H`), outage tests (`PUT` 503, `GET` `available: false`)
- [ ] 1.6 Roles: `@Secured` on every method, rows in `PermissionMatrixIntegrationTest` and `RouteCoverageIntegrationTest` for every role (ADMIN writes, VIEWER+PROXY read rules, catalogue PUT PROXY only, catalogue GET refused for PROXY)
- [ ] 1.7 `application.yml` keys with comments; regenerate the client spec (`client/specs/blackhole-api-<next free>.yml`, bump `inputSpec`); verify `./gradlew :backend:test :client:compileJava`

## 2. Hits and actions (PR 2, `feat(client-rules): record rule hits and apply their action`)

- [ ] 2.1 Failing tests first: hit stored without any trait, unknown/disabled rule 404, bypassed and not-enforced flags, rule counter incremented in the same statement, hit event content (no names, no traits), `TEMPLATE` applied once and not again while the same rule's punishment is active, step does not escalate, appealable and marked automatic with the rule source, backend failure applies nothing, retention sweep keeps the counter, erasure deletes the owner's hits, `ClientRuleNoLeakTest` scans logs, events, metrics
- [ ] 2.2 Migration `V<next free>__client_rule_hit.sql` (`client_rule_hit`, indexes on `(rule_key, at)` and `(owner_hash)`)
- [ ] 2.3 `ClientRuleHitService`, `POST /client-rule/hit` (`PROXY`), `GET /client-rule/{key}/hits` and `GET /client-rule/hits?owner=` (VIEWER+, PROXY denied), template application through the ELO automatic-punishment path with source `client-rule:<key>`
- [ ] 2.4 Domain event `client-rule.hit` (non-blocking publisher), `ClientRuleHitRetentionSweeper` (`blackhole.clientrules.hit-retention-days`, default 30), erasure hook in the profile erasure path
- [ ] 2.5 Matrix and route-coverage rows; regenerate the client spec; `.claude/rules/events.md` note

## 3. Fingerprint hashing and evasion correlation (PR 3, `feat(evasion): correlate hashed client fingerprints`)

- [ ] 3.1 Failing tests first: canonical form known-answer vectors (order independence, length prefixes prevent collisions, empty sets, brand normalisation), HMAC known answer with a test key, protocol version excluded, generic fingerprints not stored, common-fingerprint cut-off (5 vs 6 owners), window edge, `CLIENT` vs `BOTH` origin, no event content leak, key unset gives 503 outside dev/test and a random key in dev/test, oversized lists rejected, retention sweep, erasure
- [ ] 3.2 Migration `V<next free>__client_fingerprint.sql` (unique `(fingerprint, owner_hash)`, indexes); migration test
- [ ] 3.3 `ClientFingerprintKeyGuard` (HKDF from `BLACKHOLE_FINGERPRINT_KEY`, modelled on `BanIdKeyGuard`), `ClientFingerprintService` next to `IpCorrelationService`, `POST /evasion/fingerprint` (`PROXY`, 503 when off)
- [ ] 3.4 Sightings: `via` per linked owner (additive field), `evasion.client_match` event, `ClientFingerprintRetentionSweeper` (default 30 days), `blackhole.evasion.fingerprint.*` keys documented in `application.yml`, erasure hook
- [ ] 3.5 Matrix and route-coverage rows; regenerate the client spec; update `.claude/rules/evasion.md`

## 4. Velocity collection and enforcement (PR 4, `feat(velocity): collect client traits and enforce client rules`)

- [ ] 4.1 Verify against Velocity 4.3.0 (read the javadoc and a captured fixture, do not assume): `Player#getClientBrand`, `PlayerClientBrandEvent`, `PlayerChannelRegisterEvent`, `PlayerModInfoEvent`, when each fires relative to `LoginEvent` and `ServerPreConnectEvent` on a 1.21.x vanilla, Fabric and NeoForge client; record the findings as comments in the test fixtures
- [ ] 4.2 Failing tests first: `TraitCollector` (events in any order, drop on disconnect), `RuleEngine` tables (kind x scope x action x negate x bypass, unknown brand with negate, glob and normalisation), `ClientRuleEnforcer` (login refusal, connect refusal keeps the player, disconnect for a late trait, English message, bypass records `bypassed`), fail-open cases (rules not loaded, backend down, unknown server), hit dedupe per session, settle-period fingerprint submission with a manual scheduler and resubmission only on change, catalogue k-anonymity (3 players) and no typed text
- [ ] 4.3 `clientrule/` package (`ClientTraits`, `TraitCollector`, `RuleEngine`, `RuleRegistry` with reload on notification, `ClientRuleEnforcer`, `CatalogueReporter`, `FingerprintSubmitter`, `ClientRuleNotifier`); `ClientRuleListener` for trait events, login and `ServerPreConnectEvent` using `ServerResolver`/`ServerTargets.covers`; `PlayerClientBrandListener` keeps its behaviour
- [ ] 4.4 `BackendGateway` methods and `HttpBackendGateway` over the regenerated client; config `clientRules.enabled`, `settle`, `catalogue.minPlayers`, `catalogue.heartbeat` with env overrides; staff notice over a Redis pub/sub channel like `ReportNoticeChannel`, permissions `blackhole.clientrules.notify` and `blackhole.clientrules.bypass` in `Permissions`, de/en staff messages, parity test (`I18nBundlesTest`)
- [ ] 4.5 Verify `./gradlew :velocity:test`; manual check on a local Velocity with a vanilla and a Fabric client (rule hit, `NOTIFY`, `DENY`)

## 5. Command rules (PR 5, `feat(velocity): enforce command rules`)

- [ ] 5.1 Verify against 4.3.0: `CommandExecuteEvent` fires for forwarded commands, how the signed state is exposed (`getInvocationInfo()`), `PlayerAvailableCommandsEvent` includes backend commands; note findings in the tests
- [ ] 5.2 Failing tests first: normalisation table (slash, leading spaces, case, `namespace:label`), scope per current server and none, `CommandDenial` matrix (`SIGNED_WITH_ARGS`, `SIGNED_WITHOUT_ARGS`, `UNSIGNED`, `UNSUPPORTED` x SignedVelocity present/absent), not enforced recorded and player told, bypass, hiding removes only covered nodes and not for bypass, catalogue takes commands from the tree only
- [ ] 5.3 `CommandRuleListener` (`CommandExecuteEvent`, `PlayerAvailableCommandsEvent`), `CommandDenial` reusing `MuteChatDenial.RELAY_PLUGIN_ID`, extend the startup SignedVelocity warning to commands
- [ ] 5.4 Verify `./gradlew :velocity:test`; manual check with a signed and an unsigned command, with and without SignedVelocity

## 6. Panel (PR 6, `feat(web): manage client rules in the panel`)

- [ ] 6.1 API types and client methods for rules, hits, catalogue and the sightings `via`; fake-client tests
- [ ] 6.2 Failing tests first, then the `ClientRulesView`: list with hit counts, editor per kind, scope picker reusing the section target picker, action selector with deny message and template picker, acknowledgement checkbox and warning for BRAND/MOD/CHANNEL + TEMPLATE, hide option, server errors next to fields, read-only below `ADMIN`, catalogue suggestions with free-text fallback
- [ ] 6.3 Hits list in the view and on the player page, weak-hint label for `CLIENT` origin in the evasion hints; no raw traits rendered
- [ ] 6.4 de/en strings, `i18n.keys.test.ts` parity, nav entry gated by role (`rolePermissions.test.ts`), live regions; verify `npm test` and the type check

## 7. Docs (PR 7, `docs(client-rules): document client rules and fingerprints`)

- [ ] 7.1 README section: what rules do, the three actions, scopes, command rules and SignedVelocity, the honesty statement (best effort, spoofable, deterrent and signal), `BLACKHOLE_FINGERPRINT_KEY` and the proxy config keys
- [ ] 7.2 `.claude/rules/client-rules.md` (path-scoped to `clientrule/`) and updates to `evasion.md`, `security.md` (matrix rows)
- [ ] 7.3 Operations note: rollout order, dry run with `NOTIFY`, key rotation caveat; `openspec validate --all --strict`

## 8. Pull requests

- [ ] 8.1 Open the PRs in order with the titles above, each based on its predecessor's branch (the first on `main`), each body ending with the session link; none merged by the author. Archive after the last merge with `docs(openspec): archive add-client-fingerprint-rules`
