# punishment-evidence Specification

## Purpose
Lets integrated systems and staff attach evidence (anticheat records, replay links, chat excerpts, external links, uploaded screenshots and videos) to punishments so staff and appeal reviewers can see why a punishment was issued, under GDPR-conscious retention, erasure and audit rules.

## Requirements

### Requirement: Evidence kinds and storage
The system SHALL store evidence items of kind `ANTICHEAT`, `REPLAY`, `CHAT_LOG`, `LINK` or `FILE`. Every item records its kind, a `sourceSystem` identifier (1-64 characters, client-supplied and unverified), an optional `capturedAt`, a creation timestamp, an optional client-supplied `createdBy` UUID, and exactly one of: a structured payload, an external reference, or a stored file. A punishment MAY have any number of items.

#### Scenario: Several items on one punishment
- **WHEN** an anticheat record, a replay link and a screenshot are attached to the same punishment
- **THEN** listing the punishment's evidence returns three items

### Requirement: Attach to a punishment by identifier or short id
The system SHALL accept evidence for a punishment referenced by its full identifier or by its short id. A short id that matches more than one punishment MUST be rejected with HTTP 409 listing the candidate identifiers, and an unknown reference with HTTP 404.

#### Scenario: Attach by short id
- **WHEN** a client posts LINK evidence for the short id `#A1B2C3D4` that matches exactly one punishment
- **THEN** the item is attached to that punishment

#### Scenario: Ambiguous short id
- **WHEN** the short id matches two punishments
- **THEN** the response is HTTP 409 and no item is stored

### Requirement: Attach before the punishment exists
The system SHALL accept evidence addressed by an `owner` hash and a client-chosen `correlationId` before any punishment exists. When a punishment is created for that owner with the same `evidenceCorrelationId`, the system MUST attach all pending items with that correlation to the new punishment. Pending items not claimed within `blackhole.evidence.pending-ttl` (default P1D) MUST be deleted with reason `UNCLAIMED_EXPIRED`.

#### Scenario: Anticheat sends evidence then bans
- **WHEN** evidence is posted with owner O and correlation C, and later a punishment for O is created with `evidenceCorrelationId` C
- **THEN** the punishment lists the evidence

#### Scenario: Unclaimed evidence expires
- **WHEN** pending evidence is not claimed within the pending TTL
- **THEN** the housekeeping sweep deletes it and publishes `evidence.deleted` with reason `UNCLAIMED_EXPIRED`

### Requirement: Per-kind structured payloads
The system SHALL validate the payload of each JSON kind against a schema that rejects undeclared properties, and MUST reject payloads larger than `blackhole.evidence.max-payload-bytes` (default 32768) with HTTP 413. `ANTICHEAT` requires `check` and permits `values` (flat string/number/boolean map), `violationLevel`, `server`, `detail`. `CHAT_LOG` requires 1 to `max-chat-lines` (default 50) lines of `{timestamp, speaker, text}`. `REPLAY` and `LINK` carry a reference and an optional `description`.

#### Scenario: Undeclared property
- **WHEN** an ANTICHEAT payload contains an undeclared property
- **THEN** the response is HTTP 400 naming the property

#### Scenario: Oversized payload
- **WHEN** a payload exceeds the size limit
- **THEN** the response is HTTP 413 and nothing is stored

### Requirement: Evidence is pseudonymised
Owners in requests and payloads MUST be 128-character hex SHA-512 owner hashes. The system SHALL reject structured payload strings that look like a UUID or an IP address with HTTP 422 naming the JSON pointer, and SHALL replace IPv4/IPv6 addresses inside `CHAT_LOG` line text with `[ip]` before storing. Display names MUST NOT be stored by this capability.

#### Scenario: Raw UUID in payload
- **WHEN** an ANTICHEAT payload contains a canonical UUID string value
- **THEN** the response is HTTP 422 and nothing is stored

#### Scenario: IP in chat text
- **WHEN** a chat line reads `join 203.0.113.5 now`
- **THEN** the stored text is `join [ip] now`

### Requirement: References are stored, never fetched
For `REPLAY` and `LINK` the system SHALL accept only absolute `http` or `https` URLs of at most 2048 characters without userinfo, and MUST NOT issue any server-side request to a stored reference.

#### Scenario: Disallowed scheme
- **WHEN** a LINK uses `javascript:` or `file:`
- **THEN** the response is HTTP 400 and nothing is stored

### Requirement: File upload
The system SHALL accept a file upload as a streamed multipart request and store it through a `BlobStore` (S3-compatible object storage by default, local filesystem when configured). It MUST enforce `blackhole.evidence.files.max-image-bytes` (default 10 MiB) and `max-video-bytes` (default 100 MiB) without buffering the whole body in memory.

#### Scenario: Streaming limit
- **WHEN** a 90 MiB video is uploaded
- **THEN** it is stored without the request body being held in memory as a whole

### Requirement: Upload type detection and keys
The system MUST determine an uploaded file's type from its magic bytes, ignoring the client MIME type and file name, and MUST accept only the configured allow-list (default `image/png`, `image/jpeg`, `video/mp4`, `video/webm`). Stored keys MUST be server-generated; client file names are kept only as a sanitised display name.

#### Scenario: Spoofed MIME type
- **WHEN** an executable is uploaded with `Content-Type: image/png`
- **THEN** the response is HTTP 415 and nothing is stored

#### Scenario: Too large
- **WHEN** an image exceeds the image size limit
- **THEN** the response is HTTP 413 and no partial file remains in the store

#### Scenario: Traversal in file name
- **WHEN** the client file name is `../../etc/passwd`
- **THEN** the object key is server-generated and the display name contains no path separators

### Requirement: Image metadata is stripped
The system SHALL re-encode accepted PNG and JPEG images so EXIF and other embedded metadata are not stored, and MUST reject images above `max-image-pixels` (default 50,000,000) before decoding them.

#### Scenario: EXIF removed
- **WHEN** a JPEG with GPS EXIF data is uploaded
- **THEN** the stored file contains no EXIF segment

### Requirement: Malware scan of uploads
The system SHALL pass uploads through an `EvidenceScanner` (ClamAV `clamd` adapter) before storing. When scanning is enabled, a positive result MUST reject the upload with HTTP 422 and an unavailable scanner MUST reject it with HTTP 503 (fail closed); a deployment may explicitly disable scanning.

#### Scenario: Infected upload
- **WHEN** the enabled scanner flags a file (for example the EICAR test string)
- **THEN** the upload is rejected with HTTP 422 and nothing is stored

#### Scenario: Scanner down
- **WHEN** scanning is enabled and the scanner is unreachable
- **THEN** the upload is rejected with HTTP 503 and nothing is stored

### Requirement: Staff-only content, backend-proxied download
Evidence content and metadata SHALL be served only by the content endpoints, which are intended for staff tooling behind the network boundary. File content MUST be streamed through the backend (never via presigned URLs), MUST support HTTP `Range` requests, and MUST be sent with the stored sniffed content type, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: sandbox`, `Cache-Control: private, no-store`, and `Content-Disposition: inline` for allow-listed image/video types.

#### Scenario: Range request
- **WHEN** a client requests bytes 0-1023 of a stored video
- **THEN** the response is HTTP 206 with exactly those bytes

### Requirement: Players learn only the count
Punishment entries SHALL expose `evidenceCount`, and `GET /punishment/{ref}/evidence/count` SHALL return only the number of `VALID` items (lifted and revoked evidence is not counted); neither MAY reveal kind, source, reference, payload or file data.

#### Scenario: Count only
- **WHEN** the count endpoint is called for a punishment with 3 items
- **THEN** the response contains `{"count": 3}` and nothing else

### Requirement: Evidence lives as long as its punishment record
The system MUST NOT delete evidence because of elapsed time. Evidence SHALL be deleted only when its punishment record is deleted (cascade, including stored files) or explicitly by an admin delete, and MUST otherwise be kept regardless of expiry, revocation or lift of the punishment.

#### Scenario: Expired punishment keeps evidence
- **WHEN** a punishment expired years ago and remains in the history
- **THEN** its evidence and files are still present

#### Scenario: Revoke keeps evidence
- **WHEN** staff revoke an active punishment
- **THEN** its evidence is not deleted

#### Scenario: Punishment record deleted
- **WHEN** a punishment record is deleted
- **THEN** its evidence rows and files are deleted and `evidence.deleted` events with reason `CASCADE` are published

### Requirement: Admin delete requires a reason
Deleting one item or all evidence of an owner hash SHALL require a `reason` (`ERASURE_REQUEST`, `WRONG_EVIDENCE`, `OTHER`) and write an audit entry per deleted item containing the reason, optional note and client-supplied actor; a request without a reason MUST be rejected with HTTP 400 and delete nothing. The system MUST NOT erase evidence automatically for data-subject requests; the operator decides such requests case by case.

#### Scenario: Delete without reason
- **WHEN** an item is deleted without a reason
- **THEN** the response is HTTP 400 and the item still exists

#### Scenario: Erasure request handled
- **WHEN** an admin deletes all evidence of an owner hash with reason `ERASURE_REQUEST`
- **THEN** every item and file is deleted, an audit entry per item remains without content, and `evidence.deleted` events with reason `ERASURE` are published

### Requirement: Housekeeping sweep
A sweep MUST run every `blackhole.evidence.housekeeping.interval` (default PT1H) that deletes unclaimed pending evidence after the pending TTL and blobs without a database row older than one day, and MUST NOT delete evidence that belongs to a punishment.

#### Scenario: Orphan blob
- **WHEN** a blob exists in the store without a matching row for more than one day
- **THEN** the sweep deletes it

#### Scenario: Claimed evidence untouched
- **WHEN** the sweep runs and evidence belongs to a punishment, however old
- **THEN** it is not deleted

### Requirement: Lifted and revoked evidence is marked and hidden by default
When a punishment is lifted by a granted full-lift appeal, the system SHALL keep its evidence and mark it `LIFTED` with the lift time and appeal id. When a punishment is revoked by staff, the system SHALL mark its evidence `REVOKED`.

#### Scenario: Revoke marks evidence
- **WHEN** staff revoke a punishment
- **THEN** its evidence is kept with status `REVOKED`

### Requirement: Marked evidence access
Marked evidence MUST be excluded from list responses unless `includeInactive=true` is passed, MUST NOT be counted in `evidenceCount`, MUST carry its status in every response, and listing it MUST write a `VIEWED_INACTIVE` audit entry. The system does not restrict `includeInactive` to admins: every reader (`VIEWER` and up) may use it, and each use is audited.

#### Scenario: Default list hides lifted evidence
- **WHEN** a punishment lifted by appeal is listed without `includeInactive`
- **THEN** its lifted items are not returned and the count excludes them

#### Scenario: Explicit inclusion is audited
- **WHEN** the list is requested with `includeInactive=true`
- **THEN** lifted items are returned with status `LIFTED`, liftedAt and the appeal id, and a `VIEWED_INACTIVE` audit entry is written

### Requirement: Audit trail
The system SHALL append an audit entry (action `ATTACHED`, `VIEWED`, `VIEWED_INACTIVE`, `DOWNLOADED`, `DELETED`, evidence id, punishment id, owner hash, client-supplied actor UUID, timestamp) for each such action. Audit entries MUST NOT contain evidence content, MUST survive deletion of the evidence, and are kept unless the operator sets `blackhole.evidence.audit-retention`.

#### Scenario: Download audited
- **WHEN** a staff tool downloads a stored file
- **THEN** a `DOWNLOADED` audit entry with the supplied actor UUID is written

### Requirement: Evidence domain events
The system SHALL publish `evidence.attached` (evidence id, punishment id or null, owner hash, kind, source system) and `evidence.deleted` (the same plus reason `MANUAL`, `ERASURE`, `CASCADE`, `UNCLAIMED_EXPIRED`) without blocking the request thread and without evidence content.

#### Scenario: Attach publishes event
- **WHEN** evidence is attached
- **THEN** an `evidence.attached` event is published and a broker failure does not fail the request
