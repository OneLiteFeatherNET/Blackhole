# Tasks

Tests come first in every group (red, then green). Every test follows F.I.R.S.T.: an injected `Clock`, no sleeps, a fresh
fixture per test (`@TempDir` or a new database schema), fixed seeds, and no shared static state. Integration tests run
through `flock ~/.cache/claude-heavy.lock` with the Testcontainers database. The code PR contains no file under `openspec/`.

## 1. Red: permission and route tests

- [ ] 1.1 Extend `PermissionMatrixIntegrationTest` with one row per endpoint of design decision 1: 200 for the minimum role, 401 without a token, 403 for a role below the minimum (a token with no Blackhole role for VIEWER-minimum rows, MODERATOR and VIEWER for ADMIN-only rows), and 404 for an unknown id where the role may read the entity
- [ ] 1.2 Add to the same test the ADMIN-only rule of the spec: a VIEWER asking for an unknown review gets 403, not 404
- [ ] 1.3 Extend `RouteCoverageIntegrationTest` with the seven routes, their `@Secured` roles and `@Version(V1)`
- [ ] 1.4 Red: a forbidden-field test per endpoint. The fixture seeds a Ban ID, a raw player UUID, an IP address and a player name; the test asserts that none of these values is in the JSON body
- [ ] 1.5 Red: the owner hash path test: a raw UUID, a name and a 127-character value each get 400 `VALIDATION` with no lookup (a fake store that fails the test when called)
- [ ] 1.6 Red: the 200 body of each endpoint equals the list item for the same entity (audit entry, allow-list entry, area ban)

## 2. Green: read-by-id endpoints

- [ ] 2.1 Appeal: `GET /appeal/{identifier}` in `AppealApi` and `AppealController`, one call to a service method in `appeal/service` returning `AppealDTO` or the no-body 404 (decision 1)
- [ ] 2.2 Exemption review: `GET /exemption/reviews/{id}` with `ExemptionDTO.ReviewResponse` and `ExemptionDTO.Error` `NOT_FOUND`
- [ ] 2.3 Area ban: `GET /area-bans/{id}` with `{VIEWER, PROXY}` and `AreaBanDTO.Response` / `AreaBanDTO.Error`
- [ ] 2.4 Allow-list entry: `GET /intel/allow-list/{owner}` with the 128-hex pattern on `owner` and `AllowListDTO.Entry` / `AllowListDTO.Error`
- [ ] 2.5 Audit entry: `GET /admin/audit/{auditId}`; `AuditDTO.Item` implements `AuditDTO`, wire unchanged
- [ ] 2.6 Staff actor: `GET /admin/staff-actor/{oid}` with `StaffActorDTO.Item`; the record implements `StaffActorDTO` if it does not
- [ ] 2.7 Terms version: `GET /appeal-conditions/versions/{version}` with `AppealConditionsDTO.Response` / `Error`
- [ ] 2.8 OpenAPI annotations for each new method on its `*Api` interface, with 200, 404 and the error schema

## 3. Red then green: inbox `changedByOthers`

- [ ] 3.1 Red: unit tests with a fake case-event reader and a fixed `Clock` for the rules of the spec: another staff member's latest event is listed; the caller's own is not; a proxy or system event is not; an event 25 hours old is not; `count` is 14 and 10 items for 14 rows; newest first
- [ ] 3.2 Red: the kind rule (`AppealFiled` and `AppealDecided` give `APPEAL`), the change enum (every case event type maps to one value, others to `OTHER`) and the anonymized name (`byName` null)
- [ ] 3.3 Green: the section in `inbox/InboxService` and `InboxQueries`, the window read from `blackhole.inbox.changed-window`, the `Actor` parameter on `InboxApi.summary`
- [ ] 3.4 Green: `InboxDTO.Response` gains `changedByOthers`, its item record and the change enum; the four existing sections are unchanged
- [ ] 3.5 Add `blackhole.inbox.changed-window` to `application.yml` as `${INBOX_CHANGED_WINDOW:PT24H}` with a comment for the intent (design decision 2)
- [ ] 3.6 Red then green: an integration test of `GET /inbox` for a VIEWER (200, section present), without a token (401), and the forbidden-field check of 1.4 on the new section

## 4. Client spec

- [ ] 4.1 Copy `client/specs/blackhole-api-0.0.67.yml` to `client/specs/blackhole-api-0.0.68.yml` (or the next free number at that time, design decision 4); never edit a published file
- [ ] 4.2 Add the seven paths, the `changedByOthers` section and the change enum to the new file
- [ ] 4.3 Move the `inputSpec` pin in `client/build.gradle.kts` to the new file and run `./gradlew :client:openApiGenerate :client:compileJava`

## 5. Verification

- [ ] 5.1 Run the targeted tests (`PermissionMatrixIntegrationTest`, `RouteCoverageIntegrationTest`, the inbox unit tests) under `flock ~/.cache/claude-heavy.lock`; check `MemAvailable` first
- [ ] 5.2 Run `./gradlew :backend:test` once under the same lock and confirm the list of passing tests includes the new ones
- [ ] 5.3 Start the backend against the local containers and call each new route with a VIEWER, a MODERATOR and an ADMIN token; record the status codes in the PR description
- [ ] 5.4 Review the diff against the Conventional Commit rule and the Ban ID, raw UUID, IP and name rules of the spec

## 6. Code pull request

- [ ] 6.1 Open the code pull request titled `feat(api): add read-by-id endpoints for the staff panel` from `origin/main`, after the proposal PR `docs(openspec): propose add-staff-panel-read-endpoints` is merged. The body follows `.github/pull_request_template.md`, with the ASVS Level 2 items it touches (V2, V3, V4, V5, V7, V14) and no openspec file
