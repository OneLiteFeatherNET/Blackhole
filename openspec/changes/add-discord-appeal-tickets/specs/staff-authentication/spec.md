# Spec Delta

## Purpose
Adds the `DISCORD` machine role and its least-privilege routes to the permission matrix.

## ADDED Requirements

### Requirement: Discord role is least-privilege
The `DISCORD` role SHALL be granted by the app role `Blackhole.Discord` in app-only tokens and SHALL imply no other role. It MUST NOT be allowed any `/case`, `/punishment`, `/appeal`, admin or erasure route, and the staff roles and `PROXY` MUST NOT be allowed `/discord/**`.

#### Scenario: Matrix test
- **WHEN** the permission-matrix test runs
- **THEN** every `/discord/**` route allows only `DISCORD` and `DISCORD` is allowed nowhere else

#### Scenario: Human token on the facade
- **WHEN** an admin token calls `GET /discord/tickets`
- **THEN** the response is HTTP 403

#### Scenario: Delegated token with the role name
- **WHEN** a delegated (user) token is used on a `/discord/**` route
- **THEN** the response is HTTP 403, because the role is read only from app-only tokens

### Requirement: Discord routes
The `DISCORD` role MUST be allowed exactly the `/discord/**` routes: link confirm, whoami and unlink (phase 0), and later open and list tickets, update a ticket, read the outbox, ingest messages, record relay links, close a ticket (which stores its protocol), ensure its protocol, list staff links, leak check and the notification reads.

#### Scenario: Bot token on a case route
- **WHEN** a token with `Blackhole.Discord` calls `GET /case`
- **THEN** the response is HTTP 403

#### Scenario: Bot token on the facade
- **WHEN** a token with `Blackhole.Discord` calls `POST /discord/tickets/{id}/protocol/ensure`
- **THEN** the call is allowed
