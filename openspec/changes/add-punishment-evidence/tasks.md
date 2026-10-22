# Tasks

Tests follow F.I.R.S.T.: unit tests use fakes (in-memory `BlobStore`, fixed `Clock`, `@TempDir`),
no sleeps, no shared static state; integration tests (PostgreSQL, S3 container) are separate.

## 1. Schema and core model

- [x] 1.1 Append `V8__evidence_items.sql` (`evidence_items`, `evidence_audit`, `status` columns, FK `ON DELETE CASCADE`, indexes on owner, (owner, correlation_id), punishment_identifier, status); verify with the `migration-checker` agent and a migration integration test on a fresh PostgreSQL (both tables exist, deleting a punishment row deletes its evidence rows)
- [x] 1.2 Add `evidence/` package: `EvidenceKind` enum, entity, repository (paged by punishment/owner), `EvidenceAuditEntity`; verify a repository integration test for insert/list/paging and `hbm2ddl=validate` startup
- [x] 1.3 Add `blackhole.evidence.*` to `application.yml` with comments and a `@ConfigurationProperties` bean; verify a unit test that defaults bind and invalid durations fail fast

## 2. Validation and pseudonymisation

- [x] 2.1 Per-kind payload validators (ANTICHEAT, CHAT_LOG, REPLAY/LINK) with undeclared-property rejection and size limit; verify one unit test per rule (undeclared property, oversize -> 413, too many chat lines)
- [x] 2.2 URL validator (http/https only, <= 2048, no userinfo) with no network access; verify unit tests for `javascript:`, `file:`, userinfo, relative URL, and that no HTTP client exists in the class
- [x] 2.3 PII guard: reject UUID/IP-looking strings in structured payloads (422 with JSON pointer), redact IPs in chat text, require 128-hex owner hashes; verify unit tests for each case including IPv6

## 3. Blob storage and uploads

- [x] 3.1 `BlobStore` port plus `FilesystemBlobStore` (alternative backend) (generated keys, normalised-path guard, atomic move, ranged read, delete, list-older-than); verify `@TempDir` unit tests incl. traversal attempt and range reads
- [x] 3.2 `S3BlobStore` (default) over Micronaut Object Storage AWS (endpoint/path-style/credentials config); verify an integration test against a SeaweedFS Testcontainer (put, ranged get, delete)
- [x] 3.3 Upload pipeline: bounded spooling, magic-byte sniffing (PNG/JPEG/MP4/WebM), allow-list, size caps per type, pixel cap, image re-encode; verify unit tests with fixtures: spoofed MIME -> 415, oversize -> 413 with no blob left, EXIF-bearing JPEG stored without EXIF, pixel bomb rejected before decode
- [x] 3.4 `EvidenceScanner` interface (no-op only for tests/explicit opt-out, fail-closed contract when enabled); verify unit tests with a fake scanner (positive -> 422, unavailable -> 503)
- [x] 3.5 ClamAV `clamd` INSTREAM adapter (first release); verify unit tests against a stub clamd socket server (clean, infected, connection refused) and a Testcontainers ClamAV integration test with the EICAR string where CI allows

## 4. Service and API

- [x] 4.1 `EvidenceDTO` sealed interface (CreateRequest, Response, PageResponse, CountResponse, Error) and `EvidenceService` (attach by id/short id/correlation, list, get, count, admin delete and erase-by-owner with mandatory reason, `includeInactive`) returning `Error` for expected failures; verify service unit tests: short-id 0/1/many matches, exactly-one target rule, many items per punishment, missing reason -> 400, lifted items hidden by default and not counted
- [x] 4.2 Claim pending evidence in punishment creation (`evidenceCorrelationId` on the apply/punish requests); verify an integration test: evidence first, ban second -> listed; wrong owner or id -> not claimed
- [x] 4.3 `EvidenceApi` (OpenAPI annotations) + thin `EvidenceController` (`@Version(ApiVersion.V1)`), multipart via `StreamingFileUpload`, `Range` support and download headers; verify controller integration tests incl. 206 response and header assertions, and review by the `backend-reviewer` agent
- [x] 4.4 Add `evidenceCount` to `PunishEntryDTO` and `AppealDTO`; verify tests that those DTOs expose no other evidence field
- [x] 4.5 Audit writes (ATTACHED/VIEWED/VIEWED_INACTIVE/DOWNLOADED/DELETED, deletes with reason and note) with the supplied `source`; verify unit tests that entries contain no content and survive evidence deletion
- [x] 4.6 Domain events `evidence.attached` / `evidence.deleted` via `DomainEventPublisher`; update `.claude/rules/events.md`; verify unit tests with a capturing publisher that payloads carry no content and a publisher failure does not fail the request

## 5. Lifetime and lifecycle

- [ ] 5.1 Lifecycle hooks: granted full-lift appeal marks evidence `LIFTED` (liftedAt, appeal id), staff revoke marks `REVOKED` (evidence never deleted), denial and reduction change nothing; verify unit tests with a fixed `Clock` for each transition
- [ ] 5.2 Cascade on punishment-record deletion: service hook deletes blobs and rows (reason `CASCADE`); verify a unit test with a fake store and an integration test that deleting the punishment removes rows and blobs
- [ ] 5.3 `EvidenceHousekeepingSweeper` (scheduled): unclaimed pending evidence after `pending-ttl`, blobs without a row older than 1 day, optional audit purge when configured; verify unit tests driving the sweep method with a fake store and fixed `Clock` (claimed evidence of any age is never deleted, orphan older than 1 day removed, younger kept)

## 6. Client, deployment, docs

- [ ] 6.1 Export the OpenAPI spec into `client/specs` with the next version number and bump the pin in `client/build.gradle.kts`; verify `./gradlew :client:build`
- [ ] 6.2 Add SeaweedFS (S3, with a bucket-init step) and ClamAV services to `docker/docker-compose.yml`, documenting ClamAV's ~1 GB RAM and slow first start; verify `docker compose config` and a manual upload round trip incl. an EICAR rejection
- [ ] 6.3 README section: storage options with the licence table, evidence lifetime (tied to the punishment record), "no auth: anyone on the network can read evidence, including lifted evidence via `includeInactive`", video metadata limitation, capacity planning and the warning not to use S3 lifecycle deletion, operator GDPR notes (punishment-history retention and lawful basis to document, privacy notice, access and erasure via the admin delete path decided case by case); add `.claude/rules/evidence.md`; verify documented env vars match `application.yml`
- [ ] 6.4 End-to-end check against local PostgreSQL + RabbitMQ + Dragonfly with SeaweedFS (and once with filesystem) storage and ClamAV: anticheat-style evidence then ban, screenshot upload and ranged download, appeal full lift marks the evidence `LIFTED` and hides it by default; record results in the PR description

## 7. Pull request

- [ ] 7.1 Open the pull request titled `feat(evidence): attach evidence to punishments` against `main`; verify the PR lint check passes and `openspec validate --all --strict` is green
