# Spec Delta

## Purpose

Adds the CloudNet catalogue routes to the permission matrix and to the proxy role.

## MODIFIED Requirements

### Requirement: Permission matrix
The backend SHALL enforce a minimum role per endpoint group: read-only routes (including `GET /cloudnet/catalogue`) need `VIEWER`; moderation routes (apply, preview and revoke punishments, resolve reports, review appeals, adjust ELO, add evidence, profile create and update) and intake routes need `MODERATOR`; section, template and vanilla-import writes, evidence deletion, profile deletion and staff-actor anonymization need `ADMIN`; `PUT /cloudnet/catalogue` needs the `PROXY` role and no human role; `GET /prometheus` needs `METRICS`. A route with no explicit rule MUST be rejected.

#### Scenario: Viewer attempts a moderation action
- **WHEN** a `VIEWER` calls `POST /punishment/apply`
- **THEN** the response is HTTP 403 and nothing is changed

#### Scenario: Moderator attempts admin configuration
- **WHEN** a `MODERATOR` calls `POST /section`
- **THEN** the response is HTTP 403

#### Scenario: Proxy attempts erasure
- **WHEN** a `PROXY` caller calls `DELETE /evidence/{id}`
- **THEN** the response is HTTP 403

#### Scenario: Matrix is checked against every route
- **WHEN** the build runs the route-coverage test
- **THEN** every registered route has a rule and its allowed roles equal the documented matrix

#### Scenario: Admin attempts to write the catalogue
- **WHEN** an `ADMIN` human token calls `PUT /cloudnet/catalogue`
- **THEN** the response is HTTP 403 and nothing is stored

#### Scenario: Viewer reads the catalogue
- **WHEN** a `VIEWER` calls `GET /cloudnet/catalogue`
- **THEN** the request is authorized

### Requirement: Proxy role is least-privilege
The `PROXY` role SHALL be allowed exactly its routes: punishment and profile reads, section and template reads, player lookup, evidence list, punishment apply and revoke, evidence upload, the intake routes, and `PUT /cloudnet/catalogue`. It MUST NOT be allowed any admin, erasure or review route, nor `GET /cloudnet/catalogue`.

#### Scenario: Proxy applies a punishment
- **WHEN** a `PROXY` caller calls `POST /punishment/apply`
- **THEN** the request is authorized

#### Scenario: Proxy resolves a report
- **WHEN** a `PROXY` caller calls `POST /report/{id}/resolve`
- **THEN** the response is HTTP 403

#### Scenario: Proxy reports the catalogue
- **WHEN** a `PROXY` caller calls `PUT /cloudnet/catalogue`
- **THEN** the request is authorized

#### Scenario: Proxy reads the catalogue
- **WHEN** a `PROXY` caller calls `GET /cloudnet/catalogue`
- **THEN** the response is HTTP 403
