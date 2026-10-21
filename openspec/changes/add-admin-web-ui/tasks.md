# Tasks

## 1. Backend support

- [x] 1.1 Add `owner` to the player lookup response; verify a unit test that it equals the proxy's hash for a fixed UUID
- [x] 1.2 Add `GET /dashboard/summary` (service + `DashboardApi` + thin controller, sealed DTO); verify service unit test and controller integration test
- [x] 1.3 Export the OpenAPI spec into `client/specs` with a version bump; verify `./gradlew :client:build`

## 2. Project scaffold

- [x] 2.1 Create `web/` with Vite + Vue 3 + TypeScript, Vue Router, Pinia, vue-i18n, Tailwind CSS v4, Vitest; verify `npm ci && npm run build && npm test` succeed
- [x] 2.2 Add `web/build.gradle.kts` (node-gradle plugin) and include it in `settings.gradle.kts`; Node 22 in `build-pr.yml`; verify `./gradlew :web:build`
- [x] 2.3 Generate `src/api/schema.d.ts` with openapi-typescript from the pinned spec before build, and the API wrapper (version header, source UUID from `/config.json`); verify `vue-tsc --noEmit` passes and a unit test checks headers

## 3. MD3 design system

- [x] 3.1 Generate MD3 light/dark color tokens from the seed color with material-color-utilities and map tokens, type scale, shape and elevation in Tailwind `@theme`; theme toggle with persisted override; verify a unit test for scheme switching
- [x] 3.2 Implement `web/src/components/md/` (button variants, text field, select, card, dialog, navigation rail/drawer, top app bar, chip, snackbar, data table, progress) with state layers and focus rings; verify component tests and an accessibility pass (keyboard focus, contrast) noted in the PR

## 4. Screens

- [x] 4.1 App shell with navigation and German/English i18n; verify all strings exist in both locale files via a test
- [x] 4.2 Dashboard; verify component test with mocked API
- [x] 4.3 Player lookup + detail (section score bars with thresholds, active punishments, history, ELO history); verify component tests for found/not-found
- [x] 4.4 Player actions: apply template (preview shows the step that will apply), revoke, adjust score with escalation preview; verify tests that the warning shows when cost crosses the permanent threshold
- [ ] 4.5 Templates CRUD with escalation-step editor (add/remove/reorder), escalation group/window, field-level API errors and delete conflict; verify component tests
- [ ] 4.6 Sections configuration incl. server patterns for SERVER sections; verify component tests
- [ ] 4.7 Reports and appeals queues; verify component tests

## 5. Deployment and docs

- [ ] 5.1 Multi-stage Dockerfile + nginx config (SPA fallback, `/api/` proxy, runtime `/config.json`), compose service; verify `docker build` in CI and manual run against the backend
- [ ] 5.2 README section for the UI (dev server, build, deployment behind private network/auth proxy); verify the documented commands run
