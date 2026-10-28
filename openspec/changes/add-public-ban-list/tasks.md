## Conventions

- Branch from the up-to-date `origin/main`. One code PR for this change, under `feat(public)`.
- Red, then green, then refactor: each group starts with the failing test named in it.
- F.I.R.S.T. for every backend and Velocity test: no `Thread.sleep`, the clock is an injected `java.time.Clock`, the keys,
  the client hash files and the UUID fixtures are fixed test fixtures, no network, fresh fixtures per test (`@TempDir` for
  the secrets directory), one behaviour per test. No test asserts on timing; the constant-time claim is tested by counting
  verifier calls.
- Migration number: the next free Flyway version at implementation time (main head `V27__kick.sql` today). Never edit
  an applied migration. Client spec: the next version after the one in `client/build.gradle.kts` at implementation
  time (`0.0.44` today).
- Do not add a Co-Authored-By or session trailer to commits.
- Preconditions: `standardize-api-pagination-and-problems` is applied (envelope, problem body, `CursorCodec`, the
  `BLACKHOLE_CURSOR_KEY` config). `add-kick` is applied (`PunishmentKind`). `amend-data-protection-principle` is applied.
  Open question 1 (the principle 2 exception) is answered. `add-player-intelligence` is applied (Velocity sends the raw player UUID).
- Test vector used by every player-hash test: UUID `123e4567-e89b-12d3-a456-426614174000`, digest
  `9efc1073aa30a316b4d4930dbf73bf852be666299041e930c9763de9f0c03029` (input
  `blackhole-ban-list-v1:123e4567-e89b-12d3-a456-426614174000`).

## 1. Publication model (backend)

- [ ] 1.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/publicbans/PublicBanHashTest.java`: the
  entry hash is 22 base64url characters; the same identifier and key give the same hash; a different identifier gives a
  different hash; a different `BLACKHOLE_PUBLIC_BAN_KEY` gives a different hash; the hash of the same bytes under
  the Ban ID key differs (separate labels).
- [ ] 1.2 Red: `PublicBanPlayerHashTest.java`: the digested input is exactly `blackhole-ban-list-v1:` plus the lowercase
  dashed UUID (UTF-8, no newline); the test vector gives `9efc1073...c03029`; an uppercase and an undashed spelling of the
  same UUID give the same value; the value is 64 lowercase hex characters; a different UUID gives a different value; the
  value does not change when either backend key changes (it uses no key). The value is computed from the stored
  lowercase dashed UUID on read, and the `@Version("1")` answer uses the `blackhole-ban-list-v1:` prefix.
- [ ] 1.3 Red: `PublicBanDayTest.java`: day rounding of an instant at 23:59:59 UTC and at 00:00:00 UTC; the day is
  UTC regardless of the default zone.
- [ ] 1.4 Red: `PublicBanStatusTest.java` with a fixed `Clock`: ACTIVE for a permanent entry; EXPIRED once the expiry
  instant has passed without a sweep; REVOKED wins over EXPIRED; ENDED entries past the retention window are not
  listed (the same rule as the SQL condition).
- [ ] 1.5 Red: `PublicBanPublicationTest.java`: a template with a public reason lists a `PUNISHMENT`, with the `player_uuid`
  stored from `playerUuid` and none stored without a UUID (the entry then has `playerHash` null); a template without one,
  a manual punishment and a `KICK` list nothing;
  a later template edit does not change the copied reason.
- [ ] 1.6 Red: `PublicBanTemplateValidatorTest.java`: 200 characters are accepted and 201 refused; blank is treated as
  absent; an IPv4 address, a UUID and a Ban ID are refused with a message that does not echo the value.
- [ ] 1.7 Red: `PublicBanApplyRequestTest.java`: the apply request accepts an absent `playerUuid` (no `player_uuid` is
  stored); accepts a UUID in dashed or 32-hex-digit form in any case, and stores the test-vector UUID in lowercase dashed
  form; refuses any other value, with a validation message that does not echo the value.
- [ ] 1.8 Implement: `publicbans/PublicBanPlayerHasher.java` (SHA-256 over the input string of 1.2, lowercase hex),
  `publicbans/PublicBanHasher.java` (HKDF-SHA256 derivation from `BLACKHOLE_PUBLIC_BAN_KEY`, label
  `blackhole-public-ban-entry-v1`, HMAC over the identifier, 16 bytes, base64url), `PublicBanDay.java` (UTC day),
  `PublicBanStatus.java` (pure function of the stored columns and the clock).
- [ ] 1.9 Implement: `publicbans/PublicBanKeyGuard.java` modelled on `BanIdKeyGuard`: refuse to start without the key
  outside `dev` and `test`; the message names the variable and never prints the key. Test first:
  `PublicBanKeyGuardTest.java`.
- [ ] 1.10 Migration `backend/src/main/resources/db/migration/V<next>__public_ban_list.sql` (the number is assigned at
  implementation time, as in the conventions above): `punishment_template.public_reason`; `punishments.player_uuid`
  (`VARCHAR(36)`, nullable, internal), `public_reason`, `public_kind`, `public_hash` (unique), `public_issued_day`,
  `public_expires_at`, `public_revoked_at`; index `(public_issued_day, public_hash)`. No `public_player_hash` column. Test first: a repository test that
  applies the migration to a scratch database and checks the columns and the unique index.
- [ ] 1.11 Template field: `PunishTemplateDTO` (`publicReason` on the request and the response, optional), `TemplateSpec`,
  `TemplateValidator` (length, PiiGuard, blank treated as absent; the ADMIN-only rule is enforced in the controller's
  role rule).
- [ ] 1.12 Apply request field: the apply request DTO that carries `owner` today gets an optional `playerUuid` (raw UUID,
  dashed or 32 hex digits, any case). The service normalises it to lowercase dashed form and stores it as `player_uuid`.
  No hash is stored. Implement after 1.7.
- [ ] 1.13 Listing writer: on apply, copy `publicReason` and the section kind, store the normalised `player_uuid` (none
  without a UUID), compute the entry hash, set `public_issued_day`, `public_expires_at`. A missing UUID does not
  prevent listing. On amendment update `public_expires_at`; on revoke set `public_revoked_at`. Kicks are skipped. Test
  first: `PublicBanPublicationWriterTest.java` (the cases of 1.5, plus the amendment and the revoke).
- [ ] 1.14 Erasure: the erasure path clears the listing columns and `player_uuid` of the erased owner's punishments. Test first: `PublicBanErasureTest.java`.
- [ ] 1.15 Tests green: `./gradlew :backend:test --tests "*publicbans*" --tests "*punishment*"`, then the full
  `./gradlew :backend:test` before the code PR.

## 2. Callers send the player UUID

- [ ] 2.1 Red: `velocity/src/test/java/.../PublicBanApplyRequestTest.java`: an in-game punishment request carries
  `playerUuid` next to `owner`; a request without a known UUID omits the field and does not fail.
- [ ] 2.2 Implement: the apply request builder sets `playerUuid` (the raw UUID the plugin already holds) next to `owner`.
  The plugin does not hash it.
- [ ] 2.3 Depends on group 5: the plugin uses the regenerated client for the new field. Do not hand-edit generated sources.
- [ ] 2.4 The backend import path, the admin web and the Discord bot send `playerUuid` when they know the UUID. Red first
  per caller: the request carries the UUID when known and omits it otherwise. Implement in each caller.

## 3. Basic Auth and named clients (backend)

- [ ] 3.1 Red: `publicbans/auth/BasicAuthHeaderParserTest.java`: a missing header, a Bearer scheme, malformed base64,
  a missing colon and a valid pair are each classified (`missing`, `malformed`, valid); a password that contains colons
  keeps everything after the first colon.
- [ ] 3.2 Red: `BanListClientRegistryTest.java` (`@TempDir` as the secrets directory): a client name outside
  `[a-z0-9-]{1,32}` is refused; a client with a well-formed hash file is registered; a client with no file is a startup
  error outside `dev` and `test`; in `dev` it is skipped and a captured warning names it; a malformed hash string is
  refused and the message does not contain the hash.
- [ ] 3.3 Red: `Argon2PasswordVerifierTest.java`: the verifier accepts the correct password and refuses a wrong one for
  a hash built from the fixed test parameters; a PHC string with other parameters or algorithm is refused at load time;
  the unknown-client path invokes the verifier exactly once against the dummy hash (a counting spy, not a timer).
- [ ] 3.4 Red: `BanListHttpsGuardTest.java`: a request over plain HTTP is answered 403 `HTTPS_REQUIRED` with no
  `WWW-Authenticate` header and the verifier is not invoked; `X-Forwarded-Proto: https` from the configured ingress
  address is honoured; the same header from another address is ignored.
- [ ] 3.5 Red: `BanListAttributionTest.java`: a successful request logs `client=<name>` (captured appender) and increments
  `blackhole_ban_list_requests_total{client="<name>"}` (in-memory registry); a request with an unknown name logs
  `client=unauthenticated` with reason `unknown-client` and the submitted name appears in no log line; no line at any
  level contains the `Authorization` value or the password; a read of an entry logs and labels no raw UUID, name or IP
  address of the player.
- [ ] 3.6 Red: `BanListSecurityRuleTest.java`: the three routes are covered by the Basic chain only; a Bearer token gets
  401 with the Basic challenge; missing, malformed, unknown-name and wrong-password requests give identical status,
  headers and bodies (`code: UNAUTHORIZED`); other routes do not accept a Basic credential; the route-coverage test
  expectation is updated here.
- [ ] 3.7 Implement: `publicbans/auth/BanListAuthenticationProvider.java` (Micronaut authentication provider, principal
  name = client name), `BanListClientRegistry.java` (reads `blackhole.ban-list.clients` and the secrets directory once at
  startup), `Argon2PasswordVerifier.java` (PHC parse, Argon2id via the chosen library, `MessageDigest.isEqual`, dummy
  hash for unknown names), `BanListHttpsGuard.java`, and the separate security chain that matches exactly the three
  routes. Confirm the Micronaut Security version and API at implementation time (open question 14).
- [ ] 3.8 Implement: `application.yml` settings with comments, following the `${ENV:default}` pattern:
  `blackhole.ban-list.clients` (`BLACKHOLE_BAN_LIST_CLIENTS`, empty default), `blackhole.ban-list.secrets-dir`
  (`BLACKHOLE_BAN_LIST_SECRETS_DIR`, default `/etc/blackhole/ban-list`), and the trusted ingress setting for
  `X-Forwarded-Proto`. Add the Argon2 library to the backend build with a version at least two weeks old.

## 4. Read API (backend)

- [ ] 4.1 Red: `PublicBansAuthOrderTest.java`: an unknown hash without credentials answers 401, not 404; a valid
  client with an unknown hash answers 404.
- [ ] 4.2 Red: `PublicBansListTest.java` (service, fixed `Clock`, in-memory fixture from the JDBC reader's test double):
  the default limit is 50; 201 rows return 200 rows and a `nextCursor`; the last page has `nextCursor` null; `limit=0`
  and `limit=500` are 400 with an `errors` entry; a tampered cursor is 400 `INVALID_CURSOR`; kind and status filters.
- [ ] 4.3 Red: `PublicBanEntryFieldsTest.java`: the JSON of an entry has exactly the ten allowed fields (with `hashScheme`); `playerHash` of
  a fixture player equals the test vector when the fixture UUID is the test vector UUID; a fixture that holds a Ban ID, a
  reference, an owner value, a raw UUID and a player name shows none of those in the body, the `Link` header or the
  cursor, and the client name is not in the body.
- [ ] 4.4 Red: `PublicBanNotFoundTest.java`: for an authenticated client, an unknown hash, a malformed hash and an entry
  outside the retention window give identical 404 bodies.
- [ ] 4.5 Red: `PublicBanCacheHeadersTest.java`: every answer, success and error, carries `Cache-Control: private, no-store`
  and no `ETag`.
- [ ] 4.6 Red: `PublicBanSummaryTest.java`: a row of 8 with 5 ACTIVE returns `active` 5 and `total` 8; a row of 2 returns
  `null` and `suppressed: true`; the totals equal the list's count for the same reason and kind.
- [ ] 4.7 Implement: `publicbans/controller/PublicBansApi.java` (OpenAPI annotations, `operationId`s `publicBanList`,
  `publicBanEntry`, `publicBanSummary`, security scheme `basicAuth`), `PublicBansController.java` (thin,
  `@Version(ApiVersion.V1)`, one service call each, response headers from the service result),
  `dto/PublicBansDTO.java` (one sealed marker interface: `Entry`, `Page`, `Summary`, `Error`), and
  `publicbans/service/PublicBansService.java` (`@Singleton`, returns the `Error` variant for expected failures).
- [ ] 4.8 Implement: `JdbcPublicBanReader.java` (keyset query `ORDER BY public_issued_day DESC, public_hash DESC` with the
  window condition `COALESCE(public_revoked_at, public_expires_at)`, no `@Transactional`), the `playerHash` filter as the
  SQL condition of 4.13, and the summary query. The v1 `playerHash` and `hashScheme` are set in the service from
  `player_uuid`, not in SQL.
- [ ] 4.9 Implement: `application.yml` settings with comments: `blackhole.public-bans.retention`
  (`BLACKHOLE_PUBLIC_BANS_RETENTION`, `P90D`), `blackhole.public-bans.summary-min-count`
  (`BLACKHOLE_PUBLIC_BANS_SUMMARY_MIN_COUNT`, `5`), and `BLACKHOLE_PUBLIC_BAN_KEY` with an empty default.
- [ ] 4.10 Integration test (in-process server, fresh instance, no sleeps): `PublicBansIntegrationTest.java`: a
  template with a public reason, apply with a `playerUuid`, revoke, list, summary, single read as a configured test
  client (fixture hash file in a `@TempDir`), 401 without credentials, and 404 after the window (with the injected clock
  moved, not a wait).
- [ ] 4.11 Update the route-coverage test expectations for the three ban-list routes, and `.claude/rules/public-api.md`.
- [ ] 4.12 Red: `PublicBanPlayerHashFilterTest.java` (service, fixed `Clock`, in-memory fixture): `playerHash` of the
  test-vector UUID returns only that entry; the uppercase spelling gives the same page; 63 characters and a non-hex
  character are 400 with an `errors` entry for `playerHash` and the value is not echoed; an unknown value is an empty
  page with `nextCursor` null; a filter that matches more than the limit pages without short pages.
- [ ] 4.13 Red: `PublicBanPlayerHashSqlTest.java` (repository test on a scratch database): the filter SQL
  `SHA2(CONCAT('blackhole-ban-list-v1:', player_uuid), 256)` returns `9efc1073...c03029` for the test-vector UUID and
  the same value as `PublicBanPlayerHasher` for two other fixtures.
- [ ] 4.14 Red: `PublicBanVersionRoutingTest.java`: no version header, `X-API-VERSION: 1` and `api-version=1` give the same
  answer with `hashScheme` `blackhole-ban-list-v1` and the v1 `playerHash`; the entry `hash` is identical across them; a
  version with no route never gets a `blackhole-ban-list-v1` hash. The v2 case is written with the v2 scheme when a v2
  is added, not in this change.

## 5. Client contract

- [ ] 5.1 Red: `client/specs` contract check (existing spec-diff test, if present) fails on the new routes, the `basicAuth`
  scheme, the template field, the apply field `playerUuid`, the entry fields `playerHash` and `hashScheme`, and the
  `playerHash` query parameter until the spec is regenerated.
- [ ] 5.2 Add `client/specs/blackhole-api-<next>.yml` (next version after `0.0.44`) with the three routes, the HTTP
  basic security scheme on them, the `playerHash` query parameter, the entry, page, summary and error schemas, the template field, and the optional `playerUuid` on the apply request. The `hashScheme` field says which scheme the `playerHash` of that answer uses. The `playerHash` field description states the input string
  `blackhole-ban-list-v1:` plus the lowercase dashed UUID, the SHA-256 hex output, the test vector, and that the backend
  computes it when the list is read, for the API version of the request. Bump `inputSpec` in `client/build.gradle.kts`.
- [ ] 5.3 Regenerate with `./gradlew :client:openApiGenerate` and run `./gradlew :client:compileJava`. Do not edit the
  generated sources by hand.

## 6. Deploy: secrets and ingress (Helm chart)

- [ ] 6.1 Red: a render check of `deploy/charts/blackhole` (`helm lint` plus `helm template` with test values, in the
  existing CI step or a script): the client names render into `BLACKHOLE_BAN_LIST_CLIENTS`; each client's Secret key is
  mounted as a file under the secrets directory; `BLACKHOLE_PUBLIC_BAN_KEY` comes from a `secretKeyRef`; no password or
  hash literal appears in `values.yaml` or the rendered manifest.
- [ ] 6.2 Implement: `banList.clients` (list of names, default empty), `banList.existingSecret` (name of a Secret with one
  key per client holding the PHC string), and `publicBanKey.existingSecret` in the chart values and templates.
- [ ] 6.3 Confirm the ingress redirects plain HTTP to HTTPS for the backend host and that it sets `X-Forwarded-Proto`
  (the setting in 3.8 depends on it). Change the ingress only if it does not.

## 7. Admin web

- [ ] 7.1 Red: `web/` component test for the template editor: an ADMIN sees the public reason field with a counter; a
  MODERATOR sees the read-only note; a refused save shows the message next to the field.
- [ ] 7.2 Implement: the field in the template editor, its validation, and the API type from the regenerated client.

## 8. Documentation

- [ ] 8.1 `.claude/rules/public-bans.md` (path-scoped to `backend/**/publicbans/**`): the
  listing rule, the entry hash rule, the player hash rule (input string, lowercase dashed UUID, computed on read from the
  stored `player_uuid`, bound to the API version, no key), the `playerHash` filter, day rounding, status and retention, the Basic Auth clients and their rotation by restart, the
  offline command that produces an Argon2id PHC hash with the fixed parameters, the template review step for reasons,
  and erasure.
- [ ] 8.2 One line in `.claude/rules/public-api.md` saying the ban-list routes are Basic Auth protected and are not
  anonymous, and one pointer line in `CLAUDE.md` to the rule file (no other additions to `CLAUDE.md`).

## 9. Code pull request

- [ ] 9.1 Open the code PR from the implementation branch against `main` with the title
  `feat(public): add the ban list for authenticated clients`. Fill `.github/pull_request_template.md`.
  Describe the problem from the trusted clients' point of view (the website and partner networks need one list they can
  read and cite, and a way to match their own players, and today every answer is a manual copy). No session link
  anywhere. Do not merge.
