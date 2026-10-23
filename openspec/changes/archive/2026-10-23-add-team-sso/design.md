# Design

## Context

See proposal.md for motivation. Current state (observed): no auth layer; controllers are
`@Version(ApiVersion.V1)` with `*Api` interfaces; actor fields are client-supplied UUIDs:
`punishment.source`, `report.resolved_by`, `appeal.decided_by`, `evidence_items.created_by`,
`evidence_audit.actor` (columns, all `uuid`), `revokedBy` and the ELO `source` (JSON metadata).
The UI sends `source`/`reviewer` from `/config.json`; the Velocity plugin builds an
unauthenticated generated `ApiClient` in `BlackholeApiClients`. System rows use fixed
name-based UUIDs (`SYSTEM_ELO_SOURCE`, the vanilla import identity). Stack per the repo:
Micronaut 5.0.2 (Gradle catalog), PostgreSQL, Flyway (V1-V8 applied), Vue 3, Java 25.

Docs consulted (current): Micronaut Security 5.x (JWKS signature, claims validators for
`iss`/`aud`/`exp`, `RolesFinder`, `intercept-url-map`), Microsoft Entra access-token claims
and client-credentials flow, `oidc-client-ts` (`UserManager`, `userStore`/`stateStore`,
`automaticSilentRenew`, refresh-token renewal).

## Goals / Non-Goals

Goals: backend verifies every caller; stable verified actor on every write; least-privilege
roles; no Entra dependency in tests or local dev; one code path for prod and dev (only the
issuer URL differs).

Non-goals: see "Out of scope" in proposal.md. Follow-up (separate change, not here): linking staff Minecraft UUIDs to Entra `oid`, so the appeal self-review check also covers in-game bans.

## Decisions

### 1. Roles: groups claim for humans, app roles for machines (Entra ID Free)

Licence decision (2026-10-23): Blackhole runs on **Entra ID Free**. Microsoft documents that
group-based assignment to an enterprise application (and therefore groups assigned to app roles)
requires Entra ID P1 or P2, so human roles cannot come from app-role assignment. Application
permissions (an app role with allowed member type `applications`, granted to a client app and
admin consented) are an app-registration feature; the Learn how-to describes them without any
licence requirement, and this design relies on that (to be confirmed once in task 6.1).

**Humans: `groups` claim.** The API app registration sets `groupMembershipClaims:
"SecurityGroup"`, so delegated access tokens carry the object ids of the user's security groups.
The backend maps configured group object ids to `ADMIN`, `MODERATOR`, `VIEWER` and `METRICS`.
The token holds object ids, not display names, so the mapping is by object id; the group names
below are documentation for the Entra admin, nothing in code names a group. Ids are env-driven
in `application.yml` (`${ENV:default}` plus an explanatory comment).

**Machines: app roles.** `Blackhole.Proxy` and `Blackhole.Metrics` are application permissions
on the API, granted to the Velocity and metrics client registrations by admin consent. They
arrive in the `roles` claim of app-only tokens (client credentials). Service principals do not
appear in group claims, so the two sources never overlap.

**No user assignment required.** The enterprise application keeps "Assignment required" off, so
any tenant member (and guest) can obtain a token. The backend is the gate: a signed-in user
whose groups map to no role has no role, every protected route answers 403, and the UI shows a
"no access" page (`GET /auth/me` still answers, with an empty role list, so the UI can tell).

**Groups overage.** Entra caps the `groups` claim in JWTs at 200 ids; above that the token has
no `groups` but `_claim_names`/`hasgroups`. Such a token gets no role, the response is 403 with
the reason `groups-overage`, and the event is logged (oid only, no token). The backend does NOT
call Microsoft Graph. The OLF user groups are few, so overage is not expected; if it ever
happens for a person, the fix on the Entra side is fewer group memberships (or the P1 upgrade
path below).

**Upgrade path (documented, config only).** With P1 later, switch to app-role assignment:
set `BLACKHOLE_AUTH_ROLES_CLAIM_HUMAN` from `groups` to `roles` and the per-role values from
group object ids to the app role values (`Blackhole.Admin`, ...), define those app roles on the
API registration and assign the groups to them. No code change.

Configuration (comma separated values per internal role):

- `BLACKHOLE_AUTH_ROLES_CLAIM_HUMAN` (default `groups`): claim read on delegated tokens.
- `BLACKHOLE_AUTH_ROLES_CLAIM_MACHINE` (default `roles`): claim read on app-only tokens.
- `BLACKHOLE_AUTH_ROLE_ADMIN`, `_MODERATOR`, `_VIEWER`, `_METRICS`: values (group object ids by
  default) accepted on delegated tokens.
- `BLACKHOLE_AUTH_MACHINE_ROLE_PROXY` (default `Blackhole.Proxy`) and
  `BLACKHOLE_AUTH_MACHINE_ROLE_METRICS` (default `Blackhole.Metrics`): accepted on app-only
  tokens.

OLF naming scheme `<typ>-<dienst>-<scope>-<umgebung>-<rolle>`, extended with the service
`blackhole` (decided). `moderator` maps to the existing scheme role `edit`; no new role name.

| Internal role | Source | Scheme role | Entra object |
|---|---|---|---|
| `ADMIN` | `groups` claim, delegated token | `admin` | group `sg-blackhole-prod-admin` |
| `MODERATOR` | `groups` claim, delegated token | `edit` | group `sg-blackhole-prod-edit` |
| `VIEWER` | `groups` claim, delegated token | `view` | group `sg-blackhole-prod-view` |
| `METRICS` | `groups` claim (delegated) or `roles` = `Blackhole.Metrics` (app-only) | `ops` | group `sg-blackhole-prod-ops`; metrics client app role |
| `PROXY` | `roles` = `Blackhole.Proxy`, app-only token | (machine) | Velocity client app role |

`audit` is not used: no endpoint exposes the evidence audit trail.

Role semantics: a custom `RolesFinder` maps claim values to internal roles. Hierarchy is
expanded there: `ADMIN` implies `MODERATOR` implies `VIEWER`. `PROXY` and `METRICS` are separate
and implied by nothing. The token kind selects the claim: tokens that carry `scp` (delegated)
are read from the human claim and can be granted `ADMIN`, `MODERATOR`, `VIEWER`, `METRICS`
(never `PROXY`); tokens without `scp` (app-only) are read from the machine claim and can be
granted `PROXY` and `METRICS` (never a human role). A user token cannot act as the proxy and a
machine token cannot act as a moderator even if mis-assigned.

### 2. Token validation

`micronaut-security-jwt` as resource server, `micronaut.security.authentication: bearer`.
Signature: remote JWKS `https://login.microsoftonline.com/{tenant}/discovery/v2.0/keys`
(cached by the library, rotates with Entra). Claims validators: `iss`
`https://login.microsoftonline.com/{tenant}/v2.0`, `aud` the API's application (client) id,
`exp` (default). The API registration must set `accessTokenAcceptedVersion: 2` in its manifest,
otherwise Entra issues v1 tokens with another issuer and `aud`, and
`groupMembershipClaims: "SecurityGroup"` so delegated tokens carry the `groups` claim. Tenant id
(`BLACKHOLE_AUTH_TENANT_ID`), API client id (`BLACKHOLE_AUTH_AUDIENCE`) and, for dev,
`BLACKHOLE_AUTH_ISSUER` / `BLACKHOLE_AUTH_JWKS_URL` are env-driven. Stateless: no session, no
cookies, no CSRF surface. No server-side request is made to a caller-supplied URL (issuer and
JWKS are operator config), so principle 5 is unaffected.

Startup guard: if the issuer is plain `http` or on `localhost` and the active environment is
neither `dev` nor `test`, the backend refuses to start unless
`blackhole.security.allow-insecure-issuer=true`. The local-dev defaults in `application.yml`
point at the mock server, and this stops them from ever being used in production.

There is no switch that turns authentication off.

### 3. Permission matrix

Enforced with `@Secured` constants from a small `Roles` class on controllers (deny by default:
a route with no rule is rejected). Required role per endpoint group (the lowest role that may
call; `ADMIN` > `MODERATOR` > `VIEWER`; `PROXY` listed separately):

| Endpoint group | Routes | Minimum human role | Also `PROXY` |
|---|---|---|---|
| Read-only | `GET /punishment`, `/punishment/short/{id}`, `/profile`, `/profile/{owner}`, `/elo/{owner}`, `/elo/{owner}/history`, `GET /report`, `GET /appeal`, `GET /section`, `/section/{key}`, `GET /template`, `/template/{id}`, `/dashboard/summary`, `/player/resolve/{name}`, evidence `GET` (list, count, one, content) | `VIEWER` | yes (profile, punishment, section, template, player, evidence list) |
| Moderation | `POST /punishment/apply`, `/preview`, `/revoke`; `POST /report/{id}/resolve`; `POST /appeal/{id}/review`; `POST /elo/{owner}/adjust`; `POST /evidence`, `/evidence/file`; `POST /profile`, `/profile/update/{owner}` | `MODERATOR` | apply, revoke, evidence upload only |
| Intake (today called by the proxy) | `POST /report`, `POST /appeal`, `POST /elo/chat`, `POST /evasion/record`, `POST /profile/{owner}/session` | `MODERATOR` | yes (these are the proxy's routes) |
| Admin configuration | `POST /section`, `/section/update`, `/section/{key}/disable`; `POST /template`, `/template/update`, `DELETE /template/delete/{id}`; `POST /admin/import/vanilla` | `ADMIN` | no |
| Erasure / destructive | `DELETE /evidence/{id}`, `DELETE /evidence/owner/{owner}`, `DELETE /profile/delete/{owner}`, `POST /admin/staff-actor/{oid}/anonymize` | `ADMIN` | no |
| Identity | `GET /auth/me` | any authenticated caller, also one with no role | yes |
| Staff link (own) | `POST /staff-link/code`, `GET /staff-link/me`, `DELETE /staff-link/me` | `VIEWER` (human token for the code) | no |
| Staff link (proxy) | `POST /staff-link/confirm`, `GET /staff-link/by-minecraft/{uuid}` | `PROXY` only | n/a |
| Staff link (admin) | `GET /admin/staff-link`, `DELETE /admin/staff-link/{oid}` | `ADMIN` | no |
| Operations | `GET /prometheus` | `METRICS` | no |
| Public | `GET /health` (status only, no details) | none | n/a |

Assumption (no longer open): `DELETE /profile/delete/{owner}` and the vanilla import are
`ADMIN` only, as in the table. Evidence reads stay `VIEWER`; showing lifted evidence remains audited server-side, now with a
real actor. Rate limiting is untouched (infra-owned, plus the existing report limiter).

A JUnit test enumerates every route of the running application (from the router) and
asserts: every route has a rule; the allowed-role set equals the matrix table in the test;
`/public/**` is empty. Adding an endpoint without a rule fails the build.

### 4. Public, operational and reserved routes

- `GET /health`: public so orchestrators can probe; details hidden
  (`endpoints.health.details-visible: NEVER`).
- `/prometheus`: `endpoints.prometheus.sensitive` becomes true and requires `METRICS`. A
  scraper uses client credentials with that app role. Operators who prefer may move
  management endpoints to an internal port; both are supported by config only.
- Swagger / OpenAPI views: not public. The static-resource mappings are only registered when
  `blackhole.docs.enabled=true` (default false; true in the dev profile) and then need
  `VIEWER`. The bypass for anonymous static resources stays off.
- `/public/**`: reserved for `add-appeal-portal`. No controller may map under it in this
  change (test). Rule for the future: routes there must declare their own explicit rule
  (player auth is an in-game one-time code, not Entra) and must not reuse staff roles.

### 5. Verified actor on writes

Source of truth: the validated token. A request-scoped `ActorResolver` (service in a new
`security` package) turns the `Authentication` into an `Actor(oid, displayName, kind)`; `kind`
is `HUMAN` (token has `scp`) or `SERVICE`. It upserts `staff_actor` on the first write per
principal and when the `name` claim differs. Only the claims `oid` and `name` are read;
`email`, `preferred_username` and `upn` are neither read nor stored (the UI requests the scopes
`openid profile offline_access` plus the API scope). Logs carry the `oid`, never tokens or
names at INFO.

Request fields: `source`, `resolvedBy`, `reviewerId`, `punishmentSource`, evidence
`source`/`createdBy` and the ELO adjustment `source` are removed from the request DTOs and the
OpenAPI spec. Micronaut Serde ignores unknown properties, so an old client that still sends
them keeps working functionally but the values are never read; a test asserts a spoofed value
is ignored. Rejecting would be stricter, but silent ignore keeps the staged rollout (proxy
updated after backend) simple; the required token is what breaks old clients anyway.

Attribution columns keep their type and meaning ("who is responsible") and are now set by the
server:
- Human caller: `oid` (UUID, fits the existing `uuid` columns).
- `PROXY` caller: optional request field `onBehalfOf` (the in-game staff member's Minecraft
  UUID); when present it is the attributed UUID, when absent the proxy's own `oid`. The field
  is rejected (400) for any other role. It is trusted only because the verified machine
  principal vouches for it.
- System rows (ELO automation, vanilla import): the existing fixed system UUIDs, unchanged.
  The import is invoked by an admin, so its `recorded_by_oid` is that admin.

New column `recorded_by_oid uuid NULL` on the punishment, report, appeal, `evidence_items` and
`evidence_audit` tables always stores the token's `oid` (also for delegated proxy calls); for
JSON metadata (`revokedBy`, ELO history) the key `recordedByOid`. NULL means "written before
SSO, unverified" and is shown as such. No backfill: legacy values cannot be verified and are
not rewritten.

Migration (append-only, rule from config.yaml): `V9__staff_actor_and_recorded_by.sql` (use the
next free number at apply time; `staff-temp-bans-elo-escalation` may add one first):

```
staff_actor(oid uuid PK, kind varchar(16) NOT NULL, display_name varchar(256),
            first_seen_at bigint NOT NULL, last_seen_at bigint NOT NULL)
ALTER TABLE <each of the five tables> ADD COLUMN recorded_by_oid uuid;
```

Exact table names are confirmed against `V1`/`V8` at apply time. Existing rows keep their
actor column and get NULL in the new one. No foreign key from the new columns, so anonymizing
or pruning `staff_actor` never blocks history.

Self-review: `AppealDecisionService` rejects (403) when the reviewer's `oid` equals the
punishment's attributed `source` or its `recorded_by_oid`. This is tamper-proof between web
staff. Known limit: a ban issued in game is attributed to the in-game UUID with the proxy as
`recorded_by_oid`; the same person reviewing in the web UI is not detected because nothing
links a Minecraft UUID to an `oid`. Linking is deferred to a separate follow-up change (decided 2026-10-23), not part of
`add-team-sso`: once staff Minecraft UUIDs are linked to `oid`, the self-review check also
covers in-game bans.

Transactions: no `@Transactional` (principle 6); the `staff_actor` upsert is one idempotent
repository save done before the main write, using the same mechanisms the services already use.

GDPR: staff data is personal data (name, a stable id). Purpose: accountability of moderation
decisions. Stored: `oid`, `display_name`, timestamps only. Retention: as long as records
referencing the person exist (punishment history is itself retained). On a leaving or erasure
request an admin calls `POST /admin/staff-actor/{oid}/anonymize`, which nulls `display_name`
(the `oid` stays; once the Entra account is deleted it no longer resolves to a person). The
retention statement is documented in the README; legal review is an open item of the ADR and
not decided here.

### 6. Velocity machine identity

Separate app registration "Blackhole Velocity" with application permission (app role)
`Blackhole.Proxy` on the API, admin consented (see decision 1 for the licence).
The metrics scraper is a second client registration with `Blackhole.Metrics`. Plugin `config.json` gains
`auth: { tenantId, clientId, clientSecret, scope }` with `scope` defaulting to
`api://<api-client-id>/.default` and a dev-only `tokenUrl` override (pointing at the mock
server). Environment variables (`BLACKHOLE_TENANT_ID`, `BLACKHOLE_CLIENT_ID`,
`BLACKHOLE_CLIENT_SECRET`, `BLACKHOLE_AUTH_SCOPE`, `BLACKHOLE_TOKEN_URL`) override file values so
the secret need not live in the file.

`ClientCredentialsTokenProvider` POSTs `grant_type=client_credentials` to
`https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token` with the JDK `HttpClient` (no
MSAL: avoids a heavy shaded dependency in a plugin; only one flow is needed). It caches the
token until `expires_in` minus a 60 s skew (injected `Clock`), refreshes single-flight, and
`BlackholeApiClients` installs a request interceptor on the generated `ApiClient`. A 401 forces
one refresh and one retry. Secret rotation: Entra allows two valid secrets; rotate by adding
the new secret, changing the plugin config, removing the old one. Certificates are possible
later without API change. If auth config is incomplete the plugin logs an error at startup and
backend-bound commands fail with a localized message; Redis-mirror enforcement keeps working.

### 7. Admin UI sign-in

`oidc-client-ts` (current 3.x, version pinned at apply time) `UserManager` with Authorization
Code + PKCE against `authority = https://login.microsoftonline.com/{tenant}/v2.0`, scopes
`openid profile offline_access api://<api-client-id>/access_as_user`. The SPA registration
uses the "Single-page application" platform (PKCE, CORS on the token endpoint).

Token storage: `userStore` is the library's `InMemoryWebStorage`, so access and refresh tokens
are never in localStorage or sessionStorage. The `stateStore` stays `sessionStorage`: it holds
only the short-lived `state` and PKCE verifier across the redirect and is cleared by the
callback. A page reload therefore loses the token; the app immediately redirects to Entra,
which signs the user in without a prompt while the Entra session lives (top-level redirect
rather than a hidden iframe, so browsers that block third-party cookies still work). Renewal
while the page is open: `automaticSilentRenew` uses the in-memory refresh token (SPA refresh
tokens are rotated and limited to 24 h by Entra; after that the user is redirected again).

HTTP layer (`web/src/api/http.ts`): adds `Authorization: Bearer`, on 401 renews once and
retries, then signs in again; 403 shows a "not permitted" state, never a retry loop. Logout
clears the in-memory user and ends the Entra session (`end_session_endpoint`).

Roles in the UI: the UI does not decode tokens. After sign-in it calls `GET /auth/me`
(`{ oid, displayName, roles }`), keeps it in a store, shows "signed in as", and hides or
disables actions the role cannot perform (sections/templates/import for non-admins, delete
evidence, moderation actions for viewers). The backend remains authoritative.

`/config.json` is served by the Nitro server (`web/server/routes/config.json.ts`), built from the environment at startup: `BLACKHOLE_UI_OIDC_AUTHORITY`,
`BLACKHOLE_UI_OIDC_CLIENT_ID`, `BLACKHOLE_UI_API_SCOPE` replace `BLACKHOLE_UI_SOURCE` and
`BLACKHOLE_UI_REVIEWER` (validated by the same strict patterns in `web/server/lib/config.ts`; an invalid value stops the server before it listens). The
"Acting as" label is removed (the verified name replaces it). The CSP `connect-src` built in
`web/server/lib/headers.ts` must allow the authority origin.

### 8. Local development and tests

One code path: only issuer and JWKS URL differ.

- Dev: `docker/docker-compose.yml` gains `ghcr.io/navikt/mock-oauth2-server` (pinned tag) with
  `docker/mock-oauth/config.json`: interactive login for three users (admin, moderator,
  viewer) plus a fourth user in no group, each with `oid`, `name`, `scp` and a `groups` claim of
  fake object ids that match the dev defaults in `application.yml` (the no-group user carries an
  empty list), and a client-credentials token callback giving
  `Blackhole.Proxy` / `Blackhole.Metrics` without `scp`. Dev defaults in `application.yml` and
  the UI/plugin examples point at it.
- Tests: `TestJwtIssuer` (test source set) generates an RSA key pair per test class, signs JWTs
  with Nimbus (already on the classpath through Micronaut Security), and a `test`-environment
  bean provides the matching signature configuration instead of a JWKS URL, so no HTTP server
  and no network. Time is the injected `Clock`. Tests cover: valid, expired, wrong issuer,
  wrong audience, bad signature, `alg=none`, missing token; role mapping and the app-only vs
  delegated rule; the route matrix; spoofed actor fields. Velocity token provider tests use a
  fake HTTP transport and a fixed `Clock`; web tests use a fake `UserManager`.
- Manual check against a real tenant is a documented how-to (task 6.1).

### 9. Rollout and compatibility

This is one breaking release delivered as a stack of PRs. Merge the stack back-to-back with no
release in between; release-please only cuts a release from the later release PR, so `main` is
briefly inconsistent but never released that way. Operators migrate in this order: register
Entra apps and roles; deploy backend with the new env vars; deploy plugin with credentials;
deploy UI with OIDC settings. Between backend and plugin/UI the system is unavailable to those
clients, so do it in one window. The OpenAPI client spec moves to the next version file; the
generated client and the `web/` `generate:api` follow it. `X-API-VERSION` stays `1`: the
contract is not versioned side by side because every caller must be updated anyway.

## Risks / Trade-offs

- Entra outage blocks new sign-ins; running sessions and cached machine tokens work until they
  expire. **No break-glass login (decision, 2026-10-23).** Consequence: while Entra is down
  there is no team panel and no staff API writes; Velocity keeps enforcing existing punishments
  from Redis, and in-game bans need a still-valid cached machine token.
- Group object ids are tenant-specific and carry no names; a wrong id silently grants nothing
  (deny by default). Mitigated by the setup how-to listing the ids to copy and the
  `GET /auth/me` check in task 6.1.
- Any tenant member or guest can obtain a token for the API (no user assignment); this is
  safe only because the backend denies every role-less caller.
- Role removal in Entra takes effect only when the access token expires (typically 60-90 min).
  Acceptable; a shorter lifetime is an Entra policy choice.
- Dropping `source` from requests loses attribution of system-to-system calls by UUID;
  replaced by `onBehalfOf` for the proxy only.
- In-game staff and Entra identities are not linked, so self-review is enforced within one
  identity domain (see Decision 5).
- Ignoring (not rejecting) legacy fields can hide a misconfigured old client; mitigated by the
  token requirement making such a client fail first.

## Open Questions

1. Legal confirmation of the staff-data retention statement (ADR-BLKH-0002 consequence).
