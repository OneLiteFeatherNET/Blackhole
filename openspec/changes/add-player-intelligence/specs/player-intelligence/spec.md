## ADDED Requirements

### Requirement: Session record per login attempt
The system SHALL store one session record for every login attempt that reaches the backend, with a client-generated `sessionId`, the owner hash, the name seen at login, the clear IP address, the protocol version, the virtual host, the outcome (`ALLOWED` or `DENIED`) and the start time.

#### Scenario: Allowed login is stored
- **WHEN** a proxy reports a start for an allowed login with a new `sessionId`
- **THEN** a session record exists with the owner hash, the clear IP, the outcome `ALLOWED` and no end time

#### Scenario: Denied login is stored
- **WHEN** a proxy reports a start with the outcome `DENIED`
- **THEN** the record is stored with the outcome `DENIED`

#### Scenario: Repeated start is idempotent
- **WHEN** the same `sessionId` is reported twice
- **THEN** exactly one record exists and the response returns that record

### Requirement: Session end and client brand updates
The system SHALL accept an update for an existing session that sets its end time, its client brand and its client version, and SHALL keep the first end time it receives.

#### Scenario: Disconnect sets the end
- **WHEN** a proxy reports the end of a stored session
- **THEN** the session's end time is set

#### Scenario: Brand arrives after login
- **WHEN** a proxy reports the client brand `vanilla` and version `1.21.4` for a stored session
- **THEN** the session shows that brand and version

#### Scenario: Update for an unknown session
- **WHEN** an update names a `sessionId` that is not stored
- **THEN** the response is a not-found error and nothing is written

### Requirement: Offline enrichment of the IP address
For each new session with an IP address, the system SHALL look up the autonomous system number, its organisation and the country code in a local MaxMind DB file configured with `blackhole.intel.geoip.asn-path` and `blackhole.intel.geoip.city-path`. The lookup MUST NOT make any network request.

#### Scenario: Known address is enriched
- **WHEN** a session is stored for an IP that the ASN file contains
- **THEN** the session carries that ASN, its organisation and the country code

#### Scenario: Unknown address is stored without enrichment
- **WHEN** a session is stored for an IP that no file contains
- **THEN** the enrichment fields are empty and the session is still stored

#### Scenario: Missing database file
- **WHEN** the configured file does not exist or cannot be read
- **THEN** sessions are stored without enrichment, the failure is logged at most once per hour, and no report is rejected

#### Scenario: Refreshed file is used without restart
- **WHEN** the file's modification time changes
- **THEN** the next lookup after the hourly check reads the new file

### Requirement: Session retention
The system SHALL delete session records whose start time is older than `blackhole.intel.retention-days` (default 90) on the `blackhole.intel.retention-sweep-cron` schedule (default 03:45 daily), and MUST NOT keep them longer.

#### Scenario: Old session is deleted
- **WHEN** the sweep runs and a session started 100 days ago exists
- **THEN** that session is deleted

#### Scenario: Recent session is kept
- **WHEN** the sweep runs and a session started 10 days ago exists
- **THEN** that session is kept

### Requirement: Sessions read for a player
The system SHALL answer `GET /intel/players/{owner}/sessions` for VIEWER and above with the newest sessions of that owner hash first, 50 per page by default, using the standard page parameters. Proxy callers MUST be refused.

#### Scenario: Viewer reads sessions
- **WHEN** a viewer requests the sessions of an owner with three stored sessions
- **THEN** the response lists those three sessions newest first with IP, enrichment, brand, protocol, virtual host, outcome and times

#### Scenario: Proxy reads sessions
- **WHEN** a proxy requests the sessions of an owner
- **THEN** the request is refused

#### Scenario: Malformed owner
- **WHEN** the owner is not a 128-character hex string
- **THEN** the response is a validation error

### Requirement: Session events carry no address
The domain events for sessions (`session.started`, `session.ended`) MUST carry the owner hash and identifiers only; they MUST NOT carry an IP address, a token or a player name.

#### Scenario: Started event payload
- **WHEN** a session is stored
- **THEN** the published `session.started` event contains the owner hash and the session id, and no IP address

### Requirement: Intelligence can be switched off
When `blackhole.intel.enabled` is false the session routes MUST answer 404, Velocity's reports MUST be dropped without affecting the login, and the existing ban and evasion paths MUST keep working.

#### Scenario: Switched off
- **WHEN** `blackhole.intel.enabled` is false and a proxy reports a session
- **THEN** the backend answers 404 and the login is unaffected
