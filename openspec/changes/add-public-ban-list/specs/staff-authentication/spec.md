## MODIFIED Requirements

### Requirement: Reserved prefix for the player portal

The route prefix `/public/**` SHALL be reserved for the player portal. Its routes SHALL each declare their own explicit security rule and MUST NOT use the staff roles. The only anonymous read-only routes under the prefix SHALL be the public ban list routes `GET /public/bans`, `GET /public/bans/summary` and `GET /public/bans/{hash}` of the `public-ban-list` capability. Every other route under the prefix SHALL require its own session rule.

#### Scenario: No route under the prefix

- **WHEN** the route-coverage test lists all routes
- **THEN** the anonymous `GET` routes under `/public` are the login and the three public ban list routes, and no other route under the prefix is anonymous

#### Scenario: Staff token on a public route

- **WHEN** a staff token is sent to any route under `/public`
- **THEN** the staff roles grant no access to it
