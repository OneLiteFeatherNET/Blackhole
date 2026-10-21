# player-reports Specification

## Purpose
Lets players report misconduct by other players and lets staff resolve those reports, optionally
applying a punishment and feeding the outcome into the dual-ELO standing system. Submission is
rate limited so reporting cannot itself become a griefing vector.

## Requirements

### Requirement: Report submission
The system SHALL accept reports via `POST /report` with a `reporterHash` and `reportedHash` (each a 128-character hex SHA-512 hash), a `category` of `CHAT_ABUSE`, `CHEATING`, `GRIEFING` or `OTHER`, an optional `description` of at most 1000 characters, optional evidence references and metadata, and SHALL record it with status `OPEN`.

#### Scenario: Valid report is recorded
- **WHEN** a player submits a well-formed report
- **THEN** the response contains the recorded report with status `OPEN` and creation timestamps set

#### Scenario: Malformed player hash
- **WHEN** a report is submitted with a `reportedHash` that is not a 128-character hex string
- **THEN** the request is rejected with a validation error and nothing is recorded

### Requirement: Server-controlled report fields
The system MUST set a new report's status, creation and update timestamps itself, and MUST leave its resolver and resolution note empty until the report is resolved.

#### Scenario: Fresh report has no resolution
- **WHEN** a report is submitted
- **THEN** its `resolvedBy` and `resolutionNote` are empty

### Requirement: Per-reporter rate limit
The system MUST reject a submission with HTTP 429 when the same `reporterHash` has already submitted `blackhole.report.rate-limit.max-reports` (default 5) reports within the rolling `blackhole.report.rate-limit.window` (default 10 minutes), and MUST NOT record it.

#### Scenario: Reporter exceeds the limit
- **WHEN** a reporter submits a sixth report within 10 minutes under default configuration
- **THEN** the response is HTTP 429 and the report is not recorded

### Requirement: Network-wide rate limit
The system MUST reject any submission with HTTP 429 when `blackhole.report.rate-limit.max-reports-network-wide` (default 50) reports have been recorded network-wide within the rate-limit window, regardless of the claimed `reporterHash`.

#### Scenario: Varying reporter hashes
- **WHEN** 50 reports with distinct reporter hashes were submitted in the last 10 minutes and another arrives
- **THEN** the response is HTTP 429

### Requirement: Report listing
The system SHALL return a paginated list of all reports via `GET /report`.

#### Scenario: List reports
- **WHEN** a caller requests `GET /report` with paging parameters
- **THEN** the response is a page of reports with their current status

### Requirement: Report resolution
The system SHALL resolve reports via `POST /report/{identifier}/resolve` with a `status`, required `resolvedBy`, and optional `resolutionNote` (at most 1000 characters), updating the report's status, resolver, note and update time. It MUST return HTTP 404 for an unknown report. Resolution MAY be repeated on the same report.

#### Scenario: Dismiss a report
- **WHEN** staff resolves a report with status `DISMISSED` and a note
- **THEN** the report's status, `resolvedBy` and `resolutionNote` are updated

#### Scenario: Unknown report
- **WHEN** a resolution targets a report identifier that does not exist
- **THEN** the response is HTTP 404

### Requirement: Punishment on resolution
When a resolution includes `punishmentTemplateId`, the system SHALL apply that template to the reported player as issued by `punishmentSource`. It MUST return HTTP 400 if `punishmentSource` is missing and HTTP 404 if the template does not exist, in both cases leaving the report unchanged.

#### Scenario: Resolve with a punishment
- **WHEN** staff resolves a report as `ACTIONED` with a valid template and source
- **THEN** the template is applied to the reported player and the report is updated

#### Scenario: Missing punishment source
- **WHEN** a resolution includes `punishmentTemplateId` but no `punishmentSource`
- **THEN** the response is HTTP 400 and the report is not modified

#### Scenario: Unknown template
- **WHEN** a resolution references a template that does not exist
- **THEN** the response is HTTP 404 and the report is not modified

### Requirement: ELO penalty for actioned reports
When a report is resolved as `ACTIONED`, the system SHALL apply `blackhole.elo.report.actioned-delta` (default -100) to the reported player's chat track for `CHAT_ABUSE` or gameplay track for `CHEATING` and `GRIEFING`. Reports in category `OTHER` MUST NOT change any ELO score.

#### Scenario: Actioned cheating report
- **WHEN** a `CHEATING` report is resolved as `ACTIONED`
- **THEN** the reported player's gameplay score decreases by the configured actioned delta

#### Scenario: Actioned report in category OTHER
- **WHEN** an `OTHER` report is resolved as `ACTIONED`
- **THEN** no ELO score changes

### Requirement: Reporter reward
The system SHALL award the reporter `blackhole.elo.report.reward-delta` (default +50) on the same track only when a report is resolved as `ACTIONED` and a punishment was actually applied through `punishmentTemplateId`. A resolution without a punishment MUST NOT reward the reporter.

#### Scenario: Actioned with punishment
- **WHEN** a `CHAT_ABUSE` report is resolved as `ACTIONED` with a punishment template applied
- **THEN** the reporter's chat score increases by the configured reward delta

#### Scenario: Actioned without punishment
- **WHEN** a `CHAT_ABUSE` report is resolved as `ACTIONED` without a punishment template
- **THEN** the reporter's score is unchanged

### Requirement: Report domain events
The system SHALL publish `report.created` (report id, reporter hash, reported hash, category) on every recorded submission and `report.resolved` (report id, reported hash, status, resolver) on every successful resolution.

#### Scenario: Events on report lifecycle
- **WHEN** a report is submitted and later resolved
- **THEN** a `report.created` event and then a `report.resolved` event are published
