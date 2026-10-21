---
paths:
  - "backend/src/main/java/**/events/**/*.java"
---

# Domain events

`events/` — Domain event bus over RabbitMQ. `BlackholeRabbitTopology` declares exchanges at
channel-creation time (not via config, since this Micronaut RabbitMQ version lacks declarative
exchange config): a topic exchange `blackhole.events` for domain events consumed by other
services. `RedisSyncConsumer` binds a queue to specific routing keys
(`punishment.created`/`.expired`/`.revoked`, `appeal.resolved`) on the topic exchange to keep a
Redis-backed read model in sync. Hot reads are not cached here: they live in the shared cache
(`cache/`, Dragonfly over the Redis protocol), and writers evict the keys after their DB write, so
no cross-replica broadcast is needed.

There is deliberately no generic connector/webhook/signal-ingestion framework anymore (the
`connector/` package, `WebhookDispatchConsumer`, `WebhookUrlValidator`, and `EloSignalConsumer`
were removed 2026-07-09 to keep the API surface simpler) — external-system integration would
need to be designed fresh if it comes back, including SSRF hardening for any caller-controlled
URL a new design introduces (see CLAUDE.md's cross-cutting conventions).
