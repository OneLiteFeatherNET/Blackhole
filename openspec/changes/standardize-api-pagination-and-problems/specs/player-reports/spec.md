## MODIFIED Requirements

### Requirement: Report listing
The system SHALL return a cursor page of all reports via `GET /report`, using the `limit` and `cursor` parameters and the `{items, nextCursor}` envelope defined by `api-pagination`.

#### Scenario: List reports
- **WHEN** a caller requests `GET /report` with `limit` or `cursor` parameters
- **THEN** the response is a cursor page of reports with their current status, newest first
