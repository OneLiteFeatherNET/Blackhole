# Spec Delta

## Purpose
Adds the rules that decide which evidence and related records a case file may show a player, and the provenance of each item.

## ADDED Requirements

### Requirement: Provenance of evidence is shown, not stored
Evidence items SHALL be presented with a provenance of `TEAM`, `PLAYER` or `SYSTEM`, derived from the existing `provenance` column and the process that wrote the row. The feature MUST NOT add a column for it, and the evidence count SHALL continue to count `STAFF` items only.

#### Scenario: Count unchanged
- **WHEN** a player uploads a file and a moderator attaches an image
- **THEN** the public evidence count includes the moderator's image only, as before

### Requirement: Presentable data types in a case file
The data types that a case file may present to a player SHALL be exactly those listed as shareable in the case-file capability: chat excerpts, image files and the summaries of previous punishments, reports and kick records. Anticheat records, replays, links, client-rule hits, evasion hints, mass kicks and team notes MUST NOT be presented, in any form, to a player.

#### Scenario: Anticheat record not presentable
- **WHEN** the presentation route receives an anticheat evidence item
- **THEN** the answer is HTTP 422 `NOT_SHAREABLE`

#### Scenario: Evasion hint never reaches the player
- **WHEN** any player route is called for a case with an evasion hint
- **THEN** the response contains no evasion data, count or owner hash
