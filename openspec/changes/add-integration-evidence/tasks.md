# Tasks

Every group writes its tests first (red, then green, then refactor). Backend tests follow F.I.R.S.T.: an injected
`java.time.Clock`, no sleeps, no real network, a fresh fixture per test. Unit tests use fakes for the registry, the
store and the blob-free evidence path; one Docker-backed integration test per group covers the Flyway migration and
the batch route against the real database (runtime-verify against Docker before merge).
UI groups get component tests (Vitest) and the i18n key-parity test.

Each numbered group is one code PR (see design, decision 6). Task checkboxes are ticked only in the follow-up PR.

## 1. Registry and principal (`feat(integration): add integration registry and machine principal`)

- [ ] 1.1 Test `Roles.INTEGRATION` expands to itself only and is not implied by ADMIN or MODERATOR; verify a unit test in `Roles`
- [ ] 1.2 Test the role finder maps an app-only token with `Blackhole.Integration` to `INTEGRATION` and maps no other role to it; verify with `BlackholeRolesFinderTest` fixtures
- [ ] 1.3 Test the registration service: create, disable and duplicate `source_system` or `entra_client_id` answers the Error variant; verify with an in-memory repository fake
- [ ] 1.4 Test each registration write records one audit entry with the actor oid and no secret; verify with a recording audit fake
- [ ] 1.5 Add `Roles.INTEGRATION`, `blackhole.auth.machine-integration` (default `Blackhole.Integration`) and `blackhole.auth.clients.integration`, each documented in `application.yml`
- [ ] 1.6 Migration `V<n>__integration_detections.sql` (part 1: `integration` table with unique `source_system` and `entra_client_id`); verify a Docker test that the migration applies on a clean database and on the state before it (the newest version on `origin/main` at implementation time)
- [ ] 1.7 `IntegrationRegistrationController` (thin), `IntegrationRegistrationApi` (OpenAPI), sealed DTO per `micronaut-dto-contract`; verify a controller test that each route calls exactly one service method
- [ ] 1.8 Integration test: a token without the role, and a token whose client id has no registration, both get 403 (spec: unauthorised principal refused)
- [ ] 1.9 Test the registration scope: `allowedTasks` and `allowedStaticServers` are stored, and the rule `<task>-<n>` (digits) matches `Lobby-2` but not `Lobbyx-2`, `Lobby-x` or `Creative-2` against `Creative`; verify with table-driven unit tests (spec: registration granularity)
- [ ] 1.10 Registration routes accept `allowedTasks` and `allowedStaticServers` in the create and update requests and reject an empty scope; verify a controller test and update the OpenAPI annotations on the `*Api` interface

## 2. Ingestion (`feat(integration): ingest detections with schema validation`)

- [ ] 2.1 Test the envelope validator: each rule of the envelope table has a test (time window at both edges, server length, summary length, missing field, unknown field); verify unit tests with a fixed `Clock`
- [ ] 2.2 Test `SourceSystem` is taken from the registration and a body value that differs yields `SOURCE_MISMATCH` (spec: body cannot name another source)
- [ ] 2.3 Test `SchemaRegistry` loads each file under `integration-schemas/` once, and an unknown triple yields `UNKNOWN_KIND`; verify with a test resource directory
- [ ] 2.4 Test the `vulcan/DETECTION` v1 schema accepts a valid example, rejects a missing `check`, an over-long `detail`, an extra field and a negative VL; verify with JSON fixtures
- [ ] 2.4a Test a payload with `ping`, `tps`, `checkType`, `experimental`, `violationLevel` or `detail` absent or null is `ACCEPTED` with nulls stored (spec: optional fields may be absent or null); verify with fixtures for both cases
- [ ] 2.4b Test the server-scope check on ingestion: `Lobby-2` by a `Lobby` registration is accepted, `Survival-1` and `Lobbyx-2` answer `SERVER_OUT_OF_SCOPE`, and the label never selects the registration (spec: registration granularity); verify with the fake registry
- [ ] 2.4c Add the validation library `com.networknt:json-schema-validator:2.0.8` (design, "Validation library") and a test that a schema declaring `$schema` 2020-12 is validated with that dialect; verify a unit test with one invalid and one valid fixture per rule
- [ ] 2.5 Test the PII guard rejects a UUID and an IPv4 literal in a payload string (`PII_IN_PAYLOAD`), and the owner is hashed with the punishment owner hash, never stored raw; verify with a unit test asserting the stored owner length and that the raw UUID is absent from the row
- [ ] 2.6 Test idempotency: the second submission of a stored `detectionId` answers `DUPLICATE` and leaves the row unchanged; verify with the fake store (spec: duplicate detection is a no-op)
- [ ] 2.7 Test batch processing answers per item in request order and stores only valid items (spec: partial batch success)
- [ ] 2.8 Test body and batch caps: more than 50 items or more than 64 KiB answers 413 before parsing; verify with a controller test
- [ ] 2.9 Migration part 2: `integration_detection` with the unique key `(integration_id, detection_id)` and the two indexes; verify a Docker test that the unique key rejects a second insert and the migration is append-only
- [ ] 2.10 `IntegrationDetectionService`, thin controller `IntegrationDetectionController`, `IntegrationDetectionApi`, sealed DTO; verify a controller test that each route calls one service method
- [ ] 2.11 `IntegrationDetectionSweeper` deletes unattached detections older than `blackhole.integration.detection-retention` (default `P180D` from `BLACKHOLE_INTEGRATION_DETECTION_RETENTION`) and leaves attached ones; verify with a fixed `Clock` and a fake store (spec: retention sweep, default retention, attached survives)
- [ ] 2.12 Test no log line or domain event carries a payload, summary, detail or owner (a captured appender assertion); verify the captured log events contain only ids and counts
- [ ] 2.13 Docker integration test: a registered client posts a valid batch to the real route, gets `ACCEPTED` then `DUPLICATE` on a retry, and a submission without the role gets 403
- [ ] 2.14 Document the sweeper, its env var and the rejection codes in `application.yml` and `.claude/rules/` (a new `integration.md` path rule)
- [ ] 2.15 Add `vulcan/DETECTION` to the schema directory with the optional fields nullable, and a fixture-based test that the file loads at startup (spec: optional fields)

## 3. Attaching as evidence (`feat(integration): attach detections as evidence`)

- [ ] 3.1 Test the `EvidenceKind` order: `INTEGRATION` takes the next ordinal and existing ordinals do not change; verify a unit test on the enum
- [ ] 3.2 Migration part 3: widen the `evidence_items.kind` CHECK to include `INTEGRATION` in one new file; verify a Docker test that existing rows are unchanged and an `INTEGRATION` row inserts
- [ ] 3.3 Test attach creates one evidence item with `sourceSystem` and the validated snapshot, sets `attached_evidence_id`, and a second attach is a no-op (spec: attach creates evidence, attach is idempotent); verify with the fake store and evidence gateway
- [ ] 3.4 Test attach goes through `PunishmentEvidenceGateway` only, never `EvidenceService` or the blob store directly; verify with an architecture test in the style of `CaseChangeSurfaceTest`
- [ ] 3.5 Test `detection.attached` is written with ids, source and count only; verify the case event payload has no check, detail or payload value
- [ ] 3.6 Test the sweeper keeps attached detections (already covered in 2.11; re-verify after the attach path lands)
- [ ] 3.7 Test erasure of an owner removes unattached detections and attached evidence through the evidence erasure path with one audit entry (spec: erasure of an owner); verify with fakes
- [ ] 3.8 Attach and list routes (`MODERATOR`), thin controller, `*Api`, sealed DTO; verify a controller test and a Docker test that an integration token cannot call them (spec: no read access)
- [ ] 3.9 Test the inbox count for an open case counts detections newer than the staff member's last view and returns no content; verify with a fake store and a fixed `Clock`
- [ ] 3.10 Test that no integration path calls the ELO engine or the punishment or appeal services (spec: detections never feed ELO or punishments); verify with a fake that records calls, asserting zero calls after a flood of 200 detections and after an attach
- [ ] 3.11 Test that an appeal reviewer sees attached `INTEGRATION` evidence with its source and trust line, and does not see unattached detections (spec: appeal reviewers see attached integration evidence); verify with the appeal evidence fixture and a component test

## 4. Panel renderer (`feat(integration): render detections in the staff panel`)

- [ ] 4.1 Test the registry resolves `vulcan/DETECTION@1` to its component and an unknown key to the generic renderer (spec: known kind, unknown kind); verify a Vitest unit test with a fake import map
- [ ] 4.2 Test `GenericDetectionTable` shows every validated field as a label and text value and renders markup in a value as text (spec: markup in a detail); verify with a component test
- [ ] 4.3 Test a URL in a summary is not rendered as a link (spec: URL in a summary); verify a component test
- [ ] 4.4 Test the panel makes no request for a component, script or stylesheet at render (spec: unknown component is never fetched); verify with a spy on `fetch` and `document.createElement('script')`
- [ ] 4.5 Test there is no `v-html` in `web/src/components/integration/` (a grep test in the existing style of the `v-html` guard, if present, otherwise a unit test over the source files)
- [ ] 4.6 Hook the renderer into the evidence list for `INTEGRATION` items; verify with `EvidenceList.test.ts` additions
- [ ] 4.7 Hook a text-only `detection.attached` line into the case timeline; verify with the timeline component test (no check name shown)
- [ ] 4.8 Add the "Detections" tab on the player page with grouping by check and the attach action; verify a component test for grouping and the attach call
- [ ] 4.9 Add `GET /integration/kinds` (registry metadata for labels) to the backend in this group, with its test; regenerate the OpenAPI client spec in `client/specs` and run `./gradlew :client:openApiGenerate`
- [ ] 4.10 Add `de` and `en` keys for all labels and the trust line; verify the key-parity test

## 5. Vulcan card (`feat(integration): add the Vulcan detection card`)

- [ ] 5.1 Test `VulcanDetectionCard` renders check, VL, server, time, ping and TPS as labelled context and names "Reported by Vulcan" (spec: Vulcan card wording); verify a component test
- [ ] 5.2 Test the VL chart has a visually hidden table with the same values and does not depend on colour alone (spec: chart has a text alternative); verify a component test
- [ ] 5.3 Test the card's keyboard path: heading, attach button with a visible label, no focusable chart; verify with a keyboard-navigation component test
- [ ] 5.4 Test the card's numbers and times use the active locale (`de` and `en` fixtures); verify a component test with fixed input
- [ ] 5.5 Register `vulcan/DETECTION@1` in `registry.ts` as a lazily loaded chunk; verify a Vitest test that the dynamic import resolves and a build check that the chunk is separate
- [ ] 5.6 Confirm the `vulcan/DETECTION` v1 field names against the Vulcan bridge change (`add-vulcan-bridge`); if a field changes, a v2 schema is added here and the card is registered for v2, v1 rows stay readable (verify with a fixture of each)

## 6. Open the code pull request

- [ ] 6.1 Open the code PR of this change in its phase order. Each PR is titled as its conventional commit from the group headings above and branched from the remote `main` that already contains the merged proposal. The PR contains no file under `openspec/`. Confirm the PR title passes the commit lint before opening.
