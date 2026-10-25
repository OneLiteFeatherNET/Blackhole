## ADDED Requirements

### Requirement: Pinned signature algorithm
The backend MUST accept only the RSA signature algorithm `RS256` for bearer tokens. A token signed with any other `alg`, including `none` and HMAC algorithms, MUST be answered with HTTP 401, even when its signature would verify with a key the backend holds.

#### Scenario: HMAC token signed with the public key
- **WHEN** a token is signed with `HS256` using the bytes of the issuer's public key as the secret
- **THEN** the response is HTTP 401 and no controller is reached

#### Scenario: RS256 token from the issuer
- **WHEN** a correctly signed `RS256` token with the configured issuer and audience is presented
- **THEN** the request is authenticated

### Requirement: Audience and tenant are mandatory outside development
When the active environment is neither `dev` nor `test`, the backend MUST refuse to start unless `blackhole.security` has a non-empty audience that is the API application's client id (a GUID) and a non-empty tenant id. The audience MUST equal the `aud` claim of a v2 access token issued for the API. No default audience MAY be used outside development.

#### Scenario: Production start without an audience
- **WHEN** the backend starts in a non-dev environment with no audience configured
- **THEN** startup fails with a message naming the audience setting

#### Scenario: Production start with a non-GUID audience
- **WHEN** the audience is `blackhole-api` or `api://<guid>` in a non-dev environment
- **THEN** startup fails with a message naming the audience setting

#### Scenario: Token for another tenant
- **WHEN** a valid token's `tid` differs from the configured tenant id
- **THEN** the response is HTTP 401

### Requirement: Token version
The backend SHALL accept only v2 access tokens, meaning tokens whose `ver` claim is `2.0`. A token with any other `ver`, or with no `ver`, MUST be answered with HTTP 401.

#### Scenario: v1 token
- **WHEN** a token carries `ver` `1.0`
- **THEN** the response is HTTP 401

### Requirement: Clock skew bound
The backend MUST allow at most 60 seconds of clock skew when checking `exp` and `nbf`, and the bound MUST be configurable only downwards.

#### Scenario: Token not yet valid by more than the bound
- **WHEN** a token's `nbf` is 2 minutes in the future
- **THEN** the response is HTTP 401

#### Scenario: Token expired less than the bound ago
- **WHEN** a token's `exp` passed 30 seconds ago
- **THEN** the request is authenticated

### Requirement: App-only tokens are bound to an allowed client
For an app-only token, a machine role (`PROXY`, `METRICS` or `DISCORD`) MUST be granted only when the token's `azp` claim is in the configured allow-list for that role. A token with the role value and an `azp` not on the list MUST grant no role. The `appid` claim MUST NOT be used for this check.

#### Scenario: Listed client with the proxy role
- **WHEN** an app-only token carries `Blackhole.Proxy` in `roles` and its `azp` is on the proxy allow-list
- **THEN** the caller has `PROXY`

#### Scenario: Unlisted client with the proxy role
- **WHEN** an app-only token carries `Blackhole.Proxy` in `roles` and its `azp` is not on the proxy allow-list
- **THEN** the caller has no role and a `PROXY` route answers HTTP 403

### Requirement: Machine clients send secrets only over https
A machine client (the Velocity plugin and the Discord bot) MUST refuse a token endpoint whose scheme is not `https` unless the environment is `dev` or `test`. The client MUST NOT send its client secret to a non-`https` endpoint in any other environment.

#### Scenario: Plain http token endpoint in production
- **WHEN** a machine client is configured with an `http` token endpoint outside dev and test
- **THEN** the client refuses to start and no request carries the secret

#### Scenario: Https token endpoint
- **WHEN** a machine client is configured with an `https` token endpoint
- **THEN** the client requests a token
