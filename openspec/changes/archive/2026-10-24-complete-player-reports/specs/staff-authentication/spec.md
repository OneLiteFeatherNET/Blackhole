# Spec Delta

## Purpose

Adds the report routes to the permission matrix.

## ADDED Requirements

### Requirement: Report routes in the matrix
`GET /report/{identifier}` SHALL need `VIEWER`. `GET /report/feedback/{owner}` and `POST /report/feedback/{identifier}/ack` SHALL be allowed for `PROXY` only, so the proxy's least-privilege set grows by exactly these two routes.

#### Scenario: Human on a proxy route
- **WHEN** an `ADMIN` calls `GET /report/feedback/{owner}`
- **THEN** the response is HTTP 403

#### Scenario: Proxy reads one report
- **WHEN** a `PROXY` caller calls `GET /report/{identifier}`
- **THEN** the response is HTTP 403
