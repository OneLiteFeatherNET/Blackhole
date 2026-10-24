---
paths:
  - "backend/src/main/java/**/events/**/*.java"
---

# Domain events

`events/` — Domain event bus over NATS JetStream (plain `jnats`, `libs.nats.jnats`). An event goes to the subject
`blackhole.events.<eventType>` (`EventSubjects` builds it and rejects whitespace, `*`, `>` and empty tokens; only the event type may
ever appear in a subject) and is captured by the stream `BLACKHOLE_EVENTS` (file storage, limits retention, `max_age` 7 days,
`duplicate_window` 2 min, `deny_delete`/`deny_purge`; `NatsStreams` builds the configuration from `blackhole.events.nats.*`).
`StreamProvisioner` creates or updates the stream and the backend's durable consumers at startup (in the background, and again
after a failed publish); a NATS outage or an unreadable `NATS_CREDS_FILE` is logged and never stops the app.

The domain code never touches a broker client: `DomainEventPublisher` depends on the `EventTransport` port (`publish` returns a
`CompletionStage<Void>`), and consumers start through `EventSubscriptionFactory` (`EventConsumer` names a durable consumer and its
event types, see `EventConsumers`). The adapters are `NatsEventTransport` (`publishAsync`, `Nats-Msg-Id` = `eventId` for dedupe)
and `NatsEventSubscriptionFactory` (one pull loop per subscription, explicit ack after the handler returned or failed, reconnects
forever; `NatsConnectionProvider` owns the one connection: jnats' connect blocks while the server is down, so it runs on a background thread and `connection()` fails at once until it is up). Tests use `RecordingEventTransport` /
`FakeEventSubscriptions`, no broker; the NATS adapters are covered by Docker tests against `nats:2.15.1-alpine` (`NatsTestServer`).
`micronaut-nats` was evaluated and rejected (see `NatsCompatibilityTest`).

`RedisSyncConsumer` is the durable pull consumer `blackhole-redis-sync` (filters `punishment.created`/`.updated`/`.expired`/
`.revoked`, `appeal.resolved`; `deliver_policy new` on first creation, `max_deliver 1`, `ack_wait 30s`), shared by all backend
replicas, to keep a Redis-backed read model in sync. Hot reads are not cached here: they live in the shared cache (`cache/`,
Dragonfly over the Redis protocol), and writers evict the keys after their DB write, so no cross-replica broadcast is needed.

There is deliberately no generic connector/webhook/signal-ingestion framework anymore (the
`connector/` package, `WebhookDispatchConsumer`, `WebhookUrlValidator`, and `EloSignalConsumer`
were removed 2026-07-09 to keep the API surface simpler) — external-system integration would
need to be designed fresh if it comes back, including SSRF hardening for any caller-controlled
URL a new design introduces (see CLAUDE.md's cross-cutting conventions).

The Discord bot (`discord` module, plain `jnats`, `NatsConsumer`) creates its own durable pull consumer `blackhole-discord-notify` on the stream
`BLACKHOLE_EVENTS` (the stream's 7 day max age bounds redelivery; `max_ack_pending` 5, explicit ack, a retry is `nakWithDelay(10 s)`) for
`report.created`, `report.resolved`, `appeal.submitted` and `appeal.resolved`; the
events carry only ids and hashes, the bot reads display facts from `GET /discord/notifications/**`. The
`discord-link.created|removed` events carry kind and subject, never a Discord user id.

Discord ticket events: `discord.ticket_opened|channel_ready|closed|archived|deleted` are published by `TicketService` after the write (in a try/catch) with
`{ticketId, ticketNo, appealId, punishmentIdentifier, owner (hashed), occurredAt}`: never a Discord user id, a Ban ID, a channel id or text. Nothing
consumes them yet. The bot's own durable consumer for the case events is `blackhole-discord-case` (filtering `case.message_*`, `case.ban_id_*`,
`case.status_changed`, `appeal.resolved` and the link-removed events, same settings as `blackhole-discord-notify`): created by the bot, a *hint* only. The truth is the
per-ticket cursor read from `GET /discord/tickets/{id}/outbox`, so a lost, duplicated or reordered broker message changes nothing. Messages written in
Discord are published as `case.message_added|edited|deleted` too (channel `DISCORD`), once per newly appended event.

Publishing must stay non-blocking: `NatsEventTransport` uses `publishAsync` and `DomainEventPublisher` only attaches a failure logger to
the returned stage. Never wait for the ack on the calling thread (it may be a Netty event-loop thread).

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

`case.message_added`, `case.message_edited`, `case.message_deleted`, `case.message_redacted`, `case.ban_id_leak_marked`
and `case.ban_id_reissued` are published by `CaseConversationService` (via `CaseEventPublisher`) with the same
envelope plus `messageId`, `authorKind`, `channel`, `visibility` (messages), `reason`/`source`/`afterFiling` (leak) or
`reason`/`fromGeneration`/`toGeneration` (reissue). Never message text, Ban IDs, file names or Discord identifiers; a player
message that moves a `WAITING` case also publishes `case.status_changed` by `SYSTEM`. The decision message of an appeal
publishes nothing (the existing `appeal.resolved` announces the decision). `transcript.exported` is not published.

- A filing through the public API publishes `appeal.submitted` like the staff route (ids and hashed owner, `eligible: true`); nothing in `/public` publishes or logs a Ban ID or text.
## Protocol events

`case.protocol_stored` is published by `AppealProtocolService` after a protocol version was stored: `{appealId, version, evidenceId, entries,
punishmentIdentifier, owner, occurredAt}`, no content, in a try/catch. The timeline records `protocol.stored` (staff visibility, not in
`PORTAL_VISIBLE_TYPES`). The stored file also announces itself as `evidence.attached` (kind `TRANSCRIPT`) and a purge as `evidence.deleted`
(reason `ERASURE`, or `DUPLICATE` when a concurrent identical run lost). Nothing binds to these keys yet.

## Client rule events

`client-rule.hit` is published by `ClientRuleHitService` for every stored hit (non-blocking, like the others) with
`{rule, owner (hashed), kind, action, at}` only: never a brand, channel, mod or command text, a player name, a server or the
rule's patterns (`ClientRuleNoLeakTest`). Nothing binds to it yet.

`evasion.client_match` is published by `ClientFingerprintService` when a stored client fingerprint links two or more
owners (and not more than `max-owners`): `{owners (hashed), at}` only, never the fingerprint or a trait. Nothing binds to it yet.
