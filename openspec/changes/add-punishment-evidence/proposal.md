# Proposal

**Ships as:** `feat(evidence): attach evidence to punishments` (additive, no BREAKING change).

## Why

Staff and appeal reviewers decide on bans without seeing why a punishment was issued. Anticheat
systems know the check that flagged a player, replay systems hold a recording, the proxy has the
chat, staff have screenshots and Discord messages - none of it can be attached to a punishment
today. The only related code is the hash-only `punishment_evidence` table written by the
automatic chat-toxicity flow. Reviewers need the real material, third-party systems need a
simple way to hand it over, and the GDPR obligations (retention, erasure, access, audit) have to
be solved up front because evidence is personal data (chat text, video, other players' names).

## What Changes

- New `evidence/` backend feature: an `evidence_items` table and API to attach evidence of kind
  `ANTICHEAT`, `REPLAY`, `CHAT_LOG`, `LINK` (structured JSON or a reference) or `FILE`
  (screenshot/video uploaded to and stored by Blackhole) to a punishment. A punishment can have
  many items.
- Attach by full punishment identifier or by short id, or **before the punishment exists** via
  `(owner, correlationId)`; the punishment creation call carries the same correlation id and
  claims the pending items. Unclaimed items expire.
- Third-party systems pass an unverified `sourceSystem` identifier; no API keys, no auth
  (network boundary, as everywhere else).
- Per-kind payload schemas with a size limit; owner identifiers must be hashes, raw UUIDs/IPs
  in structured payloads are rejected (chat text has IP addresses redacted).
- File storage behind a `BlobStore` port: S3-compatible object storage is the **default**
  (Apache-2.0 client; SeaweedFS in docker-compose for dev), a local filesystem backend stays
  available as an alternative. Streaming multipart upload, size/type limits from config,
  magic-byte content sniffing, image re-encoding to strip EXIF, and **ClamAV antivirus
  scanning in the first release** (compose service, fail-closed when configured). Staff download is **backend-proxied** (range requests for video), never
  presigned.
- Retention: evidence is kept while the punishment is active or an appeal is open, deleted
  `blackhole.evidence.retention.after-end` (default P90D, uniform for all kinds, env var
  `BLACKHOLE_EVIDENCE_RETENTION_AFTER_END`) after it ends, immediately when an appeal fully
  lifts it (flagged for owner confirmation, see design), never early on a staff revoke, and after P1D when never claimed by a punishment. Manual delete and
  owner-scoped erasure endpoints. Append-only audit log (attached/viewed/downloaded/deleted)
  without content.
- Domain events `evidence.attached` and `evidence.deleted` (ids, hashed owner, kind - no content).
- Staff-only visibility: content endpoints are for staff tools only; players learn solely the
  **count** (`evidenceCount` on the punishment entry and on the appeal DTO, plus a count
  endpoint for the proxy).
- Docker: SeaweedFS (Apache-2.0, S3 API) and ClamAV services in `docker-compose.yml`.
- OpenAPI client spec bump.

Out of scope: admin UI (`add-evidence-admin-ui`), proxy chat capture and punishment-screen
count (`capture-proxy-chat-evidence`), any server-side fetching or previewing of caller-supplied
URLs (links are stored, never requested - no SSRF surface), archive uploads, video
re-encoding/metadata stripping, evidence on reports, authentication or verified actor identity,
legal-basis determination (operator concern, see design).

## Capabilities

### New Capabilities

- `punishment-evidence`: evidence items, pending correlation, file storage, retention, erasure,
  audit and events.

### Modified Capabilities

- `elo-scoring`: the "chat evidence stores only a hash" rule is scoped to the automatic
  toxicity flow so it no longer contradicts staff/proxy-submitted `CHAT_LOG` evidence.
- `appeal-workflow`: appeal responses carry `evidenceCount`; a full-lift grant erases the
  punishment's evidence; an open appeal holds retention.

## Impact

- Backend: new `evidence/` package, Flyway `V8__evidence_items.sql`, `application.yml`
  `blackhole.evidence.*`, hooks in the revoke / appeal-resolution / punishment-creation services,
  dependencies (`micronaut-object-storage-aws`, AWS SDK v2 `s3`), `client/specs` bump.
- `docker/docker-compose.yml` (SeaweedFS and ClamAV services; ClamAV needs about 1 GB RAM),
  README (storage options, retention, operator GDPR notes).
- Depends on V7 (`feat/punishment-short-id`, open PR) being merged first: short-id lookup and
  migration numbering. The legacy `punishment_evidence` table and `EvidenceType` stay untouched.
