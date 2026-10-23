# Spec Delta

## Purpose

Defines how the backend authenticates callers with Microsoft Entra ID access tokens, maps token claims to roles, which role each endpoint group needs, which routes stay public, and how the system runs without Entra in development and tests.

## ADDED Requirements

### Requirement: Bearer token validation
The backend SHALL require `Authorization: Bearer <JWT>` on every route except the public routes, and MUST accept a token only if its signature verifies against the configured JWKS, its `iss` equals the configured issuer, its `aud` equals the configured API audience and it is not expired. A request with a missing or invalid token MUST be answered with HTTP 401 and MUST NOT reach a controller. The backend MUST keep no session and set no cookie.

#### Scenario: Valid token
- **WHEN** a request carries a correctly signed, unexpired token with the configured issuer and audience
- **THEN** the request is authenticated and routed

#### Scenario: Missing token
- **WHEN** a request to a protected route carries no `Authorization` header
- **THEN** the response is HTTP 401

#### Scenario: Wrong issuer, audience or signature
- **WHEN** the token has another issuer, another audience, a signature from an unknown key, or `alg` `none`
- **THEN** the response is HTTP 401

#### Scenario: Expired token
- **WHEN** the token's `exp` is in the past
- **THEN** the response is HTTP 401

### Requirement: Insecure issuer guard
The backend MUST refuse to start when the configured issuer uses plain `http` or a `localhost` host and the active environment is neither `dev` nor `test`, unless `blackhole.security.allow-insecure-issuer` is `true`. There SHALL be no setting that disables authentication.

#### Scenario: Production with the local default issuer
- **WHEN** the backend starts in a non-dev environment with the default `http://localhost` issuer
- **THEN** startup fails with a message naming the issuer setting

### Requirement: Role mapping
The backend SHALL derive internal roles `ADMIN`, `MODERATOR`, `VIEWER`, `PROXY` and `METRICS` from token claims, with configurable claim names and accepted values per role, and MUST hard-code no group name or object id. `ADMIN` MUST imply `MODERATOR` and `MODERATOR` MUST imply `VIEWER`. A token without a mapped value MUST have no role and be answered with HTTP 403 on any role-protected route.

#### Scenario: Human role from a group object id
- **WHEN** the moderator group object id is configured and a delegated token's `groups` contains it
- **THEN** the caller has `MODERATOR` and `VIEWER` but not `ADMIN`

#### Scenario: Machine role from an app role
- **WHEN** an app-only token's `roles` contains `Blackhole.Proxy`
- **THEN** the caller has `PROXY`

#### Scenario: Switching to app-role assignment is configuration only
- **WHEN** `claim-human` is set to `roles` and the per-role values to app role values such as `Blackhole.Moderator`
- **THEN** a delegated token whose `roles` contains that value grants `MODERATOR` without any code change

#### Scenario: Groups overage
- **WHEN** a delegated token has no `groups` claim but carries `_claim_names` or `hasgroups` for groups
- **THEN** the caller has no role, a role-protected route answers HTTP 403 with the reason `groups-overage`, the event is logged without the token, and no Microsoft Graph call is made

#### Scenario: Signed in without a mapped group
- **WHEN** a valid delegated token carries `groups` but none of the configured object ids
- **THEN** every role-protected route answers HTTP 403

#### Scenario: Unmapped token
- **WHEN** a valid token carries no recognised value in the claim for its kind
- **THEN** a protected route answers HTTP 403

### Requirement: Human and machine tokens are separated
On delegated tokens (those carrying `scp`) the backend SHALL read the claim named by `blackhole.security.roles.claim-human` (default `groups`, values are Entra group object ids) and MAY grant `ADMIN`, `MODERATOR`, `VIEWER` and `METRICS`. On app-only tokens (no `scp`) it SHALL read `blackhole.security.roles.claim-machine` (default `roles`, values are app role values) and MAY grant `PROXY` and `METRICS`. A value in the claim of the other token kind MUST grant nothing.

#### Scenario: App-only token with a human role
- **WHEN** an app-only token carries the moderator value in its `roles` claim
- **THEN** the moderator role is not granted

#### Scenario: Delegated token with the proxy role
- **WHEN** a user token carries the proxy value
- **THEN** the proxy role is not granted

#### Scenario: Metrics group member
- **WHEN** a delegated token's `groups` contains the configured metrics group object id
- **THEN** the caller has `METRICS` and can call `GET /prometheus`

### Requirement: Permission matrix
The backend SHALL enforce a minimum role per endpoint group: read-only routes need `VIEWER`; moderation routes (apply, preview and revoke punishments, resolve reports, review appeals, adjust ELO, add evidence, profile create and update) and intake routes need `MODERATOR`; section, template and vanilla-import writes, evidence deletion, profile deletion and staff-actor anonymization need `ADMIN`; `GET /prometheus` needs `METRICS`. A route with no explicit rule MUST be rejected.

#### Scenario: Viewer attempts a moderation action
- **WHEN** a `VIEWER` calls `POST /punishment/apply`
- **THEN** the response is HTTP 403 and nothing is changed

#### Scenario: Moderator attempts admin configuration
- **WHEN** a `MODERATOR` calls `POST /section`
- **THEN** the response is HTTP 403

#### Scenario: Proxy attempts erasure
- **WHEN** a `PROXY` caller calls `DELETE /evidence/{id}`
- **THEN** the response is HTTP 403

#### Scenario: Matrix is checked against every route
- **WHEN** the build runs the route-coverage test
- **THEN** every registered route has a rule and its allowed roles equal the documented matrix

### Requirement: Proxy role is least-privilege
The `PROXY` role SHALL be allowed exactly its routes: punishment and profile reads, section and template reads, player lookup, evidence list, punishment apply and revoke, evidence upload, and the intake routes. It MUST NOT be allowed any admin, erasure or review route.

#### Scenario: Proxy applies a punishment
- **WHEN** a `PROXY` caller calls `POST /punishment/apply`
- **THEN** the request is authorized

#### Scenario: Proxy resolves a report
- **WHEN** a `PROXY` caller calls `POST /report/{id}/resolve`
- **THEN** the response is HTTP 403

### Requirement: Public and operational routes
`GET /health` SHALL be the only public route and MUST expose status only, no component details. `GET /prometheus` MUST require `METRICS`. Swagger and OpenAPI views MUST NOT be reachable anonymously; they SHALL be served only when `blackhole.docs.enabled` is `true` and then require `VIEWER`.

#### Scenario: Health probe
- **WHEN** an unauthenticated client calls `GET /health`
- **THEN** the response is HTTP 200 with status only

#### Scenario: Metrics scrape without credentials
- **WHEN** an unauthenticated client calls `GET /prometheus`
- **THEN** the response is HTTP 401

#### Scenario: Swagger disabled by default
- **WHEN** documentation is not enabled and a client requests `/swagger-ui`
- **THEN** the response is not a documentation page

### Requirement: Reserved prefix for the player portal
The route prefix `/public/**` SHALL be reserved for the later player portal. No route SHALL be registered under it by this change, and any route later added there MUST declare its own explicit security rule and MUST NOT use the staff roles.

#### Scenario: No route under the prefix
- **WHEN** the route-coverage test lists all routes
- **THEN** none starts with `/public`

### Requirement: Caller identity endpoint
The backend SHALL provide `GET /auth/me` for any caller with a valid token, including one that has no role, returning the caller's `oid`, display name (if any) and internal roles (an empty list when none is mapped).

#### Scenario: Moderator asks who they are
- **WHEN** a moderator calls `GET /auth/me`
- **THEN** the response contains their `oid`, name and the roles `MODERATOR` and `VIEWER`

#### Scenario: Signed-in user without a mapped group
- **WHEN** a user with a valid token and no mapped group calls `GET /auth/me`
- **THEN** the response is HTTP 200 with an empty role list, so the UI can show its no-access page

### Requirement: Development and test issuer
The system SHALL run and be testable without Microsoft Entra: the local compose file SHALL provide a mock OIDC server (users carry a `groups` claim of fake object ids) whose issuer and JWKS URL are the only differences from production configuration, and backend tests SHALL sign their own JWTs with a per-test key and make no network call.

#### Scenario: Local sign-in with the mock server
- **WHEN** a developer starts compose and the backend with the default dev settings
- **THEN** a token issued by the mock server for the admin test user is accepted

#### Scenario: Tests need no network
- **WHEN** the backend test suite runs offline
- **THEN** all authentication tests pass using self-signed tokens
