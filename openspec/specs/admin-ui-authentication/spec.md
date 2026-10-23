# admin-ui-authentication Specification

## Purpose
Defines how staff sign in to the admin web UI with Microsoft Entra ID, how tokens are held and renewed in the browser, and how the UI adapts to the signed-in role.

## Requirements

### Requirement: Sign-in with Authorization Code and PKCE
The UI SHALL authenticate staff through the OIDC Authorization Code flow with PKCE against the configured authority and client id, and MUST NOT use the implicit flow. Authority, client id and API scope SHALL be read from `/config.json`, which the web server serves from `BLACKHOLE_UI_OIDC_AUTHORITY`, `BLACKHOLE_UI_OIDC_CLIENT_ID` and `BLACKHOLE_UI_API_SCOPE`; the web server MUST refuse to start when one is missing or malformed.

#### Scenario: Unauthenticated visit
- **WHEN** a visitor without a session opens any UI route
- **THEN** the browser is redirected to the authority with a PKCE code challenge

#### Scenario: Callback completes sign-in
- **WHEN** the authority redirects back with a valid code and state
- **THEN** the UI exchanges it, stores the user in memory and shows the originally requested route

#### Scenario: Misconfigured web server
- **WHEN** the web server starts without `BLACKHOLE_UI_OIDC_CLIENT_ID`
- **THEN** it exits with a message naming the variable

### Requirement: Tokens stay in memory
The UI MUST keep access and refresh tokens only in JavaScript memory and MUST NOT write them to `localStorage`, `sessionStorage`, IndexedDB or cookies. Only the short-lived OIDC `state` and PKCE verifier MAY be kept in `sessionStorage` across the redirect, and they MUST be removed when the callback completes.

#### Scenario: Storage inspection after sign-in
- **WHEN** a user is signed in
- **THEN** no browser storage contains an access or refresh token

#### Scenario: Page reload
- **WHEN** the page is reloaded
- **THEN** the in-memory token is gone and the UI signs the user in again through the authority

### Requirement: Silent renewal
The UI SHALL renew the access token before it expires without user interaction, and MUST send the user through sign-in again when renewal fails.

#### Scenario: Token nearing expiry
- **WHEN** the access token is about to expire while the user works
- **THEN** a new token is obtained and requests continue without a visible interruption

#### Scenario: Renewal rejected
- **WHEN** the authority refuses renewal
- **THEN** the UI redirects to sign-in

### Requirement: Authenticated API calls
Every API request SHALL carry `Authorization: Bearer`. On HTTP 401 the UI MUST renew once and retry once, then sign in again; on HTTP 403 it MUST show a "not permitted" message and MUST NOT retry.

#### Scenario: Expired token during a request
- **WHEN** a request is answered 401 and renewal succeeds
- **THEN** the request is repeated once with the new token

#### Scenario: Forbidden action
- **WHEN** a request is answered 403
- **THEN** a not-permitted message is shown and no retry is made

### Requirement: Role-aware interface
After sign-in the UI SHALL load the caller from `GET /auth/me` and MUST NOT decode tokens itself. It SHALL show the signed-in display name, hide or disable actions the role cannot perform, and MUST NOT send `source`, `resolvedBy` or `reviewerId`. The "Acting as" label and the `BLACKHOLE_UI_SOURCE` / `BLACKHOLE_UI_REVIEWER` settings SHALL no longer exist.

#### Scenario: Viewer opens a player
- **WHEN** a viewer opens a player detail page
- **THEN** punishments are listed but ban, unban and adjust controls are not offered

#### Scenario: Admin opens configuration
- **WHEN** an admin opens the sections page
- **THEN** create, edit and disable controls are offered

### Requirement: No-access page
When `GET /auth/me` returns an empty role list, the UI SHALL show a "no access" page with the sign-out action and MUST NOT request any other protected data.

#### Scenario: Signed-in user without any role
- **WHEN** `GET /auth/me` returns an empty role list for the signed-in user
- **THEN** the UI shows a "no access" page with the sign-out action and no data requests are made

### Requirement: Sign-out
The UI SHALL provide sign-out that clears the in-memory user and ends the authority session.

#### Scenario: User signs out
- **WHEN** the user chooses sign-out
- **THEN** the in-memory user is cleared and the browser is sent to the authority's end-session endpoint
