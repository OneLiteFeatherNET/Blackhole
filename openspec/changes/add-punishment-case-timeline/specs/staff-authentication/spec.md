# Spec Delta

## Purpose

Adds the case routes to the permission matrix.

## ADDED Requirements

### Requirement: Case routes in the permission matrix
Reading cases, the case list and the timeline SHALL need `VIEWER`; changing case metadata, adding notes and editing a note SHALL need `MODERATOR`; redacting a note SHALL need `ADMIN`. The `PROXY` role MUST be denied on every `/case` route. The permission matrix and route-coverage tests MUST include these routes.

#### Scenario: Viewer reads, cannot write
- **WHEN** a `VIEWER` calls `GET /case/{reference}/timeline` and then `POST /case/{reference}/notes`
- **THEN** the first succeeds and the second is HTTP 403

#### Scenario: Moderator cannot redact
- **WHEN** a `MODERATOR` calls `POST /case/{reference}/notes/{noteId}/redact`
- **THEN** the response is HTTP 403

#### Scenario: Proxy is denied
- **WHEN** the `PROXY` role calls `GET /case/{reference}`
- **THEN** the response is HTTP 403

#### Scenario: Matrix is checked against every route
- **WHEN** the build runs the route-coverage test
- **THEN** every `/case` route has a rule equal to the documented matrix
