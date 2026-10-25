# OAuth and JWT audit (2026-10-25)

Scope: backend token validation, staff panel OIDC client, player portal session, Velocity and Discord
client-credentials providers, `docs/entra-setup.{sh,md}`, and the documentation that describes them.
Checked against RFC 8725 (JWT BCP), RFC 9700 (OAuth 2.0 Security BCP), RFC 7636 (PKCE), RFC 9068
(JWT access token profile) and RFC 6265bis (cookies).

Citation method: the code was read by read-only agents. The orchestrator spot-checked the lines marked
"checked" below. Every other citation must be re-checked at apply time before a fix lands. A requirement
with no code evidence is `unverified`, not `ok`.

## 1. Findings

Status: `ok` (matches the requirement), `partial` (some of it is done), `gap` (missing or wrong),
`unverified` (needs a runtime or library check; the code alone does not settle it).

### 1a. Backend JWT validation

| # | Requirement | Status | Evidence (file:line) | Fix |
|---|---|---|---|---|
| J1 | Algorithm allow-list, `none` rejected (RFC 8725 3.1, 3.2) | partial | Key type fixed to RSA: `backend/.../security/JwksSignatureFactory.java:35-36` (checked). No explicit `alg` allow-list anywhere (searched `Algorithm`, `alg`, `JWSAlgorithm`). `none` rejection tested: `backend/src/test/.../AuthenticationIntegrationTest.java:64` (`unsigned()`, from `TestJwtIssuer.java:167-168`). | Pin `RS256` (and any other Entra alg, if needed) explicitly in the signature config. Add a key-confusion test: an HS256 token signed with the public key bytes must get 401. Status of the library behaviour: **unverified**. |
| J2 | Audience check equals the API's accepted audience (RFC 8725 3.10) | partial | Default audience `blackhole-api`: `backend/src/main/resources/application.yml:466` (checked). The API registration uses `identifierUris: api://$API` (`docs/entra-setup.sh:109`, checked). A v2 access token carries the client id GUID in `aud`, so the default does not match a real Entra token. Failure is closed (401), but the default is wrong for production. | Make `BLACKHOLE_AUTH_AUDIENCE` required outside `dev`/`test`, and document that it is the API app's client id GUID. See D3. |
| J3 | Issuer exact match, tenant-specific v2 issuer (RFC 8725 3.8) | ok | Single issuer string: `application.yml:463` (checked). Insecure-issuer guard: `security/InsecureIssuerGuard.java:80-107`. Tenant check on `tid`: `security/TenantJwtClaimsValidator.java:262-276`, active only when `tenant-id` is set (`application.yml:78`). | Make `tenant-id` required outside `dev`/`test`, so the `tid` check is always on. |
| J4 | Expiry always checked (RFC 8725 3.5) | ok | Library default. Expired-token test: `AuthenticationIntegrationTest.java:36`. | none |
| J5 | `nbf` and clock skew bounded (RFC 8725 3.5) | unverified | No `leeway` or skew setting (searched `leeway`, `skew`, `clock` in `application.yml` and `security/`). Library default not checked. | Set an explicit, small leeway (for example 60 s) and test it with a token `nbf` 2 min in the future. |
| J6 | Token type separation: `typ` / token-kind check (RFC 8725 3.11) | gap | No `typ`, `ver` or `token_use` check (searched). `BlackholeRolesFinder.java:71-73` splits by `scp` presence only. An ID token from a different app fails the audience check (J2), so the practical risk is low. | Require `ver == "2.0"` and reject tokens whose `aud` is not the API GUID. Verify the Entra access token `typ` header value before asserting it. |
| J7 | Delegated versus app-only token kinds are separated (RFC 9068 section 2 analogue) | ok | `BlackholeRolesFinder.java:54-66` (checked): delegated tokens (`scp`) read `groups`, app-only tokens read `roles`. A value on the wrong kind grants nothing. Tests cover this per `staff-authentication` spec. | none |
| J8 | Caller is bound to an allowed client (`azp` or `appid`) for app-only tokens | gap | No `azp` or `appid` check (searched, zero hits in `backend/src/main/java`). `BlackholeRolesFinder.java:62-66` (checked) grants `PROXY`, `METRICS` and `DISCORD` from the `roles` claim alone. Any app that Entra lets obtain that app role gets the role. | Add an allow-list of client ids per machine role (config `blackhole.security.roles.machine-clients`). Reject app-only tokens from other clients. |
| J9 | Groups overage fails closed | ok | `BlackholeRolesFinder.java:79-87` (checked). Handler: `security/GroupsOverageExceptionHandler.java:305-331`. Graph is not called. | none |
| J10 | Token must not be logged | ok | `ActorResolver.java:156-166` reads claims, logs nothing token-related (per agent read). | Re-check at apply. |
| J11 | Actor from `oid`, never a client field | ok | `ActorResolver.java:158-161, 197-203`. | none |
| J12 | Stateless: no session or cookie on the API | ok | `micronaut.security.authentication: bearer` (`application.yml:454`). Only the portal sets cookies (see 1c). | none |

### 1b. Staff panel OIDC client (`web/`)

| # | Requirement | Status | Evidence | Fix |
|---|---|---|---|---|
| W1 | Authorization code flow, no implicit (RFC 9700 2.1.2) | ok | `response_type: 'code'` at `web/src/auth/authService.ts:50` (checked). | none |
| W2 | PKCE with method S256 for all clients (RFC 9700 2.1.1, RFC 7636 4.2) | unverified | No explicit `code_challenge_method` in app code (searched `code_challenge`, `pkce`). The library (`oidc-client-ts` 3.5.0) applies PKCE by default. `web/node_modules` is absent, so the default method is not checked. | Install deps and read the library default. If it is not S256, set it explicitly. Add a unit test that the authorize URL carries `code_challenge_method=S256`. |
| W3 | Tokens not in web storage (RFC 9700 4.2.3 analogue) | ok | `userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() })` at `authService.ts:52` (checked). Only `state` and the PKCE verifier use `sessionStorage` (`authService.ts:53`, `:60-62`). A test asserts no other browser storage is written (`authService.test.ts:236-243`). | none |
| W4 | Exact redirect URI registration (RFC 9700 4.1.3) | partial | Built from `window.location.origin` at `authService.ts:48` and `:61` (checked), with no check against config. Enforcement depends on the Entra registration: `docs/entra-setup.sh:136-138` registers exact URLs; `127.0.0.1` is added only for localhost. | Pin the expected origin in config and refuse to start when `origin` differs. Keep the Entra exact-match rule as the second layer. |
| W5 | Silent renew without a refresh-token rotation rule | partial | `automaticSilentRenew: true` at `authService.ts:54` (checked). `offline_access` requested at `authService.ts:38` and `:51`. No `useRefreshToken` setting, so the library default applies (unverified). Silent renew falls back to interactive sign-in (`authService.ts:105`). | Drop `offline_access` unless a refresh token is used. Document the iframe renew path. |
| W6 | Logout with registered post-logout URI | ok | `signoutRedirect()` at `authService.ts:119`; `post_logout_redirect_uri: ${origin}/` at `:49` (checked); the URI is registered at `entra-setup.sh:136`. | none |
| W7 | `state` and `nonce` validated; ID token validated | unverified | `state` handled by the library, and a test checks one `oidc.*` key (`authService.test.ts:279-285`). No `nonce` in app code (searched). The test at `authService.test.ts:263` uses an unsigned token, so the test does not prove signature checks. | Confirm library behaviour with deps installed. Keep the backend as the authority (it validates the access token, not the ID token). |
| W8 | Scope limited to what is needed | partial | `scope: 'openid profile offline_access <apiScope>'` (`authService.ts:38`, `:51`). `apiScope` is configured (`web/server/lib/config.ts:91-92`). | Drop `profile` and `offline_access` if not used. |
| W9 | Sender-constrained tokens (RFC 9700 4.1.1 or DPoP) | gap (decision) | Bearer tokens in browser memory. Access lifetime not pinned in app code. DPoP is not used. | Out of scope for this change: record it as an open question (Q3). |

### 1c. Player portal session (`portal/`, not OAuth)

| # | Requirement (RFC 6265bis) | Status | Evidence | Fix |
|---|---|---|---|---|
| P1 | `__Host-` prefix, `Secure`, `HttpOnly`, `Path=/`, no `Domain` | ok | Names: `portal/src/api/contract.ts:274-276` (checked). Attributes: `portal/server/lib/bff.ts:73-76` (checked): `httpOnly`, `secure`, `sameSite`, `path: '/'`. No `Domain` (`bff.ts:44`). | none |
| P2 | `SameSite` set | ok | `COOKIE_SAME_SITE` at `bff.ts:45`, value `lax`. | none |
| P3 | Bounded lifetime | ok | Absolute 4 h (`backend/src/main/resources/application.yml:161`, `PlayerSessionService.java:96`); idle 30 min (`application.yml:159`, `JdbcPlayerSessionStore.java:24-30`); cookie `maxAge` from `bff.ts:107-111`. | none |
| P4 | Session token stored hashed | ok | SHA-256 hex (`PlayerSessionService.java:107, 137-139`). Schema `V16__player_session.sql:11, 18`. | none |
| P5 | CSRF token stored hashed like the session token | gap (low) | `csrf varchar(64) NOT NULL` at `backend/src/main/resources/db/migration/V16__player_session.sql:14` (checked) is plaintext, and `PlayerSessionService.java:94` stores it as given. | Store `HMAC(csrf)` or a SHA-256 digest. Append a new migration (never edit V16). |
| P6 | Logout clears cookie and server row | ok | `bff.ts:79-80`; `PlayerSessionService.java:118-120`. | none |
| P7 | Login rate limit | ok by policy | Absent in code; delegated to the edge (`application.yml:150-153`). Matches CLAUDE.md rule 5. | none |

### 1d. Machine clients (Velocity and Discord, client credentials)

| # | Requirement | Status | Evidence | Fix |
|---|---|---|---|---|
| M1 | Secret not in code, not logged | ok | Velocity: env `BLACKHOLE_CLIENT_SECRET` (`velocity/.../config/AuthConfig.java`, per agent; toString redacts at `:144`). Discord: env `BLACKHOLE_DISCORD_CLIENT_SECRET` (`discord/.../auth/BotConfig.java:72`); `TokenSettings.toString` redacts (`TokenSettings.java:100-104`). | Re-check line numbers at apply. |
| M2 | Token endpoint must be HTTPS (secret never in clear) | gap | Velocity: `velocity/src/main/java/net/onelitefeather/blackhole/velocity/config/AuthConfig.java:129-130` accepts `http`. Discord: `discord/src/main/java/net/onelitefeather/blackhole/discord/auth/TokenSettings.java:86-89` (checked) accepts `http` and `https`. Overrides `BLACKHOLE_TOKEN_URL` and `BLACKHOLE_DISCORD_TOKEN_URL` are not gated by environment. | Require `https` unless the environment is `dev`/`test`. Test that an `http` URL is refused outside dev. |
| M3 | Token cache with skew, refresh on expiry and on 401 | ok | 60 s skew at `ClientCredentialsTokenProvider.java:24`; expiry at `:58`; 401 retry at `BearerHttpClient.java:39-44`. | none |
| M4 | Timeouts on the token call | ok | 10 s at `JdkTokenTransport.java:17-24`. | none |
| M5 | Lock not held across network I/O | gap (low) | `synchronized token()` calls the HTTP POST in `refresh()` (`ClientCredentialsTokenProvider.java:57, 73-76`). A slow IdP blocks all callers for up to about 20 s. | Refresh outside the monitor, or single-flight with a future. |
| M6 | Scope pinned to the API `.default` | partial | Scope is operator-supplied (`AuthConfig.java:22` per agent; `BotConfig.java:73`), with no default in code. The chart uses `api://<id>/.default` (`deploy/charts/blackhole/templates/backend-servicemonitor.yaml:35`). | Validate the scope shape (`.default` or `access_as_user`) at startup. |
| M7 | Each machine app holds only its role | ok | Setup: Velocity gets `Blackhole.Proxy` (`docs/entra-setup.sh:167`), Discord gets `Blackhole.Discord` (`:182`). | none |
| M8 | Metrics principal provisioned | gap (ops) | `docs/entra-setup.sh:124-125` defines `Blackhole.Metrics` but nothing assigns it. Documented manually at `docs/entra-setup.md:78-88`. | Script the assignment, or record that it stays manual. |

### 1e. Entra setup (`docs/entra-setup.sh`, `docs/entra-setup.md`)

| # | Item | Status | Evidence | Fix |
|---|---|---|---|---|
| E1 | v2 access tokens requested | ok | `requestedAccessTokenVersion: 2` at `docs/entra-setup.sh:112` (checked). | none |
| E2 | Optional claims | ok | Only `groupMembershipClaims` at `:110`; no `optionalClaims` (searched). | none |
| E3 | Redirect URIs exact | ok | `spa.redirectUris` at `:136-142` (checked). | none |
| E4 | Secret written with restricted mode, not printed | ok | `write_env` writes to `$OUT` (`:21-58`); `set -euo pipefail` at `:14`; `az rest` output to `/dev/null` (`:158, :172, :187`). | none |
| E5 | Secret creation atomic with its save | gap (low) | Velocity secret created at `:175` and written at `:193`. A failure between them loses the secret with no record. | Write the env file after each secret, or fail before creating. |
| E6 | Production origin must be `https` | gap (low) | Only presence is checked (`:65-67`, per agent). | Refuse a non-`https` origin outside `dev`. |
| E7 | Assignment required for the apps | gap (ops) | Assignment left off (`md:28-29`). A tenant member can get a delegated token; with no group they get no role (J7). | Keep, or require assignment. Decision Q2. |

## 2. Documentation conflicts

| # | Where | Says | Reality | Action |
|---|---|---|---|---|
| D1 | `CLAUDE.md` in the task brief: "no authentication system" | Premise is stale. The text is **not present** on `origin/main`. `CLAUDE.md:82-85` already says Entra ID JWT bearer on every route except `/health`. | Nothing to change in `CLAUDE.md` for this. |
| D2 | `.claude/agents/backend-reviewer.md:27` | "no `@Secured`/auth annotations (deliberately removed)" | Contradicts `CLAUDE.md:82`, `openspec/config.yaml:15-16` and `.claude/rules/security.md:17`. `@Secured` is on every controller. | **Fix** (task 1.1). This is the real stale "no auth" text. |
| D3 | `application.yml:466` audience default `blackhole-api`, and the comment at `:462-466` | Says the audience is the API app's client id GUID. The default is a non-GUID name. | Conflicts with `docs/entra-setup.sh:109` (`identifierUris: api://$API`). | **Fix** (task 1.2). |
| D4 | `CLAUDE.md` "Test coverage is minimal ... no other module has a `src/test` yet" | Velocity has `velocity/src/test/` (`I18nBundlesTest.java`, `punish/*Test.java`). | Stale. Also the Discord module tests are unverified. | **Fix** (task 1.1). |
| D5 | `openspec/config.yaml` `context` (principle 2) | Entra JWT bearer, `@Secured`, no auth-off switch. | Matches the code. No conflict. | none |
| D6 | `openspec/specs/staff-authentication/spec.md` "Bearer token validation" | Requires `aud` equal to the configured audience. Silent on what that value is for Entra. | Ambiguous, not wrong. Clarified by delta spec. | Delta spec (see `specs/staff-authentication/spec.md`). |
| D7 | `docs/entra-setup.md:78-88` | Metrics app role assignment is manual. | Matches the script (no assignment). | Keep, or fix with J8/M8. |

## 3. Verified OK, for the record

- Only the claim named for the token kind is read (`BlackholeRolesFinder.java:54-66`).
- Overage fails closed with 403 (`BlackholeRolesFinder.java:79-87`).
- Panel tokens never touch web storage (`authService.ts:52`).
- Portal cookies meet RFC 6265bis (`bff.ts:73-76`, `contract.ts:274-276`).
- Panel CSP sets `frame-ancestors 'none'` (`web/server/lib/headers.ts:21`, per agent).
- Velocity and Discord log nothing token-related.
