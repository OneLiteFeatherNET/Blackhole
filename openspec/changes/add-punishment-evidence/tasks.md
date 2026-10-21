# Tasks

Tests follow F.I.R.S.T.: unit tests use fakes (in-memory `BlobStore`, fixed `Clock`, `@TempDir`),
no sleeps, no shared static state; integration tests (PostgreSQL, S3 container) are separate.

## 1. Schema and core model

- [ ] 1.1 Append `V8__evidence_items.sql` (`evidence_items`, `evidence_audit`, indexes on owner, (owner, correlation_id), punishment_identifier, retain_until); verify with the `migration-checker` agent and a migration integration test on a fresh PostgreSQL (both tables exist, rerun is a no-op)
- [ ] 1.2 Add `evidence/` package: `EvidenceKind` enum, entity, repository (paged by punishment/owner), `EvidenceAuditEntity`; verify a repository integration test for insert/list/paging and `hbm2ddl=validate` startup
- [ ] 1.3 Add `blackhole.evidence.*` to `application.yml` with comments and a `@ConfigurationProperties` bean; verify a unit test that defaults bind and invalid durations fail fast

## 2. Validation and pseudonymisation

- [ ] 2.1 Per-kind payload validators (ANTICHEAT, CHAT_LOG, REPLAY/LINK) with undeclared-property rejection and size limit; verify one unit test per rule (undeclared property, oversize -> 413, too many chat lines)
- [ ] 2.2 URL validator (http/https only, <= 2048, no userinfo) with no network access; verify unit tests for `javascript:`, `file:`, userinfo, relative URL, and that no HTTP client exists in the class
- [ ] 2.3 PII guard: reject UUID/IP-looking strings in structured payloads (422 with JSON pointer), redact IPs in chat text, require 128-hex owner hashes; verify unit tests for each case including IPv6

## 3. Blob storage and uploads

- [ ] 3.1 `BlobStore` port plus `FilesystemBlobStore` (generated keys, normalised-path guard, atomic move, ranged read, delete, list-older-than); verify `@TempDir` unit tests incl. traversal attempt and range reads
- [ ] 3.2 `S3BlobStore` over Micronaut Object Storage AWS (endpoint/path-style/credentials config); verify an integration test against a SeaweedFS Testcontainer (put, ranged get, delete)
- [ ] 3.3 Upload pipeline: bounded spooling, magic-byte sniffing (PNG/JPEG/MP4/WebM), allow-list, size caps per type, pixel cap, image re-encode; verify unit tests with fixtures: spoofed MIME -> 415, oversize -> 413 with no blob left, EXIF-bearing JPEG stored without EXIF, pixel bomb rejected before decode
- [ ] 3.4 `EvidenceScanner` hook (no-op default, fail-closed contract); verify unit tests with a fake scanner (positive -> 422, unavailable -> 503)
- [ ] 3.5 (optional, may move to a follow-up) ClamAV `clamd` INSTREAM adapter; verify an integration test against a ClamAV container using the EICAR string

## 4. Service and API

- [ ] 4.1 `EvidenceDTO` sealed interface (CreateRequest, Response, PageResponse, CountResponse, Error) and `EvidenceService` (attach by id/short id/correlation, list, get, count, delete, erase-by-owner) returning `Error` for expected failures; verify service unit tests: short-id 0/1/many matches, exactly-one target rule, many items per punishment
- [ ] 4.2 Claim pending evidence in punishment creation (`evidenceCorrelationId` on the apply/punish requests); verify an integration test: evidence first, ban second -> listed; wrong owner or id -> not claimed
- [ ] 4.3 `EvidenceApi` (OpenAPI annotations) + thin `EvidenceController` (`@Version(ApiVersion.V1)`), multipart via `StreamingFileUpload`, `Range` support and download headers; verify controller integration tests incl. 206 response and header assertions, and review by the `backend-reviewer` agent
- [ ] 4.4 Add `evidenceCount` to `PunishEntryDTO` and `AppealDTO`; verify tests that those DTOs expose no other evidence field
- [ ] 4.5 Audit writes (ATTACHED/VIEWED/DOWNLOADED/DELETED) with the supplied `source`; verify unit tests that entries contain no content and survive evidence deletion
- [ ] 4.6 Domain events `evidence.attached` / `evidence.deleted` via `DomainEventPublisher`; update `.claude/rules/events.md`; verify unit tests with a capturing publisher that payloads carry no content and a publisher failure does not fail the request

## 5. Retention and lifecycle

- [ ] 5.1 Lifecycle hooks: revoke/expiry set `retain_until`, full-lift appeal erases (`APPEAL_LIFTED`), open appeal holds; verify unit tests with a fixed `Clock` for each transition, incl. denial and reduction keep evidence
- [ ] 5.2 `EvidenceRetentionSweeper` (scheduled, interval from config): delete due items, expired pending items, old audit rows, and orphan blobs; verify unit tests driving the sweep method directly with a fake store and fixed `Clock` (missed hook is corrected, orphan older than 1 day removed, younger kept)

## 6. Client, deployment, docs

- [ ] 6.1 Export the OpenAPI spec into `client/specs` with the next version number and bump the pin in `client/build.gradle.kts`; verify `./gradlew :client:build`
- [ ] 6.2 Add an optional SeaweedFS service (compose profile `object-storage`) to `docker/docker-compose.yml`; verify `docker compose --profile object-storage config` and a manual upload round trip
- [ ] 6.3 README section: storage options with the licence table, retention defaults, "no auth: anyone on the network can read evidence", video metadata limitation, operator GDPR notes (lawful basis, privacy notice, access/erasure endpoints); add `.claude/rules/evidence.md`; verify documented env vars match `application.yml`
- [ ] 6.4 End-to-end check against local PostgreSQL + RabbitMQ + Dragonfly with filesystem and SeaweedFS storage: anticheat-style evidence then ban, screenshot upload and ranged download, appeal full lift erases; record results in the PR description

## 7. Pull request

- [ ] 7.1 Open the pull request titled `feat(evidence): attach evidence to punishments` against `main`; verify the PR lint check passes and `openspec validate --all --strict` is green
