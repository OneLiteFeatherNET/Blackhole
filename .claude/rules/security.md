---
paths:
  - "backend/src/main/java/**/security/**/*.java"
  - "backend/src/main/java/**/controller/**/*.java"
---

# Authentication and authorization

- Every route except `GET /health` needs an Entra ID access token (`Authorization: Bearer`). The
  backend verifies signature (JWKS), issuer, audience and expiry, keeps no session and sets no cookie.
  There is no setting that turns authentication off.
- Roles (`security/Roles`): `ADMIN` > `MODERATOR` > `VIEWER`, plus `PROXY`, `METRICS` and `DISCORD`
  (the Discord bot, app role `Blackhole.Discord`, only `/discord/**`), which imply nothing. `BlackholeRolesFinder` picks the claim by token kind: delegated tokens (they carry `scp`)
  use `groups` (Entra group object ids), app-only tokens use `roles` (`Blackhole.Proxy`,
  `Blackhole.Metrics`, `Blackhole.Discord`). A caller with no mapped role gets 403 everywhere except `GET /auth/me`; a
  groups overage answers 403 with reason `groups-overage` (no Graph call).
- Put `@Secured(Roles.X)` on every controller or method; controllers hold no authorization logic.
  `RouteCoverageIntegrationTest` and `PermissionMatrixIntegrationTest` fail for a route without a rule
  or one that differs from the matrix in `openspec/changes/archive/2026-10-23-add-team-sso/design.md` (decision 3).
- Actor: take it from `ActorResolver` (the token's `oid`), write it as `recorded_by_oid`. Never read a
  client-supplied `source`/`resolvedBy`/`reviewerId`; only `PROXY` may send `onBehalfOf`. Email and UPN
  claims are never read. For in-game actions the proxy sends `onBehalfOf` (a Minecraft UUID); the
  backend resolves it to the person through the live `staff_link` and writes `actor_oid` next to the
  UUID (null when unlinked; no backfill). Appeal self-review compares the reviewer's `oid` with
  `recorded_by_oid`, `actor_oid`, and the current link of `source`.
- Staff link (`security/` `StaffLink*`): `POST /staff-link/code` needs a human token; `confirm` and
  `by-minecraft` are `PROXY`-only; the code is stored hashed and never logged. No failed-attempt cap in
  the app on purpose (infra rate limits; `blackhole.link` goes to staff groups only). `anonymize` removes
  the link. The route matrix lives in `openspec/changes/archive/2026-10-23-add-team-sso/design.md`.
- Discord link (`discord/` `DiscordLink*`): codes work like the staff link (hashed, single use, no in-app
  limiter) but live in `discord_link_code`; `POST /staff-discord-link/code` is a human token, `POST
  /player-discord-link/code` is `PROXY` (hashed owner), `/discord/link/**` is `DISCORD` only. A Discord user
  id is personal data: only in `discord_link`, never in a response, event or log; unlink, admin removal,
  staff anonymize and profile erase delete it.
- Config is `blackhole.security.*` in `application.yml` (`BLACKHOLE_AUTH_*`). The defaults point at the
  mock OIDC server in `docker/docker-compose.yml`; a non-dev context with an `http`/localhost issuer
  refuses to start.
- Local dev signs in against the real Entra dev registration: `set -a; . ~/.config/blackhole/entra-dev.env;
  set +a` before `./gradlew :backend:run`; create it with `docs/entra-setup.sh dev`. Tests use self-signed
  JWTs (`TestJwtIssuer`) and never reach Entra.
- Case routes (`/case`): `GET` list, case and timeline need `VIEWER`; `PATCH /case/{reference}`, `POST
  /case/{reference}/notes` and `PUT .../notes/{noteId}` need `MODERATOR`; `POST .../notes/{noteId}/redact`
  needs `ADMIN`; `PROXY` is denied. `Actor.admin` carries the ADMIN role for the author-or-admin note edit.
- Active punishments (`GET /punishment/active`): the overview of currently punished players needs `VIEWER`;
  `PROXY` is denied. It returns the hashed owner, never a raw UUID, IP address or name.
- Conversation routes (`/case/{reference}/...`): `POST messages`, `PUT|DELETE messages/{id}` and `POST ban-id/leak` need
  `MODERATOR` (edit/delete: verified author or `Actor.admin`, staff messages only); `POST messages/{id}/redact`,
  `POST transcript/erase-player`, `POST ban-id/reissue` and `GET transcript` need `ADMIN`; `PROXY` is denied everywhere.
  No response, event or log carries a Ban ID (`CaseConversationNoLeakTest`).
