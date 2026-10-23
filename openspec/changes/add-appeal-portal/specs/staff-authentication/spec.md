# Spec Delta

## Purpose
Gives the reserved `/public/**` prefix its routes and permission-matrix entry, and adds the staff rows for the conversation.

## MODIFIED Requirements

### Requirement: Reserved prefix for the player portal
The route prefix `/public/**` SHALL be used only by the player portal. Every route under it MUST declare an explicit rule that is not a staff role: `POST /public/session` is anonymous and every other route requires a valid player session. No route outside `/public/**` MAY accept a player session, and no staff role MAY authorize a route that needs one.

#### Scenario: No route under the prefix
- **WHEN** the route-coverage test lists all routes under `/public`
- **THEN** each has the anonymous or player-session rule, none uses a staff role, and a route without a rule fails the build

#### Scenario: Player session on a staff route
- **WHEN** a request with a player session token calls a route outside `/public/**`
- **THEN** it is rejected

#### Scenario: Staff token on a session route
- **WHEN** a request with a valid staff token and no player session calls `GET /public/conversation`
- **THEN** the response is HTTP 401

## ADDED Requirements

### Requirement: Permission rows for the conversation
The permission matrix SHALL contain: `GET /punishment/{ref}/ban-id` for `MODERATOR` and `PROXY`; case messages `POST` and ban-id leak for `MODERATOR`; message redact, player-side erase, Ban ID reissue and transcript export for `ADMIN`; reading the conversation for `VIEWER`. `PROXY` MUST have no case rule.

#### Scenario: Proxy and case
- **WHEN** a `PROXY` caller posts a case message
- **THEN** the response is HTTP 403

#### Scenario: Matrix test
- **WHEN** the permission-matrix test runs
- **THEN** every new route's allowed roles equal the documented rows
