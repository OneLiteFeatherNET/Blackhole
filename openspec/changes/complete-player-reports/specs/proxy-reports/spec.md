# Spec Delta

## Purpose

Lets players report other players from the game and tells staff about new reports, through the Velocity plugin.

## ADDED Requirements

### Requirement: Report command
The proxy plugin SHALL provide `/report <player> <reason> [details]` for players. The permission `blackhole.report` SHALL count as granted unless a permission plugin explicitly denies it. The report SHALL identify both players only by their owner hash, and the reporter SHALL be told with the Blackhole prefix, in their language (English or German), that the report was sent.

#### Scenario: Report is sent
- **WHEN** a player runs `/report Steve cheating flying`
- **THEN** the plugin submits a `CHEATING` report with both owner hashes and the text `flying`, and confirms it to the reporter

#### Scenario: Permission explicitly denied
- **WHEN** a player to whom `blackhole.report` is explicitly denied runs `/report`
- **THEN** nothing is submitted and the player is told that they may not report

### Requirement: Report reasons
The reason SHALL be one of `chat`, `cheating`, `griefing` or `other` (case-insensitive; `chat_abuse`, `cheat`, `hacks`, `grief` and `sonstiges` are accepted too) and map to the backend categories `CHAT_ABUSE`, `CHEATING`, `GRIEFING` and `OTHER`. Tab completion SHALL offer the online players and the four reasons. The reason `other` SHALL require details, and details SHALL NOT exceed 1000 characters.

#### Scenario: Unknown reason
- **WHEN** a player runs `/report Steve fly`
- **THEN** nothing is submitted and the player is told the valid reasons and the usage

### Requirement: Who cannot be reported
The plugin MUST refuse a report in which the reporter names themselves, and a report against an online player who holds `blackhole.report.bypass`. A name that is neither online nor known to the backend SHALL be refused by the target resolution before anything is submitted.

#### Scenario: Self-report
- **WHEN** a player reports themselves
- **THEN** nothing is submitted and the player is told that they cannot report themselves

#### Scenario: Protected staff
- **WHEN** a player reports an online player who holds `blackhole.report.bypass`
- **THEN** nothing is submitted and the player is told that this player cannot be reported

### Requirement: Chat excerpt as report evidence
When chat capture is enabled and the reported player has buffered chat, the plugin SHALL post that chat, redacted and aliased exactly as for punishment evidence, as `CHAT_LOG` evidence owned by the reported player, without naming the reporter, and reference the stored evidence from the report. A failed or slow upload MUST NOT stop the report. With capture disabled the plugin MUST NOT send any chat.

#### Scenario: Chat is attached
- **WHEN** capture is enabled and the reported player chatted recently
- **THEN** the report references one `CHAT_LOG` evidence item holding that chat

#### Scenario: Upload fails
- **WHEN** the evidence upload fails
- **THEN** the report is still submitted, without an evidence reference

### Requirement: No plugin-side rate limit
The plugin MUST NOT add a cooldown, quota or other rate limit to `/report`. When the backend's report limiter answers HTTP 429 the reporter SHALL be told to wait a moment.

#### Scenario: Backend limit
- **WHEN** the backend answers a report with HTTP 429
- **THEN** the reporter sees a "please wait" message and nothing else is retried

### Requirement: Staff notice for new reports
When a report is recorded, every online player holding `blackhole.report.notify` on any proxy SHALL be told, once, the reporter, the reported player, the reason and a one-line excerpt of at most 80 characters of the text. The notice SHALL have an "open" button that suggests `/blackhole <player> info` (a click MUST NOT run a command) and, only when `panelUrl` is an http(s) address and the report has an identifier, a "panel" button that opens `<panelUrl>/reports/<id>`.

#### Scenario: Player without the permission
- **WHEN** a report is filed
- **THEN** online players without `blackhole.report.notify` receive nothing

#### Scenario: No panel address
- **WHEN** `panelUrl` is empty or not an http(s) address
- **THEN** the notice has the "open" button only

### Requirement: Cross-proxy delivery of the notice
The proxy that took the report SHALL publish one notice on the Redis channel `blackhole.report.created`, and every proxy, the publisher included, SHALL deliver it to its own staff, so no staff member is told twice. Names and text MUST NOT be stored or sent to the backend with the notice. If nobody receives the published notice, the publishing proxy SHALL deliver it to its own staff.

#### Scenario: Staff on two proxies
- **WHEN** a report is filed on proxy A while staff are online on proxies A and B
- **THEN** each staff member receives exactly one notice

#### Scenario: Redis unavailable
- **WHEN** the notice cannot be published
- **THEN** the staff of the proxy that took the report still receive it
