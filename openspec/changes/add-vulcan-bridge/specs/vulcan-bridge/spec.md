# vulcan-bridge Specification

## ADDED Requirements

### Requirement: Platform and installation
The bridge SHALL be a Paper plugin that runs on Paper and its Paper-compatible forks (Purpur, Pufferfish). It SHALL declare Folia support so Folia does not refuse it. It SHALL NOT require Vulcan at build time. It SHALL NOT load on Minestom.

#### Scenario: Paper server with Vulcan installed
- **WHEN** the plugin is installed on a Paper server that also runs Vulcan
- **THEN** the plugin enables, probes Vulcan at runtime and logs one INFO line with the detected features

#### Scenario: Paper server without Vulcan
- **WHEN** the plugin is installed on a Paper server without Vulcan
- **THEN** the plugin enables, finds no flag event class, logs one WARN and sends nothing

### Requirement: Credentials are never exposed
The bridge SHALL read its client secret from the environment variable `BLACKHOLE_BRIDGE_CLIENT_SECRET` first and from the config file key `entra.client-secret` second. It SHALL NOT write the secret, the access token or any value derived from them to a log, a metric, the status command or a file other than the operator's own config. The token SHALL be cached in memory and refreshed before it expires.

#### Scenario: Secret from the environment
- **WHEN** `BLACKHOLE_BRIDGE_CLIENT_SECRET` is set and the config file has a different `entra.client-secret`
- **THEN** the environment value is used for the token request

#### Scenario: No secret configured
- **WHEN** neither the environment variable nor the config key provides a secret
- **THEN** the bridge stays idle, logs one WARN without the value of any secret, and sends nothing

#### Scenario: Token refresh before expiry
- **WHEN** 80% of the token lifetime has passed
- **THEN** the bridge requests a new token before the next batch is sent

#### Scenario: Secret never printed
- **WHEN** an operator runs `/blackhole-bridge status`
- **THEN** the output contains no secret, no token and no config value that holds a secret

### Requirement: Server label from the server
The envelope `server` SHALL be the CloudNet service name when the process exposes it and it matches `^[A-Za-z0-9_-]{1,64}$`, otherwise the configured `server-name`. The bridge SHALL NOT send an IP address or a host name as `server`.

#### Scenario: Service name available
- **WHEN** the process exposes the CloudNet service name `Lobby-2`
- **THEN** every envelope from this server has `server` set to `Lobby-2`

#### Scenario: Service name unavailable
- **WHEN** no service name is exposed and `server-name` is `survival-2`
- **THEN** every envelope has `server` set to `survival-2`

#### Scenario: Invalid service name
- **WHEN** the exposed service name contains a space or is longer than 64 characters
- **THEN** the configured `server-name` is used instead and the bridge logs one WARN without the value

### Requirement: Flag capture on the game thread
The bridge SHALL handle a Vulcan flag event by copying its fields into a bounded queue and returning. The handler SHALL NOT perform network I/O, SHALL NOT block on another thread, and SHALL NOT throw to Vulcan.

#### Scenario: Flag event handled
- **WHEN** Vulcan fires a flag event with a check name
- **THEN** the handler returns without waiting for the network and one envelope is queued

#### Scenario: Handler error
- **WHEN** reading a getter on the event throws an exception
- **THEN** the exception is not propagated to Vulcan, the Vulcan integration is disabled for the rest of the run and one ERROR line with the exception class name is logged

### Requirement: Version independence
The bridge SHALL detect the Vulcan flag event class and each getter at runtime, by name, and SHALL NOT reference a Vulcan class at compile time. Each feature (check name, check type, violation level, experimental, detail) SHALL be recorded as available or missing. A missing optional feature SHALL be sent as `null`. A flag without a check name SHALL be dropped and counted as `dropped_total{reason="unmapped"}`.

#### Scenario: Full API shape
- **WHEN** the probe finds the flag event class and every getter
- **THEN** every field of `vulcan/DETECTION` v1 that Vulcan provides is set and the INFO line lists all features as available

#### Scenario: Reduced API shape
- **WHEN** the probe finds the flag event class but not the violation level getter
- **THEN** envelopes are sent with `violationLevel` set to `null`, the INFO line lists it as missing, and the flags are still delivered

#### Scenario: Missing check name
- **WHEN** a flag event yields no check name
- **THEN** the flag is not queued and `dropped_total{reason="unmapped"}` increases by one

#### Scenario: Two API shapes map the shared fields the same way
- **WHEN** the same flag is fed to the adapter for the full API shape and for the reduced API shape
- **THEN** the envelopes have the same `check`, `summary` and `occurredAt` source and differ only in the missing optional fields

#### Scenario: Unknown version
- **WHEN** no flag event class exists under any known name
- **THEN** the adapter stays idle, logs exactly one WARN and queues nothing

### Requirement: Detection envelope
Each envelope SHALL carry a UUID `detectionId` generated once per flag and kept for every retry of that flag, `kind` `DETECTION`, `schemaVersion` 1, `occurredAt` taken from the server clock when the flag is handled, `server` as specified above, `owner` as the player's Minecraft UUID, a `summary` of at most 200 characters and a payload with the `vulcan/DETECTION` v1 fields. The bridge SHALL NOT send an IP address, a world name, coordinates, inventory contents, chat text or the player name.

#### Scenario: Retry keeps the identifier
- **WHEN** a batch fails with a network error and is sent again
- **THEN** each envelope carries the same `detectionId` as in the first attempt

#### Scenario: No personal data beyond the UUID
- **WHEN** an envelope is built for a flag
- **THEN** it contains no IP address, player name, world, coordinates or inventory field

### Requirement: Bounded queue with drop-oldest
The bridge SHALL hold at most 5 000 detections and at most 8 MiB of serialised envelopes. When a new detection would exceed either bound, the bridge SHALL drop the oldest queued detection and increment `dropped_total{reason="queue_full"}`.

#### Scenario: Queue full
- **WHEN** the queue holds 5 000 detections and a new flag arrives
- **THEN** the oldest detection is removed, the new one is queued and the drop counter increases by one

#### Scenario: Oversized envelope
- **WHEN** a single serialised envelope exceeds 64 KiB
- **THEN** it is not sent and `dropped_total{reason="too_large"}` increases by one

### Requirement: Batching and flush cadence
The sender SHALL send at most 50 detections and at most 64 KiB per request, flushing every 2 seconds or when a batch is full. Requests SHALL use a 2 second connect timeout and a 5 second request timeout.

#### Scenario: Batch limit
- **WHEN** 120 detections are queued
- **THEN** the sender sends them in requests of at most 50 detections each

#### Scenario: Timer flush
- **WHEN** 2 seconds have passed and one detection is queued
- **THEN** that detection is sent in a request

### Requirement: Retry with backoff and jitter
When a request fails with a network error or a 5xx response, the sender SHALL retry the whole batch with exponential backoff from 1 second up to 60 seconds, with jitter drawn from an injected source. A 401 response SHALL trigger one token refresh before the next attempt and SHALL NOT retry in a tight loop. Other 4xx responses SHALL NOT be retried.

#### Scenario: Backend down
- **WHEN** the backend is unreachable for several minutes
- **THEN** the sender retries with growing delays capped at 60 seconds and the queued detections stay within the queue bounds

#### Scenario: Unauthorized
- **WHEN** the backend answers 401
- **THEN** the bridge refreshes its token once and retries the batch

### Requirement: Per-item answers and idempotency
The sender SHALL remove an item from the queue when the backend answers it `ACCEPTED`, `DUPLICATE` or `REJECTED`. It SHALL count `REJECTED` items by reason code and SHALL NOT log the owner, the detail or the payload of a rejected item.

#### Scenario: Duplicate after a lost response
- **WHEN** a batch was accepted by the backend but the response was lost and the batch is sent again
- **THEN** the backend answers `DUPLICATE` for each item and the bridge removes them without counting an error

#### Scenario: Rejected item
- **WHEN** the backend answers an item `REJECTED` with reason `SERVER_OUT_OF_SCOPE`
- **THEN** the item is removed and `rejected{reason="SERVER_OUT_OF_SCOPE"}` increases by one

### Requirement: Shutdown flush
On server shutdown the bridge SHALL stop capturing new flags and attempt one flush with a 2 second deadline. Detections still queued after the deadline SHALL be counted as `dropped_total{reason="shutdown"}`.

#### Scenario: Clean shutdown
- **WHEN** the server stops with 10 detections queued and the backend answers in time
- **THEN** the 10 detections are sent before the plugin disables

#### Scenario: Backend slow at shutdown
- **WHEN** the backend does not answer within the 2 second deadline
- **THEN** the plugin disables after the deadline and the unsent detections are counted as dropped with reason `shutdown`

### Requirement: Operator status
The bridge SHALL provide `/blackhole-bridge status`, which is available only to senders holding the permission `blackhole.bridge.status`. It SHALL print the enabled state, the detected Vulcan features, the queue size and the counters.

#### Scenario: Operator with permission
- **WHEN** an operator with `blackhole.bridge.status` runs the command
- **THEN** the output lists the enabled state, the Vulcan features, the queue size and the counters, and no secret

#### Scenario: Player without permission
- **WHEN** a player without the permission runs the command
- **THEN** the command is refused and no status is shown
