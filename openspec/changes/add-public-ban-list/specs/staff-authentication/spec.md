## MODIFIED Requirements

### Requirement: Reserved prefix for the player portal

The route prefix `/public/**` SHALL be reserved for the later player portal. The only routes registered under it by the `public-ban-list` capability are `GET /public/bans`, `GET /public/bans/summary` and `GET /public/bans/{hash}`. These routes are not anonymous and not staff routes: they SHALL be answered only through the Basic Auth check of a configured ban-list client, and they SHALL NOT accept an Entra access token, a Ban ID session or a player session token. This is a deliberate exception to `openspec/config.yaml` principle 2 (Entra JWT on every route except `GET /health`) and is an open question for the maintainer. Every other route under the prefix MUST declare its own explicit security rule and MUST NOT use the staff roles.

#### Scenario: No route under the prefix

- **WHEN** the route-coverage test lists all routes under `/public`
- **THEN** the only routes are the three ban-list routes, and each one requires Basic Auth with a configured client and no Entra token

#### Scenario: Staff token on a public ban-list route

- **WHEN** a valid staff Entra token is sent to `GET /public/bans`
- **THEN** the response is HTTP 401 with the Basic challenge, and the staff roles grant no access to it

#### Scenario: Ban-list credentials are not an Entra token

- **WHEN** a configured ban-list client sends a Basic credential to any route outside the ban-list routes
- **THEN** the credential is not accepted and the Entra chain answers as before
