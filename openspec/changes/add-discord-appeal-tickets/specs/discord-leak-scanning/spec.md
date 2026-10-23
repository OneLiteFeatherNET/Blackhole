# Spec Delta

## Purpose
Finds Ban IDs posted in public Discord channels, removes them and marks them leaked, without acting on look-alike text.

## ADDED Requirements

### Requirement: Scope of the scan
By default the bot SHALL scan new and edited messages in every channel and thread where `@everyone` can view the channel, excluding the ticket and archive categories; `DISCORD_LEAK_SCAN=LIST` MUST restrict it to configured channels and `OFF` disable it. Message history MUST NOT be scanned.

#### Scenario: Public channel
- **WHEN** a message is posted in a public channel
- **THEN** it is scanned

#### Scenario: Ticket channel
- **WHEN** a Ban ID is written in a private ticket channel
- **THEN** it is not scanned and not treated as a leak

#### Scenario: Scan off
- **WHEN** `DISCORD_LEAK_SCAN` is `OFF`
- **THEN** no message content is sent to the backend for matching

### Requirement: Exact candidate format
A candidate SHALL be `#` followed by 8 Crockford base32 characters (case-insensitive, with at most one space or hyphen in the middle) at a word boundary, at most five per message. A token without the `#` MUST NOT be a candidate.

#### Scenario: Prefixed candidate
- **WHEN** a message contains `#k7m2-9qxa`
- **THEN** one candidate `K7M29QXA` is produced

#### Scenario: Bare word
- **WHEN** a message contains the word `ABCDEFGH` or the number `12345678` without `#`
- **THEN** no candidate is produced

#### Scenario: Too many
- **WHEN** a message contains seven candidates
- **THEN** only five are sent

### Requirement: Backend verification before any action
The bot SHALL send the normalised candidates to `POST /discord/leak-check` and act only when the backend reports at least one match. A candidate that is not a current Ban ID MUST cause no deletion, no event and no stored record of the token.

#### Scenario: Look-alike
- **WHEN** a candidate matches no Ban ID
- **THEN** the message stays and nothing is recorded

#### Scenario: Real Ban ID
- **WHEN** a candidate matches a current Ban ID
- **THEN** the backend marks it leaked with reason `POSTED_PUBLIC`, source `DISCORD_BOT` and the poster's participant, and the bot deletes the message and posts a notice without any identifier

#### Scenario: Already leaked
- **WHEN** the same Ban ID is posted again
- **THEN** the message is deleted and no second leak event is appended

#### Scenario: Reissued old ID
- **WHEN** a retired Ban ID is posted
- **THEN** it does not match and the message stays

### Requirement: Poster information for staff
The leak event SHALL record the poster only as a participant reference and whether the poster is the ticket's bound player, so that staff can judge blame, and the panel SHALL show it.

#### Scenario: Owner posted it
- **WHEN** the bound player posts their own Ban ID
- **THEN** the event shows `posterIsOwner` true

#### Scenario: Third party posted it
- **WHEN** another member posts it
- **THEN** the event shows `posterIsOwner` false and an admin can reissue

### Requirement: No content in logs and bounded retry
The bot MUST NOT log message content or candidates. When the backend is unreachable it SHALL retry the candidates from a bounded in-memory queue for five minutes and then drop them with a counter increment.

#### Scenario: Backend down
- **WHEN** the backend is unreachable for ten minutes
- **THEN** the candidates are dropped after five minutes with a warning counter and no content in any log

### Requirement: Freeze follows from the leak
A leak recorded through the scanner SHALL have the same effect as a staff mark: forfeiture before filing, freeze after filing, sessions deleted, and the ticket channel frozen.

#### Scenario: Leak during an open ticket
- **WHEN** the Ban ID of an open ticket is found in a public channel
- **THEN** the ticket channel is frozen for the player and the portal shows the frozen state
