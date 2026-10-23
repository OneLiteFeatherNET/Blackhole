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

Publishing must stay non-blocking: `DomainEventRabbitPublisher.publish` returns a `Publisher<Void>`
and `DomainEventPublisher` subscribes fire-and-forget. A `void` `@RabbitClient` method blocks on a
Mono, which Reactor refuses on Netty event-loop threads (where controllers and services run).

## Evidence events

`evidence.attached` and `evidence.deleted` are published by `EvidenceService` (payloads built in
`EvidenceEvents`). The payload is `{evidenceId, punishmentId (null while the item is pending),
owner, kind, sourceSystem}`; `evidence.deleted` adds `reason` (`MANUAL` for one admin delete,
`ERASURE` for an erase-by-owner, `CASCADE` when the punishment record is deleted,
`UNCLAIMED_EXPIRED` when pending evidence expires). Never put the payload, reference, file name or
any other content in an event: the broker is a second at-rest copy. Claiming pending evidence for a
new punishment publishes no event (the item was announced when it was attached). Nothing binds to
these keys yet; the service wraps `publish` in a try/catch so a broker problem never fails the
request that already changed the evidence.

## Case events

`case.note_added`, `case.note_edited`, `case.note_redacted`, `case.status_changed`,
`case.assignee_changed`, `case.tags_changed` and `case.priority_changed` are published by `CaseService`
(payloads built in `CaseEvents`): `{caseEventId, punishmentIdentifier, owner, seq, actorTrust, actorOid,
occurredAt}` plus the non-content change (`from`/`to`, tag lists, `noteId`, redaction reason code). Never
note text. Nothing binds to them yet (the future Discord sync). Lifecycle facts keep their
`punishment.*`/`appeal.*`/`evidence.*` keys.
