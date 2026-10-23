# Spec Delta

## MODIFIED Requirements

### Requirement: Plugin configuration
The proxy plugin SHALL read its settings from `config.json` in its data directory, containing `baseUrl` (backend API base URL, default `http://localhost:8080`), `redisUri` (Redis connection URI, default `redis://localhost:6379`) and an `auth` object with `tenantId`, `clientId`, `clientSecret` and `scope` (default `api://<client id of the API>/.default`; a development-only `tokenUrl` override may be set). Environment variables `BLACKHOLE_TENANT_ID`, `BLACKHOLE_CLIENT_ID`, `BLACKHOLE_CLIENT_SECRET`, `BLACKHOLE_AUTH_SCOPE` and `BLACKHOLE_TOKEN_URL` MUST override the file values. When the file does not exist, the plugin MUST create it with these defaults and empty credentials and use them.

#### Scenario: First start without a config file
- **WHEN** the proxy starts and `config.json` does not exist in the plugin data directory
- **THEN** the plugin writes `config.json` with `baseUrl` `http://localhost:8080`, `redisUri` `redis://localhost:6379` and an empty `auth` object, and uses those values

#### Scenario: Existing config file
- **WHEN** the proxy starts and `config.json` exists
- **THEN** the plugin sends backend API calls to the configured `baseUrl` and connects to Redis at the configured `redisUri`

#### Scenario: Secret from the environment
- **WHEN** `BLACKHOLE_CLIENT_SECRET` is set and `config.json` has a different `clientSecret`
- **THEN** the environment value is used

## ADDED Requirements

### Requirement: Machine authentication to the backend
The proxy plugin SHALL obtain an access token with the OAuth2 client credentials grant and send it as a bearer token on every backend call. It MUST NOT log the secret or the token, and MUST send the executing staff member's UUID as `onBehalfOf` on punishment, revocation and evidence calls.

#### Scenario: Staff ban in game
- **WHEN** a staff member runs `/ban`
- **THEN** the backend call carries a bearer token and their UUID as `onBehalfOf`

### Requirement: Token caching and refresh
The plugin MUST cache the token until 60 seconds before expiry, refresh it without concurrent duplicate token requests, and on HTTP 401 refresh once and retry the call once.

#### Scenario: Token reuse
- **WHEN** two backend calls happen within the token's lifetime
- **THEN** only one token request is made

#### Scenario: Token near expiry
- **WHEN** a call happens within 60 seconds of expiry
- **THEN** a new token is requested first

#### Scenario: Rejected token
- **WHEN** the backend answers 401
- **THEN** the plugin fetches a fresh token and repeats the call once

### Requirement: Incomplete credentials fail safe
When the credentials are incomplete the plugin MUST log an error at startup, backend-bound commands MUST fail with a localized message, and Redis-based login and chat enforcement SHALL continue.

#### Scenario: Missing credentials
- **WHEN** `clientSecret` is empty and no environment override is set
- **THEN** an error is logged, `/ban` answers with a localized configuration error, and Redis-based enforcement still runs
