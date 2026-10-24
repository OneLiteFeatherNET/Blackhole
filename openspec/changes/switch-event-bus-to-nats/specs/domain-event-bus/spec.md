# Spec Delta

## Purpose

Defines the domain event bus that carries Blackhole's content-free events between the backend and its consumers: the transport (NATS JetStream), subjects, durable consumers, deduplication, credentials and the privacy rules for what an event or subject may contain.

## ADDED Requirements

### Requirement: Events travel on NATS subjects derived from the event type
The backend SHALL publish every domain event to the subject `blackhole.events.<eventType>`, where `<eventType>` is the event's dot-separated type (for example `blackhole.events.punishment.created`), with the unchanged `DomainEvent` JSON envelope as the body. A subject MUST contain nothing but that fixed prefix and the event type, and an event type that is empty or contains whitespace, `*` or `>` MUST NOT be published.

#### Scenario: Subject for a punishment event
- **WHEN** the backend publishes a `punishment.created` event
- **THEN** a message arrives on `blackhole.events.punishment.created` whose body is the `DomainEvent` envelope

#### Scenario: No identifier in a subject
- **WHEN** any event is published
- **THEN** its subject contains no owner hash, punishment identifier, Ban ID, raw player UUID or Discord id

#### Scenario: Illegal event type
- **WHEN** a caller asks to publish an event type containing a space or a wildcard
- **THEN** nothing is published, the problem is logged and the calling write path is unaffected

### Requirement: Events are kept in a bounded JetStream stream
The system SHALL capture `blackhole.events.>` in a file-backed JetStream stream `BLACKHOLE_EVENTS` with limits retention, a maximum age of seven days by default, a maximum size, and deletes and purges denied. The backend SHALL create or update the stream idempotently at startup.

#### Scenario: Old events expire
- **WHEN** an event is older than the configured maximum age
- **THEN** it is no longer delivered to any consumer

#### Scenario: Restart is idempotent
- **WHEN** the backend starts twice against the same server
- **THEN** the stream exists once with the configured settings and no error is raised

### Requirement: Publishing never blocks and never fails the write
Publishing SHALL be asynchronous and SHALL NOT block the calling thread, and a broker failure SHALL be logged and MUST NOT fail or roll back the request that raised the event.

#### Scenario: Broker unavailable
- **WHEN** NATS is unreachable while a profile is created over HTTP
- **THEN** the request succeeds and an error naming the event type is logged

### Requirement: Publisher deduplication
The publisher SHALL send the event's `eventId` as the `Nats-Msg-Id` header so that a retried publish of the same event within the stream's duplicate window is stored once.

#### Scenario: Retried publish
- **WHEN** the same `DomainEvent` is published twice within the duplicate window
- **THEN** the stream holds one message for it

### Requirement: Durable consumers replace the durable queues
Consumers SHALL use named durable pull consumers with explicit acknowledgement and subject filters equal to the routing keys the former queues were bound to: `blackhole-redis-sync`, `blackhole-discord-notify` and `blackhole-discord-case` (filters listed in `design.md` D4). All backend replicas SHALL share the single durable consumer `blackhole-redis-sync`, so each event is handled by one replica.

#### Scenario: Replicas share work
- **WHEN** two backend replicas run and ten matching events are published
- **THEN** each event is handled by exactly one of them

#### Scenario: Consumer catches up
- **WHEN** the Discord bot is down for an hour and events keep arriving
- **THEN** after it reconnects it receives the stored events of its filters

### Requirement: Redis sync stays best effort
The Redis sync consumer SHALL acknowledge each message after a single handling attempt, log a handler failure, and MUST NOT redeliver the message.

#### Scenario: Handler failure
- **WHEN** handling a `punishment.created` event throws
- **THEN** the error is logged and the message is acknowledged

### Requirement: Discord bot retry semantics are preserved
The bot SHALL acknowledge a message whose outcome is done or dropped, and for a retry outcome SHALL negatively acknowledge it with a ten second delay. It MUST limit unacknowledged messages to five per consumer and keep serving commands while NATS is unreachable.

#### Scenario: Retry later
- **WHEN** the handler answers retry for a message
- **THEN** the message is redelivered after about ten seconds and not before

#### Scenario: Broker down
- **WHEN** NATS is unreachable
- **THEN** the bot keeps answering Discord commands and reconnects automatically

### Requirement: Events carry no content
Event payloads and subjects SHALL contain only ids, hashes and non-content facts, never message or note text, Ban IDs, raw player UUIDs, IP addresses, file names or Discord user ids.

#### Scenario: Case message event
- **WHEN** a case message is added
- **THEN** the published payload holds the message id, author kind and channel but not the text

### Requirement: Per-client credentials with least privilege
Every client SHALL connect with its own NATS credentials from a `.creds` file (`NATS_CREDS_FILE`) in the dedicated `BLACKHOLE` account. Only the backend MAY publish to `blackhole.events.>`; the Discord bot MUST only be able to manage and consume its own two durable consumers; a client outside the cluster MUST connect over `wss://` with a WebSocket-only user.

#### Scenario: Bot cannot publish
- **WHEN** the bot's credentials publish to `blackhole.events.punishment.created`
- **THEN** the server rejects it with a permissions violation

#### Scenario: Other account isolated
- **WHEN** a client of another NATS account subscribes to `blackhole.events.>`
- **THEN** it receives nothing

### Requirement: Configuration by environment
The backend SHALL read `NATS_URL`, `NATS_CREDS_FILE`, `NATS_CONNECTION_NAME`, `NATS_STREAM_REPLICAS`, `NATS_EVENTS_MAX_AGE`, `NATS_EVENTS_MAX_BYTES` and `NATS_CONNECT_TIMEOUT` with local-development defaults documented in `application.yml`. The bot SHALL require `NATS_URL` and accept `NATS_CREDS_FILE`. `RABBITMQ_*` variables MUST no longer be read.

#### Scenario: Missing bot setting
- **WHEN** the bot starts without `NATS_URL`
- **THEN** it exits with code 2 and names the missing variable
