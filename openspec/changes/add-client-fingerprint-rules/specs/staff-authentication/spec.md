# Spec Delta

## Purpose
Adds the permission matrix rows for the client rule and fingerprint routes.

## ADDED Requirements

### Requirement: Client rule team routes
Rule reads (`GET /client-rule`, `GET /client-rule/{key}`) SHALL need `VIEWER` or higher, or `PROXY`; rule writes (`POST /client-rule`, `PUT /client-rule/{key}`, `DELETE /client-rule/{key}`) SHALL need `ADMIN`. Hit and catalogue reads (`GET /client-rule/{key}/hits`, `GET /client-rule/hits`, `GET /client-rule/catalogue`) SHALL need `VIEWER` or higher and deny `PROXY`.

#### Scenario: Admin writes
- **WHEN** an `ADMIN` creates a rule
- **THEN** the response is 201

#### Scenario: Proxy cannot edit
- **WHEN** the `PROXY` role creates or deletes a rule
- **THEN** the response is 403

#### Scenario: Proxy cannot read hits
- **WHEN** the `PROXY` role reads hits or the catalogue
- **THEN** the response is 403

### Requirement: Client rule proxy routes
`PUT /client-rule/catalogue`, `POST /client-rule/hit` and `POST /evasion/fingerprint` SHALL be `PROXY` only. `METRICS` and `DISCORD` SHALL be denied on every client rule route, and the permission matrix and route coverage tests SHALL assert each row with every role.

#### Scenario: Staff cannot report
- **WHEN** an `ADMIN` posts a hit or submits a fingerprint
- **THEN** the response is 403

#### Scenario: Discord role
- **WHEN** the `DISCORD` role calls any client rule route
- **THEN** the response is 403
