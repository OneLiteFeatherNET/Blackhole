# Tasks

Prerequisites: ADR-BLKH-0002 is accepted; `staff-temp-bans-elo-escalation`,
`capture-proxy-chat-evidence` and `serve-web-with-nitro` are merged (rebase otherwise). The work ships as a stack of small
PRs, one per section 1-5, each branching from the previous one and merged back-to-back with no
release in between (design decision 9). Backend tests follow F.I.R.S.T.: self-signed JWTs, an
injected `Clock`, a fresh key and application context per class, no network to Entra. UI tests
use Vitest with a fake `UserManager` and `fakeFetch`.

## 1. Backend security foundation (PR 1: `feat(auth)!: require entra id sign-in for the api and admin ui`)

- [x] 1.1 Add Micronaut Security and its JWT module to `backend/build.gradle.kts` and the `security` config block to `application.yml` (issuer, audience, JWKS URL, human and machine role claim names, per-role values with group object ids from env vars, machine role values, `allow-insecure-issuer`, `docs.enabled`), each tunable documented by a comment that says the human values are Entra group object ids; verify `./gradlew :backend:compileJava` and that the config binds in a context test
- [x] 1.2 Insecure-issuer startup guard; verify tests that a non-dev context with `http://localhost` fails to start, and that dev, test and the explicit override start
- [x] 1.3 `TestJwtIssuer` (per-class RSA key, Nimbus signing, injected `Clock`) and a `test`-environment signature bean; verify tests for valid, expired, wrong issuer, wrong audience, bad signature, `alg=none` and missing token (401), all offline
- [x] 1.4 `RolesFinder`: delegated tokens map the configured human claim (default `groups`, object ids) to `ADMIN`/`MODERATOR`/`VIEWER`/`METRICS`, app-only tokens map the machine claim (default `roles`) to `PROXY`/`METRICS`; role hierarchy, claim chosen by token kind, groups overage (`_claim_names`/`hasgroups`) answered 403 with reason `groups-overage` and a log line, no Graph call; verify table-driven unit tests for every scenario in `staff-authentication`: each role by group id, no mapped group (403 everywhere, `GET /auth/me` empty roles), overage, wrong claim for the token kind, and the config-only switch of the human claim to `roles` with app role values
- [x] 1.5 Apply the permission matrix with `@Secured` constants on every controller (no logic added to controllers); verify the route-coverage test: every route has a rule, allowed roles equal the matrix table, `/public/**` is empty, and an unannotated test route is rejected
- [x] 1.6 Public and operational routes: `/health` public without details, `/prometheus` needs `METRICS`, Swagger registered only with `blackhole.docs.enabled`; verify `@MicronautTest` HTTP tests for each (401/403/200)
- [x] 1.7 `GET /auth/me` through a service and a sealed DTO (`oid`, `displayName`, `roles`) per the controller and DTO rules; verify tests for human and machine tokens
- [x] 1.8 OpenAPI security scheme (bearer JWT) and the new client spec version in `client/specs/` (regenerate, bump `inputSpec` in `client/build.gradle.kts`); verify `./gradlew :client:openApiGenerate` and that the spec lists the scheme and `/auth/me`
- [x] 1.9 Mock OIDC server in `docker/docker-compose.yml` (pinned `mock-oauth2-server` tag) with `docker/mock-oauth/config.json`: users admin, moderator, viewer and one in no group, each carrying `oid`, `name`, `scp` and a `groups` claim of fake object ids that match the dev defaults in `application.yml`; proxy and metrics client-credentials callbacks giving `roles` without `scp`; verify against the running stack: a token from the mock server calls `/auth/me` with the expected roles, a viewer token gets 403 on `POST /section`, and the no-group user gets 403 everywhere but `/auth/me`
- [x] 1.10 Full backend verification; run `./gradlew :backend:test` and `./gradlew clean build`

## 2. Verified actor on writes (PR 2: `feat(auth)!: record the verified actor on every write`)

- [x] 2.1 Append `V9__staff_actor_and_recorded_by.sql` (next free number): `staff_actor` table and nullable `recorded_by_oid` on the punishment, report, appeal, `evidence_items` and `evidence_audit` tables; verify the PostgreSQL Testcontainers integration test migrates a database that already holds V1-V8 rows (legacy rows keep their actor value, new column NULL) and Hibernate `validate` passes
- [x] 2.2 `ActorResolver` and `staff_actor` upsert in a new `security` package, no `@Transactional`; verify unit tests for first write, name change, `HUMAN` vs `SERVICE`, and that email/UPN claims are never read
- [x] 2.3 Remove the client-supplied actor fields (`source`, `resolvedBy`, `reviewerId`, `punishmentSource`, evidence `source`/`createdBy`, ELO adjustment `source`) from the request DTOs, path variables and `*Api` interfaces; set the actor from `ActorResolver`; write `recorded_by_oid` / `recordedByOid`; verify per endpoint a test that a spoofed field is ignored and the token's `oid` is stored
- [x] 2.4 `onBehalfOf` for punishment, revocation and evidence writes, accepted only for `PROXY` (400 otherwise); verify tests for both roles and for the absent value
- [x] 2.5 Self-review compares the reviewer's `oid` with the punishment's source and `recorded_by_oid`; verify tests for same-oid (403), different moderator (200), spoofed `reviewerId` ignored and a legacy punishment
- [x] 2.6 Expose unverified (NULL `recorded_by_oid`) vs verified in the read DTOs and events where actors appear (`punishment.revoked`, evidence audit); verify DTO and event tests
- [x] 2.7 `POST /admin/staff-actor/{oid}/anonymize` (`ADMIN`, audited with the admin's `oid`); verify tests incl. 403 for moderators and that history still references the `oid`
- [x] 2.8 Regenerate the client spec; adapt Velocity call sites to compile against the regenerated client (they send `onBehalfOf` instead of `source`, no auth yet); verify `./gradlew build` across all modules
- [x] 2.9 Verify `./gradlew :backend:test` and the integration tests (`-PexcludeTags=integration` and without)

## 3. Velocity machine credentials (PR 3: `feat(velocity): authenticate to the backend with client credentials`)

- [x] 3.1 `BlackholeConfig` gains `auth` (`tenantId`, `clientId`, `clientSecret`, `scope`, dev `tokenUrl`) with env-var overrides and validation; verify unit tests for defaults, file values, env override precedence and that the secret is never logged
- [x] 3.2 `ClientCredentialsTokenProvider` (JDK `HttpClient` behind an interface, injected `Clock`, 60 s skew, single-flight refresh); verify tests with a fake transport: reuse inside lifetime, refresh near expiry, concurrent callers cause one request, error responses surface a clear exception
- [x] 3.3 Install the bearer interceptor in `BlackholeApiClients` with one 401 refresh-and-retry; verify tests with a fake server transport for the retry and for no retry loop
- [x] 3.4 Fail-safe when credentials are incomplete: startup error log, localized message for backend-bound commands, Redis enforcement continues; verify tests and add the message to all locale files
- [x] 3.5 Update the `proxy-enforcement` behaviour tests for `onBehalfOf`; verify `./gradlew :velocity:test` and `:velocity:build`
- [x] 3.6 Verify against the mock OIDC server: the plugin (or a small harness) obtains a `Blackhole.Proxy` token and applies a punishment recorded with the proxy `oid` and the delegated UUID

## 4. Admin UI sign-in (PR 4: `feat(web): sign in with entra id in the admin ui`)

- [x] 4.1 Add `oidc-client-ts` (current stable, exact version pinned) and `authService.ts`: `UserManager` with PKCE, in-memory `userStore`, `sessionStorage` state store, `automaticSilentRenew`; verify unit tests with a fake manager that no token reaches any web storage and the state is removed after the callback
- [x] 4.2 Router guard, `/auth/callback` route, sign-out with end-session; verify router tests for the redirect, callback and return-to route
- [x] 4.3 Bearer header, one renew-and-retry on 401, 403 handling in `src/api/http.ts`; remove `source`/`reviewer` plumbing from `blackhole.ts` and `config.ts`; verify the existing API wrapper tests updated, plus tests for 401 retry once and 403 no retry
- [x] 4.4 `/config.json` in `web/server/lib/config.ts` (served by `web/server/routes/config.json.ts`): `BLACKHOLE_UI_OIDC_AUTHORITY`, `_OIDC_CLIENT_ID`, `_API_SCOPE` replace `BLACKHOLE_UI_SOURCE` / `_REVIEWER` with strict validation and fail-fast startup; add the authority origin to the CSP `connect-src` in `web/server/lib/headers.ts`; verify the Vitest tests in `web/server/lib/` for valid, missing and malformed values and the exact CSP string
- [x] 4.5 `GET /auth/me` store, "signed in as" in the top bar, removal of the "Acting as" label, role-aware hiding or disabling of actions per the matrix, and a "no access" page for a signed-in user with an empty role list; regenerate `src/api/schema.d.ts` from the new spec; verify component tests per role (none, viewer, moderator, admin) and `vue-tsc --noEmit`
- [x] 4.6 German and English strings, a11y check of the sign-in states (focus, `aria-live` for the redirect message); verify the locale-parity test and component tests
- [x] 4.7 Verify against the mock OIDC server in a browser: sign-in as each test user, silent renewal (short token lifetime in the mock config), reload, sign-out; note the results in the PR

## 5. Documentation and principle change (PR 5: `docs(auth): replace the no-auth principle with entra id sign-in`)

- [ ] 5.1 Rewrite principle 2 in `openspec/config.yaml` ("Team SSO via Entra ID, verified actor, no tenancy") and keep principles 1 and 3-6 intact; verify `openspec validate --all --strict`
- [ ] 5.2 Update `CLAUDE.md`: replace the "No authentication system" bullet and the "Known gap" paragraph, the `@Secured`/micronaut-security warning and the Modules/Commands notes (mock OIDC in compose); verify no remaining text says the API is open
- [ ] 5.3 Update `.claude/rules/*` mentions (`evidence.md` "no auth layer", `controller-versioning.md` to add the security-rule convention) and the `application.yml` header comment that explains the old trust model; verify `grep -rni "no auth\|no authentication\|every endpoint is open" --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=build .` only finds historical archive entries
- [ ] 5.4 Update `README.md` and `web/README.md` (remove the identity-gap section, add the sign-in, roles, retention statement, local dev with the mock server) and add an operator migration guide covering Velocity config, web env, backend env and the rollout order; verify by following the guide on a clean compose stack
- [ ] 5.5 Add the Entra setup how-to for Entra ID Free (API registration with `accessTokenAcceptedVersion: 2` and `groupMembershipClaims: "SecurityGroup"`; machine app roles `Blackhole.Proxy` and `Blackhole.Metrics` with allowed member type applications; groups `sg-blackhole-prod-admin|edit|view|ops` and how to copy their object ids into the `BLACKHOLE_AUTH_ROLE_*` variables, since tokens carry ids not names; "Assignment required" off; SPA redirect URIs; Velocity and metrics client secrets with admin consent; secret rotation; the P1 upgrade path as a config-only switch to app-role assignment) in `README.md` or `docs/`; verify a peer review by the Entra admin

## 6. Verification against a real Entra tenant

- [ ] 6.1 Manual check by the user with an Entra admin on the Free tier (app registration is an admin action, not automated): register the API (Application ID URI, `accessTokenAcceptedVersion: 2`, `groupMembershipClaims: "SecurityGroup"`, application app roles `Blackhole.Proxy` and `Blackhole.Metrics`), the SPA (PKCE, redirect URIs) and the Velocity and metrics client apps (secret, admin consent for the app role; confirm this works without P1), create the four `sg-blackhole-prod-*` groups and configure their object ids, then verify: staff sign in and see role-appropriate UI; a tenant member in no group gets the no-access page and 403 on every protected route; a non-tenant user is refused; the proxy applies a ban; a spoofed actor field is ignored; the self-review check fires; `/prometheus` is scraped with a `Blackhole.Metrics` principal; removing a user from a group takes effect at token expiry
- [ ] 6.2 Record the outcome and any adjustments (claim names, licence behaviour, group ids) in the PR and update ADR-BLKH-0002 status to accepted if the check passes

## 7. Pull requests

- [ ] 7.1 Open the stacked pull requests, each with a Conventional Commit title and each based on the previous branch: `feat(auth)!: require entra id sign-in for the api and admin ui` (section 1, `BREAKING CHANGE:` footer describing the required bearer token, removed actor fields, Velocity and web settings), `feat(auth)!: record the verified actor on every write`, `feat(velocity): authenticate to the backend with client credentials`, `feat(web): sign in with entra id in the admin ui`, `docs(auth): replace the no-auth principle with entra id sign-in`; verify CI is green on each and that commitlint accepts the titles
