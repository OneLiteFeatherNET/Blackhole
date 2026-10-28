## ADDED Requirements

### Requirement: One audit entry per staff action

Every successful staff action SHALL produce exactly one audit entry in the `staff_audit` table, recording the actor (Entra object id and display name snapshot), the surface, the action name, the target and the time. The entry SHALL be written after the action has succeeded. A failed insert MUST NOT roll back or change the action, and MUST be counted and logged without the payload.

#### Scenario: Revoke from the panel

- **WHEN** a moderator revokes a punishment from the panel
- **THEN** one entry with action `punishment.revoke`, surface `PANEL`, the moderator's oid and the case reference is written

#### Scenario: Audit insert fails

- **WHEN** the action succeeds and the audit insert fails
- **THEN** the action stays applied, the response carries `X-Audit: unrecorded`, and the failure counter increases

#### Scenario: Read-only request

- **WHEN** a request only reads data (for example opening a case)
- **THEN** no audit entry is written

### Requirement: Surface and actor are derived from the verified caller

The surface SHALL be derived by the backend from the verified caller: a `PROXY` caller that sends `onBehalfOf` gives `IN_GAME`; a `DISCORD` caller that sends `onBehalfOf` gives `DISCORD`; a human caller gives `PANEL` when it sends the panel surface header and `API` otherwise. The actor SHALL be the attributed staff oid, and `recorded_by_oid` SHALL be the oid of the caller that sent the request.

#### Scenario: In-game ban

- **WHEN** the proxy applies a ban with `onBehalfOf` set to a staff member
- **THEN** the entry has surface `IN_GAME`, the staff member as actor and the proxy's oid as `recorded_by_oid`

#### Scenario: Surface is not a request field

- **WHEN** a request body contains a `surface` field
- **THEN** the field is ignored and the surface is derived from the caller

### Requirement: Audit entries carry no Ban ID, IP address or raw UUID

The audit entry, its event, its link, its Discord message and the logs about it MUST NOT contain a Ban ID, an IP address or a raw player UUID. A target is named by its case short id, a hashed owner or a section or server name, and by the player name where one is needed.

#### Scenario: Ban ID is in the request

- **WHEN** a staff action is recorded for a case whose Ban ID is known to the backend
- **THEN** the entry, the event payload and the panel link contain no Ban ID value

#### Scenario: Event payload scan

- **WHEN** a `blackhole.events.staff.action` payload is inspected
- **THEN** it contains the fields of version 1 and no field carries a Ban ID, an IP address or a UUID that is not a staff oid

### Requirement: Staff action event

Each entry SHALL be published once on the subject `blackhole.events.staff.action` after it is stored, with the payload fields `auditId`, `occurredAt`, `surface`, `action`, `actorOid`, `actorName`, `targetKind`, `targetRef`, `targetName` and `panelPath`. The event MUST NOT carry free text such as a reason.

#### Scenario: Entry stored before publish

- **WHEN** an entry is published
- **THEN** the row with the same `auditId` already exists in `staff_audit`

#### Scenario: No free text in the event

- **WHEN** a punishment is revoked with a justification text
- **THEN** the event does not contain the justification text

### Requirement: Discord webhook delivery

The `discord` bot SHALL post every staff action event to the configured webhook in event order, one message at a time, and acknowledge an event only after its message has been accepted or finally rejected.

#### Scenario: Order kept

- **WHEN** three entries are published in order while the webhook is slow
- **THEN** the webhook receives the three messages in that order, one after another

### Requirement: Retries of webhook answers

The `discord` bot SHALL retry a `429` answer after the server's `retry_after` delay, and a `5xx` answer or a network error with exponential backoff capped at five minutes. Any other `4xx` answer SHALL NOT be retried; it SHALL be logged once with its status and the `auditId`, counted, and acknowledged.

#### Scenario: Rate limited

- **WHEN** the webhook answers `429` with `retry_after` of 2.5 seconds
- **THEN** the entry is delivered again after about 2.5 seconds and not dropped

#### Scenario: Server error

- **WHEN** the webhook answers `500` three times and then `204`
- **THEN** the entry is delivered on the fourth attempt with growing delays between the attempts

#### Scenario: Webhook deleted

- **WHEN** the webhook answers `404`
- **THEN** the entry is not retried, the status is logged with its `auditId` and not the URL, and the consumer moves on

### Requirement: Delivery never blocks a staff action

A delivery failure, or a bot that is offline, MUST NOT block, delay or roll back the staff action that produced the entry. The entry SHALL wait in the event stream until it is delivered or its retention ends.

#### Scenario: Staff action while the bot is down

- **WHEN** the bot is offline and a staff member revokes a punishment
- **THEN** the revoke succeeds and the entry is posted after the bot returns

### Requirement: Webhook URL is a validated secret

The bot SHALL accept `DISCORD_AUDIT_WEBHOOK_URL` only when it matches `https://discord.com/api/webhooks/<id>/<token>` or the `discordapp.com` equivalent. A missing or non-matching value SHALL stop the bot at startup with a message that names the variable and not its value. The URL MUST NOT appear in any log line, metric label or exception message.

#### Scenario: Malformed URL

- **WHEN** the bot starts with `http://example.test/hook` as the webhook URL
- **THEN** startup fails with a message naming `DISCORD_AUDIT_WEBHOOK_URL` and the URL is not printed

#### Scenario: Log scan

- **WHEN** a delivery fails with a `500` answer
- **THEN** no log line contains the webhook URL or its token

### Requirement: Each entry links to the team panel

Each Discord message SHALL end with a direct link `<panel base URL><panelPath>`, where `panelPath` is `/cases/<case short id>` for a case target and `/players/<owner>` for a player target. The panel base URL is the configured `BLACKHOLE_PANEL_URL`.

#### Scenario: Revoke links to the case

- **WHEN** a case is revoked
- **THEN** the message ends with the link to `/cases/<short id>` of that case

#### Scenario: Action without a case

- **WHEN** a client rule is changed
- **THEN** the message links to the client rule catalogue page of the panel and carries no case reference

### Requirement: Retention and anonymization of audit rows

Audit rows older than the configured retention (default one year) SHALL be deleted in batches by a sweeper that uses the injected clock. When a staff member is anonymized, the `actor_name` of that member's rows SHALL be cleared and the `actor_oid` kept.

#### Scenario: Row past retention

- **WHEN** the sweeper runs with a clock one year and one day after a row was written
- **THEN** that row is deleted and a row written a day earlier stays

#### Scenario: Staff anonymized

- **WHEN** an administrator anonymizes a staff member
- **THEN** the member's audit rows keep the oid and have no display name

### Requirement: Audit read route for administrators

`GET /admin/audit` SHALL return the audit entries newest first, cursor-paginated with a `limit` between 1 and 100 and an opaque `cursor`, and SHALL be available to ADMIN only. Other roles SHALL receive 403 and no rows.

#### Scenario: Moderator reads the audit

- **WHEN** a MODERATOR requests `GET /admin/audit`
- **THEN** the response is 403

#### Scenario: Paging

- **WHEN** an ADMIN requests the first page with `limit` 2 and then the next cursor
- **THEN** the pages do not overlap and together contain the entries in newest-first order
