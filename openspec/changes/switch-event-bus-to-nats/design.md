# Design

## Context

Current state on `main` (read 2026-10-24):

| Piece | Where | What it does |
|---|---|---|
| Topic exchange `blackhole.events` | `BlackholeRabbitTopology` (declared on channel creation) | Routing key = event type |
| Publisher | `DomainEventPublisher` -> `DomainEventRabbitPublisher` (`@RabbitClient`, returns `Publisher<Void>`) | Fire and forget, never blocks the Netty event loop, failure is logged, never fails the write |
| `blackhole.redis.sync` | durable queue, bound to `punishment.created/.updated/.expired/.revoked`, `appeal.resolved`; consumed by `RedisSyncConsumer` (raw client, manual ack, ack after one attempt) | Mirrors active punishments into Dragonfly; one queue shared by all replicas |
| `blackhole.discord.notify` | bot-declared durable queue, TTL 7 d, bound to `report.created/.resolved`, `appeal.submitted/.resolved`; `basicQos(5)`; RETRY = nack+requeue after 10 s | Team channel notifications |
| `blackhole.discord.case` | bot-declared durable queue, TTL 7 d, bound to `case.message_*`, `case.ban_id_*`, `case.status_changed`, `appeal.resolved`, `staff-link.removed` | A *hint* to poll the per-ticket cursor now |
| Velocity | no AMQP at all | Reads Dragonfly keys and pub/sub channels |
| Cache fanout | none (removed with the shared Dragonfly cache) | - |
| Tests | `PostgresIntegrationTest` starts `RabbitMQContainer`; `DomainEventDeliveryIntegrationTest` binds a temp queue; `RecordingEventPublisher extends DomainEventPublisher` with `super(null)` | - |

Event types published today: `profile.created/.updated/.deleted`,
`punishment.created/.updated/.expired/.revoked`, `appeal.submitted/.resolved`, `report.created/
.resolved/.feedback`, `elo.threshold_crossed`, `evasion.detected`, `section.changed/.escalated`, plus
the `evidence.*`, `case.*`, `discord.ticket_*`, `discord-link.*`, `staff-link.*` and
`case.protocol_stored` families published by their services.

### What feather-core NATS offers (Kubernetes-FLUX, read-only)

- **Deployment:** Helm chart `nats` `=2.15.1` (HelmRelease in namespace `nats`), 3 replicas
  clustered, one pod per node (topology spread), PDB on, Prometheus exporter + PodMonitor. The server
  version is the chart's appVersion; re-check it when the chart is bumped and pin the test image to
  the same minor.
- **JetStream:** enabled. File store 45 Gi max on a 50 Gi `ceph-rbd-fr01` PVC per pod.
- **Auth:** decentralised JWT (operator mode, in-memory resolver). Operator JWT plus the `SYS` and
  `CORVUS` account JWTs are preloaded from a SOPS secret (`nats-auth.sops.conf`). The `CORVUS` account
  accepts JWT credentials only; its `corvus-proxy` user is limited to WebSocket connections.
  Adding an account means `nsc` (operator signing key kept in the team password manager), a new
  entry in `resolver_preload`, and a config reload (no pod restart). **There is no `BLACKHOLE`
  account yet.**
- **TLS:** none on the client port 4222 inside the cluster (plain TCP, cluster-internal). The
  WebSocket listener (8080) is plain HTTP; TLS ends at the edge.
- **External exposure today:** (a) Cloudflare Tunnel `Ingress` `nats.onelitefeather.dev` ->
  service `nats:8080`, so clients use `wss://nats.onelitefeather.dev` (TLS at Cloudflare, server pings
  every 30 s so Cloudflare keeps idle connections; max payload 1 MiB); (b) an Envoy Gateway
  `HTTPRoute` `wss://nats.apps.onelite.feather` for internal-network clients, TLS from the step-ca
  root (clients must trust it), because Cloudflare challenges the CloudNet node's IP. There is **no raw
  TCP exposure**: the only MetalLB pool (`10.200.90.1-15`) is private RFC 1918 space.
- **Precedent:** the Corvus plugin (another OLF project) already connects clients outside the
  cluster over `wss://` with jnats 2.26.4 and a restricted JWT user.

### Facts checked on the web (2026-10-24)

- `io.nats:jnats` latest **2.26.4** (Maven Central). WebSocket (`ws://`, `wss://`) is supported since
  2.16.8. JetStream API: `Connection.jetStream()`, `JetStreamManagement` (`addStream`),
  pull subscriptions (`PullSubscribeOptions.bind`, `fetch`, `iterate`), `Message.ack()`,
  `nakWithDelay(Duration)`, `term()`; publish dedupe through `PublishOptions.messageId`
  (`Nats-Msg-Id`).
- `micronaut-nats` guide **5.1.0**: `@NatsClient`, `@NatsListener` (core), `@JetStreamClient`,
  `@JetStreamListener` with `@PushConsumer`, `PullConsumerRegistry`. Its compatibility with the
  Micronaut 5 line used here must be verified before relying on it (D2).
- **Testcontainers has no NATS module** (no `nats` artifact under `org.testcontainers`); use
  `GenericContainer<>("nats:<tag>").withCommand("-js")` exposing 4222. Testcontainers core is 2.0.5.
- Core NATS is at-most-once; JetStream adds persistence, acks and the dedupe window. Named (durable)
  consumers can be pulled from by several processes, which share the work (the analogue of competing
  consumers on one queue).

## Goals / Non-Goals

Goals: replace RabbitMQ one-for-one with NATS JetStream, keep the envelope and the privacy rules,
keep publishing non-blocking, authenticate every client with its own least-privilege credentials,
keep the domain code broker-agnostic and unit-testable without a broker.

Non-goals: new events, new guarantees (the bus stays a best-effort side channel), changes to Redis,
the HTTP API or the database.

## Decisions

### D1. Subject hierarchy

`blackhole.events.<eventType>`; the event type already is a dot-separated token list, so
`punishment.created` becomes `blackhole.events.punishment.created`. A subject builder rejects any
event type that contains whitespace, `*` or `>` or an empty token (a test enumerates every type
published in the code base). Wildcards replace the topic patterns (`blackhole.events.punishment.*`).
Nothing but the event type is allowed in a subject (GDPR).

### D2. Client library: plain `jnats`, not `micronaut-nats`

Recommended. The existing consumers already use the raw broker client on purpose (`RedisSyncConsumer`
comment), the bot is not a Micronaut app, and the needed semantics (named durable pull consumer,
`nakWithDelay`, explicit stream management, `Nats-Msg-Id`) are first-class in `jnats`. One
`io.nats.client.Connection` `@Singleton` factory in the backend (reconnect forever, connection
name `blackhole-backend`, credentials from `NATS_CREDS_FILE`) keeps the dependency to one small
library, identical in backend and bot (version catalog entry `nats-jnats` = 2.26.4; re-check Maven
Central and the two-week rule when applying). Alternative: `micronaut-nats`. Open question 1.

### D3. Ports in the backend

```java
public interface EventTransport {          // publishing
    CompletionStage<Void> publish(String eventType, DomainEvent event);   // never blocks the caller
}
public interface EventSubscription extends AutoCloseable {   // one durable consumer
    // started by the consumer bean; hands decoded DomainEvents to a handler, acks after it returns
}
```

`DomainEventPublisher` depends on `EventTransport` (not on a broker class); `RedisSyncConsumer`
depends on a subscription factory. `RecordingEventPublisher` stops calling `super(null)`; tests
construct `DomainEventPublisher` over an in-memory `EventTransport`. The refactor lands first, still
on RabbitMQ, so the swap is a pure adapter change.

### D4. Mapping, one row per current use

| RabbitMQ today | NATS |
|---|---|
| Topic exchange `blackhole.events`, routing key = event type | Subjects `blackhole.events.<eventType>` captured by stream `BLACKHOLE_EVENTS` |
| `@RabbitClient` `publish(routingKey, event)` returning `Publisher<Void>` | `JetStream.publishAsync(subject, json, PublishOptions{messageId=eventId})` returning a `CompletableFuture<PublishAck>`; subscribed fire-and-forget; failure logged, never rethrown |
| Durable queue `blackhole.redis.sync`, 5 routing keys, shared by replicas | Durable pull consumer `blackhole-redis-sync` on `BLACKHOLE_EVENTS`, `filter_subjects` = the 5 subjects, `deliver_policy: new` on first creation, `ack_policy: explicit`, `ack_wait: 30s`; every replica binds to the same durable and pulls (work is shared, each event handled once) |
| `RedisSyncConsumer` acks after a single attempt, error logged | Same: handler exception is logged and the message is acked; `max_deliver: 1` as a backstop |
| Bot queue `blackhole.discord.notify`, TTL 7 d, `basicQos(5)` | Durable pull consumer `blackhole-discord-notify`, filters `report.created/.resolved`, `appeal.submitted/.resolved`, `ack_policy: explicit`, `max_ack_pending: 5`, `ack_wait: 30s`, `max_deliver: -1` bounded by the stream's 7 d max age |
| Bot `Outcome.RETRY` = `basicNack(requeue)` after 10 s | `msg.nakWithDelay(10 s)`; `DONE`/`DROP` = `msg.ack()` |
| Bot queue `blackhole.discord.case`, same TTL | Durable pull consumer `blackhole-discord-case`, same settings, filters = the bot's `CaseEventHandler.ROUTING_KEYS` prefixed with `blackhole.events.` |
| Queue TTL 7 days (`x-message-ttl`) | Stream `max_age: 7d` (`NATS_EVENTS_MAX_AGE`, default `P7D`) |
| Exchange/queues declared on connect, idempotent | Stream and consumers created with create-or-update at startup by their owner (backend: stream + `blackhole-redis-sync`; bot: its two consumers) |
| Broker unreachable: publish error logged; bot retries connect every 30 s | jnats `maxReconnects(-1)` with a reconnect wait; bot keeps serving commands (cursor poll stays the truth); publish error logged |
| `blackhole.cache.invalidate` fanout | Does not exist on main. If ever needed: a plain core subject `blackhole.cache.invalidate` with no JetStream (a missed eviction is bounded by the entry TTL per the `shared-cache` spec); not added now |
| Velocity (Redis pub/sub, not RabbitMQ) | Core subscriptions on `blackhole.events.*` subjects with a subscribe-only credential, see D7 |

Stream `BLACKHOLE_EVENTS`: `subjects: [blackhole.events.>]`, `storage: file`, `retention: limits`,
`max_age: 7d`, `discard: old`, `num_replicas: NATS_STREAM_REPLICAS` (3 in feather-core, 1 locally),
`duplicate_window: 2m`, `deny_delete: true`, `deny_purge: true`, `max_bytes` bounded (default
2 GiB, far below the 45 Gi store; payloads are tiny and carry no content). Limits retention (not
work-queue) because three independent consumers read overlapping subjects (`appeal.resolved` is read
by redis-sync and by the bot).

### D5. Exactly-once and dedupe

`Nats-Msg-Id` = `eventId` (a random UUID, not PII). A publisher retry of the same `DomainEvent`
inside `duplicate_window` is stored once. This is publish-side dedupe only; consumers stay idempotent
as today: `PunishmentSyncHandler` sets/clears keys, the bot treats events as hints and reads cursors.
No outbox is added; the write path already tolerates a lost event (try/catch around `publish`).

### D6. Authentication, TLS, exposure (per client)

One account `BLACKHOLE` (JetStream enabled with limits, e.g. 5 GiB storage, 1 stream, 8 consumers)
so the Corvus account cannot read Blackhole events and vice versa. Users are created with `nsc`,
delivered as `.creds` files through Kubernetes Secrets (SOPS); clients take `NATS_CREDS_FILE`.
Subject permissions, least privilege:

| User | Where it runs | Connection types | Publish allow | Subscribe allow |
|---|---|---|---|---|
| `blackhole-backend` | feather-core | STANDARD | `blackhole.events.>`, `$JS.API.STREAM.CREATE/UPDATE/INFO.BLACKHOLE_EVENTS`, `$JS.API.CONSUMER.CREATE/DURABLE.CREATE/INFO/MSG.NEXT.BLACKHOLE_EVENTS.blackhole-redis-sync` (+ filtered create subject), `$JS.ACK.BLACKHOLE_EVENTS.blackhole-redis-sync.>` | `_INBOX.>` |
| `blackhole-discord` | feather-core (or compose profile) | STANDARD, WEBSOCKET if outside the cluster | same `$JS.API.CONSUMER.*` and `$JS.ACK` subjects for `blackhole-discord-*`, plus `$JS.API.STREAM.INFO.BLACKHOLE_EVENTS`; no `blackhole.events.>` | `_INBOX.>` |
| `blackhole-velocity` | outside feather-core | WEBSOCKET only | **deny `>`** (nothing) | exactly `blackhole.events.punishment.*`, `blackhole.events.appeal.resolved`, `blackhole.events.section.changed`, `blackhole.events.report.created`, `blackhole.events.report.feedback`; no `_INBOX`, no `$JS.>` |

Only the backend publishes events; the proxy is subscribe-only and uses plain core subscriptions (no JetStream, no consumer). The exact `$JS.API` list is verified against a real server in the
integration test (a forbidden publish fails with a permissions violation, which the test asserts).

TLS: client port 4222 inside the cluster is plain today. In-cluster traffic carries only hashed ids
and event types, the same trust boundary as the Dragonfly and PostgreSQL traffic today, so in-cluster
TLS is a hardening follow-up on the NATS side, not a blocker. The stream is a second at-rest copy:
check that the `ceph-rbd-fr01` PVC class is encrypted at rest. Everything that leaves the cluster
uses `wss://` (Cloudflare certificate, or step-ca for the internal Envoy route).

Exposure options for a client outside feather-core (Velocity, or a bot hosted elsewhere):

1. **`wss://nats.onelitefeather.dev` through the existing Cloudflare Tunnel** (recommended; built
   and proven by Corvus). TLS at Cloudflare, JWT auth in NATS, WebSocket-only user. Constraints:
   only `wss://` (no 4222), 1 MiB payload (ours are a few KiB), the 30 s server ping is already set.
2. `wss://nats.apps.onelite.feather` via Envoy for hosts inside the internal network (needs the
   step-ca root in the client trust store; avoids Cloudflare's bot challenge on server IPs).
3. **Raw TCP 4222 over Cloudflare Tunnel is not usable by a plain NATS client.** A tunnel carries
   non-HTTP TCP only to a client that runs `cloudflared access tcp` (or WARP) beside the app; jnats
   cannot dial a bare hostname that way. Rejected: an extra process per host for no gain over
   WebSocket.
4. A TCP `LoadBalancer`/NodePort with TLS plus a firewall allow-list: needs a public address or a
   pool that does not exist today (MetalLB is private-only). Rejected unless Cloudflare is ruled out.

Decided by the user: option 1 for Velocity.

### D7. Velocity: drops Redis/Dragonfly, uses the API plus NATS over WebSocket

User decision (2026-10-24): Velocity runs outside feather-core, gets no direct Dragonfly access, and
moves to NATS over `wss://nats.onelitefeather.dev` (the Corvus pattern). Verified in the code:
`velocity/.../redis/RedisSyncService` reads the mirrored punishment keys, the shared-cache lists
(`blackhole:cache:v1:templates:all`, `sections:all`) and subscribes to four Redis channels
(`blackhole.punishment.sync`, `blackhole.section.changed`, `blackhole.report.created`,
`blackhole.report.feedback`); it already falls back to HTTP whenever Redis is unavailable.

After the move the proxy has two inputs only: the HTTP API (truth) and NATS events (hints that
invalidate local caches):

| Today (Redis) | After |
|---|---|
| Punishment key read at login (`blackhole:punish:*`) | Profile over HTTP at login, cached per player |
| `blackhole.punishment.sync` pub/sub | Core subscription `blackhole.events.punishment.*` and `blackhole.events.appeal.resolved`: update or evict the cached player, trigger the cross-proxy ban kick (fetch profile, disconnect if banned) |
| `blackhole.section.changed` | `blackhole.events.section.changed`: refresh the section list over HTTP |
| Shared-cache lists for tab completion | HTTP with a short local TTL cache |
| `blackhole.report.created` (written by the proxy that took the report, with player names) | `blackhole.events.report.created` (hashes only, written by the backend): each proxy renders the staff notice from an HTTP lookup of the report. Needs a read endpoint that returns display facts; if none exists the notice stays local to the receiving proxy (Question 3) |
| `blackhole.report.feedback` | `blackhole.events.report.feedback` |

The proxy never publishes, so the report notice cannot be proxy-originated any more.

**NATS unreachable: fail open to HTTP, never open to the player.** Core subscriptions are
at-most-once, so the local cache is only trustworthy while the connection is up. The proxy:
(1) discards the whole local punishment cache on a disconnect event and again on reconnect;
(2) while disconnected, answers every login and chat check from the backend over HTTP, with the
existing backend-unreachable rule unchanged (login allowed, chat delivered, as today);
(3) bounds staleness while connected with a maximum cache age (config `cacheMaxAge`, default 60 s)
so a lost message heals itself; (4) reconnects forever (jnats `maxReconnects(-1)`) from a non-blocking
connect on plugin start; no login or chat path ever waits on NATS; (5) an unreadable or missing creds
file is logged and leaves the proxy on HTTP-only checks. Spec: `proxy-enforcement` delta.

Velocity config (`config.json`) loses `redisUri` (BREAKING for the plugin config: the legacy key is
ignored with one warning) and gains `nats.url`, `nats.credsFile`, `cacheMaxAge`. Lettuce is removed from
`velocity/build.gradle.kts`; jnats (version catalog) is added and shaded/relocated per the plugin's
existing packaging. The backend's Redis punishment mirror and its `blackhole-redis-sync` consumer lose
their only reader with this change; removing them is a separate follow-up PR (Question 2) so the
transport swap and the proxy move stay independent. Discord and the backend stay in-cluster
(`nats://nats.nats.svc:4222`, STANDARD connection type).

### D8. Migration: hard cut, no dual-run, no dual-publish

Blackhole is not deployed in feather-core yet, and the durable consumers are best-effort by design
(Redis sync self-heals on the next mutation and through the HTTP fallback; the bot re-reads cursors).
A dual-publish phase needs both brokers, doubles the code and buys nothing. Cutover: provision the
NATS account and users, deploy the new backend and bot together, drop `RABBITMQ_*`. For an existing
local setup: stop the old compose service; queued RabbitMQ messages are discarded (documented). The
port (D3) lands first on RabbitMQ so each step is independently revertible.

### D9. Configuration (env-driven, documented in `application.yml`)

```yaml
# NATS JetStream backs the domain event bus (subjects blackhole.events.<type>, stream BLACKHOLE_EVENTS)
blackhole:
  events:
    nats:
      url: ${NATS_URL:`nats://localhost:4222`}       # wss://... for a client outside the cluster
      creds-file: ${NATS_CREDS_FILE:}                # empty = no credentials (local dev only)
      connection-name: ${NATS_CONNECTION_NAME:blackhole-backend}
      stream-replicas: ${NATS_STREAM_REPLICAS:1}     # 3 in feather-core
      stream-max-age: ${NATS_EVENTS_MAX_AGE:P7D}
      stream-max-bytes: ${NATS_EVENTS_MAX_BYTES:2147483648}
      connect-timeout: ${NATS_CONNECT_TIMEOUT:5s}
```

An unreadable `NATS_CREDS_FILE` is logged at startup and the app keeps running (same posture as
today: the bus is an additive side channel). The bot makes `NATS_URL` required and `NATS_CREDS_FILE`
optional (fail-fast message like its other variables) and relies on jnats' own reconnect instead of
the 30 s loop. `RABBITMQ_HOST/PORT/USER/PASSWORD/VHOST` are removed. No Flyway migration (no schema
change).

### D10. Docker and build

`docker/docker-compose.yml`: remove `rabbitmq`; add `nats` (`nats:<same minor as the chart's server>-alpine`,
command `-js -sd /data -m 8222`, ports `4222` and `8222`, volume `./nats/data/`, network
`blackhole_go_net`); the `discord` service gets `NATS_URL: nats://nats:4222` and `depends_on: nats`.
Local dev runs without auth; the credential path is covered by the integration test. Gradle:
`mn.micronaut.rabbitmq` and `mn.testcontainers.rabbitmq` out, `io.nats:jnats` in (catalog entry
replaces `rabbitmq-amqp-client`). Docs updated: README, `docs/discord-bot.md`, `discord/README.md`,
`.claude/rules/events.md`, the `openspec/config.yaml` stack line, and the RabbitMQ wording in
in-flight changes.

### D11. Tests (F.I.R.S.T.)

- **Fast/Independent/Repeatable:** domain code and consumers are tested against an in-memory
  `EventTransport` and a fake subscription; no sleeps, injected `Clock`, no shared static state.
  `RecordingEventPublisher` becomes a thin `EventTransport` recorder.
- Pure unit tests: the subject builder (every published event type yields a legal subject; hostile
  types are rejected; no payload value ever appears in a subject), the stream/consumer config builder
  (filters, ack policy, max age equal the table in D4), `BotConfig` NATS parsing, the `Outcome` to
  ack/nak mapping with a fake `Message`.
- **Integration (tag `integration`, Docker):** `GenericContainer("nats:<tag>").withCommand("-js")`,
  fresh per class. Cases: publish reaches `blackhole-redis-sync` and the handler runs; two instances
  on one durable share work; a duplicate `eventId` is stored once; `nakWithDelay` redelivers; a
  consumer created after a restart still gets stored messages inside max age; a user restricted as
  in D6 is denied a forbidden publish. Waiting uses `fetch(timeout)` or latches, never `Thread.sleep`.
- **Self-validating:** a captured logback appender asserts a publish failure is logged and does not
  throw (replaces the log assertion in `DomainEventDeliveryIntegrationTest`).
- **Timely:** each task group starts with the failing tests.

## Risks / Trade-offs

- Stream ownership: the backend creates the stream; if its credentials lack stream-update rights
  after a config change, startup logs and continues. Create-or-update is a separate, tested class.
- A shared JetStream account quota can be exhausted: `max_bytes` and `max_age` bound it; the account
  limit is set by the operator.
- Cloudflare WebSocket idle closure: mitigated server side (30 s pings); jnats reconnects.
- `micronaut-nats` not chosen: if preferred, only the adapter classes change (D3).
- In-flight changes (`add-discord-appeal-tickets`, and others) mention RabbitMQ; wording only.

## Open Questions (for the user, each with a default)

Decided already (2026-10-24): Velocity moves to NATS over `wss://nats.onelitefeather.dev` and drops
Dragonfly; exposure is the existing Cloudflare Tunnel WebSocket route; Discord and backend stay
in-cluster.

1. **Client library.** Default: **plain `io.nats:jnats` 2.26.4** in backend, bot and plugin.
   Alternative: `micronaut-nats` 5.1.0 in the backend only.
2. **Remove the backend Redis punishment mirror (and the `blackhole-redis-sync` consumer) once
   Velocity has moved?** Default: **yes, as a separate follow-up PR** after the Velocity PR is
   deployed. Dragonfly stays for the shared cache.
3. **Cross-proxy report notices.** Default: each proxy renders the notice from the backend
   `report.created` event plus an HTTP lookup of display facts (add a read endpoint if missing, which
   would be an API change in its own PR). Fallback: the notice stays local to the proxy that took the
   report.
4. **Who creates the `BLACKHOLE` account and users in Kubernetes-FLUX, and when?** Default: the user,
   with `nsc`, before the first deploy, following D6; `docs` ships the permission list and an `nsc`
   snippet.
