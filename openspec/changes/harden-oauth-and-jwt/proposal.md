## Why

The Entra ID sign-in path works, but the read-only audit in `audit.md` finds gaps against RFC 8725 (JWT
Best Current Practices), RFC 9700 (OAuth 2.0 Security BCP) and RFC 6265bis (cookies). The most
serious are a client secret that the Velocity and Discord token providers will send over plain
`http` when an override is set (`audit.md` M2), app-only tokens that are not bound to an allowed
client (J8), and a default audience that does not match a real Entra v2 token (J2). The documentation
also still says the API has no `@Secured` rule in one place (D2), which is wrong.

## Commit type and PR split

This change ships as several PRs, each with one Conventional Commits type (see `design.md`, Decision 1):

- `docs(openspec): propose oauth and jwt hardening` - this proposal (type `docs`).
- `docs` - stale documentation corrections (`audit.md` D2, D3, D4). No behaviour change.
- `fix(security)` - one PR per real gap. Each has failing tests first.

A change that mixes types is two changes, so the fixes never share a PR with a docs correction.

## What Changes

- Pin the accepted JWT signature algorithm explicitly and add a key-confusion test (J1).
- Require the API audience to be set outside `dev`/`test`, and make it the API client id GUID (J2). **BREAKING** for any deployment that relies on the default `blackhole-api`.
- Make the `tid` tenant check mandatory outside `dev`/`test` (J3).
- Bind app-only tokens to an allow-list of client ids per machine role (J8). **BREAKING** for deployments that do not configure the list.
- Reject tokens whose `ver` is not `2.0` (J6), and set an explicit, small clock-skew leeway (J5).
- Refuse non-`https` token endpoints for Velocity and Discord outside `dev`/`test` (M2). **BREAKING** for any dev-style override in production.
- Pin the panel's redirect origin to configuration and test the PKCE method (W2, W4).
- Hash the portal CSRF token at rest (P5), via a new Flyway migration (append-only).
- Fix the stale documentation (D2, D3, D4), and script the metrics role assignment or document it as manual (M8).
- Remove `offline_access` and unused scopes from the panel request (W5, W8).

Out of scope:
- DPoP or other sender-constrained tokens (W9). Recorded as an open question (Q3).
- Replacing Entra ID, changing the group model, or changing the role matrix.
- Player portal login (it is a session, not OAuth, and its cookies already meet RFC 6265bis).
- Rate limiting (infra owns it, per `openspec/config.yaml` principle 5).
- Reintroducing a break-glass login or an auth-off switch (forbidden by principle 2).
- Multi-tenancy: the one-tenant-per-deployment model stays.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `staff-authentication`: adds the algorithm pin, mandatory audience and tenant outside dev, client-id binding for app-only tokens, `ver` check, clock-skew bound, and the HTTPS rule for machine-client token endpoints. The delta is in `specs/staff-authentication/spec.md`.

The panel OIDC behaviour (W2, W4, W5, W8) and the portal cookie (P5) are not specified in `openspec/specs/`
today. They are covered by unit tests and `design.md`, not by a spec delta, because the change to them is
implementation detail and not an externally observable contract.

## Impact

- **Backend**: `security/` (signature config, `BlackholeRolesFinder`, `TenantJwtClaimsValidator`, a new client-id allow-list), `application.yml` (`blackhole.security.*` defaults and comments), one new Flyway migration `V<n>__player_session_csrf_hash.sql` that hashes the existing CSRF values (no edit to V16).
- **Velocity and Discord**: `AuthConfig.java` and `TokenSettings.java` (HTTPS rule), `ClientCredentialsTokenProvider.java` (lock scope, low priority).
- **Panel**: `web/src/auth/authService.ts` (scope, origin pin, PKCE test).
- **Ops**: `docs/entra-setup.sh` and `docs/entra-setup.md` (metrics assignment, atomic secret save, `https` origin check).
- **Generated client**: no HTTP API change is planned, so `client/specs` is not regenerated. Confirm during apply.
- **Velocity plugin**: BREAKING only for an `http` token URL, which it should not have.
