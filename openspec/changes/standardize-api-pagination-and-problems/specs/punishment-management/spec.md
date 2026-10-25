## MODIFIED Requirements

### Requirement: Punishment listing
The system SHALL expose `GET /punishment` returning all punishments, active and historical, as a cursor page of punishment entries, using the `limit` and `cursor` parameters and the `{items, nextCursor}` envelope defined by `api-pagination`.

#### Scenario: List punishments
- **WHEN** `GET /punishment` is requested
- **THEN** a cursor page of punishment entries is returned, newest first, with `nextCursor` set when more entries remain

#### Scenario: Next page of punishments
- **WHEN** a client follows `nextCursor` from a previous `GET /punishment` response
- **THEN** the next page continues from the entry after the last one returned
