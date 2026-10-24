# Proposal

**Ships as:** `refactor(events)`, `feat(events)`, `feat(discord)`, optionally `feat(velocity)`,
`build` and `docs`, as a stack of small pull requests, one Conventional Commits type each (see
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
- **BREAKING (operational, not API):** deployments must provide NATS and the `BLACKHOLE` account
  and drop `RABBITMQ_*`. No HTTP API, OpenAPI spec, generated client or database schema changes,
  so no client regeneration and no Flyway migration. The Velocity plugin is unaffected unless
  Question 1 in `design.md` is answered with "yes".

## Findings that differ from the brief

- **Velocity does not consume RabbitMQ.** It reads Dragonfly (Redis protocol): keys plus pub/sub
  channels `blackhole.punishment.sync`, `blackhole.section.changed`, `blackhole.report.created`,
  `blackhole.report.feedback` (`velocity/.../redis/RedisTopology.java`). `section.changed` is a
  domain event published to the bus *and* mirrored to a Redis channel by the backend. So moving
  the bus does not touch Velocity. Putting Velocity on NATS is a separate, optional step.
- **There is no `blackhole.cache.invalidate` fanout exchange on main.** Hot reads moved to the
  shared Dragonfly cache and writers evict keys (`shared-cache` spec, `.claude/rules/events.md`),
  so no cross-replica broadcast exists to port. This change adds none.

## Out of scope

- Moving Velocity's Redis pub/sub onto NATS (optional PR, gated by a user decision).
- Kafka/Strimzi, and any new event type, payload field or consumer.
- Provisioning the NATS account and users in Kubernetes-FLUX (that repository carries the operator
  and account JWTs; this change specifies what to request, `tasks.md` 0.x tracks it).
- Authentication of the HTTP API, application-level rate limiting, `@Transactional`.
- Dual-running RabbitMQ and NATS (see `design.md`, decision D8).

GDPR: events keep carrying hashed owners and ids only. Subjects are event *types* (a fixed,
public vocabulary); no Ban ID, owner hash, punishment id, raw UUID or Discord id is ever placed in
a subject, a header (other than the random `eventId`) or a consumer name.
