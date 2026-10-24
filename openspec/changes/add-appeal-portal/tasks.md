# Tasks

Eight small stacked PRs, each squash-merged; the PR title is the Conventional Commit. Every backend
group lands its own JUnit tests and every UI group its own Vitest tests (F.I.R.S.T.: injected
`Clock`, seeded `SecureRandom`, no sleeps, fake clients, Testcontainers for SQL). A bug found while
implementing is reproduced by a failing test first.

## 1. Ban ID (PR 1, `feat(appeal)`)

- [x] 1.1 Failing tests first: normalisation table, generator (alphabet, retry on collision, legacy short id rejected), HKDF/HMAC/AES round trip with the punishment id as AAD, `PiiGuard` rejects a Ban ID shape
- [x] 1.2 Migration `V13__ban_id.sql` (next free version): `ban_id_hash` (unique), `ban_id_enc`, `ban_id_gen`, case leak columns, partial unique index for one open appeal (existing duplicates flagged `legacy_duplicate_open`, not failed); migration test over representative rows (open-appeal pre-check)
- [x] 1.3 `BanIdService`, `BLACKHOLE_BANID_KEY` config with the start guard, issue on punishment creation, `BanIdBackfill` (idempotent, batched, gauge `blackhole.banid.missing`, `--reissue-all` for a key rotation, the v1 rotation path)
- [x] 1.4 `GET /punishment/{ref}/ban-id` (`MODERATOR`, `PROXY`, `no-store`); `POST /punishment/lookup` with the Ban ID in the body (`MODERATOR`, `PROXY`, uniform 404, `no-store`); the short-id route stays legacy hex only (409 behaviour unchanged), a Ban ID never goes in a URL
- [x] 1.5 Test that logs, events and metrics of issue, reveal and lookup never contain the Ban ID
- [x] 1.6 Regenerate the client spec (`client/specs/blackhole-api-0.0.N.yml`, next free number, bump `inputSpec`); update `.claude/rules`; verify `./gradlew :backend:test :client:compileJava`

## 2. Case conversation (PR 2, `feat(case)`)

- [x] 2.1 Failing tests first: payloads per type incl. unknown version, dedupe keys, visibility forced `PLAYER`, trust `PLAYER`, edit/delete as new events, current-text rule, `WAITING` to `IN_REVIEW`
- [x] 2.2 Sealed payloads `message.added|edited|deleted|redacted`, `ban_id.leak_marked`, `ban_id.reissued`, `transcript.exported`; `CaseDrafts`/`CaseTimeline` extended; the portal-visible type allow-list
- [x] 2.3 CTE writes: leak mark (case + sessions + event, idempotent), reissue (punishment + case + event); `CaseService` routes `POST /case/{ref}/messages`, `ban-id/leak`, `ban-id/reissue`
- [x] 2.4 Redaction: per-message chain, player-side erase, `message.redacted`; `CaseEventRepository` surface test still green
- [x] 2.5 Transcript export (`json|text`, `includeNotes`, `transcript.exported`, `no-store`, attachment)
- [x] 2.6 Domain events `case.message_*`, `case.ban_id_*` without content, captured-publisher tests
- [x] 2.7 Review request `playerMessage` (appeal decision) with `PiiGuard`
- [x] 2.8 Extend `PermissionMatrixIntegrationTest` / `RouteCoverageIntegrationTest`; regenerate client spec; update `.claude/rules` (case-timeline, events, security)

## 3. Appeal protocol (PR 3, `feat(case)`)

- [x] 3.1 Failing tests first: byte-identical regeneration (fixed `Clock`), author labels and the no-Ban-ID/Discord-id/owner-hash scan, edit/delete/attachment/redaction rendering, digest and count recomputable from the JSON, idempotent versions (same digest, concurrent runs), settle delay and `force` on the test clock, redaction purge with a failing regeneration, `TRANSCRIPT` rejected by the attach routes, not counted, not marked on lift or revoke, audit on open and download
- [x] 3.2 Migration `V17__appeal_protocol.sql` (next free version): `appeal_protocol` with the unique `(appeal_id, version)` and `(appeal_id, source_digest)` indexes, the `TRANSCRIPT` kind and the second file key on `evidence_items`; migration test
- [x] 3.3 `evidence/`: kind `TRANSCRIPT`, `GeneratedEvidenceGateway.store(...)` (file pair, no sniff, scan or guards, `blackhole.protocol.max-bytes`), attach routes reject the kind, excluded from `evidenceCount` and from lift/revoke marking, attachment serving headers; update `.claude/rules/evidence.md`
- [x] 3.4 `casetimeline/protocol/`: `AppealProtocolService` (model, text and JSON renderers, digest, completeness footer), `protocol.stored` event type, `case.protocol_stored` domain event, trigger on the appeal decision, `ProtocolReconciler` (missing protocols, late changes after `blackhole.protocol.settle-delay`, retries), config documented in `application.yml`
- [x] 3.5 Redaction hook: message redaction, player-side erase, participant erasure and staff anonymisation mark versions `PURGE_PENDING` in the same statement, regenerate and delete the purged files (reason `ERASURE`)
- [x] 3.6 Staff routes (`VIEWER`+): list versions, open the text, download text or JSON; matrix and route-coverage tests; update `.claude/rules` (case-timeline, evidence, events, security)

## 4. Public API and player session (PR 4, `feat(appeal)`)

- [x] 4.1 Failing tests first: uniform 401, session idle/absolute expiry on `TestClock`, sweeper, revocation on leak and reissue, `LIMITED` scope, own-punishment-only access, filing 409 reasons with `availableFrom`, one open appeal under concurrency, visibility filter in SQL
- [x] 4.2 Migration `V16__player_session.sql` (next free version); `publicapi/` package: `PlayerSessionService`, `PublicApi` + thin controllers (`@Version(ApiVersion.V1)`), sealed DTOs, the `PlayerSession` rule in `security/`
- [x] 4.3 Routes: session (login, logout, read), punishment facts allow-list, appeal file/read, conversation read (`seq` cursor), message post with `Idempotency-Key`, `PiiGuard`, length cap; request body logging off for `/public/**`
- [x] 4.4 Metric `blackhole.public.login{result}`; config `blackhole.public.*` documented in `application.yml`
- [x] 4.5 `GET /public/appeal/protocol?format=text|json`: the player-visible view rendered on demand with the portal allow-list (FULL session, decided appeal, no staff names, no `STAFF` entries, `no-store`, attachment); tests that a `STAFF` row and the stored protocol never reach a player session
- [x] 4.6 Update the route-coverage and permission-matrix tests (public rule both ways); regenerate the client spec; verify `./gradlew :backend:test`

## 5. Portal app (PR 5, `feat(portal)`)

- [x] 5.1 Failing tests first: BFF allow-list (unknown paths 404), cookie flags, CSRF + Origin, uniform login error mapping, no body logging, security headers, runtime config validation
- [x] 5.2 New module `portal/` (Gradle node driver, Nitro `node-server`, Vue 3 + Tailwind MD3 tokens), `settings.gradle.kts` include, `Dockerfile`, `/healthz`
- [x] 5.3 Pages: login, facts, appeal form, conversation (15 s visible-tab polling, composer, drafts in memory only), forfeited / frozen / expired pages, logout
- [x] 5.4 German and English strings with parity test; accessibility and keyboard component tests; plain-text rendering test
- [x] 5.5 CI: build and smoke-check the portal image beside the web image in `.github/workflows/build-pr.yml`

## 6. Panel conversation (PR 6, `feat(web)`)

- [x] 6.1 API types and client methods; fake-client tests
- [x] 6.2 Conversation section on the case page (thread, composer, banners), Ban ID mask + reveal, leak dialog, reissue dialog, transcript export; role gating tests
- [x] 6.3 Appeals queue "unanswered" marker; `playerMessage` field in the decision dialog
- [x] 6.4 de/en strings, `i18n.keys.test.ts` parity; live regions; verify `npm test` and the type check
- [ ] 6.5 Protocol section on the case page: versions list (current, superseded), open the text, download text or JSON (`VIEWER`+), notice while a decided appeal has no protocol; fake-client tests, nothing in browser storage

## 7. Velocity ban screen (PR 7, `feat(velocity)`)

- [x] 7.1 Failing tests first: screen with Ban ID, backend failure shows none (and never a hex id), nothing persisted
- [x] 7.2 Fetch via the generated client (`PROXY` role), in-memory cache, remove the local hex short id from the player-facing screen; verify `./gradlew :velocity:test`

## 8. Documentation (PR 8, `docs(appeal)`)

- [ ] 8.1 Docs for operators: portal deployment (host `appeal.onelitefeather.net`, env, container), session limits (30 minutes idle, 4 hours absolute), key rotation is `BanIdBackfill --reissue-all` in v1, required edge rate-limit rules, the key and its backup, no-body-logging requirement, backfill gauge
- [ ] 8.2 Docs for staff: Ban ID reveal, leak and reissue rules, conversation visibility, transcript export, the stored appeal protocol (when it is made, versions, what redaction does to it, the player copy), retention statement (pending legal confirmation)
- [ ] 8.3 Note in `.claude/PROGRESS.md` per PR as it lands

## 9. Verification and wrap-up

- [ ] 9.1 Manual check against the real stack (Docker PostgreSQL + RabbitMQ + portal): backfill, log in, file, message both ways, mark leaked (frozen), reissue, redact (the protocol is purged and regenerated), export, decide an appeal (protocol version 1, player copy without staff entries); record the result in PR 6
- [ ] 9.2 `openspec validate --all --strict`; archive with `docs(openspec): archive add-appeal-portal` after the PRs merge
- [ ] 9.3 Open the pull requests as small stacked PRs against `main` (each squash-merged, title is the Conventional Commit): `feat(appeal): add ban ids as the appeal credential`, `feat(case): add the case conversation, leak rule and transcript export`, `feat(case): store the appeal protocol as transcript evidence`, `feat(appeal): add the public player api with sessions`, `feat(portal): add the public appeal portal`, `feat(web): add the case conversation and ban id actions`, `feat(velocity): show the ban id on the ban screen`, `docs(appeal): document the appeal portal`
