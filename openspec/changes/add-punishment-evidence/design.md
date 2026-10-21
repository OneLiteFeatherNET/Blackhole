# Design

## Context

Punishments are identified by a string identifier (and, with V7, a derived short id such as
`#A1B2C3D4`); players are identified by a SHA-512 owner hash; the API has no auth and every
`source`/actor is client-supplied; domain events go to RabbitMQ through the non-blocking
`DomainEventPublisher`; Flyway owns the schema (latest applied V6, V7 in the open PR
`feat/punishment-short-id`).

An unrelated table already exists: `punishment_evidence` (V1) with `EvidenceType`
(`CHAT_MESSAGE`, `ANTICHEAT_FLAG`, `REPORT`, `MANUAL_NOTE`) holds hash-only records created by
the automatic chat-toxicity flow and referenced from reports and ELO history. It cannot carry
payloads or files, its enum is an ordinal `smallint`, and the `elo-scoring` spec forbids raw
text there. This change leaves it untouched and adds a new aggregate beside it (see Decision 1).

## Goals / Non-Goals

**Goals:** let systems and staff attach heterogeneous evidence to punishments, including files
Blackhole stores; make staff able to review it; keep GDPR retention/erasure/audit
operable; stay inside the ratified principles (no auth, no server-side fetch of caller URLs,
hashed identifiers, infra-owned rate limiting, append-only Flyway).

**Non-Goals:** authentication or verified actors; previewing/unfurling links; archive uploads;
video transcoding; evidence on reports; the admin UI and proxy capture (separate changes).

## Decisions

### 1. New `evidence_items` aggregate, legacy table untouched
Extending `punishment_evidence` would mean widening an ordinal enum, adding five-plus
columns, and reconciling the "no raw text" rule that governs the toxicity flow. A new table in
a new `evidence/` package keeps both lifecycles independent. The `elo-scoring` requirement is
reworded (MODIFIED) to say it governs the automatic flow only. A later cleanup may merge the
two; that is an open question, not part of this change.

### 2. Data model - `V8__evidence_items.sql`
(V7 is taken by `feat/punishment-short-id`; V8 assumes it merges first. If the order flips, the
number is adjusted at implementation. Append-only; no existing rows to migrate - new tables only.)

`evidence_items`:

| column | type | notes |
|---|---|---|
| `identifier` | uuid PK | |
| `punishment_identifier` | varchar(255) null | FK to `punishments`; null while pending |
| `owner` | varchar(255) not null | owner hash; indexed - erasure and pending lookup |
| `correlation_id` | varchar(64) null | pending claim key, indexed with `owner` |
| `kind` | smallint not null | ordinal enum per repo convention: ANTICHEAT, REPLAY, CHAT_LOG, LINK, FILE |
| `source_system` | varchar(64) not null | unverified |
| `external_ref` | varchar(2048) null | REPLAY / LINK URL |
| `payload` | jsonb null | ANTICHEAT / CHAT_LOG / optional descriptions; <= max-payload-bytes |
| `payload_schema_version` | integer not null default 1 | per-kind schema version |
| `captured_at` | bigint null | client time of capture |
| `created_at` | bigint not null | |
| `created_by` | uuid null | unverified actor |
| `file_key` | varchar(255) null | server-generated blob key |
| `file_name` | varchar(255) null | sanitised display name |
| `file_content_type` | varchar(100) null | sniffed |
| `file_size` | bigint null | |
| `file_sha256` | varchar(64) null | of the stored (re-encoded) bytes |
| `status` | smallint not null default 0 | VALID, REVOKED, LIFTED (ordinal enum); pending items are VALID with null `punishment_identifier` |
| `status_changed_at` | bigint null | when the item became REVOKED / LIFTED |
| `lifted_by_appeal` | uuid null | appeal that lifted the punishment (LIFTED only) |

`evidence_audit` (append-only, no FK so it survives deletion): `identifier` uuid PK,
`evidence_identifier` uuid, `punishment_identifier` varchar(255) null, `owner` varchar(255),
`action` smallint (ATTACHED, VIEWED, VIEWED_INACTIVE, DOWNLOADED, DELETED), `actor` uuid null, `reason`
varchar(32) null, `note` varchar(500) null (admin delete reason text), `at` bigint. No content columns, ever.

The FK `punishment_identifier -> punishments(identifier)` is `ON DELETE CASCADE`: evidence lives
exactly as long as the punishment record. Rows disappear with the punishment; the service
hook deletes the blobs at the same time and the blob reconcile sweep catches any left behind.

JSON validity and enum ranges are enforced in the service layer, not by `CHECK`, as
`.claude/rules/database.md` requires. A row has exactly one of {payload, external_ref,
file_key} as primary content; the service enforces this.

Many per punishment; ordering by `created_at`. Blob deletion is idempotent and runs before the
row is deleted; a reconcile sweep removes blobs without a row older than one day, so a crash
between the two steps self-heals.

### 3. Attaching: by id, short id, or correlation
- `POST /evidence` (JSON kinds) and `POST /evidence/file` (multipart) take a `target`:
  either `punishment` (full identifier **or** short id) or `owner` + `correlationId`.
  Exactly one form is valid, otherwise 400.
- Short ids can collide (4 bytes): 0 matches -> 404, 1 -> attach, >1 -> 409 with the candidate
  identifiers so the caller retries with the full id. Reuses the V7 short-id lookup.
- Pre-punishment: the punishment-creating endpoints (template apply / direct punish) accept an
  optional `evidenceCorrelationId`. The creation service, after persisting, claims
  `owner + correlationId` items in the same call (set `punishment_identifier`; a claim publishes no extra event, the item was already announced when attached). Evidence arriving
  *after* the ban uses the punishment identifier returned by the ban call. A late item with a
  correlation whose punishment already exists is not magically linked; callers use the id.
- Pending items expire after `pending-ttl` (default P1D, set from `created_at`).

*Alternative rejected:* a free-form tag on the punishment metadata. It cannot hold files before
the punishment exists and has no cleanup path.

### 4. Payload schemas and size
Validated by hand-written, versioned validators in the service (no reflection-heavy JSON Schema
dependency for four tiny shapes), `payload_schema_version = 1`. Undeclared properties are
rejected so a caller cannot smuggle identifiers through unknown fields.
- `ANTICHEAT`: `check` (string <= 64, required), `values` (flat map, <= 32 entries,
  scalar values), `violationLevel` (number), `server` (<= 64), `detail` (<= 500).
- `CHAT_LOG`: 1..`max-chat-lines` (50) lines `{timestamp, speaker, text <= 256}`; `speaker` is an
  owner hash, or a per-evidence alias (e.g. "Spieler A", max 32 chars, no UUID/IP shape) as sent
  by the proxy, or null - names are never stored, the UI resolves hashes via the existing lookup
  and shows aliases as-is.
- `REPLAY` / `LINK`: `external_ref` + optional `description` (<= 500).
Total payload limit 32 KiB, enforced on the raw request body before parsing (413).

### 5. Pseudonymisation of third-party data
Decision: **reject, do not rewrite.** Rewriting a UUID into its hash would silently guess that
the UUID is a player UUID and would keep the raw value in access logs of the caller anyway.
Callers hash first (same function the proxy and `GET /player/resolve` already use). Structured
fields: any string matching a canonical UUID or an IP literal gives HTTP 422 with the JSON
pointer. Exception: `CHAT_LOG.text` is free human text; players do type IPs there, so IPv4/IPv6
literals in it are redacted to `[ip]` rather than rejected (rejecting would drop the evidence).
UUID-looking strings in chat text are left alone (a typed UUID is not an identifier of a
player the system knows). Access logs of the backend must not log request bodies (already the
case; asserted by a test on the logging config is out of scope but noted in the README).

### 6. File storage: `BlobStore` port with two adapters (S3 default)
`BlobStore { put(key, InputStream, length, contentType); open(key, rangeStart, rangeEnd);
delete(key); listOlderThan(...) }`.
- `FilesystemBlobStore` (alternative for tiny single-replica setups): base dir `blackhole.evidence.files.local.path`; keys are
  server-generated (`yyyy/MM/<uuid>`); the resolved path is normalised and must stay under the
  base directory; files written to a temp name then atomically moved. Suitable for one backend
  replica or a shared volume.
- `S3BlobStore` (**default**, `blackhole.evidence.files.storage=s3`): S3-compatible,
  path-style endpoint override. Built on **Micronaut Object Storage (AWS module)**
  over AWS SDK for Java v2 - both Apache-2.0. Fall back to using SDK v2 `S3Client` directly if the
  Micronaut module's API gets in the way of ranged reads; the port isolates the choice.

Licence comparison for choosing what ships in `docker-compose.yml` and what the docs
recommend (the owner moved off Liquibase over FSL, so restrictive licences are a negative):

| Server | Licence | Status / fit |
|---|---|---|
| MinIO | AGPL-3.0 | Community edition went into maintenance mode (README 2025-12-03), official community images stopped Oct 2025, repo reported archived in 2026; source-only. **Rejected.** |
| Garage | AGPL-3.0 | Good, narrower S3 API, geo-distributed small clusters. AGPL -> **not recommended** for this owner (usable as a separate process, but we avoid it). |
| SeaweedFS | Apache-2.0 | Production-proven since 2015, broad S3 API, single `weed server -s3` container; heavier than Garage to operate at scale. **Recommended for compose / self-host.** |
| RustFS | Apache-2.0 | MinIO drop-in feel, but docs say not for production; revisit later. |
| Ceph RGW | LGPL-2.1 | Production grade, far too heavy for this project. Operators who already run Ceph can use it. |
| Cloud S3 (AWS, R2, B2, Hetzner...) | n/a | Operator's choice; works through the same adapter. |

Decision (owner): **S3-compatible object storage is the default; SeaweedFS (Apache-2.0) runs
in `docker-compose.yml` for development and is the documented self-hosted choice; any S3
service works in production. The filesystem backend remains available behind the same
`BlobStore` interface (`files.storage=local`) for tiny single-replica setups.** The S3 adapter is verified
against SeaweedFS in an integration test (Testcontainers), keeping unit tests fast and
independent. Licence facts above come from the sources listed under "Sources"; re-verify the
two project licence files before the README states them.

### 7. Upload pipeline (streaming, bounded, untrusting)
1. Micronaut `StreamingFileUpload` (`micronaut.server.multipart.*` and request-size limits set
   from `blackhole.evidence.files.*`) streams the part into a **bounded temp file** (size
   counter aborts at the limit -> 413). Spooling gives a known length for S3 `putObject`,
   lets us sniff, hash and scan, and never holds the body in heap. Temp dir under
   `java.io.tmpdir`, files removed in `finally`.
2. **Sniff** the first bytes against a small signature table (PNG, JPEG, MP4 `ftyp`, WebM/EBML);
   the client MIME type and extension are ignored, a mismatch with the allow-list gives 415.
   Hand-rolled over Apache Tika (Apache-2.0) to avoid a large dependency with a long CVE
   history for four formats; Tika stays the documented upgrade path if the allow-list grows.
3. **Images**: check dimensions via `ImageReader` header before decoding (pixel cap), then
   decode and re-encode with `ImageIO` (JPEG q 0.9, PNG). A freshly written image carries no
   EXIF/XMP/ICC text chunks, so GPS and camera data are dropped; the stored hash is of the
   re-encoded bytes. Animated GIF and WebP are not allowed (ImageIO lacks WebP; GIF re-encode
   would drop animation).
4. **Video**: stored as uploaded (MP4/WebM) after sniffing and the size cap. Container metadata
   is **not** stripped (needs ffmpeg; out of scope) - documented limitation; staff are told
   in the UI/README to prefer screen recordings of the game.
5. `EvidenceScanner` interface with a ClamAV `clamd` adapter shipped in the first release:
   `INSTREAM` over TCP to a `clamav` compose service (the daemon is GPL-2.0 but we only speak a
   socket protocol, no linking). Scanning is configured via `scan.enabled`; when enabled,
   positive = 422 and scanner down = 503 (fail closed). A no-op scanner remains for unit
   tests and deployments that explicitly disable scanning. ClamAV needs about 1 GB RAM
   (signature database loaded in memory), documented in compose and README; the first start
   downloads signatures and is slow. Tests use a stub clamd socket server (protocol-level, fast)
   plus a Testcontainers ClamAV integration test with the EICAR test string where CI allows.
6. `BlobStore.put`, then insert the row; on insert failure delete the blob.
No archives (zip/tar/gz) are in the allow-list, so zip-bomb handling is not needed; the pixel
cap covers image decompression bombs.

### 8. Download: backend-proxied, not presigned
`GET /evidence/{id}/content` streams from the `BlobStore`. Rationale: the trust model has no
auth, so a presigned URL is an unauthenticated bearer link that can be pasted into Discord and
would bypass auditing and retention; it would also force the object store onto a reachable
network. Proxying keeps the store private, lets us write `DOWNLOADED` audit entries and
revoke instantly on delete. Cost: backend bandwidth - acceptable for staff-only traffic; the
operator can cache at their reverse proxy if needed. Supports `Range` (ranged S3 GET /
`FileChannel` seek) so video scrubbing works. Headers: sniffed `Content-Type`, `nosniff`,
`Content-Security-Policy: sandbox`, `Cache-Control: private, no-store`, `inline` only for
allow-listed image/video else `attachment`.

### 9. Staff-only visibility under the no-auth model
No authentication is added. The content endpoints are exactly as open as every other endpoint;
confidentiality comes from the network boundary (the proxy plugin and admin UI are the only
intended callers). The *player-facing* guarantee is enforced by what the proxy is built to call
and display: only `evidenceCount` (punishment entry, appeal DTO, count endpoint). The DTOs
that player-facing flows use (`PunishEntryDTO`, `AppealDTO`) are tested to contain no field
beyond the count. Per-actor access control stays deferred with the identity gap.

### 10. Evidence lifetime, lifted evidence, erasure, GDPR
Owner decisions (network security has priority): **evidence lives exactly as long as its
punishment record exists in the history.** There is no time-based expiry, no
`BLACKHOLE_EVIDENCE_RETENTION_AFTER_END` and no retention sweep for time-based deletion.

*Deletion paths (the only ones):*
- (a) the punishment record is deleted: rows go via `ON DELETE CASCADE`, blobs are deleted by
  the service hook in the same operation (reason `CASCADE`).
- (b) an admin calls `DELETE /evidence/{id}` or `DELETE /evidence/owner/{owner}`. A `reason`
  is mandatory (400 without it), an audit entry with reason, optional note and the unverified
  actor is written per item (the entries contain no content and outlive the evidence), and
  `evidence.deleted` is published. This is the path for data-subject erasure requests
  (Art. 17); **the operator decides each request case by case** (for example refusing under
  Art. 17(3)(e) when evidence is needed for legal claims). Nothing erases automatically.
- Housekeeping only (`housekeeping.interval`, default PT1H): unclaimed correlation evidence
  after `pending-ttl` (P1D, reason `UNCLAIMED_EXPIRED`), blobs without a row older than one day,
  and optional audit-row purge if `audit-retention` is set (unset by default = keep). The
  sweep never touches evidence that belongs to a punishment.

*Lifted and revoked evidence - kept, marked, hidden by default:*
- A granted full-lift appeal sets the punishment's items to `LIFTED` (`status_changed_at`,
  `lifted_by_appeal`) instead of deleting them. Reductions and denials change nothing.
- A staff revoke never deletes. **Proposal:** it marks the items `REVOKED` (same mechanics,
  label "Strafe widerrufen" / "Punishment revoked"), because a revoked punishment's evidence
  also no longer supports a current punishment. This is a proposal; if the owner prefers,
  revoked evidence simply stays `VALID`.
- Marked items are labelled ("Strafe per Appeal aufgehoben"), are **not** counted by
  `evidenceCount`/the count endpoint, must not be presented as supporting a current
  punishment, and are excluded from lists unless `includeInactive=true`; each such list is
  audited (`VIEWED_INACTIVE`). The admin UI hides them behind the explicit toggle
  "aufgehobene Beweise anzeigen".
- **Honest limit:** there is no auth layer, so "admins only" cannot be enforced in the
  application. The protection is the UI default (hidden), the audit entry and the deployment
  boundary (only trusted callers reach the API). Anyone who can reach the API can pass
  `includeInactive=true`. Per-actor control stays deferred with the identity gap.

*GDPR rationale:*
- **Purpose: network security** - preventing repeat offences and ban evasion by keeping the
  basis of a sanction with the sanction.
- **Storage limitation (Art. 5(1)(e))** is justified by tying evidence to the punishment
  record's lifetime: the evidence is kept no longer than the record it substantiates, and is
  erased with it. The software therefore does not impose a second clock; the operator's
  punishment-history retention is the clock.
- **Operator duties (documented in the README, not decided by the software):** document the
  punishment-history retention period and its justification, and the lawful basis (typically
  Art. 6(1)(f) legitimate interest with a balancing test); name the evidence categories (chat,
  recordings, anticheat data, links) in the privacy notice and the records of processing
  (Art. 30); answer access requests (Art. 15) with `GET /evidence?owner=&includeInactive=true`
  and erasure/objection requests (Art. 17/21) through the admin delete path, deciding case by
  case.
- Evidence also mentions other players (chat). Speakers are hashes or per-evidence aliases only.
- Audit trail: see `evidence_audit`; the actor is the unverified client-supplied UUID.

*Storage growth and capacity planning:* with no time-based deletion, evidence grows with the
punishment history. Operators size the bucket/volume for the expected history (for example
videos up to 100 MiB x expected video evidence per month x retention of the punishment
history), monitor usage, and may lower `max-video-bytes` or remove `video/*` from
`allowed-types`. **S3 lifecycle expiry rules must not be used to delete evidence**; deletion goes
through the admin path so database, blob and audit stay consistent. The blob reconcile sweep only
removes blobs without a row.

### 11. API contract
Version via `X-API-VERSION` 1 (class-level `@Version(ApiVersion.V1)`), version-less paths.
Per the micronaut-* skills: one sealed `EvidenceDTO` marker interface with nested records
`CreateRequest`, `Response`, `PageResponse`, `CountResponse`, `Error` (implements the shared
`ErrorResponse`); `EvidenceService` answers expected failures with `Error`; thin
`EvidenceController` implements `EvidenceApi` (all `@Operation`/`@ApiResponse`).

| Method & path | Purpose |
|---|---|
| `POST /evidence` | attach JSON-kind evidence (target by punishment/short id or owner+correlation) |
| `POST /evidence/file` | multipart upload (`file`, `sourceSystem`, target fields, optional `description`) |
| `GET /punishment/{ref}/evidence?page&size&includeInactive` | list (Micronaut `Pageable`, default 20, max 100); `includeInactive=true` also returns `LIFTED`/`REVOKED` items and writes a `VIEWED_INACTIVE` audit entry |
| `GET /punishment/{ref}/evidence/count` | `{count}` of `VALID` items - the only player-visible view |
| `GET /evidence?owner=&page&size` | list by owner (access requests) |
| `GET /evidence/{id}` | metadata + payload |
| `GET /evidence/{id}/content` | file stream, `Range` aware |
| `DELETE /evidence/{id}?reason=&note=&source=` | admin delete; `reason` required (`ERASURE_REQUEST`, `WRONG_EVIDENCE`, `OTHER`), audited |
| `DELETE /evidence/owner/{owner}?reason=&note=&source=` | erasure of all evidence of an owner hash; same required reason and per-item audit |

Consistent with the existing API, the unverified actor is a client-supplied `source` UUID
(body field, or query parameter on GET/DELETE), stored as `created_by` / audit `actor`; no
header or token scheme is introduced. Rate limiting is left to infra (CLAUDE.md).

### 12. Events
`evidence.attached` and `evidence.deleted` through `DomainEventPublisher` (fire-and-forget
`Publisher<Void>`, see the fixed RabbitMQ publisher - no blocking call on the Netty event
loop). Payloads: `{evidenceId, punishmentId|null, owner, kind, sourceSystem}` plus `reason`
for deleted (`MANUAL`, `ERASURE`, `CASCADE`, `UNCLAIMED_EXPIRED`). No payload content, no file data. Added to `.claude/rules/events.md`. No
Redis/Dragonfly keys are written: evidence is not part of the proxy mirror; counts are read
from the database.

### 13. Config
`blackhole.evidence.*` in `application.yml` with `${ENV:default}` and a comment per tunable:
`max-payload-bytes`, `max-chat-lines`, `pending-ttl`, `housekeeping.interval` (PT1H),
`audit-retention` (unset = keep), `files.storage` (`s3` default | `local`),
`files.local.path`, `files.max-image-bytes`, `files.max-video-bytes`, `files.max-image-pixels`,
`files.allowed-types`, `files.s3.{endpoint,bucket,region,access-key,secret-key,path-style}`,
`scan.enabled`, `scan.clamd.{host,port}`.

## Risks / Trade-offs

- **Backend bandwidth/disk**: video up to 100 MiB through the backend. Mitigation: configurable
  caps, infra rate limiting, S3 backend for scale.
- **Video metadata not stripped**: documented; operators can disable video via `allowed-types`.
- **No auth means anyone on the network can read evidence**: accepted and consistent with the
  ratified model; the README states it prominently.
- **Unverified `sourceSystem`/`createdBy`**: audit entries are not tamper-proof attributions.
- **Raw-text chat storage** is a deliberate departure from the toxicity flow's hash-only rule,
  bounded by line caps, redaction, the punishment-record lifetime and staff-only access.
- **Unbounded growth**: with no time-based expiry, storage grows with the punishment history
  (see capacity planning in decision 10); S3 lifecycle rules must not be used to delete evidence.
- **Lifted-evidence hiding is advisory**: there is no auth layer, so the default hiding, the audit
  and the deployment boundary are the only controls.
- **Filesystem backend with several replicas** needs a shared volume; documented, S3 is the default.
- **ClamAV memory (~1 GB) and slow first start**; scanning fails closed, so an unhealthy
  scanner blocks uploads (not other evidence kinds).
- **Short-id ambiguity** pushes a retry to callers; acceptable, rare.

## Migration Plan

Append `V8__evidence_items.sql` (two new tables + indexes; nothing to migrate). Deploy backend,
then enable integrations. Rollback: stop callers; tables are additive and unused by older
versions. Orphaned blobs removed by the reconcile sweep or by deleting the bucket/dir.

## Open Questions

Whether evidence of revoked punishments should be marked `REVOKED` and hidden like `LIFTED`
(proposed here, decision 10); merging the legacy `punishment_evidence` table later.

## Sources

- MinIO status: [Vonng, "MinIO Is Dead, Long Live MinIO"](https://blog.vonng.com/en/db/minio-resurrect/index.md); [Glukhov, "MinIO CE in 2026"](https://glukhov.org/data-infrastructure/object-storage/minio-dead/); [ayedo, "MinIO in Maintenance Mode"](https://ayedo.de/en/posts/minio-im-maintenance-mode/) (secondary sources; dates provisional).
- Licences/maturity of SeaweedFS, Garage, RustFS: [Elest.io comparison](https://blog.elest.io/rustfs-vs-seaweedfs-vs-garage-which-minio-alternative-should-you-pick/); [Glukhov, self-hosted S3 alternatives 2026](https://glukhov.org/data-infrastructure/object-storage/self-hosted-s3-alternatives/); [OpenEdX forum, replacing MinIO with Garage](https://discuss.openedx.org/t/replacing-minio-in-tutor-minio-why-we-chose-garage-and-what-comes-next/18868).
- Micronaut Object Storage (reactive API, local and AWS modules): [docs.micronaut.io/5.1.x/object-storage](https://docs.micronaut.io/5.1.x/object-storage/); [guide: Object Storage with S3](https://guides.micronaut.io/micronaut-object-storage-aws-maven-java/).
- Not re-fetched in this proposal, to be confirmed at implementation: AWS SDK for Java v2 licence (Apache-2.0, github.com/aws/aws-sdk-java-v2), [OWASP File Upload Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html), GDPR Art. 5(1)(e), 6(1)(f), 15, 17, 30, 32 ([gdpr-info.eu](https://gdpr-info.eu/)), ClamAV clamd `INSTREAM` protocol.
