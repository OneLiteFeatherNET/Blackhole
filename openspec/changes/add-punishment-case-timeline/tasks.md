# Tasks

Four small stacked PRs, each squash-merged; the PR title is the Conventional Commit. Every
backend group lands its own JUnit tests (F.I.R.S.T.: injected `Clock`, no sleeps, self-signed JWTs,
Testcontainers for the migration), every UI group its own Vitest tests with a fake API client.

## 1. Timeline model and recording (PR 1, `feat(case)`)

- [x] 1.1 Failing tests first: payload (de)serialisation per type incl. unknown type/version, trust derivation, dedupe, ordering by `occurred_at` then `seq`
- [x] 1.2 Migration `V<n>__punishment_case.sql` (next free version): `punishment_case`, `case_event`, indexes, backfill of design decision 3; Testcontainers test over representative old rows (counts, origin `BACKFILLED`, trust, `CLOSED` for ended punishments, idempotent re-run, cascade)
- [x] 1.3 `case/` package: entities, `CaseEventRepository` with only append / redact / delete-for-punishment (+ architecture test), sealed `CaseEventPayload`, `CaseTimeline.record` with dedupe key and failure counter `blackhole.case.record.failures`
- [x] 1.4 Record from the flows: apply and escalation (`punishment.applied`), revoke, expiry sweeper (once per punishment), appeal filed / decided / lifted, evidence attached / lifted / revoked through `PunishmentEvidenceGateway`; automatic reopen of a `CLOSED` case on appeal; tests prove a recording failure never fails the primary change
- [x] 1.5 `CaseReconciler` with `blackhole.case.*` config documented in `application.yml`; tests on fixed data with `TestClock` (source time used, no duplicates)
- [x] 1.6 Update `.claude/rules` for the new package; verify `./gradlew :backend:test`

## 2. Metadata, notes and API (PR 2, `feat(case)`)

- [x] 2.1 Failing tests first: stale version gives 409 with no state or event change, unchanged value appends nothing, unknown assignee 422, tag validation, note length, `PiiGuard`, forced `STAFF` visibility, author-or-admin edit
- [x] 2.2 `JdbcCaseWriter` single-statement CTE writes (state + event atomic), `CaseService`, sealed `CaseDTO`, `CaseController` thin + `CaseApi` OpenAPI annotations (`@Version(ApiVersion.V1)`, `@Secured` per route)
- [x] 2.3 Routes: list, case + `related`, paged filtered timeline (`seq` cursor, max 200), `PATCH`, notes `POST` / `PUT`, redact; actor from `ActorResolver`; redaction of the whole note chain + `note.redacted`
- [x] 2.4 Domain events `case.*` (no note text), try/catch publish; tests with a captured publisher
- [x] 2.5 Extend `PermissionMatrixIntegrationTest` and `RouteCoverageIntegrationTest` (VIEWER reads, MODERATOR writes, ADMIN redacts, PROXY denied); anonymize test showing nameless actors
- [x] 2.6 Regenerate the OpenAPI client spec: new `client/specs/blackhole-api-0.0.N.yml` (next free number; 0.0.17/0.0.18 are claimed by `integrate-cloudnet-sections`) and bump `inputSpec` in `client/build.gradle.kts`
- [x] 2.7 Update `.claude/rules` (events, security matrix); verify `./gradlew :backend:test :client:compileJava`

## 3. Web case page (PR 3, `feat(web)`)

- [x] 3.1 API types and client methods for the case routes; tests with a fake client
- [x] 3.2 Router entries `/cases` and `/cases/:reference`, link from the player detail's punishments
- [x] 3.3 Case header (status, assignee picker, tags, priority, role gating, 409 handling) with component tests
- [x] 3.4 Timeline component: icon per type, unknown type, trust and origin badges, filters, cursor paging, edited / redacted notes; keyboard and focus tests
- [x] 3.5 Notes editor (counter, Ctrl+Enter, edit own, admin redact dialog), inline evidence via existing components, related cases, case list view
- [x] 3.6 German and English strings; `i18n.keys.test.ts` parity; live region and semantic markup checks; verify `npm test` and the type check

## 4. Documentation (PR 4, `docs(case)`)

- [ ] 4.1 README / docs section on the case timeline: model, trust levels, backfill markers, notes and erasure, `blackhole.case.*` config, the reserved types for the portal and Discord, event keys `case.*`; verify docs match config keys and routes in code
- [ ] 4.2 Note in `.claude/PROGRESS.md` per PR as it lands

## 5. Verification and wrap-up

- [ ] 5.1 Manual check against the real stack (Docker PostgreSQL + NATS): migrate a database with legacy punishments, open a backfilled case, add and edit a note, redact it as admin, anonymize a staff actor; record the result in PR 3
- [ ] 5.2 `openspec validate --all --strict`; archive with `docs(openspec): archive add-punishment-case-timeline` after the PRs merge
- [ ] 5.3 Open the pull requests as small stacked PRs against `main` (each squash-merged, title is the Conventional Commit): `feat(case): record an append-only case timeline per punishment`, `feat(case): add case metadata, notes and api`, `feat(web): add case page with timeline and notes`, `docs(case): document the case timeline`
