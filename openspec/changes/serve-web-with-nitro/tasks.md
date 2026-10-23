# Tasks

Prerequisites: ADR-BLKH-0003 accepted (done). This change lands before `add-team-sso` PR 4.
It ships as a stack of small PRs, each branching from the previous one and merged back-to-back.
Tests follow F.I.R.S.T. (see design.md, "Tests"): Vitest in `web/server/**`, no container, no
network, no sleeps; the only container check is task 2.4.

## 1. Nitro server (PR 1: `build(web): add a nitro server for the admin ui`)

- [ ] 1.1 Add `nitro@3.0.260903-beta` (exact) and the `nitro/vite` plugin to `web/package.json` and `vite.config.ts`, plus `nitro.config.ts` with `serverDir: 'server'`; verify `npm run build` produces `.output/server/index.mjs` and `.output/public/assets/*`, `node .output/server/index.mjs` serves `/` and an unknown path as `index.html`, and `npm run dev` still proxies `/api`; if v3 is unusable, record the fallback to `nitropack@2.13.4` in design.md
- [ ] 1.2 `server/lib/config.ts` `parseUiConfig`, tests first: port every case of `nginx/40-config-json.test.ts` and add backend-URL scheme, trailing slash and aggregated-error cases; verify `npm test`
- [ ] 1.3 `server/plugins/config.ts` fails fast; verify a unit test of the plugin function with a fake exit and stderr, and a manual run that a bad env exits non-zero before listening
- [ ] 1.4 `server/routes/config.json.ts` and `server/routes/healthz.ts`; verify handler unit tests (body, `application/json`, `no-store`, `ok`)
- [ ] 1.5 `server/lib/headers.ts` and `server/middleware/security-headers.ts`; verify unit tests for each header, the exact CSP string, absence of `Server` and `X-Powered-By`, and `no-cache` for the index document
- [ ] 1.6 `server/lib/proxy.ts` and `server/routes/api/[...path].ts`; verify unit tests with a fake `fetch`: `/api/x` reaches `<backend>/x`, query and method kept, forwarded headers, `no-store`, evidence route uses the 120 s signal and does not read the body, backend failure gives 502, aborted signal gives 504, and a table of hostile paths (`..`, `//host`, encoded slash, backslash, scheme) answers 400 without calling fetch
- [ ] 1.7 `publicAssets` for `/assets/` (one year, immutable, 404 on miss); verify with a local run and note the observed headers in the PR
- [ ] 1.8 Change the `vite.config.ts` test `include` to `server/**/*.test.ts` (replacing `nginx/**`); verify `npm run typecheck`, `npm test` and `./gradlew :web:build`

## 2. Container (PR 2: `build(web): serve the admin ui from a nitro node image`)

- [ ] 2.1 Rewrite `web/Dockerfile` (node:24-alpine build and runtime, `.output` only, `USER node`, `NITRO_PORT=8080`, `NITRO_HOST=0.0.0.0`, healthcheck) and adjust `Dockerfile.dockerignore`; verify `docker build -f web/Dockerfile -t blackhole-web .`
- [ ] 2.2 Delete `web/nginx/` (template, script, shell test); verify `grep -rni nginx web .github docker` only finds intended prose
- [ ] 2.3 Adjust the web service comments and healthcheck in `docker/docker-compose.yml`; verify `docker compose -f docker/docker-compose.yml config`
- [ ] 2.4 Container smoke check script (`web/scripts/smoke.mjs`) and a CI step in `build-pr.yml` job `web_image`: run the image against a throwaway upstream and assert the cases in design.md, then run it without `BLACKHOLE_UI_SOURCE` and assert a non-zero exit and the message; verify locally and in CI
- [ ] 2.5 Align the Node version in CI and `web/build.gradle.kts` with `main` after #185; verify `./gradlew :web:build`

## 3. Documentation (PR 3: `docs(web): document the nitro runtime`)

- [ ] 3.1 Update `web/README.md` (Container section, unchanged variable table, an access-control note replacing the nginx `allow`/`deny`/basic-auth text, `server/` in the structure table), the comment in `vite.config.ts` and the Dockerfile header; verify no stale nginx statement remains
- [ ] 3.2 Add the non-equivalences from design.md to the README; verify against design.md

## 4. Coordinate with add-team-sso (PR 4: `docs(openspec): retarget add-team-sso web config to nitro`)

- [ ] 4.1 Rewrite `openspec/changes/add-team-sso/tasks.md` 4.4 to target `web/server/lib/config.ts`, `web/server/lib/headers.ts` (CSP `connect-src`) and Vitest tests instead of `nginx/40-config-json.sh` and shell tests, add "`serve-web-with-nitro` is merged" to its Prerequisites, and update the nginx mentions in its `proposal.md` Impact and `design.md` section 7
- [ ] 4.2 Reword the `admin-ui-authentication` delta requirement "Sign-in with Authorization Code and PKCE" and its "Misconfigured container" scenario from "the container writes" to "the web server serves ... refuses to start"; verify `openspec validate --all --strict`

## 5. Pull requests

- [ ] 5.1 Open the stacked pull requests, each based on the previous branch, with these titles: `build(web): add a nitro server for the admin ui` (section 1), `build(web): serve the admin ui from a nitro node image` (section 2), `docs(web): document the nitro runtime` (section 3), `docs(openspec): retarget add-team-sso web config to nitro` (section 4); after merge archive this change as `docs(openspec): archive serve-web-with-nitro`; verify CI is green on each and that commitlint accepts the titles
