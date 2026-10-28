## Conventions

- Branch from the up-to-date `origin/main`. One code PR for this change, under `feat(public)`.
- Red, then green, then refactor: each group starts with the failing test named in it.
- F.I.R.S.T. for every backend test: no `Thread.sleep`, the clock is an injected `java.time.Clock`, the key is a
  fixed test key, no network, fresh fixtures per test (`@TempDir` where a file is needed), one behaviour per test.
- Migration number: the next free Flyway version at implementation time (main head `V27__kick.sql` today). Never edit
  an applied migration. Client spec: the next version after the one in `client/build.gradle.kts` at implementation
  time (`0.0.44` today).
- Do not add a Co-Authored-By or session trailer to commits.
- Preconditions: `standardize-api-pagination-and-problems` is applied (envelope, problem body, `CursorCodec`, the
  `BLACKHOLE_CURSOR_KEY` config). `add-kick` is applied (`PunishmentKind`).

## 1. Publication model (backend)

- [ ] 1.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/publicbans/PublicBanHashTest.java`: the
  hash is 22 base64url characters; the same identifier and key give the same hash; a different identifier gives a
  different hash; a different `BLACKHOLE_PUBLIC_BAN_KEY` gives a different hash; the hash of the same bytes under
  the Ban ID key differs (separate labels).
- [ ] 1.2 Red: `PublicBanDayTest.java`: day rounding of an instant at 23:59:59 UTC and at 00:00:00 UTC; the day is
  UTC regardless of the default zone.
- [ ] 1.3 Red: `PublicBanStatusTest.java` with a fixed `Clock`: ACTIVE for a permanent entry; EXPIRED once the expiry
  instant has passed without a sweep; REVOKED wins over EXPIRED; ENDED entries past the retention window are not
  listed (the same rule as the SQL condition).
- [ ] 1.4 Red: `PublicBanPublicationTest.java`: a template with a public reason publishes a `PUNISHMENT`; a template
  without one, a manual punishment and a `KICK` publish nothing; a later template edit does not change the copied
  reason.
- [ ] 1.5 Red: `PublicBanTemplateValidatorTest.java`: 200 characters are accepted and 201 refused; blank is treated as
  absent; an IPv4 address, a UUID and a Ban ID are refused with a message that does not echo the value.
- [ ] 1.6 Implement: `publicbans/PublicBanHasher.java` (HKDF-SHA256 derivation from `BLACKHOLE_PUBLIC_BAN_KEY`, label
  `blackhole-public-ban-entry-v1`, HMAC over the identifier, 16 bytes, base64url), `PublicBanDay.java` (UTC day),
  `PublicBanStatus.java` (pure function of the stored columns and the clock).
- [ ] 1.7 Implement: `publicbans/PublicBanKeyGuard.java` modelled on `BanIdKeyGuard`: refuse to start without the key
  outside `dev` and `test`; the message names the variable and never prints the key. Test first:
  `PublicBanKeyGuardTest.java`.
- [ ] 1.8 Migration `backend/src/main/resources/db/migration/V<next>__public_ban_list.sql`: `punishment_template.public_reason`;
  `punishments.public_reason`, `public_kind`, `public_hash` (unique), `public_issued_day`, `public_expires_at`,
  `public_revoked_at`; index `(public_issued_day, public_hash)`. Test first: a repository test that applies the
  migration to a scratch database and checks the columns and the unique index.
- [ ] 1.9 Template field: `PunishTemplateDTO` (`publicReason` on the request and the response, optional), `TemplateSpec`,
  `TemplateValidator` (length, PiiGuard, blank treated as absent, ADMIN-only is enforced in the controller's role rule).
- [ ] 1.10 Publication writer: on apply, copy `publicReason` and the section kind, compute the hash, set
  `public_issued_day`, `public_expires_at`; on amendment update `public_expires_at`; on revoke set `public_revoked_at`.
  Kicks are skipped. Test first: `PublicBanPublicationWriterTest.java` (the four cases of 1.4, plus the amendment and the
  revoke).
- [ ] 1.11 Erasure: the erasure path clears the publication columns of the erased owner's punishments. Test first:
  `PublicBanErasureTest.java`.
- [ ] 1.12 Tests green: `./gradlew :backend:test --tests "*publicbans*" --tests "*punishment*"`, then the full
  `./gradlew :backend:test` before the code PR.

## 2. Read API (backend)

- [ ] 2.1 Red: `PublicBansRouteRuleTest.java`: `GET /public/bans`, `GET /public/bans/summary` and `GET /public/bans/{hash}`
  are anonymous; a staff, proxy and player-session token gain nothing on them; the route-coverage test lists these three
  as the only anonymous `GET` routes under `/public` besides the login.
- [ ] 2.2 Red: `PublicBansListTest.java` (service, fixed `Clock`, in-memory fixture from the JDBC reader's test double):
  the default limit is 50; 201 rows return 200 rows and a `nextCursor`; the last page has `nextCursor` null; `limit=0`
  and `limit=500` are 400 with an `errors` entry; a tampered cursor is 400 `INVALID_CURSOR`; kind and status filters.
- [ ] 2.3 Red: `PublicBanEntryFieldsTest.java`: the JSON of an entry has exactly the eight allowed fields; a fixture
  that holds a Ban ID, a reference, an owner hash and a UUID shows none of them in the body, the `Link` header or the
  cursor.
- [ ] 2.4 Red: `PublicBanNotFoundTest.java`: an unknown hash, a malformed hash and an entry outside the retention window
  give identical 404 bodies.
- [ ] 2.5 Red: `PublicBanCacheHeadersTest.java`: `Cache-Control` `public, max-age=60, stale-while-revalidate=300`;
  `Vary: Accept`; `ETag` and `304` on `If-None-Match`; errors are `no-store`.
- [ ] 2.6 Red: `PublicBanSummaryTest.java`: a row of 8 with 5 ACTIVE returns `active` 5 and `total` 8; a row of 2 returns
  `null` and `suppressed: true`; the totals equal the list's count for the same reason and kind.
- [ ] 2.7 Implement: `publicbans/controller/PublicBansApi.java` (OpenAPI annotations, `operationId`s `publicBanList`,
  `publicBanEntry`, `publicBanSummary`), `PublicBansController.java` (thin, `@Version(ApiVersion.V1)`, one service call
  each, `SecurityRule.IS_ANONYMOUS`, response headers from the service result), `dto/PublicBansDTO.java` (one sealed
  marker interface: `Entry`, `Page`, `Summary`, `Error`).
- [ ] 2.8 Implement: `publicbans/service/PublicBansService.java` (`@Singleton`, returns the `Error` variant for expected
  failures), `JdbcPublicBanReader.java` (keyset query `ORDER BY public_issued_day DESC, public_hash DESC` with the
  window condition `COALESCE(public_revoked_at, public_expires_at)`, no `@Transactional`), summary query.
- [ ] 2.9 Implement: `application.yml` settings with comments, following the `${ENV:default}` pattern:
  `blackhole.public-bans.retention` (`BLACKHOLE_PUBLIC_BANS_RETENTION`, `P90D`), `blackhole.public-bans.max-age`
  (`BLACKHOLE_PUBLIC_BANS_MAX_AGE`, `PT60S`), `blackhole.public-bans.summary-min-count`
  (`BLACKHOLE_PUBLIC_BANS_SUMMARY_MIN_COUNT`, `5`), and `BLACKHOLE_PUBLIC_BAN_KEY` with an empty default.
- [ ] 2.10 Integration test (in-process server, fresh instance, no sleeps): `PublicBansIntegrationTest.java`: a
  template with a public reason, apply, revoke, list, summary, single read, 404 after the window (with the injected
  clock moved, not a wait).
- [ ] 2.11 Update the route-coverage test expectations for the three anonymous routes, and `.claude/rules/public-api.md`
  (the one `no-store` exception, with the reason).

## 3. Client contract

- [ ] 3.1 Red: `client/specs` contract check (existing spec-diff test, if present) fails on the new routes and the new
  template field until the spec is regenerated.
- [ ] 3.2 Add `client/specs/blackhole-api-<next>.yml` (next version after `0.0.44`) with the three routes, the entry,
  page, summary and error schemas, and the template field. Bump `inputSpec` in `client/build.gradle.kts`.
- [ ] 3.3 Regenerate with `./gradlew :client:openApiGenerate` and run `./gradlew :client:compileJava`. Do not edit the
  generated sources by hand.

## 4. Portal (BFF and page)

- [ ] 4.1 Red: `portal/server/lib/bff.test.ts`: `/api/bans`, `/api/bans/summary` and `/api/bans/:hash` forward no
  cookie, accept only their query and shape parameters, and relay the backend's `Cache-Control` and `ETag` because
  `publicCache` is set; a session route still answers `no-store`; a malformed hash is 404 without a backend call.
- [ ] 4.2 Red: `portal/src/api/portal.test.ts`: the client functions build the query for `limit`, `cursor`, `kind`,
  `status`, and return the `nextCursor` unchanged.
- [ ] 4.3 Implement: `portal/src/api/contract.ts` (three `ROUTES` entries, a `publicCache` flag on `RouteSpec`),
  `portal/server/lib/bff.ts` (relay the public cache headers only for flagged routes), `portal/src/api/portal.ts`.

## 5. Portal page `/bans`

- [ ] 5.1 Red: `portal/src/views/BansView.test.ts`: renders the summary table; renders entries with status and kind
  labels; "load more" appends and hides when `nextCursor` is null; a suppressed summary row shows the "fewer than
  five" label, not a number; there is no search input; no name or Ban ID field.
- [ ] 5.2 Red: `portal/src/bansRoute.test.ts`: `/bans` and `/bans?entry=<hash>` select the view by pathname, like `/leave`.
- [ ] 5.3 Implement: `portal/src/views/BansView.vue` (Material Design 3 tokens, phone width with a 16px gutter, no
  horizontal scroll), `portal/src/main.ts` (pathname selection), locale strings in both portal locales, and a test
  that every key exists in both locales (`i18n.test.ts`).

## 6. Admin web

- [ ] 6.1 Red: `web/` component test for the template editor: an ADMIN sees the public reason field with a counter; a
  MODERATOR sees the read-only note; a refused save shows the message next to the field.
- [ ] 6.2 Implement: the field in the template editor, its validation, and the API type from the regenerated client.

## 7. Documentation

- [ ] 7.1 `.claude/rules/public-bans.md` (path-scoped to `backend/**/publicbans/**`): the publication rule, the hash
  rule, day rounding, status and retention, cache exception, erasure, and the template review step for reasons.
- [ ] 7.2 One pointer line in `CLAUDE.md` to that rule file (no other additions to `CLAUDE.md`).

## 8. Code pull request

- [ ] 8.1 Open the code PR from the implementation branch against `main` with the title
  `feat(public): add the anonymous ban list with a summary per reason`. Fill `.github/pull_request_template.md`.
  Describe the problem from the community's point of view (a player or a moderator asks why a ban was given and the
  answer depends on who asks). No session link anywhere. Do not merge.
