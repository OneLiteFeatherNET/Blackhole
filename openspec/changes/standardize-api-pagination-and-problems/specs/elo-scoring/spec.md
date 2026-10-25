## MODIFIED Requirements

### Requirement: ELO profile and history read endpoints
The system SHALL expose `GET /elo/{owner}` returning the owner's current chat and gameplay scores, or 404 if no ELO profile exists, and `GET /elo/{owner}/history` returning that owner's ELO history events as a cursor page, using the `limit` and `cursor` parameters and the `{items, nextCursor}` envelope defined by `api-pagination`.

#### Scenario: Profile not found
- **WHEN** `GET /elo/{owner}` is requested for an owner with no ELO profile
- **THEN** the response is 404 with a problem body

#### Scenario: Paginated history
- **WHEN** `GET /elo/{owner}/history` is requested for an owner with recorded events
- **THEN** a cursor page of that owner's history events is returned, newest first
