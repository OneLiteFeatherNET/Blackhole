## Why

Both public hosts, `appeal.onelitefeather.net` (portal) and `blackhole.onelitefeather.net`
(panel), are internet-facing, and the repository has no way for a security researcher to
report a problem: no `SECURITY.md`, no `security.txt`, no published contact or policy.

RFC 9116 defines `security.txt`, served under `/.well-known/` (RFC 8615), as the
machine-readable place for that contact. It is cheap to serve and it is the first thing a
researcher or scanner looks for.

The file carries an `Expires` field that must stay current. A static file baked into the
image would go stale silently, so the content comes from runtime configuration that the
operator sets per deployment, and it is validated so a stale or malformed value is loud.

## What Changes

- The portal server and the panel (`web/`) server answer `GET /.well-known/security.txt`
  with an RFC 9116 document built from runtime configuration. The route is public: no login,
  no backend call, no BFF allow-list entry, and it is not under `/api`.
- Configuration keys are shared by both apps (see `design.md`): `BLACKHOLE_SECURITY_CONTACT`,
  `BLACKHOLE_SECURITY_EXPIRES`, `BLACKHOLE_SECURITY_ENCRYPTION`,
  `BLACKHOLE_SECURITY_ACKNOWLEDGMENTS`, `BLACKHOLE_SECURITY_POLICY`,
  `BLACKHOLE_SECURITY_PREFERRED_LANGUAGES` (default `en, de`). `Canonical` is derived from
  each app's own public origin.
- The panel gets a new `BLACKHOLE_UI_ORIGIN` (the web server has no origin setting today;
  the portal already has `BLACKHOLE_PORTAL_ORIGIN`).
- Startup validation: a malformed value, or `Expires` in the past or more than one year
  ahead, stops the process with a non-zero exit code, the same pattern as the existing
  config parsers. An unset `Contact` disables the route (404) and logs one warning, so a
  deployment without the setting does not break.
- Helm: a `securityTxt` values block, rendered into the `BLACKHOLE_SECURITY_*` environment of
  the portal and web Deployments; `values.schema.json` entries; a `validate.yaml` check that
  fails the render when `Contact` is set without `Expires`, or `Expires` is not an RFC 3339
  timestamp. The one-year window is checked by the apps at startup, not by the chart, so the
  chart test stays deterministic.
- The file is served as `Content-Type: text/plain; charset=utf-8`, with
  `Cache-Control: public, max-age=86400`, and carries the existing security headers.

## Capabilities

### New Capabilities

- `security-disclosure`: the RFC 9116 file served by the portal and the panel, its configuration
  and its validation, and the Helm wiring that sets it.

### Modified Capabilities

- `admin-web-ui`: the panel server answers the public disclosure route before its single-page
  fallback.

The portal's route belongs to `security-disclosure`, not to `appeal-portal`: that capability
exists only as an in-flight change (`add-appeal-portal`) and has no main spec yet.

## Out of scope

- OpenPGP signing of the file (RFC 9116 recommends it, it is not required). It needs a key
  in a Secret and a signing step; a follow-up change if the org wants it.
- A published vulnerability disclosure policy text and an acknowledgments page. The config
  only links to them.
- Any security contact or policy address hard-coded in the repository. The values come from
  the operator (see `design.md` open questions).
- Serving `/security.txt` at the top level (RFC 9116 allows it only for legacy compatibility).
- Changes to the backend, the Velocity plugin, the generated client or the database. No
  Flyway migration, no API change, nothing breaking.

## Type, scope and PR split

Conventional Commits types, one per PR (see `tasks.md` for the PR order):

- `feat(portal)`: the route, config parsing and startup validation in `portal/`.
- `feat(web)`: the same in `web/`, plus `BLACKHOLE_UI_ORIGIN`.
- `build(deploy)`: the Helm values, env wiring, schema, `validate.yaml` and the chart test.

The two `feat` PRs are separate because they ship independently and each app has its own
Vitest suite. The `build(deploy)` PR comes last because without env vars both apps are inert,
so merging the apps first is safe.

## Impact

- Affected code: `portal/server/` (new route and config parsing), `web/server/` (same),
  `deploy/charts/blackhole/` (values, schema, template, chart test).
- `openspec/config.yaml` principle 2 says every route except `GET /health` needs a verified
  Entra token. This change needs one more explicit exemption, `GET /.well-known/security.txt`,
  and the exemption is recorded there as part of the apply work, not silently.
- No breaking changes.
