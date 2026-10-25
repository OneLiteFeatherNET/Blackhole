## MODIFIED Requirements

### Requirement: Bearer token validation
The backend SHALL require `Authorization: Bearer <JWT>` on every route except the public routes, and MUST accept a token only if its signature verifies against the configured JWKS, its `iss` equals the configured issuer, its `aud` equals the configured API audience and it is not expired. A request with a missing or invalid token MUST be answered with HTTP 401 as an RFC 9457 problem (`application/problem+json`, see `api-problem-details`) and MUST NOT reach a controller. The backend MUST keep no session and set no cookie.

#### Scenario: Valid token
- **WHEN** a request carries a correctly signed, unexpired token with the configured issuer and audience
- **THEN** the request is authenticated and routed

#### Scenario: Missing token
- **WHEN** a request to a protected route carries no `Authorization` header
- **THEN** the response is HTTP 401 as a problem body

#### Scenario: Wrong issuer, audience or signature
- **WHEN** the token has another issuer, another audience, a signature from an unknown key, or `alg` `none`
- **THEN** the response is HTTP 401 as a problem body

#### Scenario: Expired token
- **WHEN** the token's `exp` is in the past
- **THEN** the response is HTTP 401 as a problem body
