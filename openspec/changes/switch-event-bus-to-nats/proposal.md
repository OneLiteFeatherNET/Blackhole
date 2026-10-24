# Proposal

**Ships as:** `refactor(events)`, `feat(events)`, `feat(discord)`, `feat(velocity)`, `refactor(velocity)!`,
`build`, `docs` and an optional `refactor(punishment)`, as a stack of small pull requests, one Conventional Commits type each (see
`tasks.md`). The change as a whole is a transport swap: the event envelope, the event types and the
GDPR rules for payloads do not change.

## Why

Blackhole is going to the OLF Kubernetes cluster `feather-core`. That cluster runs no RabbitMQ. It
runs a 3-node NATS cluster with JetStream (`foundation/messaging/base/nats` in Kubernetes-FLUX)
and Strimzi Kafka. Operating a RabbitMQ only for Blackhole would add a broker to patch, back up and
monitor, while NATS is already operated, monitored and reachable from outside the cluster. The user
decided on 2026-10-24 to move the domain event bus to NATS.

The bus is small and loosely coupled, which makes the move cheap now and expensive later:

- one publisher (`DomainEventPublisher`), about 16 event families, fire-and-forget;
- three durable consumers: the backend `RedisSyncConsumer` (`blackhole.redis.sync`) and the two
  Discord bot queues (`blackhole.discord.notify`, `blackhole.discord.case`);
- every consumer already treats the bus as a *hint* (Redis sync self-heals on the next mutation
  and every proxy falls back to HTTP on a miss; the bot reads cursors from the backend), so
  exactly-once delivery is not load-bearing.

## What Changes

- **Subjects.** The topic exchange `blackhole.events` becomes the subject hierarchy
  `blackhole.events.<event type>` (e.g. `blackhole.events.punishment.created`). The event type
  stays the routing identity; the `DomainEvent` JSON envelope is byte-identical.
- **JetStream stream** `BLACKHOLE_EVENTS` (file storage, limits retention, 7 days max age) captures
  `blackhole.events.>`. It replaces the durable queues' 7 day TTL.
- **Durable pull consumers** replace the durable queues: `blackhole-redis-sync` (shared by all
  backend replicas, so each event is processed once), `blackhole-discord-notify` and
  `blackhole-discord-case`, with the same routing-key filters as today.
- **Dedupe.** The publisher sets the `Nats-Msg-Id` header to the event's `eventId`, so a publish
  retry inside the stream's duplicate window is stored once.
- **A transport port in the backend** (`EventTransport` for publishing, `EventSubscription` for
  consuming) so the domain code and tests depend on an interface, not on a broker client. The
  RabbitMQ classes (`BlackholeRabbitTopology`, `RabbitTopology`, `DomainEventRabbitPublisher`) are
  deleted.
- **Discord bot:** `RabbitConsumer` becomes a `NatsConsumer` with the same `Outcome` handling
  (done: ack, retry: delayed nak, drop: ack). `RabbitSettings` becomes `NatsSettings`.
- **Auth and exposure** follow the feather-core setup: a dedicated NATS account `BLACKHOLE` with
  one least-privilege user per client (backend, Discord bot, and Velocity if it joins), `.creds`
  files, TLS on every path that leaves the cluster (see `design.md`).
- **Configuration:** `RABBITMQ_*` is removed; `NATS_URL`, `NATS_CREDS_FILE` and a few tunables
  replace it, documented in `application.yml` and the bot's `BotConfig`.
- **Local development:** the compose service `rabbitmq` is replaced by `nats` with JetStream.
- **Velocity moves to NATS and drops Redis/Dragonfly** (user decision 2026-10-24). The proxy runs
  outside feather-core and gets no Dragonfly access. It keeps the HTTP API as the truth and receives
  punishment, section and report changes as core subscriptions over `wss://nats.onelitefeather.dev`
  with its own subscribe-only, WebSocket-only credential. If NATS is unreachable it discards its
  local cache and checks over HTTP (fail open to the fallback, never open to the player).
  Two PRs: receive over NATS, then remove Redis from the proxy. Discord and the backend stay
  in-cluster.
- **BREAKING (operational and plugin config, not HTTP API):** deployments must provide NATS and the
  `BLACKHOLE` account and drop `RABBITMQ_*`; the Velocity `config.json` loses `redisUri` and gains
  `nats.url` and `nats.credsFile`. No HTTP API, OpenAPI spec, generated client or database schema
  changes (unless open question 3 adds a report read endpoint, in its own PR), so no Flyway migration.

## Findings that differ from the brief

- **Velocity does not consume RabbitMQ.** It reads Dragonfly (Redis protocol): the mirrored
  punishment keys, the shared-cache lists, and pub/sub channels `blackhole.punishment.sync`,
  `blackhole.section.changed`, `blackhole.report.created`, `blackhole.report.feedback`
  (`velocity/.../redis/RedisTopology.java`), with an HTTP fallback whenever Redis is unavailable.
  Moving the bus alone would not touch it; the user's decision above moves it onto NATS anyway.
- **There is no `blackhole.cache.invalidate` fanout exchange on main.** Hot reads moved to the
  shared Dragonfly cache and writers evict keys (`shared-cache` spec, `.claude/rules/events.md`),
  so no cross-replica broadcast exists to port. This change adds none.

## Out of scope

- Removing the backend's Redis punishment mirror (optional follow-up PR 9; Dragonfly stays for the shared cache).
- Kafka/Strimzi, and any new event type, payload field or consumer.
- Provisioning the NATS account and users in Kubernetes-FLUX (that repository carries the operator
  and account JWTs; this change specifies what to request, `tasks.md` 0.x tracks it).
- Authentication of the HTTP API, application-level rate limiting, `@Transactional`.
- Dual-running RabbitMQ and NATS (see `design.md`, decision D8).

GDPR: events keep carrying hashed owners and ids only. Subjects are event *types* (a fixed,
public vocabulary); no Ban ID, owner hash, punishment id, raw UUID or Discord id is ever placed in
a subject, a header (other than the random `eventId`) or a consumer name.
