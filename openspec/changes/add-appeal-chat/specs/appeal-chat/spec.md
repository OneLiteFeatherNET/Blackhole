# Spec Delta

## Purpose
Turns the appeal conversation into a chat: files in both directions, quarantine until a clean scan, safe delivery, provenance, and the chat interface of the portal.

## ADDED Requirements

### Requirement: Allowed files and limits
The system SHALL accept chat files of the types PNG, JPEG, WebP, PDF and plain text (UTF-8), each at most 10 MiB, at most 5 per message. The type MUST be decided from the content, never from the client's type or name. A message MAY have no text when it has at least one file. A quota of 50 files per appeal and 10 staged files at once MUST answer HTTP 409 `QUOTA`. These limits are configuration (`blackhole.chat.attachments.*`). The system MUST NOT add an application-level rate limit.

#### Scenario: Accepted types
- **WHEN** a player uploads a valid PNG, JPEG, WebP, PDF and TXT file
- **THEN** each is staged and answered with an `attachmentId`, name, type and size

#### Scenario: Wrong type
- **WHEN** a PNG is uploaded under the name `a.pdf`, or an MP4, a ZIP or an SVG is uploaded
- **THEN** the PNG is accepted as `image/png` and the others answer HTTP 415

#### Scenario: Too large
- **WHEN** a file is 10 MiB plus one byte
- **THEN** the answer is HTTP 413 and nothing is stored

#### Scenario: Sixth file
- **WHEN** a message names six `attachmentIds`
- **THEN** the answer is HTTP 400 and nothing is claimed

### Requirement: Scan before anything is stored
Every chat file SHALL be scanned before it is written to the store. A flagged file MUST answer HTTP 422 `FILE_FLAGGED` without the signature name, and an unavailable scanner MUST answer HTTP 503; in both cases nothing is stored. Magic-byte sniffing, size and pixel caps MUST run after the scan on the same bytes.

#### Scenario: Infected file
- **WHEN** the scanner flags an upload
- **THEN** the answer is HTTP 422 `FILE_FLAGGED`, no blob and no evidence row exists

#### Scenario: Scanner down
- **WHEN** the scanner cannot be reached
- **THEN** the answer is HTTP 503 and nothing is stored

### Requirement: Type-specific handling
PNG and JPEG SHALL be re-encoded so EXIF, XMP and trailing data are dropped. WebP SHALL be validated as a RIFF container of exact length, stripped of EXIF and XMP, checked against the pixel cap and refused when animated. A PDF MUST start with `%PDF-` and be refused as `FILE_ACTIVE_CONTENT` when it names active content or encryption. A text file MUST be strictly valid UTF-8 and not start like a markup document. A file name shaped like a UUID, IP or Ban ID MUST become `attachment.<ext>`.

#### Scenario: EXIF removed
- **WHEN** a JPEG or a WebP with EXIF GPS data is uploaded
- **THEN** the stored file contains no EXIF or XMP

#### Scenario: WebP with trailing bytes
- **WHEN** a WebP has bytes after the RIFF length
- **THEN** the answer is HTTP 422 and nothing is stored

#### Scenario: PDF with script
- **WHEN** a PDF contains `/JavaScript`
- **THEN** the answer is HTTP 422 `FILE_ACTIVE_CONTENT`

#### Scenario: Text that is a document
- **WHEN** a text file starts with `<svg`
- **THEN** the answer is HTTP 415

### Requirement: Staged, then bound to a message
An upload SHALL create a staged file that is in no listing, count, conversation, share or download until a message binds it. Binding MUST happen in the same statement that records the message, MUST accept only staged files of this appeal that no message used, and MUST be idempotent with the `Idempotency-Key`. A staged file not bound within `pending-ttl` MUST be deleted. A player MAY delete a staged file of their own appeal at once.

#### Scenario: Visible only after sending
- **WHEN** a player has uploaded a file but not sent the message
- **THEN** staff see nothing of it and no read route returns it

#### Scenario: Foreign file id
- **WHEN** a message names an `attachmentId` staged for another appeal
- **THEN** the answer is HTTP 400 and nothing is stored or claimed

#### Scenario: Repeated post
- **WHEN** the same message is posted twice with the same `Idempotency-Key`
- **THEN** one message exists and the second answer has `repeated: true`

#### Scenario: Expired staging
- **WHEN** a staged file is older than `pending-ttl` at the next sweep
- **THEN** its row and blob are deleted

### Requirement: Public file routes
`POST /public/attachments` (one file per request), `GET /public/attachments/{id}` and `DELETE /public/attachments/{id}` SHALL need a `FULL` player session, and the upload an open appeal. The id MUST be checked against the session's punishment: another punishment's id and an unknown id both answer HTTP 404. A file is downloadable only through a visible, not removed message of the session's thread or an active share. Answers are `no-store`; no Ban ID appears in a URL, header, event or log.

#### Scenario: Other player's file
- **WHEN** a session requests the id of a file of another punishment
- **THEN** the answer is HTTP 404

#### Scenario: Removed message
- **WHEN** the message that carried the file was deleted by staff
- **THEN** the download answers HTTP 404

#### Scenario: Leaked scope
- **WHEN** a session has scope `LIMITED`
- **THEN** every file route answers HTTP 403

#### Scenario: No appeal
- **WHEN** a session without an open appeal uploads
- **THEN** the answer is HTTP 409 `NO_APPEAL`

### Requirement: Safe delivery
Every served chat file SHALL carry the detected `Content-Type`, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: sandbox` and `Cache-Control: private, no-store`. Only PNG, JPEG and WebP MAY be served `inline`; PDF and text files MUST be served as `Content-Disposition: attachment` with a sanitised name, in the portal, the panel and the BFF. The BFF MUST stream the body and forward these headers unchanged.

#### Scenario: PDF is a download
- **WHEN** a PDF is requested through the portal
- **THEN** the response has `Content-Disposition: attachment`, `nosniff`, `Content-Security-Policy: sandbox` and `no-store`

#### Scenario: Image inline
- **WHEN** a PNG is requested for display in a bubble
- **THEN** it is served `inline` with the same safety headers

### Requirement: Provenance and evidence
A chat file SHALL be a case evidence item of kind `FILE`. A player's file MUST have provenance `PLAYER`, a staff member's `STAFF`. Player-supplied files MUST count toward the evidence of the case and MUST be labelled "player-supplied, unverified" wherever evidence is shown. Events and payloads MUST NOT carry file bytes, storage keys or hashes.

#### Scenario: Counted and labelled
- **WHEN** a player sends a message with two files
- **THEN** the case evidence count rises by two, the count answer reports `playerSupplied: 2`, and the panel labels both

#### Scenario: Event content
- **WHEN** the `case.message_added` event for that message is published
- **THEN** it carries ids, kinds and seq only

### Requirement: Staff files and warnings
Staff with `MODERATOR` SHALL be able to upload files in the chat under the same rules. A staff file becomes visible to the player when its message is posted. The panel MUST say so before sending. The staff upload route MUST deny `PROXY`, `VIEWER` and `DISCORD`.

#### Scenario: Moderator uploads
- **WHEN** a moderator uploads a PNG and posts the message
- **THEN** the player sees the image and the evidence item has provenance `STAFF`

#### Scenario: Viewer uploads
- **WHEN** a viewer calls the staff upload route
- **THEN** the answer is HTTP 403

### Requirement: Erasure of chat files
A redaction of a message SHALL delete the message's files and blobs with reason `ERASURE`. The player-side erase SHALL delete every `PLAYER` provenance chat file of the case. The cascade with the punishment MUST delete all of them as before.

#### Scenario: Redact
- **WHEN** an administrator redacts a message with two files
- **THEN** the message shows as redacted, both evidence rows and blobs are deleted and the downloads answer HTTP 404

### Requirement: Chat interface
The portal SHALL present the conversation as a chat: own messages on one side, the team's on the other, grouped, with timestamps, day separators, system markers, thumbnails with a lightbox, file cards for PDF and text, and a composer with attach button, drag and drop, paste, per-file state and a counter. Send MUST stay disabled until every file is ready. The team MUST be shown as "Moderation team", never by name. Drafts stay in memory only; strings are English only.

#### Scenario: Attach by paste
- **WHEN** a player pastes an image into the composer
- **THEN** a chip appears, goes through uploading and scanning to ready, and Send becomes enabled

#### Scenario: Rejected file
- **WHEN** the server rejects a file
- **THEN** its chip shows the fixed reason text and Send ignores that chip

#### Scenario: Team name hidden
- **WHEN** a verified staff member replies
- **THEN** the portal shows "Moderation team" and no name

### Requirement: Seen by the player
The system SHALL keep `player_seen_seq` per case, moved to the highest returned seq by the player's conversation read in a single statement, with no timestamp. Staff MUST see "Seen" under the last staff message that is at or below that seq. The portal MUST fetch only while the tab is visible.

#### Scenario: Seen
- **WHEN** the player's conversation read returns up to seq 40
- **THEN** staff messages at seq 40 or below show "Seen"

#### Scenario: Never decreases
- **WHEN** a stale read returns up to seq 30 afterwards
- **THEN** `player_seen_seq` stays 40
