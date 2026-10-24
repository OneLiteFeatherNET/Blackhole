# Spec Delta

## MODIFIED Requirements

### Requirement: Plugin configuration
The proxy plugin SHALL read its settings from `config.json` in its data directory, containing `baseUrl` (default `http://localhost:8080`), a `nats` object with `url` (default `wss://nats.onelitefeather.dev`) and `credsFile` (path of the NATS credentials file, default empty) and an `auth` object with `tenantId`, `clientId`, `clientSecret`, `scope` (default `api://<API id>/.default`) and an optional development-only `tokenUrl`. The plugin MUST NOT read a Redis or Dragonfly address. The `BLACKHOLE_*` environment variables for the auth keys, and `NATS_URL` and `NATS_CREDS_FILE`, MUST override the file. A missing file MUST be created with defaults.

#### Scenario: First start without a config file
- **WHEN** the proxy starts and `config.json` does not exist in the plugin data directory
- **THEN** the plugin writes `config.json` with `baseUrl` `http://localhost:8080`, the default `nats` object and an empty `auth` object, and uses those values

#### Scenario: Existing config file
- **WHEN** the proxy starts and `config.json` exists
- **THEN** the plugin sends backend API calls to the configured `baseUrl` and connects to NATS at the configured `nats.url`

#### Scenario: Legacy Redis setting
- **WHEN** `config.json` still contains `redisUri`
- **THEN** the plugin ignores it, logs one warning that Redis is no longer used, and starts normally

#### Scenario: Secret from the environment
- **WHEN** `BLACKHOLE_CLIENT_SECRET` is set and `config.json` has a different `clientSecret`
- **THEN** the environment value is used

### Requirement: Login enforcement
On every login the proxy SHALL first report an evasion sighting (owner hash and IP) to the backend, then check for an active ban in its local cache while that cache is trusted, otherwise from the backend profile over HTTP. A player with an active ban of type NETWORK MUST be disconnected with the rendered template message; bans of other types MUST NOT block login. Allowed logins record session info (protocol version).

#### Scenario: Network-banned player connects
- **WHEN** a player whose active ban has type NETWORK logs in
- **THEN** the player is disconnected with the rendered message of the ban's template

#### Scenario: Server-scoped ban does not block the proxy
- **WHEN** a player whose active ban has type SERVER logs in
- **THEN** the login is allowed and session info is recorded

#### Scenario: Redis unavailable
- **WHEN** a player logs in while the proxy is not connected to the event bus (NATS; the scenario name is kept from the Redis era)
- **THEN** the proxy fetches the punishment profile over HTTP and applies the same NETWORK-ban decision

#### Scenario: Evasion sighting recorded for every login
- **WHEN** any player logs in, banned or not
- **THEN** the proxy submits an evasion sighting with the player's owner hash and IP address to the backend

#### Scenario: Backend unreachable on fallback
- **WHEN** the event bus is unavailable and the backend profile request fails
- **THEN** the login is allowed

### Requirement: Proxy-side sync cache
The proxy SHALL subscribe, with a subscribe-only credential, to the punishment events `punishment.created`, `punishment.updated`, `punishment.expired`, `punishment.revoked` and `appeal.resolved` on `blackhole.events.>` and keep a per-player local cache of ban and mute state, seeded at login from the backend profile over HTTP and invalidated or updated from received events; entries MUST be removed when the player disconnects and MUST NOT be trusted for longer than a configured maximum age (default 60 seconds). If NATS cannot be reached at startup the proxy MUST keep working using HTTP checks only.

#### Scenario: Login seeds both slots
- **WHEN** a player logs in
- **THEN** the profile is fetched over HTTP and both ban and mute state are cached so the following chat check needs no network call

#### Scenario: Mute applied elsewhere
- **WHEN** a `punishment.created` event for the mute section of a cached player arrives
- **THEN** that player's next chat message is checked against fresh state and denied if the mute is active

#### Scenario: Cached entry too old
- **WHEN** a cached entry is older than the maximum age
- **THEN** the next check reads the backend over HTTP and re-caches the result

#### Scenario: Redis down at startup
- **WHEN** the proxy cannot connect to the event bus (NATS; the scenario name is kept from the Redis era) on startup
- **THEN** it logs the error, keeps retrying in the background, and enforcement continues via backend HTTP calls

### Requirement: Cross-proxy ban kick
When a `punishment.created` or `punishment.updated` event for a BAN section is received and the affected owner hash matches a player connected to this proxy, the proxy SHALL fetch that player's profile and, if it has an active ban, disconnect the player with the rendered template message, regardless of which proxy issued the ban.

#### Scenario: Ban issued on another proxy
- **WHEN** a ban is applied for a player connected to a different proxy than the one where staff issued it
- **THEN** the proxy hosting the player disconnects them immediately with the ban's template message

### Requirement: Incomplete credentials fail safe
When the backend credentials are incomplete the plugin MUST log an error at startup, backend-bound commands MUST fail with a localized message, and enforcement from the local cache SHALL continue for already cached players. When the NATS credentials file is missing or unreadable the plugin MUST log an error and run on HTTP checks only.

#### Scenario: Missing credentials
- **WHEN** `clientSecret` is empty and no environment override is set
- **THEN** an error is logged and `/ban` answers with a localized configuration error

#### Scenario: Missing NATS credentials file
- **WHEN** `nats.credsFile` points to a file that does not exist
- **THEN** an error is logged and login and chat enforcement continue over HTTP

## ADDED Requirements

### Requirement: Proxy fails open to HTTP when NATS is unreachable
While the proxy is not connected to NATS it MUST NOT trust its local punishment cache: on disconnect it SHALL discard the cache, every login and chat check SHALL read the backend over HTTP, and on reconnect it SHALL discard the cache again before trusting events. "Failing open" here means falling back to the HTTP check, never allowing an action because the event bus is down. The proxy MUST keep reconnecting without limit and MUST NOT block a login or chat message waiting for NATS.

#### Scenario: Connection lost mid-session
- **WHEN** the NATS connection drops while a muted player is online
- **THEN** the next chat message of that player is checked over HTTP and denied if the mute is active

#### Scenario: Reconnect
- **WHEN** the connection is restored
- **THEN** the cache is empty and is refilled from HTTP checks before events are relied on

#### Scenario: NATS and backend both down
- **WHEN** NATS is unreachable and the HTTP check fails
- **THEN** the existing backend-unreachable behaviour applies unchanged (login allowed, chat delivered)

### Requirement: Proxy NATS credential is subscribe-only
The proxy SHALL connect to NATS over `wss://` with its own credential in the `BLACKHOLE` account, restricted to WebSocket connections, with publishing denied everywhere and subscription allowed only on the `blackhole.events` subjects it consumes. It MUST NOT use Redis or Dragonfly.

#### Scenario: Publish attempt
- **WHEN** the proxy credential tries to publish to any subject
- **THEN** the server rejects it with a permissions violation

#### Scenario: Unrelated subject
- **WHEN** the proxy credential subscribes to `blackhole.events.case.>`
- **THEN** the server rejects the subscription

### Requirement: Section and template caches follow section changes
The proxy SHALL fetch the section list and the punishment templates over HTTP into short-lived local caches and SHALL refresh the section list when a `section.changed` event arrives.

#### Scenario: Section added
- **WHEN** a `section.changed` event arrives
- **THEN** the next tab completion of sections reads the backend and shows the new section
