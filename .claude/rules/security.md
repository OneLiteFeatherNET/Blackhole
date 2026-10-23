---
paths:
  - "backend/src/main/java/**/security/**/*.java"
  - "backend/src/main/java/**/controller/**/*.java"
---

# Authentication and authorization

- Every route except `GET /health` needs an Entra ID access token (`Authorization: Bearer`). The
  backend verifies signature (JWKS), issuer, audience and expiry, keeps no session and sets no cookie.
  There is no setting that turns authentication off.
- Roles (`security/Roles`): `ADMIN` > `MODERATOR` > `VIEWER`, plus `PROXY` and `METRICS`, which imply
  nothing. `BlackholeRolesFinder` picks the claim by token kind: delegated tokens (they carry `scp`)
  use `groups` (Entra group object ids), app-only tokens use `roles` (`Blackhole.Proxy`,
  `Blackhole.Metrics`). A caller with no mapped role gets 403 everywhere except `GET /auth/me`; a
  groups overage answers 403 with reason `groups-overage` (no Graph call).
- Put `@Secured(Roles.X)` on every controller or method; controllers hold no authorization logic.
  `RouteCoverageIntegrationTest` and `PermissionMatrixIntegrationTest` fail for a route without a rule
  or one that differs from the matrix in `openspec/changes/add-team-sso/design.md` (decision 3).
- Actor: take it from `ActorResolver` (the token's `oid`), write it as `recorded_by_oid`. Never read a
  client-supplied `source`/`resolvedBy`/`reviewerId`; only `PROXY` may send `onBehalfOf`. Email and UPN
  claims are never read.
- Config is `blackhole.security.*` in `application.yml` (`BLACKHOLE_AUTH_*`). The defaults point at the
  mock OIDC server in `docker/docker-compose.yml`; a non-dev context with an `http`/localhost issuer
  refuses to start.
- Local dev signs in against the real Entra dev registration: `set -a; . ~/.config/blackhole/entra-dev.env;
  set +a` before `./gradlew :backend:run`; create it with `docs/entra-setup.sh dev`. Tests use self-signed
  JWTs (`TestJwtIssuer`) and never reach Entra.
