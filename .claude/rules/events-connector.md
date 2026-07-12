---
paths:
  - "backend/src/main/java/**/events/**/*.java"
  - "backend/src/main/java/**/connector/**/*.java"
---

# Domain events & connector framework

**`events/`** — Domain event bus over RabbitMQ. `BlackholeRabbitTopology` declares two
exchanges at channel-creation time (not via config, since this Micronaut RabbitMQ version lacks
declarative exchange config): a topic exchange `blackhole.events` for domain events consumed by
other services, and a fanout exchange `blackhole.cache.invalidate` for cross-replica Caffeine
cache invalidation so the API can scale horizontally. `WebhookDispatchConsumer` fans domain
events out to connector-registered webhooks using a native RabbitMQ TTL+DLX retry loop (not an
in-process retry) — after `max-retries` a dispatch parks in `blackhole.webhook.failed` instead
of retrying forever.

**Connector framework** (`ConnectorController`, `ConnectorScopes`, `ConnectorRegistrationEntity`,
`EventSubscriptionEntity`) — external systems (anti-cheat tools, dashboards, other backends)
integrate via a connector registration with declared scopes (currently descriptive metadata
only, not enforced — this codebase has no auth layer at all, see CLAUDE.md), a generic
`SignalController` (`/signal`) ingestion endpoint, and signed webhook delivery for subscribed
event types, rather than bespoke per-integration code.

Webhook delivery URLs are arbitrary caller input to the open `ConnectorController` endpoint.
`WebhookUrlValidator` enforces SSRF hardening (`blackhole.webhook.allow-private-networks` must
stay `false` outside local dev/loopback testing); `InvalidWebhookUrlException` is the standard
rejection path. Any new feature accepting a caller-controlled URL needs equivalent hardening.
