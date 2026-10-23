# Spec Delta

## Purpose
Adds the system-generated kind `TRANSCRIPT` so appeal protocols are stored with the case under the existing storage, access, audit and lifetime rules.

## MODIFIED Requirements

### Requirement: Evidence kinds and storage
The system SHALL store evidence items of kind `ANTICHEAT`, `REPLAY`, `CHAT_LOG`, `LINK`, `FILE` or `TRANSCRIPT`. Every item records its kind, a `sourceSystem` identifier (1-64 characters, client-supplied and unverified), an optional `capturedAt`, a creation timestamp, an optional client-supplied `createdBy` UUID, and exactly one of: a structured payload, an external reference, or a stored file; an item of kind `TRANSCRIPT` is the only exception and holds a stored pair of files (a text and a JSON rendering) written together. A punishment MAY have any number of items.

#### Scenario: Several items on one punishment
- **WHEN** an anticheat record, a replay link and a screenshot are attached to the same punishment
- **THEN** listing the punishment's evidence returns three items

#### Scenario: Transcript holds a file pair
- **WHEN** a protocol version is stored
- **THEN** one `TRANSCRIPT` item exists holding a text file and a JSON file

### Requirement: Players learn only the count
Punishment entries SHALL expose `evidenceCount`, and `GET /punishment/{ref}/evidence/count` SHALL return only the number of `VALID` items of kinds other than `TRANSCRIPT` (lifted and revoked evidence is not counted); neither MAY reveal kind, source, reference, payload or file data.

#### Scenario: Count only
- **WHEN** the count endpoint is called for a punishment with 3 items
- **THEN** the response contains `{"count": 3}` and nothing else

#### Scenario: Protocol not counted
- **WHEN** a punishment has two anticheat items and a stored protocol
- **THEN** the count is 2

## ADDED Requirements

### Requirement: Transcript evidence is system generated
Items of kind `TRANSCRIPT` SHALL be created only by the backend's protocol generator, with `sourceSystem` `blackhole` and no `createdBy`, and always belong to a punishment (never pending). The attach and upload routes MUST reject the kind with HTTP 400.

#### Scenario: Client tries to attach a transcript
- **WHEN** a client posts evidence of kind `TRANSCRIPT`
- **THEN** the response is HTTP 400 and nothing is stored

### Requirement: Transcript content is stored as generated
Because the backend generates the content from stored case events and it holds no uploaded bytes, it SHALL be stored directly as `text/plain` and `application/json` without the upload allow-list, malware scan, re-encoding or the UUID and IP guards, so that the stored protocol equals the record. Its size is bounded by `blackhole.protocol.max-bytes` (default 16 MiB); an oversized protocol fails generation with a metric and a log line, not a truncated file.

#### Scenario: Guards do not rewrite the record
- **WHEN** a protocol contains a chat line with an IP address
- **THEN** the stored protocol contains that line unchanged

#### Scenario: Too large
- **WHEN** the rendered protocol exceeds the size bound
- **THEN** no item is stored, a failure metric increases and the reconciler reports it

### Requirement: Transcript status and listing
A `TRANSCRIPT` item SHALL NOT be marked `LIFTED` or `REVOKED` when its punishment is lifted or revoked, SHALL always be returned by evidence listings without `includeInactive`, and lives as long as its punishment record; a manual admin delete requires a reason like any evidence.

#### Scenario: Punishment lifted
- **WHEN** an appeal lifts the punishment
- **THEN** the protocol item keeps status `VALID` and is still listed by default

#### Scenario: Revoked punishment
- **WHEN** staff revoke the punishment
- **THEN** the protocol is not marked and not hidden

#### Scenario: Evidence listing
- **WHEN** a staff tool lists the evidence of a punishment with a protocol
- **THEN** the item is returned with kind `TRANSCRIPT` and its version

### Requirement: Transcript access
A `TRANSCRIPT` item SHALL be served only to staff roles `VIEWER` and above through the content endpoints, as an attachment with `X-Content-Type-Options: nosniff`, `Content-Security-Policy: sandbox` and `Cache-Control: private, no-store`. Opening it MUST write a `VIEWED` and downloading it a `DOWNLOADED` audit entry.

#### Scenario: Download
- **WHEN** a moderator downloads the JSON
- **THEN** the response carries the required headers and a `DOWNLOADED` audit entry exists
