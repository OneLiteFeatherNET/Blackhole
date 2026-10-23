# Proposal

**Ships as:** `build(web): serve the admin ui with a nitro server instead of nginx` (runtime and
image switch, stacked PRs, see tasks.md). Tests ship with the code they cover; the docs PR is
`docs(web)`, the SSO retargeting PR is `docs(openspec)`.
Decision record: [ADR-BLKH-0003](https://outline.onelitefeather.dev/doc/adr-blkh-0003-nitro-statt-nginx-als-runtime-fur-die-blackhole-admin-ui-qifGzv0W4N)
(status `accepted`, decided 2026-10-23). The decision is not reopened here.

## Why

The admin UI image is `nginx-unprivileged` plus a POSIX shell script (`40-config-json.sh`) that
writes `/config.json`, an envsubst template for the proxy and a test that spawns `sh`. Validation,
header policy and proxy rules live in three languages that cannot be unit tested the way the rest
of `web/` is. The upcoming SSO work (`add-team-sso`, PR 4) adds three more validated settings and a
CSP change; doing that in shell and nginx config deepens the problem. ADR-BLKH-0003 moves the
runtime to Nitro so the server side is TypeScript in the same package, with Vitest unit tests and
typed, fail-fast configuration.

## What Changes

- `web/` gains a Nitro v2 server (`nitropack@2.13.4`, pinned exactly, `node-server` preset) next to
  the unchanged Vite build. It serves `web/dist` as public assets, falls back to `index.html`, proxies `/api/**` to `BLACKHOLE_BACKEND_URL` read at
  **runtime**, sets the security headers, serves `/healthz` and a typed `/config.json`.
- Runtime settings keep their names (`BLACKHOLE_BACKEND_URL`, `BLACKHOLE_UI_SOURCE`,
  `BLACKHOLE_UI_REVIEWER`, `BLACKHOLE_UI_EVIDENCE_*`); they are validated once at startup and the
  process exits with a message naming every bad variable. No mapping to `NITRO_*` is needed
  (design decision 3).
- `web/Dockerfile` becomes a Node 24 alpine image, non-root, with a healthcheck. `nginx/` is
  deleted together with its shell test; the same cases move to Vitest.
- CI builds the image and runs one container smoke check.
- Behaviour towards browsers and the backend stays equivalent; design.md lists every nginx
  responsibility and its Nitro counterpart.
- **Coordination with `add-team-sso`:** this change lands **before** SSO PR 4. A task here
  rewrites `add-team-sso` task 4.4, its design paragraph and the `admin-ui-authentication` spec
  wording from nginx/shell to the Nitro config route and header module.

Not BREAKING for operators: same port (8080), same variable names and defaults. The visible
differences are listed in design.md (non-equivalences): no commented `allow`/`deny` or basic-auth
block, and a different base image.

Out of scope: SSR or any server-rendered page; adding authentication (that is `add-team-sso`);
TLS termination, rate limiting and access control (infra owns them); changing the API, the
backend or the generated client; moving other modules to Nitro.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `admin-web-ui`: single-origin deployment is served by the Nitro server (modified); new
  requirements for static serving and SPA fallback, runtime configuration with fail-fast
  validation, security headers, health endpoint and container runtime.

## Impact

- `web/`: `package.json` (+`nitropack` exact, scripts), `vite.config.ts` (test include), `nitro.config.ts`,
  `server/**` (new), `Dockerfile`, `Dockerfile.dockerignore`, `README.md`; `web/nginx/**`
  removed; `web/public/config.json` stays for the Vite dev server only.
- `docker/docker-compose.yml` (web service comments/healthcheck), `.github/workflows/build-pr.yml`
  (smoke check, Node version follows #185).
- `openspec/changes/add-team-sso/{tasks.md,design.md,proposal.md,specs/admin-ui-authentication/spec.md}`.
- Nitro v2 (`nitropack`) is the stable line; a later move to Nitro v3 is a small follow-up once v3
  is stable (design decision 1).
