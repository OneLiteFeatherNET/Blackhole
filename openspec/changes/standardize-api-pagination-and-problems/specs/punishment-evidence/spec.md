## MODIFIED Requirements

### Requirement: Marked evidence access
Marked evidence MUST be excluded from list responses unless `includeInactive=true` is passed, MUST NOT be counted in `evidenceCount`, MUST carry its status in every response, and listing it MUST write a `VIEWED_INACTIVE` audit entry. The system does not restrict `includeInactive` to admins: every reader (`VIEWER` and up) may use it, and each use is audited. Evidence list routes (`GET /evidence` and `GET /punishment/{reference}/evidence`) SHALL return a cursor page using the `limit` and `cursor` parameters and the `{items, nextCursor}` envelope defined by `api-pagination`.

#### Scenario: Default list hides lifted evidence
- **WHEN** a punishment lifted by appeal is listed without `includeInactive`
- **THEN** its lifted items are not returned and the count excludes them

#### Scenario: Explicit inclusion is audited
- **WHEN** the list is requested with `includeInactive=true`
- **THEN** lifted items are returned with status `LIFTED`, liftedAt and the appeal id, and a `VIEWED_INACTIVE` audit entry is written
