# Spec Delta

## MODIFIED Requirements

### Requirement: Single-origin deployment
The UI SHALL be served by a Nitro server that also forwards requests under `/api/` to the backend at `BLACKHOLE_BACKEND_URL` (read from the environment at container start, default `http://backend:8080`) with the `/api` prefix removed, so the browser talks to one origin and the backend requires no cross-origin configuration.

#### Scenario: API call from the browser
- **WHEN** the UI requests `/api/player/resolve/Steve`
- **THEN** the request goes to the UI's own origin and the server forwards it to `<backend>/player/resolve/Steve` with its method, query and body, and answers with `Cache-Control: no-store`

#### Scenario: Backend URL comes from the runtime environment
- **WHEN** the container is started with `BLACKHOLE_BACKEND_URL=http://other:9000` without rebuilding the image
- **THEN** `/api/x` is forwarded to `http://other:9000/x`

#### Scenario: Path cannot change the target
- **WHEN** a request path contains `..`, an encoded slash, a backslash or a `//host` authority after `/api/`
- **THEN** the server answers 400 and sends no request to any host

#### Scenario: Backend unreachable
- **WHEN** the backend refuses the connection or does not answer within the timeout
- **THEN** the server answers 502 or 504 and keeps running

#### Scenario: Evidence upload
- **WHEN** the UI uploads a video of 100 MiB to `/api/evidence/file`
- **THEN** the body is streamed to the backend without a size limit in the UI server and with a 120 second timeout

## ADDED Requirements

### Requirement: Static files and single-page fallback
The server SHALL serve the built UI files, answer any other `GET` path that is not an asset, `/api/` or an operational route with `index.html` and status 200, and cache content-hashed files under `/assets/` for one year as immutable while `index.html` is revalidated.

#### Scenario: Deep link
- **WHEN** a browser requests `/players/abc123`
- **THEN** `index.html` is returned with status 200 and `Cache-Control: no-cache`

#### Scenario: Hashed asset
- **WHEN** a browser requests an existing file under `/assets/`
- **THEN** it is returned with `Cache-Control: public, max-age=31536000, immutable`

#### Scenario: Missing asset
- **WHEN** a browser requests a file under `/assets/` that does not exist
- **THEN** the server answers 404 and not `index.html`

### Requirement: Runtime configuration with fail-fast validation
The server SHALL read its `BLACKHOLE_UI_*` settings and `BLACKHOLE_BACKEND_URL` from the environment once at startup, validate them with strict patterns, and serve the result as `GET /config.json` with `Cache-Control: no-store`. When a value is missing or malformed the process MUST exit non-zero before accepting connections and name every offending variable.

#### Scenario: Valid configuration
- **WHEN** the container starts with a UUID in `BLACKHOLE_UI_SOURCE`
- **THEN** `GET /config.json` returns `source` and `reviewer` (defaulting to the source) as JSON

#### Scenario: Missing required variable
- **WHEN** the container starts without `BLACKHOLE_UI_SOURCE`
- **THEN** it exits non-zero with a message naming `BLACKHOLE_UI_SOURCE` and never starts listening

#### Scenario: Injection attempt
- **WHEN** `BLACKHOLE_UI_SOURCE` contains `"` or other characters outside the UUID pattern
- **THEN** startup fails and no config is served

#### Scenario: Evidence limits
- **WHEN** `BLACKHOLE_UI_EVIDENCE_MAX_IMAGE_BYTES=2048` and `BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES=image/png,video/mp4` are set
- **THEN** `/config.json` contains `evidence.maxImageBytes` 2048 and `evidence.allowedTypes` `["image/png","video/mp4"]`

#### Scenario: Malformed backend URL
- **WHEN** `BLACKHOLE_BACKEND_URL` is not an `http` or `https` URL
- **THEN** startup fails with a message naming `BLACKHOLE_BACKEND_URL`

### Requirement: Security headers
Every response of the server, including static files, the single-page fallback, API proxy responses and errors, SHALL carry `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` and a `Content-Security-Policy` of `default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'`, and MUST NOT disclose the server software in `Server` or `X-Powered-By`.

#### Scenario: Headers on the start page
- **WHEN** a browser requests `/`
- **THEN** the response carries the four headers

#### Scenario: Headers on a proxied response
- **WHEN** `/api/x` is forwarded
- **THEN** the response carries the four headers as well

#### Scenario: No software disclosure
- **WHEN** any response is inspected
- **THEN** it has neither `Server` nor `X-Powered-By`

### Requirement: Health endpoint and container runtime
The server SHALL answer `GET /healthz` with status 200 and body `ok`, and the container image SHALL run the server as a non-root user on port 8080 with a healthcheck against `/healthz`.

#### Scenario: Health check
- **WHEN** `GET /healthz` is requested
- **THEN** the answer is 200 with body `ok`

#### Scenario: Non-root
- **WHEN** the container is running
- **THEN** the server process runs with a uid other than 0

#### Scenario: Read-only root filesystem
- **WHEN** the container runs with a read-only root filesystem
- **THEN** it starts and serves requests, because it writes no files
