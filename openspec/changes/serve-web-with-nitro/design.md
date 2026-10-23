# Design

## Context

Current serving (all in `web/`): `Dockerfile` (node:22 build, `nginxinc/nginx-unprivileged`
runtime), `nginx/default.conf.template` (envsubst of `BLACKHOLE_BACKEND_URL`),
`nginx/40-config-json.sh` (writes `/tmp/config/config.json`, strict validation, exits 1 on bad
input) and its `sh`-spawning test. Vite builds the SPA to `dist/`; `vite.config.ts` proxies `/api`
for development only.

No Flyway migrations are appended and no backend API or client spec changes.

## Research findings (checked 2026-10-23)

- `npm view nitro`: dist-tag `latest` is `3.0.260903-beta` (published 2026-09-03); v3 has only
  alpha/beta releases. `nitropack` (v2) latest is 2.13.5 (2026-10-19, too new), previous 2.13.4
  (2026-04-29). `nitro@3.0.260903-beta` needs Node `^20.19 || >=22.12`, peers `vite ^7 || ^8`
  (repo has Vite 8.3.x), depends on h3 2 rc and srvx.
- Vite plugin: `import { nitro } from 'nitro/vite'` in `plugins`; Nitro options go in
  `nitro.config.ts` or under a `nitro` key. `serverDir` is off by default and must be set for
  `server/` scanning.
- Renderer: a root `index.html` is the template for every unmatched route (lowest priority
  catch-all); with Vite, serving `index.html` for unmatched routes is the default, i.e. SPA fallback.
- Public assets: `public/` is served at `/` with ETag/Last-Modified/304. `publicAssets` entries
  accept `maxAge` (emits `public, max-age=N, immutable`, effective only with `fallthrough: false`).
- `routeRules`: patterns support `headers`, `proxy` (wildcard keeps the path) and `cache`. They are
  build configuration; `'**'` headers are the documented way for global headers, but the docs warn
  that static hosting may skip them for static files.
- `runtimeConfig`: overridden by `NITRO_*` env vars **only for keys declared at build time**;
  `.env` is not read by the built server.
- Node preset: `node .output/server/index.mjs`, `NITRO_PORT`/`PORT`, `NITRO_HOST`/`HOST`,
  graceful shutdown on SIGINT/SIGTERM (`NITRO_SHUTDOWN_TIMEOUT`, default 30 s).

Consequences: a build-time `routeRules.proxy` cannot take the backend URL from the runtime
environment, so the proxy is a handler (decision 4). Security headers on static files must be
verified, so they are set in middleware and asserted in the smoke test (decision 5).

## Decisions

### 1. Nitro v3 beta, pinned `nitro@3.0.260903-beta`

The SPA is built by Vite 8; only v3 has the first-class `nitro/vite` integration (v2 is a separate
`nitropack` build pipeline). Chosen version: the newest release older than two weeks (7 weeks old,
the newest beta before it is from June). Pinned without `^`; the lockfile pins transitive
h3/srvx. Risk: beta API churn. Mitigation: exact pin, each upgrade is its own `build(deps)` PR,
our logic lives in plain functions that survive upgrades, and the container smoke test covers
the wiring. Fallback if v3 proves unusable during PR 1: `nitropack@2.13.4` with the same handler
modules (h3 1.x `proxyRequest`); the equivalence table does not change.

### 2. Layout

```
web/nitro.config.ts            serverDir: 'server', renderer from index.html, publicAssets
web/server/plugins/config.ts   reads env once at startup, fails fast
web/server/lib/config.ts       parseUiConfig(env): pure, typed, unit tested
web/server/lib/headers.ts      securityHeaders(): pure
web/server/lib/proxy.ts        buildBackendRequest(...): pure URL/header building
web/server/middleware/security-headers.ts
web/server/routes/config.json.ts
web/server/routes/healthz.ts
web/server/routes/api/[...path].ts
web/server/**/*.test.ts        Vitest, `@vitest-environment node`
```

Handlers are thin: pure functions hold the logic and are tested without Nitro, a container or a
network.

### 3. Runtime configuration: keep `BLACKHOLE_*`, no `NITRO_*` mapping

`parseUiConfig(env: Record<string, string | undefined>)` returns
`{ backendUrl, source, reviewer, evidence? }` or throws `ConfigError` listing **all** invalid
variables (one line each, naming the variable and an example), using the same patterns as
`40-config-json.sh` (UUID; positive integer bytes `^[1-9][0-9]{0,15}$`; MIME list). Additionally
`BLACKHOLE_BACKEND_URL` (default `http://backend:8080`) must parse as an `http:` or `https:` URL
without credentials, query or fragment; a trailing slash is stripped.
A Nitro plugin calls it with `process.env` at startup and stores the result; on `ConfigError` it
logs the message to stderr and the process exits non-zero before listening. `runtimeConfig` is not
used for these values because `NITRO_*` overrides only work for keys declared at build time and
the existing names are part of the operator contract. `GET /config.json` is a server route
returning the typed object (built with `JSON.stringify`, so injection is impossible by
construction; the strict patterns stay as defence in depth), `Cache-Control: no-store`. The SSO
change later swaps the schema (decision 8). `public/config.json` remains only as the Vite
dev-server fixture; a test asserts the server route wins over a static file of the same name.

### 4. API proxy as a handler

`server/routes/api/[...path].ts` forwards `/api/<rest>` to `<backendUrl>/<rest>` with method, query
and body (streamed, no buffering, no size limit in the proxy; the backend enforces the evidence
limits), and sets `X-Forwarded-For` (client address), `X-Forwarded-Proto` and the backend `Host`.
Timeouts via `AbortSignal.timeout`: 30 s default, 120 s for `POST /api/evidence/file` (as nginx).
Responses get `Cache-Control: no-store`. Backend unreachable gives 502, timeout 504, plain text.
Hardening (the rest-path is caller-controlled, the origin is not): the path is normalised and
rejected with 400 if it contains `..` segments, backslashes, encoded slashes or a scheme/authority
(`//host`), so the target origin always equals the origin of `backendUrl`. This is tested with a
table of hostile paths. `BLACKHOLE_BACKEND_URL` is operator configuration, not caller input, so a
scheme check is the only URL validation needed (CLAUDE.md SSRF rule applies to caller-supplied
URLs).

### 5. Security headers via middleware

A global middleware sets on every response, including static files and the SPA fallback:
`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` and
`Content-Security-Policy` (the string used today, produced by `securityHeaders()` so the SSO change
adds `connect-src` in one place). No `Server` or `X-Powered-By` header (asserted). `routeRules` is
not used because of the static-file caveat above.

### 6. Static files, caching and SPA fallback

Built files under `/assets/` are public assets with `maxAge` one year and `fallthrough: false`
(`public, max-age=31536000, immutable`; a missing `/assets/x` is a real 404, like `try_files =404`).
`index.html` (renderer) gets `Cache-Control: no-cache`. Any other unmatched `GET`/`HEAD` path
serves `index.html` with 200. Whether the Vite client build lands in `.output/public` unchanged
(hashed names under `assets/`) is verified in PR 1; if not, the `publicAssets` entry is adjusted.

### 7. Image

```
FROM node:24-alpine AS build   (npm ci, npm run build -> web/.output)
FROM node:24-alpine            (copy .output only; USER node; EXPOSE 8080)
ENV NITRO_PORT=8080 NITRO_HOST=0.0.0.0 NODE_ENV=production
HEALTHCHECK wget -q --spider http://127.0.0.1:8080/healthz
CMD ["node", ".output/server/index.mjs"]
```

Runs as the non-root `node` user; a read-only root filesystem works (config is in memory, which
removes nginx's `/tmp/config`). Alpine's busybox `wget` serves the healthcheck; distroless was
rejected because it has no `wget`, so the healthcheck would need a Node one-liner, for little gain.
Node 24 follows PR #185; until that merges the image may use `node:22-alpine` (Nitro needs
>= 22.12), the PR 2 author picks whichever `main` uses. Base image pinned like other images
(Renovate).

### 8. Coordination with `add-team-sso`

This change lands **first**. `add-team-sso` task 4.4 currently says "`/config.json` and
`nginx/40-config-json.sh` ... update the nginx CSP `connect-src`". After this change it targets
`web/server/lib/config.ts` (schema: OIDC authority, client id and API scope replace
`source`/`reviewer`; same strict validation and fail-fast), `web/server/lib/headers.ts`
(`connect-src` gains the authority origin) and their Vitest tests instead of shell tests. The
paragraph in its design.md (section 7) and the `admin-ui-authentication` requirement "Sign-in with
Authorization Code and PKCE" are reworded from "the container writes ... refuses to start" to "the
web server serves ... refuses to start"; behaviour is unchanged. Its proposal Impact line
`nginx/40-config-json.sh` becomes `server/lib/config.ts`.

## Equivalence: every nginx responsibility and its Nitro counterpart

| # | nginx today | Nitro counterpart | Verified by |
|---|-------------|-------------------|-------------|
| 1 | Static files from `/usr/share/nginx/html` | Vite build + Nitro public assets (`.output/public`) | smoke: `GET /` 200 |
| 2 | SPA fallback `try_files $uri /index.html` | Nitro renderer with root `index.html` | smoke: `GET /players/abc` 200 html |
| 3 | `/assets/` 1 y `public, immutable`, miss = 404 | `publicAssets` `maxAge`, `fallthrough: false` | smoke: header and 404 |
| 4 | `index.html` `Cache-Control: no-cache` | header set in middleware for the index document | unit: headers |
| 5 | `/api/` proxy, prefix stripped, to `BLACKHOLE_BACKEND_URL` (envsubst at start) | `server/routes/api/[...path].ts`, URL from validated env at startup | unit: URL builder, fake fetch |
| 6 | `/api/evidence/file`: body 128 MB, no request buffering, 120 s | same handler, streamed body, no proxy-side limit, 120 s timeout for that route | unit: timeout selection, body not read |
| 7 | `/api/` read timeout 30 s | `AbortSignal.timeout(30_000)` gives 504 | unit with an aborted signal |
| 8 | `X-Forwarded-For/Proto`, `Host` = backend | set explicitly in the proxy | unit |
| 9 | `Cache-Control: no-store` on API | set on the proxy response | unit |
| 10 | Security headers (nosniff, DENY, no-referrer, CSP) on all responses | global middleware + `securityHeaders()` | unit + smoke |
| 11 | `server_tokens off` | no `Server` header from Nitro/srvx; middleware removes `X-Powered-By` | unit + smoke |
| 12 | `/config.json` from shell script, `no-store`, exit 1 on bad env | typed route + `parseUiConfig`, exit 1 at startup naming every bad variable | unit |
| 13 | `/healthz` 200 `ok`, no access log | `routes/healthz.ts` 200 `ok` text/plain; Nitro logs no access lines by default | unit + smoke |
| 14 | Healthcheck `wget /healthz` | same `wget` against the Node server | CI smoke |
| 15 | Unprivileged user, port 8080 | `USER node`, `NITRO_PORT=8080` | smoke: uid != 0 |
| 16 | Backend host must resolve at nginx start | resolved per request: no start-time failure, 502 until reachable | unit |

### Non-equivalences

- nginx's commented `allow`/`deny` and `auth_basic` blocks have no built-in Nitro form. They were
  never enabled; access control stays with the deployment boundary (and SSO later). The README
  says so and points at a reverse proxy.
- nginx can proxy WebSocket upgrades; the UI uses none, so the Nitro proxy does not forward them.
- No nginx access log and no dynamic response compression; static compression
  (`compressPublicAssets`) is optional and off. The Node proxy is in-process, acceptable for an
  internal admin tool; the streamed body avoids buffering.
- Image size grows (Node runtime vs nginx); the runtime stage contains only `.output`.

## Tests (F.I.R.S.T.)

- Fast, Independent, Repeatable: `parseUiConfig`, `securityHeaders`, the proxy URL/header builder
  and the handler functions are plain functions tested in Vitest with
  `// @vitest-environment node`, a fake `fetch`, a fresh input object per test, no sockets, no
  sleeps, no shared state; timeouts are tested by passing an already-aborted `AbortSignal`.
- Self-validating: every case of `40-config-json.test.ts` is ported (valid, reviewer default,
  missing/malformed UUID, injection attempts, evidence limits, MIME list, README documents each
  variable) plus backend URL scheme and aggregated-error cases.
- Timely: tests are written before the handler (red, green, refactor).
- One container smoke check (locally and in CI job `web_image`): build, run with a throwaway
  upstream, assert `/healthz`, `/config.json`, SPA fallback, asset headers, CSP, `/api/x` proxied,
  non-root, and that a missing `BLACKHOLE_UI_SOURCE` stops the container with the message. It is an
  integration test and may relax Fast but not Repeatable or Self-validating.

## Risks

- Beta churn (decision 1). Headers on static files (decision 5, smoke-tested). `vite.config.ts`
  test `include` lists `nginx/**` and must change to `server/**`.
- Startup failure semantics inside a Nitro plugin: verified in PR 1; if a throwing plugin does not
  exit non-zero, the plugin logs and calls `process.exit(1)`.
