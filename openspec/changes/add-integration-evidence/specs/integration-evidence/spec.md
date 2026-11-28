# integration-evidence Specification

## ADDED Requirements

### Requirement: Integration principal and registration
The backend SHALL accept detections only from a caller whose token carries the app role `Blackhole.Integration` AND whose
client id maps to an enabled `integration` registration. The `sourceSystem` of every accepted detection SHALL be the one
of that registration. Registrations SHALL be written only by an ADMIN, and each write SHALL be audited with the actor's
oid and without any secret.

#### Scenario: Ingest from a registered integration
- **WHEN** a token with role `Blackhole.Integration` and the client id of an enabled `vulcan` registration posts a valid detection
- **THEN** the detection is stored with `sourceSystem` `vulcan`

#### Scenario: Unauthorised principal refused
- **WHEN** a token without the role `Blackhole.Integration` (for example a `Blackhole.Proxy` token) posts a detection
- **THEN** the response is HTTP 403 and nothing is stored

#### Scenario: Unregistered or disabled client refused
- **WHEN** a token with the role `Blackhole.Integration` has a client id with no registration, or with a disabled one
- **THEN** the response is HTTP 403 and nothing is stored

#### Scenario: Body cannot name another source
- **WHEN** a `vulcan` integration posts an item whose `sourceSystem` is `grim`
- **THEN** that item is answered `REJECTED` with reason `SOURCE_MISMATCH` and nothing is stored for it

#### Scenario: Registration is audited
- **WHEN** an ADMIN creates or disables a registration
- **THEN** an audit entry records the action, the actor's oid and the registration id, and no client secret

### Requirement: Registration granularity and server scope
A registration SHALL list the CloudNet tasks (`allowedTasks`) and the static servers (`allowedStaticServers`) its client
may report for. The `server` label of a detection is reported by the bridge and MUST NOT be used to authenticate it.
A detection whose `server` label is outside the registration's scope SHALL be answered `REJECTED` with reason
`SERVER_OUT_OF_SCOPE`, and nothing is stored for it. A CloudNet service name is `<task>-<n>` with `<n>` digits.

#### Scenario: Dynamic service of an allowed task
- **WHEN** a registration with `allowedTasks` `Lobby` reports a detection with `server` `Lobby-2`
- **THEN** the detection is accepted

#### Scenario: Service of another task
- **WHEN** a registration with `allowedTasks` `Lobby` reports a detection with `server` `Survival-1`
- **THEN** the item is answered `REJECTED` with reason `SERVER_OUT_OF_SCOPE`

#### Scenario: Prefix lookalike
- **WHEN** a registration with `allowedTasks` `Lobby` reports a detection with `server` `Lobbyx-2`
- **THEN** the item is answered `REJECTED` with reason `SERVER_OUT_OF_SCOPE`

#### Scenario: Static server exact name only
- **WHEN** a registration with `allowedStaticServers` `Creative` reports a detection with `server` `Creative-2`
- **THEN** the item is answered `REJECTED` with reason `SERVER_OUT_OF_SCOPE`

#### Scenario: Label does not authenticate
- **WHEN** a token whose client id has no registration reports a detection with an in-scope `server` label
- **THEN** the response is HTTP 403 and nothing is stored

### Requirement: Integration principal has no read access
The `Blackhole.Integration` role SHALL grant only the detection submission route. It MUST NOT grant read access to
players, punishments, evidence, cases or other integrations' detections.

#### Scenario: Read attempt by an integration
- **WHEN** a token with role `Blackhole.Integration` requests a player's detections or a case
- **THEN** the response is HTTP 403

### Requirement: Envelope and schema validation
The backend SHALL accept a detection only when its envelope is valid (detection id, kind, schema version, occurrence time
within the 24-hour-back and 5-minute-forward window, server label of 1 to 64 characters, owner, summary of 1 to 200
characters) and its payload validates against the schema registered for (`sourceSystem`, `kind`, `schemaVersion`). The
backend MUST reject unknown fields, and it MUST store nothing for a rejected item.

#### Scenario: Ingest valid detection
- **WHEN** a registered `vulcan` integration posts a `DETECTION` version 1 whose payload matches the schema
- **THEN** the item is answered `ACCEPTED` and one `integration_detection` row is stored with the validated payload

#### Scenario: Optional fields may be absent or null
- **WHEN** a `DETECTION` version 1 payload has no `ping`, `tps`, `checkType` or `violationLevel`, or sends them as null
- **THEN** the item is answered `ACCEPTED` and the missing fields are stored as null

#### Scenario: Reject invalid schema
- **WHEN** a `DETECTION` version 1 payload has a missing required field, a string over its length limit or an extra field
- **THEN** the item is answered `REJECTED` with reason `SCHEMA_INVALID` and nothing is stored

#### Scenario: Reject unknown kind or version
- **WHEN** an item names a kind or schema version with no registered schema
- **THEN** the item is answered `REJECTED` with reason `UNKNOWN_KIND` and nothing is stored

#### Scenario: Reject a payload containing personal identifiers
- **WHEN** a payload string contains a UUID or an IP address literal
- **THEN** the item is answered `REJECTED` with reason `PII_IN_PAYLOAD` and nothing is stored

#### Scenario: Reject an occurrence time outside the window
- **WHEN** `occurredAt` is more than 24 hours in the past or more than 5 minutes in the future
- **THEN** the item is answered `REJECTED` with reason `OCCURRED_AT_OUT_OF_RANGE`

#### Scenario: Oversized body
- **WHEN** a request body exceeds 64 KiB or carries more than 50 items
- **THEN** the response is HTTP 413 and nothing is parsed or stored

### Requirement: Idempotent ingestion
A detection SHALL be unique per (integration, detection id). Re-sending an item that was already stored SHALL answer
`DUPLICATE` and SHALL NOT create a row or change the stored one.

#### Scenario: Duplicate detection is a no-op
- **WHEN** an integration re-posts a detection with an already stored detection id
- **THEN** the item is answered `DUPLICATE`, the row count does not change and the stored payload is unchanged

#### Scenario: Batch retry after a network failure
- **WHEN** an integration re-sends a whole batch whose first attempt was stored
- **THEN** every previously stored item is answered `DUPLICATE` and only the new items are stored

### Requirement: Batch answers per item
A batch SHALL be processed item by item. A failing item SHALL NOT prevent the others from being stored, and the response
SHALL list one result (status and reason code, if any) per item in request order.

#### Scenario: Partial batch success
- **WHEN** a batch of three items has a valid item, a schema-invalid item and a duplicate
- **THEN** the response lists `ACCEPTED`, `REJECTED` with `SCHEMA_INVALID` and `DUPLICATE` in that order, and only the first is stored

### Requirement: Detections are staff-only and retained for a bounded time
Detections SHALL be readable only through role-protected staff routes (`MODERATOR` and up). Unattached detections SHALL be
kept for the retention period `BLACKHOLE_INTEGRATION_DETECTION_RETENTION` (ISO-8601 duration, default `P180D`), and the
sweeper SHALL delete the ones older than that. The sweeper SHALL NOT touch attached ones.

#### Scenario: Retention sweep
- **WHEN** an unattached detection's `occurred_at` is older than the retention period
- **THEN** the sweeper deletes it and logs only the count

#### Scenario: Default retention
- **WHEN** no retention is configured
- **THEN** the retention period is 180 days

#### Scenario: Attached detection survives the sweep
- **WHEN** a detection older than the retention period has been attached as evidence
- **THEN** the sweeper leaves the detection and the evidence item in place

#### Scenario: Staff-only read
- **WHEN** an integration token requests detections of an owner
- **THEN** the response is HTTP 403

### Requirement: Erasure of integration detections
An erasure request for an owner SHALL delete that owner's unattached detections and the attached copies through the
evidence erasure path (reason `ERASURE`), in one request with one audit entry. No log line or domain event SHALL contain
a payload, summary, detail or owner value at any point of retention or erasure.

#### Scenario: Erasure of an owner
- **WHEN** an erasure is requested for an owner with detections, some attached
- **THEN** unattached detections are deleted and the attached evidence is removed through the evidence erasure path, with one audit entry

#### Scenario: No content in logs or events
- **WHEN** detections are received, attached, swept or erased
- **THEN** no log line or domain event contains a payload, summary, detail or owner value

### Requirement: Attaching detections to a case
A MODERATOR SHALL be able to attach one or more unattached detections of an owner to a punishment or open case. An attach
SHALL create one `evidence_items` row of kind `INTEGRATION` with `sourceSystem` and the validated payload as its snapshot,
and SHALL write one staff-only case event `detection.attached` carrying ids, the source and the count only.

#### Scenario: Attach creates evidence
- **WHEN** a moderator attaches a detection to a case
- **THEN** an `INTEGRATION` evidence item exists for the punishment, the detection points to it, and the case timeline shows `detection.attached`

#### Scenario: Attach is idempotent
- **WHEN** the same detection is attached twice
- **THEN** the second attach changes nothing and creates no second evidence item

#### Scenario: Detections are not attached automatically
- **WHEN** a detection is stored for a player with an open case
- **THEN** no evidence item is created and no case event is written

#### Scenario: Timeline stays content-free
- **WHEN** a `detection.attached` event is shown in the case timeline
- **THEN** it shows the source and count, not the check name, detail or any payload value

### Requirement: Detections never feed ELO or punishments
Integration detections MUST NOT change any ELO score (chat or gameplay), MUST NOT create, change or lift a punishment,
MUST NOT start, decide or change an appeal, and MUST NOT publish anything outside the staff panel. The ingestion path
and the attach path MUST NOT call the ELO engine or the punishment or appeal services. Any future use of detections for
these purposes requires an amendment of principle 4 in `openspec/config.yaml` and its own change.

#### Scenario: Flood of detections
- **WHEN** a registered integration posts 200 detections for one owner
- **THEN** no ELO score changes, no punishment is created and the inbox shows only a count

#### Scenario: Attach does not decide
- **WHEN** a moderator attaches a detection to a case
- **THEN** the punishment, its ELO scores and its appeal state are unchanged

### Requirement: Renderer registry with generic fallback
The staff panel SHALL render an integration detection through a component registered for its (`sourceSystem`, `kind`,
`schemaVersion`). A triple without a registered component SHALL render through the generic renderer, which shows every
validated field as a labelled text value. Components SHALL be reviewed repository code loaded as chunks; the panel SHALL
NOT load a component from the API, a URL or a third-party bundle.

#### Scenario: Known kind uses its component
- **WHEN** a `vulcan` `DETECTION` version 1 is shown
- **THEN** the `VulcanDetectionCard` component renders it

#### Scenario: Unknown kind uses generic renderer
- **WHEN** a detection of a kind or version without a registered component is shown
- **THEN** the generic renderer shows each validated payload field as a label and text value, and the card names the source

#### Scenario: Unknown component is never fetched
- **WHEN** the panel renders any integration detection
- **THEN** it performs no request for a component, script or stylesheet outside the bundled chunks

### Requirement: Appeal reviewers see attached integration evidence
Appeal reviewers SHALL see integration evidence that is attached to the punishment under appeal, through the existing
evidence path, marked with its source and as reported. They SHALL NOT see unattached detections. Attached integration
evidence follows the existing evidence rules, including the `LIFTED` and `REVOKED` marking.

#### Scenario: Reviewer sees attached evidence
- **WHEN** an appeal reviewer opens an appeal whose punishment has an attached `INTEGRATION` evidence item
- **THEN** the item is listed with its source, its kind and the trust line "reported, not verified"

#### Scenario: Unattached detections stay hidden from reviewers
- **WHEN** an appeal reviewer opens the same appeal and the owner has unattached detections
- **THEN** the unattached detections are not listed

#### Scenario: Lifted punishment keeps its marked evidence
- **WHEN** a granted full-lift appeal lifts the punishment
- **THEN** its integration evidence is kept and marked `LIFTED`, as for other evidence

### Requirement: No HTML from integrations
Integration text (summary, detail, check names, payload strings) SHALL be rendered as text only. The panel MUST NOT use
`v-html` or any markdown or HTML parser for integration data, and a URL inside integration text SHALL be shown as text,
not as a link.

#### Scenario: Markup in a detail
- **WHEN** a detail contains `<img src=x onerror=alert(1)>`
- **THEN** the panel shows the characters as text and creates no element from them

#### Scenario: URL in a summary
- **WHEN** a summary contains a URL
- **THEN** the URL is shown as plain text and is not rendered as a link

### Requirement: Trust wording on every integration element
Every component that shows an integration detection SHALL name the reporting integration, state that the item is a signal
and not a verdict, and show any severity as the integration's reported value. The attach action SHALL be labelled as an
attach, not as a punishment.

#### Scenario: Vulcan card wording
- **WHEN** a `VulcanDetectionCard` is shown
- **THEN** it displays "Reported by Vulcan", the signal-not-verdict line and the reported severity or VL

### Requirement: Accessible and localised integration components
Integration components SHALL meet the keyboard, focus and text-alternative requirements of the admin UI. Severity and VL
SHALL NOT be conveyed by colour alone, and every chart SHALL have a text alternative. All component labels SHALL exist
in `de` and `en`, with the existing key-parity test covering them. Numbers and times SHALL use the active locale.

#### Scenario: Chart has a text alternative
- **WHEN** the VL chart of a `VulcanDetectionCard` is rendered
- **THEN** the same values are available as a table for assistive technology

#### Scenario: Missing translation fails the build
- **WHEN** a `de` key of an integration component is missing
- **THEN** the key-parity test fails
