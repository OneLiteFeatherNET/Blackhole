## MODIFIED Requirements

### Requirement: Appeal listing
The system SHALL return a cursor page of all appeals via `GET /appeal`, using the `limit` and `cursor` parameters and the `{items, nextCursor}` envelope defined by `api-pagination`. Each appeal SHALL include its status, checklist snapshot and decision fields.

#### Scenario: List appeals
- **WHEN** a caller requests `GET /appeal` with `limit` or `cursor` parameters
- **THEN** the response is a cursor page of appeals, newest submission first
