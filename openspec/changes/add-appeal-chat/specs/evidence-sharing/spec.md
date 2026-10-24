# Spec Delta

## Purpose
Lets the team show one existing piece of case evidence to the appealing player, explicitly, auditably, revocably and with the other people in it minimised.

## ADDED Requirements

### Requirement: Nothing is visible without a share
Evidence SHALL NOT be visible to a player in any route, event or export except through an active share. A share MUST be created by `MODERATOR` or `ADMIN` for one evidence item of the case, and MUST require an appeal on the case and a Ban ID that is not marked leaked.

#### Scenario: Unshared evidence
- **WHEN** a player's session reads the conversation of a case that has evidence and no share
- **THEN** no evidence item, count of staff evidence or file appears

#### Scenario: Leaked Ban ID
- **WHEN** a moderator shares evidence while the Ban ID is marked leaked
- **THEN** the answer is HTTP 409 `FROZEN` and no share exists

#### Scenario: No appeal
- **WHEN** the case has no appeal
- **THEN** the answer is HTTP 409 `NO_APPEAL`

### Requirement: Shareable kinds
Only `FILE` evidence that is an image and `CHAT_LOG` evidence SHALL be shareable, and only when it is `VALID` and belongs to the case's punishment. `ANTICHEAT`, `REPLAY`, `LINK`, `TRANSCRIPT`, video files and player-supplied files MUST answer HTTP 422 `NOT_SHAREABLE`.

#### Scenario: Anticheat
- **WHEN** a moderator shares an `ANTICHEAT` item
- **THEN** the answer is HTTP 422 `NOT_SHAREABLE`

#### Scenario: Lifted evidence
- **WHEN** the item is `LIFTED` or `REVOKED`
- **THEN** the answer is HTTP 409 and no share exists

### Requirement: Minimised excerpt of a chat capture
An `EXCERPT` share SHALL store only the selected line indexes (at most 20) and the chosen appellant speaker. The player's view MUST be rendered from the evidence on read, never copied: the appellant is "You", others "Player 2", "Player 3" by first appearance, times are offsets, lines are plain text. Each line MUST pass the UUID and IP check at share time (HTTP 422 `PII_REJECTED`). A share MUST carry `confirmedMinimised: true`, and the confirming `oid` is stored.

#### Scenario: Relabelled
- **WHEN** a moderator shares lines from speakers `Alice` (the appellant), `Bob` and `Carol`
- **THEN** the player sees "You", "Player 2" and "Player 3" and no original label

#### Scenario: No confirmation
- **WHEN** the request lacks `confirmedMinimised: true`
- **THEN** the answer is HTTP 400 and no share exists

#### Scenario: Too many lines
- **WHEN** 21 lines are selected
- **THEN** the answer is HTTP 400

#### Scenario: Evidence deleted later
- **WHEN** the evidence item is deleted after the share
- **THEN** the share card shows "no longer available" and returns no lines

#### Scenario: Preview matches
- **WHEN** a staff member requests the preview for a selection
- **THEN** it returns exactly the lines and labels the player would see, and changes nothing

### Requirement: Shared screenshots
A `FILE` image share SHALL need `confirmedMinimised: true`, with a dialog text that says the image may show other players' names. The player SHALL fetch it through `GET /public/attachments/{shareId}` rules while the share is active, with the safe delivery headers.

#### Scenario: Active share
- **WHEN** a share of a screenshot is active
- **THEN** the player's file route serves the image inline with safety headers

#### Scenario: Revoked share
- **WHEN** the share was revoked
- **THEN** the file route answers HTTP 404

### Requirement: Share and revoke are audited case events
Creating a share SHALL append `evidence.shared` and revoking SHALL append `evidence.share_revoked`, each in the same statement as the change, with visibility `PLAYER`, a dedupe key per share and the staff member's verified `oid`. Payloads MUST hold ids, kind, mode, line count and a revoke reason code (`MISTAKE`, `PRIVACY`, `OTHER`) and MUST NOT contain evidence content, a file name, a speaker label or a line. An identical active share MUST return the existing one.

#### Scenario: Audit trail
- **WHEN** a moderator shares and later revokes an item
- **THEN** the timeline shows both events with the moderator as verified actor and no content

#### Scenario: Repeat
- **WHEN** the same item and selection is shared twice while the first share is active
- **THEN** one share exists and the second answer reports `repeated: true`

#### Scenario: Revoke twice
- **WHEN** a revoked share is revoked again
- **THEN** nothing changes and the answer reports the share as revoked

### Requirement: Routes and roles
`POST /case/{reference}/evidence-shares` and `POST /case/{reference}/evidence-shares/{shareId}/revoke` SHALL need `MODERATOR`. `GET /case/{reference}/evidence-shares` and `POST /case/{reference}/evidence-shares/preview` SHALL need `VIEWER`. `PROXY`, `DISCORD` and player sessions MUST be denied on all of them.

#### Scenario: Viewer shares
- **WHEN** a viewer calls the share route
- **THEN** the answer is HTTP 403

#### Scenario: Player calls staff route
- **WHEN** a player session token calls a share route
- **THEN** the answer is HTTP 401 or 403 and nothing changes

### Requirement: Player view of shares
The player conversation SHALL show a share as a card in thread order with the label "shared by the moderation team", the excerpt lines or the image, and the state `ACTIVE`, `REVOKED` or `UNAVAILABLE`. A revoked or unavailable card MUST contain no lines and no file.

#### Scenario: Revoked card
- **WHEN** a share is revoked
- **THEN** the card shows "No longer shared" and no content on the next read

### Requirement: Protocol records shares without content
The appeal protocol SHALL list a share and a revoke as marker lines (kind and, for an excerpt, line count) and MUST NOT contain the excerpt or the image.

#### Scenario: Protocol
- **WHEN** a protocol is generated for an appeal with one share
- **THEN** it contains `[evidence shared: chat excerpt, 5 lines]` and no chat line
