# Spec Delta

## Purpose
Delivers chat changes to open pages with Server-Sent Events, content-free, safe across replicas and the tunnel, with polling as the fallback.

## ADDED Requirements

### Requirement: Event contract
A live stream SHALL send `retry: 5000` once, `event: ready` with `{"seq":N}` on connect, `event: changed` with `id: N` and `{"seq":N}` after every change the reader may see, and a keep-alive comment at most every 15 seconds. An event MUST NOT carry text, names, file names or any identifier other than the seq. With `Last-Event-ID` below the current seq the server MUST send `changed` right after `ready`.

#### Scenario: New message
- **WHEN** staff post a message while a player's stream is open
- **THEN** the stream sends `changed` with the new seq and nothing else

#### Scenario: Reconnect
- **WHEN** a client reconnects with `Last-Event-ID: 40` and the case is at seq 43
- **THEN** the server sends `ready` and `changed` with seq 43

#### Scenario: Staff note
- **WHEN** staff add an internal note
- **THEN** no event is sent to the player's stream

### Requirement: Player stream through the portal
The portal BFF SHALL expose `GET /api/events` for a player session cookie: 401 without a body when invalid, the backend stream opened server-side with the session token, events passed through unchanged, the upstream aborted when the browser disconnects, and `no-cache, no-transform` with `X-Accel-Buffering: no`. The backend route `GET /public/conversation/events` MUST need a `FULL` session, check it without sliding at connect and each keep-alive, and send `event: expired` and close when it ends.

#### Scenario: Expired session
- **WHEN** the session expires while a stream is open
- **THEN** the backend sends `expired` and closes, and the client switches to the login flow

#### Scenario: Invalid session
- **WHEN** a browser opens the stream without a valid session
- **THEN** the BFF answers 401 with no body and the browser does not reconnect

#### Scenario: Browser closes
- **WHEN** the browser disconnects
- **THEN** the BFF aborts the backend request and the hub releases the stream

#### Scenario: Keep-alive does not extend the session
- **WHEN** only keep-alives are exchanged for longer than the idle limit
- **THEN** the session expires as usual

### Requirement: Staff stream
`GET /case/{reference}/events` SHALL need `VIEWER` or above with the staff token, send the staff-visible changes including `playerSeenSeq` changes, and deny `PROXY`, `DISCORD` and player sessions. The panel SHALL read it with a streaming `fetch` using a fresh token on every connection and exponential backoff from 1 to 30 seconds.

#### Scenario: Proxy denied
- **WHEN** the proxy's token opens the stream
- **THEN** the answer is HTTP 403

#### Scenario: Token refresh
- **WHEN** the panel reconnects after a drop
- **THEN** it sends a currently valid token

### Requirement: Bounds on streams
The system SHALL allow one stream per player session (a newer one closes the older with `event: replaced`), at most `blackhole.chat.live.max-streams` per replica (HTTP 503 beyond it) and a lifetime of at most `blackhole.chat.live.max-lifetime` before the client reconnects. These are caps on held state, not rate limits.

#### Scenario: Second tab
- **WHEN** the same session opens a second stream
- **THEN** the first receives `replaced` and closes

#### Scenario: Capacity
- **WHEN** the replica holds `max-streams` streams
- **THEN** a new request answers 503 and the client polls

### Requirement: Signals come from every writer
A case change signal SHALL be raised after every append of a case event that a reader can see, by the one place that appends case events, in a try/catch that counts failures and never fails the write. A test MUST fail when a case event write path does not raise it.

#### Scenario: Signal failure
- **WHEN** raising the signal throws
- **THEN** the write still succeeds and `blackhole.live.signal.failures` rises

#### Scenario: New writer
- **WHEN** a new method appends case events without the notifier
- **THEN** the surface test fails

### Requirement: Fan-out across replicas
The hub SHALL depend only on a `ChangeSignalTransport` port. An in-process transport MUST exist for a single replica and tests. The bus adapter MUST be a transient, non-durable core NATS subscription per replica (`micronaut-nats` 5.0.0 from the BOM, change `switch-event-bus-to-nats`) and ships in its own PR after the in-process transport. No RabbitMQ adapter is written. Without a bus adapter the clients' safety poll MUST bound the delay to 30 seconds.

#### Scenario: Two replicas
- **WHEN** replica A raises a signal for a case and replica B holds a stream for it
- **THEN** B's stream sends `changed` through the transport (tested with a fake transport)

#### Scenario: Lost signal
- **WHEN** a signal is lost
- **THEN** the client's 30 second safety poll shows the change

### Requirement: Polling fallback
The portal and the panel SHALL keep polling. With an open stream they MUST poll at most every 30 seconds; without one, every 15 seconds while the tab is visible. A hidden tab MUST NOT fetch. The page MUST show a reconnecting state only after the second failed attempt.

#### Scenario: Stream unsupported
- **WHEN** the stream cannot be opened
- **THEN** the page polls every 15 seconds and shows messages as before

#### Scenario: Hidden tab
- **WHEN** a signal arrives while the tab is hidden
- **THEN** the page fetches when the tab becomes visible

### Requirement: Works behind the tunnel
The responses SHALL be streamed without compression or buffering, with a keep-alive well below the shortest proxy idle timeout, and the operations documentation MUST list that no cache, challenge or compression rule may apply to the stream paths. The implementation MUST verify the behaviour on the real tunnel before the portal PR is merged.

#### Scenario: Verification
- **WHEN** the spike task runs against the deployed tunnel
- **THEN** events arrive within a second of the write and the stream survives more than two keep-alive intervals
