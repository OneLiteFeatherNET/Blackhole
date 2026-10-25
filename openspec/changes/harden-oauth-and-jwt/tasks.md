# Tasks

Each numbered group is one PR, with one Conventional Commits type (see `proposal.md`). Every backend or
client test is written and seen to fail before the code it covers. All tests are F.I.R.S.T.: they use
self-signed JWTs (`TestJwtIssuer`), a fresh fixture per test, no sleeps, no network and no system-time
reads except with explicit offsets of at least 30 seconds, and they assert on behaviour, not on logs.

## 1. Documentation corrections (PR type `docs`)

- [x] 1.1 Remove the stale "no `@Secured`" text from `.claude/agents/backend-reviewer.md:27` (the stale text is in that agent file, not in `CLAUDE.md`), and correct the "no other module has a `src/test`" sentence in `CLAUDE.md` (Velocity has tests). Verify: `grep -rn "deliberately removed" .claude/agents` returns nothing, and `ls velocity/src/test` is cited correctly in `CLAUDE.md`.
- [x] 1.2 Correct the audience comment and default note at `backend/src/main/resources/application.yml:462-466` so it states the API client id GUID, and check whether `deploy/charts/blackhole/values.yaml` sets `BLACKHOLE_AUTH_AUDIENCE` and `BLACKHOLE_AUTH_TENANT_ID`. Verify: the comment names the GUID; if the chart lacks the values, add them and make `deploy/charts/blackhole/tests/run-all.sh` pass.
- [x] 1.3 Confirm `CLAUDE.md:82-85` and `openspec/config.yaml` still match the code, so no change is needed for them. Verify: `grep -n "Entra" CLAUDE.md openspec/config.yaml` shows no "no authentication" text.

## 2. Machine clients require https token endpoints (PR type `fix(security)`)

- [x] 2.1 Write failing tests: `velocity/src/test/.../config/AuthConfigTest` asserts that `BLACKHOLE_TOKEN_URL=http://...` is refused outside `dev`; `discord/src/test/.../auth/TokenSettingsTest` asserts the same. Verify: `./gradlew :velocity:test --tests "*AuthConfigTest*"` and `./gradlew :discord:test --tests "*TokenSettingsTest*"` fail first.
- [x] 2.2 Implement the gate in `velocity/.../config/AuthConfig.java:129-130` and `discord/.../auth/TokenSettings.java:86-89`. Verify: the two tests pass.
- [x] 2.3 Test that no request carries the secret to an `http` endpoint, using a fake transport. Verify: `ClientCredentialsTokenProviderTest` asserts zero transport calls for an `http` endpoint.

## 3. Backend JWT hardening (PR type `fix(security)`, one PR per numbered item, each BREAKING where noted)

- [x] 3.1 Record the real Entra v2 claims (`ver`, `azp`, `aud`, `typ` header) from the dev registration, using `docs/entra-setup.sh dev` and `~/.config/blackhole/entra-dev.env`. Paste the claim names and values (no token) into the PR description. Verify: the PR description shows `ver: "2.0"` and `aud` as a GUID. This settles audit items J2 and J8.
- [x] 3.2 Pin `RS256`: write `security/JwksSignatureAlgorithmTest` with a key-confusion token (`HS256`, secret = public key bytes), expecting 401. Watch it fail, then pin the algorithm in `JwksSignatureFactory.java:20-41`. Verify: the test passes; `./gradlew :backend:test` is green.
- [ ] 3.3 Reject `ver` other than `2.0`: write `security/TokenVersionTest` (a `ver: "1.0"` token expects 401), then add the check. Verify: test green.
- [ ] 3.4 Bound clock skew at 60 s: write `security/ClockSkewTest` (`nbf` 2 min ahead → 401; `exp` 30 s past → 200), then set the leeway in `application.yml`. Verify: test green.
- [ ] 3.5 Mandatory audience and tenant outside dev: write `security/StartupRequirementsTest` (empty audience, `blackhole-api`, and `api://<guid>` each fail to start in a non-dev environment), then implement the guard beside `InsecureIssuerGuard.java:80-107`. Verify: test green. BREAKING.
- [ ] 3.6 Bind machine roles to `azp`: write `security/MachineClientBindingTest` (a listed `azp` gets `PROXY`; an unlisted one gets nothing), then add `blackhole.security.roles.machine-clients.*` and the check in `BlackholeRolesFinder.java:62-66`. Update `PermissionMatrixIntegrationTest` for the new config. Verify: green. BREAKING.

## 4. Hash the portal CSRF token at rest (PR type `fix(security)`)

- [ ] 4.1 Write a failing test in `publicapi/PlayerSessionServiceTest` that the stored `csrf` value is a SHA-256 digest and never the raw token, and that a request with the correct raw token still passes. Verify: test fails first.
- [ ] 4.2 Add the new Flyway migration `V25__player_session_csrf_hash.sql` (append-only; V16 is not edited) that replaces each `player_session.csrf` with its SHA-256 digest, and change `PlayerSessionService.java:94` to store the digest. Verify: `./gradlew :backend:test` is green, and the migration runs against a scratch PostgreSQL (see `.claude/rules/database.md`).

## 5. Panel OIDC client (PR type `fix(security)`, `web/`)

- [ ] 5.1 Write failing unit tests in `web/src/auth/authService.test.ts`: the authorize URL carries `code_challenge_method=S256`, and `createUserManager` refuses to start when `window.location.origin` differs from the configured panel origin. Verify: fail first, with deps installed (`npm ci` in `web/`), which also settles audit W2 and W7.
- [ ] 5.2 Implement the origin pin in `authService.ts:48-61` and set `code_challenge_method` explicitly if the library default is not S256. Verify: tests pass.
- [ ] 5.3 Remove `offline_access` and `profile` from `SCOPES` (`authService.ts:38`) if the UI does not read them. Verify: a test asserts the scope string, and the sign-in still works in the dev stack.

## 6. Entra setup script (PR type `fix(security)`, `docs/`)

- [ ] 6.1 Refuse a non-`https` production origin in `docs/entra-setup.sh:65-67`, and save each client secret to the env file immediately after creation (`:175`, `:190`). Verify: `shellcheck docs/entra-setup.sh` is clean, and a dry-run with a non-`https` production origin exits non-zero before creating anything.
- [ ] 6.2 Assign `Blackhole.Metrics` in the script, or document the manual step as the decision (proposal, `audit.md` M8). Update `docs/entra-setup.md:78-88` to match. Verify: the doc and script agree; `shellcheck` clean.
- [ ] 6.3 Require user assignment on the Admin UI app (decision Q2, design Decisions 2026-10-25): the script sets `appRoleAssignmentRequired` to true on the `Blackhole Admin UI (<env>)` service principal, and `docs/entra-setup.md` states it as a production step. Verify: `shellcheck docs/entra-setup.sh` is clean, and a dry-run prints the PATCH and creates nothing.

## 7. Open the pull requests (last task)

- [ ] 7.1 For each PR group above (1 through 6), push its branch and run `gh pr create --base main` with a Conventional Commits title and the body ending with `https://claude.ai/code/session_01TFcvDovaYNHZewdbM2f8Ru`. Run `gh pr checks --watch` on each. Do not merge. Verify: each PR URL is listed in the change's `tasks.md` under this task, and the proposal PR is `docs(openspec): propose oauth and jwt hardening`.

## Workflow follow-up

- Archive this change after all PRs merge: `docs(openspec): archive harden-oauth-and-jwt`.
