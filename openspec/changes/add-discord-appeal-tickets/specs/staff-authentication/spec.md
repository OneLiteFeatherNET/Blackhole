# Spec Delta

## Purpose
Adds the `DISCORD` machine role and its least-privilege routes to the permission matrix.

## ADDED Requirements

### Requirement: Discord role is least-privilege
The `DISCORD` role SHALL be granted by the app role `Blackhole.Discord` in app-only tokens, SHALL imply no other role, and MUST be allowed exactly the `/discord/**` routes: open and list tickets, update a ticket, read the outbox, ingest messages, record relay links, close a ticket, leak check, and staff link confirm and removal. It MUST NOT be allowed any `/case`, `/punishment`, `/appeal`, admin or erasure route, and the staff roles and `PROXY` MUST NOT be allowed `/discord/**`.

#### Scenario: Matrix test
- **WHEN** the permission-matrix test runs
- **THEN** every `/discord/**` route allows only `DISCORD` and `DISCORD` is allowed nowhere else

#### Scenario: Human token on the facade
- **WHEN** an admin token calls `GET /discord/tickets`
- **THEN** the response is HTTP 403

#### Scenario: Delegated token with the role name
- **WHEN** a delegated (user) token is used on a `/discord/**` route
- **THEN** the response is HTTP 403, because the role is read only from app-only tokens
