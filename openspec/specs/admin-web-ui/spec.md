# admin-web-ui Specification

## Purpose
Provides a browser-based management interface for admins and staff to configure sections and templates and to review players, reports and appeals without calling the API by hand.

## Requirements

### Requirement: Material Design 3 appearance
The UI SHALL use Material Design 3 color roles, type scale, shapes and elevation derived from a single seed color, and SHALL support light and dark schemes following the operating system preference with a manual override that is remembered.

#### Scenario: Dark mode
- **WHEN** the operating system prefers dark mode and no override is set
- **THEN** the UI renders with the dark MD3 scheme

#### Scenario: Manual override persists
- **WHEN** a user switches to light mode and reloads the page
- **THEN** the light scheme is still applied

### Requirement: Localised interface
The UI SHALL provide all texts in German and English, defaulting to German, with a language switch that is remembered.

#### Scenario: Switch to English
- **WHEN** a user selects English
- **THEN** all navigation, labels and messages are shown in English

### Requirement: Dashboard
The UI SHALL show a dashboard with active punishments per section, open reports, pending appeals and the most recent automatic permanent escalations.

#### Scenario: Open dashboard
- **WHEN** a user opens the UI start page
- **THEN** the counts and the latest escalations are displayed

### Requirement: Player lookup and detail
The UI SHALL let users look up a player by name and show the player's score per section against its soft and permanent thresholds, active punishments per section, punishment history and ELO history.

#### Scenario: Look up a player
- **WHEN** a user searches for `Steve`
- **THEN** the player detail shows Steve's section scores, active punishments and history

#### Scenario: Unknown player
- **WHEN** the name cannot be resolved
- **THEN** the UI shows a not-found message and no detail page

### Requirement: Staff actions from the player detail
The player detail SHALL allow applying a template, revoking an active punishment per section and adjusting a section score with a mandatory note, each after a confirmation that previews the resulting score and whether it will cause a permanent escalation.

#### Scenario: Apply template with escalation preview
- **WHEN** a user selects a template whose ELO cost would drop the score below the permanent threshold
- **THEN** the confirmation shows the escalation step that will be used and warns that the player will be permanently punished in that section before anything is applied

#### Scenario: Revoke permanent ban
- **WHEN** a user confirms revoking a permanent punishment
- **THEN** the punishment is lifted and the detail shows the score reset to the section's pardon score

### Requirement: Template management
The UI SHALL list, create, edit and delete templates with key, reason, section, escalation group, escalation window and an ordered list of steps (duration and ELO cost each, add/remove/reorder), showing validation errors returned by the API next to the affected field. Deleting a template still referenced MUST show the conflict instead of failing silently.

#### Scenario: Create template
- **WHEN** a user submits a valid template form
- **THEN** the template appears in the list

#### Scenario: Invalid duration
- **WHEN** a user submits a template with a step that has no positive duration
- **THEN** that step's duration field shows the validation error and nothing is saved

#### Scenario: Edit escalation steps
- **WHEN** a user adds a third step P30D/250 to template `hacking` and saves
- **THEN** the template list shows three steps for `hacking`

### Requirement: Section configuration
The UI SHALL list sections and let users create, edit and disable them, editing enforcement kind, server patterns (only for SERVER sections), base score, soft threshold and duration, permanent threshold, pardon score and permanent reason.

#### Scenario: Change permanent threshold
- **WHEN** a user changes the `network` permanent threshold to 250 and saves
- **THEN** the section list shows the new threshold

#### Scenario: Create a server section
- **WHEN** a user creates section `bedwars` with enforcement SERVER and pattern `bedwars-*`
- **THEN** the section list shows `bedwars` with its pattern

### Requirement: Report and appeal queues
The UI SHALL list open reports and pending appeals and let users resolve reports and decide appeals with the decisions the API supports.

#### Scenario: Grant an appeal
- **WHEN** a user grants a full lift on a pending appeal
- **THEN** the appeal leaves the pending queue and the punishment is lifted

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
- **THEN** the body is streamed to the backend (the UI server caps request bodies at 128 MiB on this route, 413 above, and 1 MiB on other API routes) with a 120 second timeout

### Requirement: No personal data retained by the UI
The UI MUST NOT persist player names, UUIDs or owner hashes in browser storage; only theme, language and the optional acting-as label MAY be stored.

#### Scenario: Reload after lookup
- **WHEN** a user looks up a player and reloads the browser
- **THEN** no player identifier is restored from browser storage

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

### Requirement: Evasion hints on the player page
The player page SHALL show a "Possible alt accounts" panel, for every role that may open the page, built from `GET /evasion/{owner}/sightings`. Each linked account SHALL link to its player page and show the reason, the number of shared addresses and the first and last sighting. The panel MUST NOT show an IP, a token or a full owner hash as text, SHALL say when detection is switched off instead of claiming there are no alts, and SHALL announce its state in a polite live region.

#### Scenario: Linked account
- **WHEN** the backend lists one linked owner
- **THEN** the panel shows a link to that player with the reason in words

#### Scenario: Nothing linked
- **WHEN** the backend lists no linked owners and detection is enabled
- **THEN** the panel says no other account shared an address in the detection window

### Requirement: Import panel requires a template
The vanilla import panel on the admin page SHALL require a template before it offers the preview or the import, offering only templates whose section is not a mute section. It SHALL let the admin map an original reason of the file to another template, send only complete mappings, show the template each reason gets (as a table with the preview and after the import) and name the template in the confirmation. Texts MUST be available in German and English.

#### Scenario: No template chosen
- **WHEN** a list is pasted but no template is chosen
- **THEN** the preview and import buttons are disabled

#### Scenario: Mute templates are not offered
- **WHEN** the template picker is opened
- **THEN** the templates of mute sections are not among the options

#### Scenario: Preview shows the template of each reason
- **WHEN** the preview finishes
- **THEN** a table lists each original reason with its template and count

### Requirement: Imported punishments section
The admin page SHALL have an "Importierte Strafen" (Imported punishments) section listing the imported punishments that have no template, grouped by original reason, with a template picker per group (mute templates not offered) and a link action that is disabled until a template is picked. Applying SHALL ask for confirmation naming the count, the reason and the template, send nothing before it is given, show the backend message in the dialog when refused and otherwise reload the list. The section SHALL say so when every imported punishment has a template.

#### Scenario: Link a group
- **WHEN** an admin picks a template for the group `Hacking` and confirms
- **THEN** the reason and template are sent once and the list is reloaded

#### Scenario: Nothing to link
- **WHEN** no imported punishment is without a template
- **THEN** the section says every imported punishment is linked
