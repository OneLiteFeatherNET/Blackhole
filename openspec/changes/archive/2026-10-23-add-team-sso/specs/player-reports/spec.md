# Spec Delta

## MODIFIED Requirements

### Requirement: Report resolution
The system SHALL let callers with at least `MODERATOR` resolve reports via `POST /report/{identifier}/resolve` with a `status` and optional `resolutionNote` (at most 1000 characters), updating the report's status, resolver, note and update time. The resolver SHALL be the caller's verified `oid`; the request MUST NOT carry `resolvedBy` or `punishmentSource`, and when a punishment is applied its source is the same verified identity. It MUST return HTTP 404 for an unknown report. Resolution MAY be repeated on the same report.

#### Scenario: Dismiss a report
- **WHEN** staff resolves a report with status `DISMISSED` and a note
- **THEN** the report's status, `resolvedBy` (the caller's `oid`) and `resolutionNote` are updated

#### Scenario: Unknown report
- **WHEN** a resolution targets a report identifier that does not exist
- **THEN** the response is HTTP 404
