## ADDED Requirements

### Requirement: Alias is derived from the database
The system SHALL keep one OPNsense firewall alias (type Network, name from `blackhole.ipban.firewall.alias`, default `blackhole_ipban`) equal to the set of active IP bans. The database MUST be the source of truth: the alias content is recomputed from it, and entries that are not active in the database MUST be removed from the alias.

#### Scenario: Ban created
- **WHEN** an IP ban becomes active
- **THEN** after the next reconciliation the alias contains its range

#### Scenario: Ban lifted
- **WHEN** an IP ban is lifted
- **THEN** after the next reconciliation the alias no longer contains its range

#### Scenario: Manual edit on the firewall
- **WHEN** an entry is added to the alias by hand on the firewall
- **THEN** the next reconciliation removes it, and the operator note says so

### Requirement: Reconciliation triggers
The reconciliation SHALL run at start-up, every `blackhole.ipban.firewall.reconcile-interval` (default `PT5M`), and on an `ipban.changed` event. Event-triggered runs MUST be debounced to at most one run per 5 seconds.

#### Scenario: Burst of changes
- **WHEN** five IP bans change within 5 seconds
- **THEN** at most one event-triggered reconciliation runs in that window

### Requirement: Reconciliation writes only the difference
A reconciliation SHALL read the alias, add the missing entries, delete the extra ones, and call the reconfigure operation only when something changed. An unchanged alias MUST produce no write and no reconfigure call.

#### Scenario: Nothing changed
- **WHEN** the alias already equals the desired set
- **THEN** no add, delete or reconfigure call is made

### Requirement: Read-back verification
A reconciliation SHALL succeed only when, after its writes and the reconfigure call, a fresh read of the alias equals the desired set. A mismatch MUST be recorded as a failed run and retried at the next trigger.

#### Scenario: Read-back differs
- **WHEN** the read after the writes does not equal the desired set
- **THEN** the run is recorded as failed and the in-sync gauge is 0

### Requirement: Protected networks never reach the alias
The adapter MUST refuse to write any range that overlaps a protected network, even if the database holds one, and MUST count each refusal.

#### Scenario: Edge address in the database
- **WHEN** the database holds a row that overlaps a protected network
- **THEN** the adapter does not write it to the alias and `blackhole.ipban.firewall.refused` increases

### Requirement: Firewall layer is off by default
The firewall layer SHALL be disabled unless `blackhole.ipban.firewall.enabled` is true. When disabled, no request is sent to the firewall and the proxy layer still enforces IP bans.

#### Scenario: Default configuration
- **WHEN** the backend starts with no firewall settings
- **THEN** no request is made to any firewall and the health indicator reports the layer as disabled

### Requirement: Unreachable firewall
When the firewall cannot be reached or returns an error, the system SHALL keep the database unchanged, record the error with the operation name, back off after consecutive errors (30 seconds, doubling, capped at 15 minutes) and MUST NOT send a burst of requests while it is unreachable.

#### Scenario: Firewall down
- **WHEN** three reconciliations in a row fail with a connection error
- **THEN** the next attempt waits longer than the previous one and `blackhole.ipban.alias.errors` has increased for operation `list` or `add`

#### Scenario: Recovery
- **WHEN** a reconciliation succeeds after failures
- **THEN** the backoff resets and the in-sync gauge returns to 1 after the read-back

### Requirement: Health and metrics of the firewall link
The system SHALL expose the gauges `blackhole.ipban.alias.in_sync`, `blackhole.ipban.alias.pending` and `blackhole.ipban.alias.last_success_seconds`, the counter `blackhole.ipban.alias.errors` tagged by operation, and a health indicator `ipban-firewall`. The indicator MUST report DOWN when the last successful reconciliation is older than three reconcile intervals, and a DOWN indicator MUST NOT stop the application.

#### Scenario: Stale link
- **WHEN** no reconciliation has succeeded for longer than three reconcile intervals
- **THEN** the `ipban-firewall` health indicator reports DOWN and the application keeps serving requests

### Requirement: Credentials stay out of logs
The API key and secret MUST be read from `BLACKHOLE_OPNSENSE_API_KEY` and `BLACKHOLE_OPNSENSE_API_SECRET`, MUST NOT be logged or returned by any route, and requests MUST verify the firewall's TLS certificate by default.

#### Scenario: Authentication failure
- **WHEN** the firewall rejects the credentials
- **THEN** the error is recorded as an authentication error, the request is not retried in a loop, and the secret does not appear in the log line
