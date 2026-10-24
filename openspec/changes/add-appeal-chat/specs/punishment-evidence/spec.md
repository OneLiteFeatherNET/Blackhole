# Spec Delta

## Purpose
Adds provenance and a chat upload profile to evidence, and counts player-supplied files.

## ADDED Requirements

### Requirement: Evidence provenance
Every evidence item SHALL have a provenance, `STAFF` or `PLAYER`, stored as an append-only ordinal with default `STAFF`. Existing items MUST become `STAFF`. Evidence responses MUST include the provenance and the count responses MUST include the number of player-supplied items. The count of valid evidence MUST include player-supplied items.

#### Scenario: Migration
- **WHEN** the migration runs on a database with evidence
- **THEN** every existing item has provenance `STAFF`

#### Scenario: Count breakdown
- **WHEN** a case has three staff items and two player-supplied items
- **THEN** the valid count is 5 and `playerSupplied` is 2

### Requirement: Upload profiles
The upload pipeline SHALL have an `EVIDENCE` profile with today's behaviour and a `CHAT` profile with the chat types and limits. A profile MUST NOT change the other's allowed types, and both MUST scan first and fail closed.

#### Scenario: Evidence unchanged
- **WHEN** staff upload an MP4 as evidence
- **THEN** it is accepted as before

#### Scenario: Chat refuses video
- **WHEN** an MP4 is uploaded as a chat file
- **THEN** the answer is HTTP 415

### Requirement: Pending chat evidence is invisible
A staged chat file SHALL be a pending evidence item with the correlation id `chat:<appealId>`; it MUST be absent from listings, counts, the owner listing and the content route until a message claims it.

#### Scenario: Listing
- **WHEN** staff list a punishment's evidence while a player file is staged
- **THEN** the staged file is not returned or counted

### Requirement: Discord files are evidence only when accepted
A Discord attachment of the bound player or of verified staff that passed the chat profile SHALL be evidence. All other Discord attachments MUST stay metadata. This replaces the earlier rule that no Discord attachment is evidence.

#### Scenario: Metadata only
- **WHEN** an external participant posts a file
- **THEN** no evidence item exists

### Requirement: Erasure covers chat files
Deleting a message's content by redaction, the player-side erase and the cascade with the punishment SHALL delete chat evidence items and their blobs with the existing reasons, and an administrator's evidence delete MUST end any share of that item.

#### Scenario: Admin deletes shared evidence
- **WHEN** an administrator deletes an evidence item with an active share
- **THEN** the share reads as `UNAVAILABLE`
