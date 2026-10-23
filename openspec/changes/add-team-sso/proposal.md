# Proposal

**Ships as:** `feat(auth)!: require entra id sign-in for the api and admin ui` (BREAKING).
Decision record: [ADR-BLKH-0002](https://outline.onelitefeather.dev/doc/adr-blkh-0002-team-sso-uber-microsoft-entra-id-statt-offener-api-SnoHO5QNwa) (status `proposed`; accepting it is a prerequisite for applying this change).

## Why

Blackhole has no authentication: every endpoint is open and trust is a network boundary. That
leaves three gaps the project can no longer carry:

1. `source`, `resolvedBy`, `reviewerId` and the evidence actor fields are client-supplied, so no
   record says who really did something and the appeal self-review check can be bypassed by
   sending another UUID. The admin UI writes everything as one configured operator UUID.
2. A per-punishment case timeline needs a verified actor on every event.
3. The planned public player portal (separate change `add-appeal-portal`) needs a backend that
   already distinguishes trusted staff and machine callers from the open internet.

OneLiteFeather already runs its other services on Microsoft Entra ID. This change makes Blackhole
speak OIDC to Entra itself (not through a proxy), so the backend can verify the caller.

## What Changes

- **BREAKING** (operators, API clients): every endpoint except `GET /health` requires a valid
  Entra ID access token (`Authorization: Bearer`). Swagger is no longer public, `/prometheus`
  needs a metrics role.
- Backend validates tokens as an OAuth2 resource server (JWKS signature, issuer, audience,
  expiry), stateless, no sessions. The design targets **Entra ID Free**: assigning groups to app
  roles needs P1, so it is not used. Human staff roles come from the **`groups` claim** (the API
  registration sets `groupMembershipClaims: "SecurityGroup"`); configured group object ids map
  to `ADMIN`, `MODERATOR`, `VIEWER` and `METRICS`. Machine principals (Velocity, metrics
  scraper) keep **app roles** (application permissions granted by admin consent) in the `roles`
  claim of app-only tokens. Claim names and values are configuration, so moving to app-role
  assignment after a P1 upgrade is a config change only.
- Any tenant member can sign in (no user assignment required); a signed-in user whose groups map
  to no role gets no role: every protected route answers 403 and the UI shows a "no access"
  page.
- A permission matrix (read-only, moderation, admin, proxy, metrics) is enforced per endpoint
  and covered by a table-driven test over every route.
- **BREAKING** (API/client): the client-supplied actor fields (`source`, `resolvedBy`,
  `reviewerId`, `punishmentSource`, evidence `source`/`createdBy`, ELO adjustment `source`) are
  removed from requests. The server records the actor from the token: Entra object id (`oid`) in
  the existing columns, plus a new `recorded_by_oid` column and a `staff_actor` table with a
  display-name snapshot. The Velocity plugin, which acts for in-game staff, sends an optional
  `onBehalfOf` Minecraft UUID that only the proxy role may use.
- Appeal self-review compares the verified identity, so it can no longer be bypassed.
- **BREAKING** (operators): the Velocity plugin authenticates with OAuth2 client credentials
  (tenant id, client id, secret, scope in `config.json` or env vars), caches and refreshes the
  token.
- **BREAKING** (operators): the admin UI signs staff in with Authorization Code + PKCE, keeps
  tokens in memory only and renews silently; it shows only what the user's role permits. The
  `BLACKHOLE_UI_SOURCE` / `BLACKHOLE_UI_REVIEWER` settings and the "Acting as" label are removed.
- Local dev and tests run without Entra: a mock OIDC server in `docker/docker-compose.yml`, and
  self-signed test JWTs in JUnit (no network).
- A route prefix `/public/**` is reserved (no route exists yet) for the later player portal.
- Docs follow (tasks, last PR): principle 2 in `openspec/config.yaml`, the `CLAUDE.md` bullets
  "No authentication system" and "Known gap", `.claude/rules/*`, `README.md`, `web/README.md`,
  `application.yml` comments, and an Entra setup how-to.

Out of scope: the player portal and player authentication (in-game one-time code, not Entra);
calling Microsoft Graph (no groups-overage resolution, overage is denied); SCIM; token
revocation / continuous access evaluation; authentication of Redis, RabbitMQ or PostgreSQL;
app-level rate limiting (stays with infra).

Decisions taken on 2026-10-23 (by the user):

- **Group naming:** the OLF scheme `<typ>-<dienst>-<scope>-<umgebung>-<rolle>` gets the service
  `blackhole`: `sg-blackhole-prod-admin`, `sg-blackhole-prod-edit` (moderator),
  `sg-blackhole-prod-view`, `sg-blackhole-prod-ops` (metrics). No new role name is introduced.
- **Licence:** Entra ID Free only; hence groups claim for humans, app roles for machines.
- **No break-glass login.** If Entra is down there is no team panel and no staff API writes;
  Velocity keeps enforcing existing punishments from Redis.
- **Follow-up, not part of this change:** linking staff Minecraft UUIDs to Entra `oid` is its
  own later change, because the appeal self-review check then also covers in-game bans.

## Capabilities

### New Capabilities

- `staff-authentication`: token validation, role mapping, permission matrix, public and
  operational routes, reserved portal prefix, dev/test issuer.
- `verified-actor`: server-recorded actor, `staff_actor` snapshot, delegated attribution for the
  proxy, retention and anonymization.
- `admin-ui-authentication`: sign-in, in-memory tokens, silent renew, role-aware UI.

### Modified Capabilities

- `punishment-management`: source of apply/revoke comes from the token.
- `appeal-workflow`: self-review compares the verified identity; `reviewerId` removed.
- `player-reports`: `resolvedBy` and `punishmentSource` come from the token.
- `proxy-enforcement`: plugin configuration gains the Entra client credentials.

## Impact

- `backend/`: security dependencies, `application.yml`, all controllers (annotations), DTOs,
  services that read actor fields, Flyway `V9__staff_actor_and_recorded_by.sql` (next free
  number at apply time), tests, OpenAPI.
- `client/specs/`: new API spec version (security scheme, removed actor fields, `GET /auth/me`).
- `velocity/`: config, token provider, API client wiring, call sites of the removed fields.
- `web/`: `oidc-client-ts`, auth service, router guards, `src/api/*`, `config.ts`,
  `nginx/40-config-json.sh`, role-aware components, locales.
- `docker/`: mock OIDC server. `openspec/config.yaml`, `CLAUDE.md`, `.claude/rules/*`, READMEs.
- Entra (done by an Entra admin, see tasks 6.1): API app registration (machine app roles,
  `groupMembershipClaims`), SPA registration, Velocity and metrics client registrations with
  admin consent, the four `sg-blackhole-prod-*` groups.
- Open question left: legal confirmation of the staff-data retention wording (design.md).
- Depends on: ADR-BLKH-0002 accepted; in-flight changes touching the same code
  (`staff-temp-bans-elo-escalation`, `capture-proxy-chat-evidence`) merged or rebased first.
