## Context

Staff sign in with Entra ID (v2) in the panel; the backend validates bearer tokens against the issuer's
JWKS (`backend/src/main/resources/application.yml:447-479`, `security/JwksSignatureFactory.java`). App-only
machine clients (Velocity, Discord, metrics) use the client-credentials flow and an app role, in
`velocity/.../config/AuthConfig.java` and `discord/.../auth/TokenSettings.java`. The player portal uses its
own opaque session (`portal/server/lib/bff.ts`), not OAuth. The full evidence is in `audit.md`. See
`proposal.md` for motivation.

Constraints (from `openspec/config.yaml`): no auth-off switch, no break-glass login, no per-user identity
beyond the token `oid`, one tenant per deployment, Flyway migrations are append-only.

## Goals / Non-Goals

**Goals:**
- Close the gaps ranked in `audit.md` section 1, each in its own PR, each test-first.
- Make the production configuration fail closed: a missing audience, tenant or https token endpoint refuses to start.
- Keep every fix testable with self-signed JWTs (`TestJwtIssuer`) and no network call.

**Non-Goals:**
- Sender-constrained tokens (DPoP, mTLS) (Q3).
- Changing the role model or the permission matrix in `staff-authentication`.
- Changing how the portal session works beyond hashing the CSRF value.
- Replacing client secrets with certificates or managed identity (Q4).

## Decisions

### Decision 1: One change type per PR, the proposal is `docs`
Each PR carries one Conventional Commits type. The proposal is `docs(openspec)`. The stale-documentation
corrections are `docs`. Each real gap is `fix(security)`. Alternative: one `fix(security)` PR for all of
it. Rejected: it mixes a docs correction with behaviour changes and cannot be reverted in pieces.

### Decision 2: Audience is the API app's client id GUID, required outside dev
Entra v2 access tokens carry the API app's client id in `aud`. The setup script registers
`identifierUris: api://$API` (`docs/entra-setup.sh:109`) and requests `requestedAccessTokenVersion: 2`
(`:112`). The default `blackhole-api` (`application.yml:466`) never matches a real token.
Decision: `BLACKHOLE_AUTH_AUDIENCE` must be set to the GUID outside `dev`/`test`, and startup fails if it is
empty or is not a GUID. Alternative: accept both the GUID and `api://<guid>`. Rejected for now: the v2
token only carries the GUID, so accepting a second form widens the surface for no gain.

### Decision 3: Bind app-only tokens to allowed client ids
An app-only token's `roles` claim proves the app role, not which app asked. Decision: add
`blackhole.security.roles.machine-clients.{proxy,metrics,discord}` (lists of client ids). Check the `azp`
claim (v2 name; `appid` is the v1 name and is not accepted) against the list for the role it grants. A
token with a role and an unlisted client gets no role. Alternative: rely on Entra assignment alone.
Rejected: assignment is one tenant admin action, and nothing in the API would catch a mistake there.

### Decision 4: Explicit algorithm and version checks
Pin the signature algorithm to `RS256` in `JwksSignatureFactory` and reject any other `alg`. Reject tokens
whose `ver` claim is not `2.0`. The library default is not relied on (`audit.md` J1, J5, J6).

### Decision 5: Machine token endpoints must be https
Velocity (`AuthConfig.java:129-130`) and Discord (`TokenSettings.java:86-89`) refuse `http` unless the
environment is `dev` or `test`. The secret is never sent in clear. Alternative: warn and continue. Rejected:
the failure mode is a leaked secret.

### Decision 6: Portal CSRF is hashed at rest, in a new migration
Store `HMAC-SHA-256(csrf)` or a SHA-256 digest in `player_session.csrf` (`audit.md` P5). The existing rows
are migrated in the new migration (see Migration Plan). V16 is not edited.

### Decision 7: Panel config is pinned, not derived from the window
The panel's `redirect_uri` origin comes from config and is compared with `window.location.origin` at start-up.
A mismatch stops the app. Entra's exact-match rule stays as the second layer.

## Risks / Trade-offs

- **[Risk] Production deployments on the default audience or without `tenant-id` stop at start-up.**
  → Mitigation: this is intentional (fail closed). The changelog marks it BREAKING. Whether the Helm chart
  (`deploy/charts/blackhole`) already sets both values is **unverified**; check it in task 1.2 and add the
  values and a chart test case (`tests/run-all.sh`) if they are missing.
- **[Risk] Client-id allow-list locks out a machine client after rotation.**
  → Mitigation: the allow-list is config, not code. The runbook names the step; the test covers the
  rejected-client path.
- **[Risk] `ver` or `azp` claim absent from a real Entra token, so every machine token is refused.**
  → Mitigation: verify the claim set against a real dev token before the PR merges (task 3.1). Until then
  the status of `ver` and `azp` is `unverified`.
- **[Risk] Clock leeway too wide weakens expiry.** → Mitigation: 60 s maximum, tested.
- **[Risk] The CSRF hash migration breaks live portal sessions.** → Mitigation: the migration hashes existing
  values in place; a session whose CSRF cannot be matched is ended (the user signs in again). Idle sessions
  end within 30 minutes anyway (`application.yml:159`).

## Migration Plan

1. Docs PR first (`D2`, `D3`, `D4`): no behaviour change.
2. Each `fix(security)` PR ships behind config defaults that keep `dev` and `test` working.
3. Production steps, in order, before deploying the audience and tenant PRs: set
   `BLACKHOLE_AUTH_AUDIENCE` to the API client id GUID, set `BLACKHOLE_AUTH_TENANT_ID`, set the machine
   client ids, and check the Velocity and Discord token URLs are `https`.
4. Flyway: a new `V25__player_session_csrf_hash.sql` (next free version after `V24__player_seen.sql`)
   replaces each `player_session.csrf` value with its SHA-256 digest. It is not reversible, so rollback is
   "end all portal sessions" (`DELETE FROM player_session`). That is safe because sessions last at most 4 hours.
5. Rollback for the auth PRs: revert the PR, then restore the previous config values. No data is affected.

## Decisions (2026-10-25)

- **Q2, user assignment (yes).** The Entra Admin UI app requires user assignment (`appRoleAssignmentRequired`
  on its service principal). Set by the setup script and documented in `docs/entra-setup.md` (task 6.3). It
  changes who can get a token, not what they can do.
- **Q3, DPoP (out of scope).** Sender-constrained tokens are not part of this change. Bearer access tokens in
  browser memory stay as they are (`audit.md` W9).
- **Q4, machine client secrets (kept).** Velocity and Discord keep client secrets for now. Certificates or
  managed identity come later, in a separate change. The secret stays env-only and is never logged (`audit.md` M1).
- **Stale "no auth" text.** The stale text is in `.claude/agents/backend-reviewer.md:27`, not in `CLAUDE.md`.
  Task 1.1 targets that file. `CLAUDE.md:82-85` is correct and needs no change for this.

## Open Questions

Resolved on 2026-10-25; see Decisions above.

- **Q1 (resolved in Decision 2):** audience is the GUID, not `api://`. No further input needed.
- **Q2:** resolved, yes: assignment required on the Admin UI app.
- **Q3:** resolved, DPoP is out of scope.
- **Q4:** resolved, machine clients keep client secrets for now.
