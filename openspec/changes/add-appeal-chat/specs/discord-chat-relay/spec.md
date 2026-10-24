# Spec Delta

## Purpose
Carries chat files, shares and Markdown between the portal, the panel and the Discord ticket channel with the same type, size and safety rules.

## ADDED Requirements

### Requirement: Files from the case to the channel
The outbox SHALL carry the attachments of a relayed message and shares as items, and the bot SHALL fetch the bytes with `GET /discord/tickets/{ticketId}/attachments/{attachmentId}` (`DISCORD` role only) and upload them to the ticket channel. The route MUST apply the player route's visibility rule scoped to the ticket's appeal and answer 404 for anything else. An image share MUST be posted as a labelled file, an excerpt as a code block of the server-rendered lines.

#### Scenario: Portal file reaches the channel
- **WHEN** a player sends a message with a PDF in the portal
- **THEN** the bot posts the text and uploads the PDF to the ticket channel and links the message

#### Scenario: Not visible
- **WHEN** the bot requests a file that is not part of a visible message or active share of the ticket's appeal
- **THEN** the answer is HTTP 404

#### Scenario: Other roles
- **WHEN** a staff token or a player session calls the ticket attachment route
- **THEN** the answer is HTTP 403 or 401

### Requirement: Revoke removes the Discord copy
A revoked share SHALL produce an outbox item that deletes the linked Discord message and posts a system line "Evidence share withdrawn". A redacted message MUST delete its Discord message as before.

#### Scenario: Revoke
- **WHEN** a share that was posted to the channel is revoked
- **THEN** the bot deletes that Discord message and posts the withdrawn line

### Requirement: Files from the channel to the case
For a message from the bound player or a verified staff participant, the bot SHALL download each Discord attachment only from `cdn.discordapp.com` or `media.discordapp.net` over HTTPS without redirects, capped at 10 MiB and 5 files, and post the bytes to `POST /discord/tickets/{ticketId}/attachments` with its Discord attachment id. The backend MUST apply the chat profile, idempotent per Discord attachment id. The CDN link MUST NOT be stored; other participants' files MUST stay metadata-only.

#### Scenario: Accepted
- **WHEN** the bound player posts a PNG in the ticket channel
- **THEN** the PNG is scanned and stored as player-supplied evidence and the ingested message references its `attachmentId`

#### Scenario: Wrong host
- **WHEN** an attachment URL is on another host or redirects
- **THEN** it is not downloaded and the file is recorded as rejected

#### Scenario: Too large
- **WHEN** a Discord attachment is larger than 10 MiB
- **THEN** it is not downloaded and is recorded as rejected `TOO_LARGE`

#### Scenario: Scanner down
- **WHEN** the backend answers 503 for an upload
- **THEN** the message is not ingested yet and is retried on the next pass

#### Scenario: External participant
- **WHEN** a participant who is not the bound player nor verified staff posts a file
- **THEN** only metadata is recorded

#### Scenario: Replay
- **WHEN** the same Discord attachment is posted to the backend twice
- **THEN** one evidence item exists

### Requirement: Markdown both ways
Outgoing messages SHALL use the Discord mapping of the chat-markdown capability and incoming messages SHALL be mapped to the supported subset before ingest, with identical results for the shared corpus. Discord ids MUST NOT enter the case through mentions.

#### Scenario: Staff replies with formatting
- **WHEN** a moderator writes `**ok** see [docs](https://example.org)` in the panel
- **THEN** the channel shows **ok** and `docs <https://example.org>` without an embed

#### Scenario: Discord underline
- **WHEN** the player writes `__word__` in the channel
- **THEN** the case source contains `word`

### Requirement: Evidence status of Discord files
Accepted files of the bound player and of verified staff SHALL be evidence of provenance `PLAYER` or `STAFF`; every other Discord attachment and sticker MUST stay metadata. The appeal protocol MUST list accepted files as marker lines.

#### Scenario: Count
- **WHEN** the player posts two accepted files in the channel
- **THEN** the case's player-supplied figure rises by two and the team evidence count is unchanged
