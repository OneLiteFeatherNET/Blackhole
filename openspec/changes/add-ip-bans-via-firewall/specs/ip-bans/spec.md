## ADDED Requirements

### Requirement: IP ban covers one address or one range
The system SHALL store an IP ban for a single IPv4 or IPv6 address, or for a CIDR range, normalised to its canonical text with a prefix length (a single address carries `/32` or `/128`). Ranges shorter than `/16` for IPv4 or `/32` for IPv6 MUST be refused. The system MUST store the clear value, because the firewall and the proxy compare addresses.

#### Scenario: Single IPv4 address
- **WHEN** a moderator bans `203.0.113.7`
- **THEN** the ban is stored as `203.0.113.7/32`

#### Scenario: Range too wide
- **WHEN** a ban is requested for an IPv4 range shorter than `/16`
- **THEN** the response is HTTP 422 with the code `IPBAN_RANGE_TOO_WIDE` and nothing is stored

#### Scenario: IPv6 range
- **WHEN** a ban is requested for `2001:db8::/48`
- **THEN** the ban is stored with that prefix

### Requirement: Protected networks cannot be banned
The system MUST refuse an IP ban that overlaps a protected network. The protected networks are loopback, link-local, the RFC 1918 and unique-local ranges, and the ranges listed in `blackhole.ipban.protected-networks`.

#### Scenario: Loopback refused
- **WHEN** a ban is requested for `127.0.0.1`
- **THEN** the response is HTTP 422 with the code `IPBAN_PROTECTED` and nothing is stored

#### Scenario: Operator-protected range refused
- **WHEN** a ban is requested for an address inside a range the operator listed as protected
- **THEN** the response is HTTP 422 with the code `IPBAN_PROTECTED`

### Requirement: Creating an IP ban by role
A MODERATOR SHALL be able to create an IP ban only when it expires within `blackhole.ipban.moderator-max-duration`. An ADMIN SHALL be able to create a permanent IP ban. The role check MUST be enforced in the service, not only in the controller.

#### Scenario: Moderator temporary ban
- **WHEN** a moderator creates an IP ban that expires in 7 days
- **THEN** the ban is created and its source is `COMMAND` or `PANEL` as requested

#### Scenario: Moderator permanent ban
- **WHEN** a moderator creates a permanent IP ban
- **THEN** the response is HTTP 403 with the code `FORBIDDEN_DURATION` and nothing is stored

#### Scenario: Admin permanent ban
- **WHEN** an admin creates a permanent IP ban
- **THEN** the ban is created without an expiry

### Requirement: One active ban per range
The system MUST NOT hold two active IP bans for the same canonical range.

#### Scenario: Same range twice
- **WHEN** an address that is already actively banned is banned again
- **THEN** the response is HTTP 409 with the code `ALREADY_BANNED` and the existing ban is unchanged

### Requirement: Overlapping ranges are accepted with a warning
A new IP ban that lies inside an active range SHALL be accepted, and the response SHALL carry a warning naming the wider ban.

#### Scenario: Narrow ban inside a wide one
- **WHEN** a single address inside an active `/24` ban is banned
- **THEN** the ban is created and the response has a `warnings` entry that names the `/24` ban

### Requirement: Lifting an IP ban
An IP ban SHALL be lifted through its own route with a mandatory reason of 1 to 200 characters, recording the lifting actor and time. Lifting an IP ban MUST NOT change any player punishment, and lifting a player punishment MUST NOT lift an IP ban.

#### Scenario: Lift with reason
- **WHEN** an admin lifts an IP ban with a reason
- **THEN** the ban is inactive, the lift is recorded with the actor's `oid` and the reason

#### Scenario: Lift without reason
- **WHEN** an IP ban is lifted without a reason
- **THEN** the response is HTTP 400 and the ban stays active

#### Scenario: Player punishment lifted
- **WHEN** the player punishment that created an IP ban is lifted
- **THEN** the IP ban stays active

### Requirement: Active list for proxies
The system SHALL serve the active IP bans at `GET /ipban/active` to callers with the `PROXY` role. An active ban is one that is not lifted and whose expiry, if any, lies after the current time. The response MUST contain the id, the canonical range, the expiry and the reason, and MUST NOT contain the actor.

#### Scenario: Expired and lifted bans are absent
- **WHEN** the proxy reads the list while one ban is lifted and one has expired
- **THEN** neither appears in the response

#### Scenario: Caller without the proxy role
- **WHEN** a VIEWER calls `GET /ipban/active`
- **THEN** the response is HTTP 403

### Requirement: Change events carry no address
IP ban events (`ipban.created`, `ipban.lifted`, `ipban.changed`) SHALL carry the ban identifier and the kind of change. They MUST NOT carry the address or range, because events leave the backend.

#### Scenario: Created event
- **WHEN** an IP ban is created
- **THEN** the published event contains the ban id and no IP address

### Requirement: Retention of lifted and expired rows
The system SHALL delete lifted and expired IP ban rows once `blackhole.ipban.retention` (default `P365D`) has passed since they were lifted or expired. Active rows MUST NOT be deleted by the retention sweep.

#### Scenario: Old lifted row
- **WHEN** the retention sweep runs and a row was lifted more than `blackhole.ipban.retention` ago
- **THEN** the row is deleted

#### Scenario: Recent lifted row
- **WHEN** the retention sweep runs and a row was lifted within the retention period
- **THEN** the row is kept

### Requirement: Staff only view of an address
The clear address of an IP ban MUST be shown only to callers with the `ADMIN`, `MODERATOR` or `VIEWER` role, and to the firewall through the token-gated feeds of `firewall-feed`. It MUST NOT appear in the public API, in any published event or in any log line written by the IP ban feature.

#### Scenario: Public API
- **WHEN** the public ban list is read
- **THEN** no IP ban appears in it
