## Context

The code this design builds on (checked on `origin/main`, 2026-10-28):

- Staff writes come from four surfaces. The panel and the API send a verified Entra token
  (`security/Actor`, `ActorKind.HUMAN`). The Velocity proxy sends a `PROXY` token and may vouch for
  a staff member through `onBehalfOf` (`verified-actor`, "Delegated attribution by the proxy").
  The Discord bot sends a `DISCORD` machine token and has no attribution today.
- `case_event` (V12) is the per-punishment timeline. `punishment_identifier` is NOT NULL with a
  foreign key to `punishment_case`, and rows can be redacted (`redacted_at`) and carry a visibility.
- `evidence_audit` (V8) audits evidence only. `elo_events` audits ELO only. `staff_actor` (V9) holds
  one row per Entra object id and clears its display name on anonymization.
- Domain events are published through `events/DomainEventPublisher` to NATS JetStream subjects
  `blackhole.events.<type>` on the stream `BLACKHOLE_EVENTS` (`events/EventSubjects`). The subject
  may hold only the event type (no ids, no payload values).
- The `discord` bot is stateless, already consumes NATS through `notify/NatsConsumer` (a durable
  consumer with ack, retry and drop), and posts to Discord through JDA. Its `BotConfig` already has
  `BLACKHOLE_PANEL_URL` (the panel base URL) and a `DISCORD_*` secret (`DISCORD_TOKEN`).
- The Helm chart (`deploy/charts/blackhole`) reads every secret from an existing Secret named in
  values (`discord.existingSecret`), with the keys listed in `README.md`.
- The panel routes that the links point to exist: `/cases/:reference` (short id, `punishment.short_id`
  from V7) and `/players/:owner` (hashed owner). The Ban ID is a separate column (V13, `banid`).
- Flyway: `V27__kick.sql` is the head on `main`. The migration number of this change is assigned at
  implementation time (next free number on main; main is at V27 today).
- `openspec/config.yaml` principles that apply: GDPR-by-design (owner hashes, no raw UUID or IP in
  published data), layered backend, append-only migrations, infra owns rate limiting, and the actor
  of every write is the token's `oid`. The amendment `amend-data-protection-principle` allows player
  names where a feature needs them. Anything published still carries no IP, UUID or name. A Discord
  channel is internal, but the event bus is read by other consumers, so the event carries only what
  the team needs to read (see decision 4).

## Goals / Non-Goals

**Goals**: one entry per staff action on every surface; the team sees each action in a channel as it
happens; each entry leads straight to the case or player; the Ban ID never leaks; a failing webhook
never stops a staff member from working.

**Non-Goals**: a full SIEM; a replacement for the domain records; auditing automatic system actions;
making the webhook a source of truth.

## Decisions

### 1. Source of truth: a new append-only `staff_audit` table, not `case_event`

Add `staff_audit` with: `seq bigint GENERATED ALWAYS AS IDENTITY` (ordering and cursor),
`identifier uuid` (PK, also the event id), `occurred_at bigint`, `actor_oid uuid`, `actor_name varchar(256)`
(snapshot at write time), `surface varchar(16)` (`PANEL`, `DISCORD`, `IN_GAME`, `API`), `action varchar(48)`,
`target_kind varchar(16)` (`CASE`, `PLAYER`, `SECTION`, `SERVER`, `NONE`), `target_ref varchar(255)` (case
short id, owner hash, section key or server name), `target_name varchar(64)` (player name, nullable),
`detail varchar(200)` (short fixed text, never a Ban ID, IP or UUID), `recorded_by_oid uuid` (the caller
that sent the request, for a proxy or bot attribution). Indexes on `(occurred_at, seq)`, `(actor_oid, seq)`,
`(action, seq)`. No foreign keys, so retention and anonymization never block history.

Rejected: **reusing `case_event`**. It belongs to one punishment (non-null foreign key), so a staff
action without a case (a client rule, a section, a template, an import, a staff link) has no home; its
redaction and visibility rules are for player-facing conversation, which an audit entry must never be;
and adding a nullable case key would make every timeline query carry the audit rows. **Reusing
`evidence_audit`**: it covers evidence only. **Only the NATS stream as the record**: JetStream retention is
short and not queryable by the panel.

### 2. Write path: after the action, in the same request, no transaction

Each staff write service calls `StaffAuditRecorder.record(...)` after its action has succeeded and before
it answers. `@Transactional` is unusable in this codebase (`blackhole-transactional-gap`), so the entry and
the action are not atomic. The rule therefore is: the action is never rolled back because the audit insert
failed. A failed insert is logged (without the payload), counted in `blackhole_audit_write_failed_total`,
and answered in the response as a header `X-Audit: unrecorded` so the panel can show it. The `staff.action`
event is published after the insert succeeds, so every event has a row.

Rejected: **write the audit row before the action** (a failed action would leave a row claiming it
happened). **A transactional outbox** (correct, but a second table, a relay and a dispatcher for a log
that already has one writer per request; can follow if the unrecorded counter ever moves).

### 3. Surface and actor

The surface is derived by the backend from the verified caller, never from a client field:

- `PROXY` caller with `onBehalfOf` → `IN_GAME`, actor = the staff oid, `recorded_by_oid` = the proxy oid.
- `DISCORD` caller with `onBehalfOf` → `DISCORD` (`add-discord-staff-ticket-actions`).
- `HUMAN` caller (Entra token, panel or API client) → `PANEL` when the request carries `X-Blackhole-Surface: panel`
  (the panel's own client sets it), otherwise `API`.

The `PANEL` vs `API` split is a declaration, not a verification: a staff member with a token can call the
API with the panel header. This is accepted because the audit is an internal record of intent, not an
access control; the trust model stays the one in `staff-authentication`. Rejected: **a surface field in the
request body** (client-supplied and unnecessary); **separate tables per surface** (one question is asked
of one table).

### 4. Event `blackhole.events.staff.action`

Published once per entry on the existing stream. Payload (version 1): `auditId`, `occurredAt`, `surface`,
`action`, `actorName` (display name snapshot or null), `actorOid`, `targetKind`, `targetRef`, `targetName`
(nullable), `panelPath` (for example `/cases/AB12CD34` or `/players/<owner>`). No Ban ID, no IP, no raw UUID,
no reason text. `targetRef` for players is the owner hash the panel already uses in its URLs. The subject is
the fixed event type, as every subject in `EventSubjects`.

Rejected: **the bot looks up names through the backend** (an extra route and an extra round trip per entry,
and the bot would need a read route with more scope than it has). **Put the whole reason text in the event**
(reason text can contain a player's appeal material; the team reads the reason in the panel, the channel
gets the action and the link only; this keeps the channel small and the event free of free text).

### 5. Delivery: the `discord` bot posts to a webhook

The bot consumes `blackhole.events.staff.action` through a durable JetStream consumer named
`discord-audit` (ack explicit, `MaxAckPending` 1 so entries are processed in order, a redelivery
delay for retries) and posts each entry to `DISCORD_AUDIT_WEBHOOK_URL`.

- **Rate limits**: a `429` answer is retried after the `retry_after` value in the body (seconds, float),
  falling back to the `Retry-After` header; the consumer naks with that delay and does not drop the entry.
- **Server errors and network errors** (`5xx`, timeouts): retried with exponential backoff, capped at
  five minutes, nak with delay; the entry stays in JetStream until its retention ends.
- **Client errors other than 429** (`400`, `401`, `404`, a deleted webhook): not retried, logged once
  with the HTTP status and the entry's `auditId` (never the URL), counted as `discord_audit_rejected_total`,
  and acked, so a broken webhook cannot pile up the stream. The backend table is still complete; a
  maintainer can re-post from the Audit view later (open question).
- **Ordering**: one in-flight message at a time, so the channel order is the event order within the bot.
  Across a restart the order of the entries not yet acked is kept by JetStream.
- **Duplicates**: at-least-once. A crash between the post and the ack posts the entry twice. Each message
  ends with the `auditId` in its footer, so a duplicate is recognizable. Exactly-once would need a Discord
  side dedupe that a webhook does not offer.
- **Failure never blocks the action**: the staff write has already completed and answered when the event is
  published; the bot is not in the request path.

Rejected: **the backend posts to the webhook** (it runs as several replicas, so every replica would post every
entry unless a claim table is added; it would also hold the Discord secret); **Velocity posts** (in-game only,
and the proxy is subscribe-only by decision D6/D7 of `switch-event-bus-to-nats`); **a dedicated worker
service** (one more deployment for a poster that the bot already is).

Startup validation: the webhook URL must match `^https://(discord\.com|discordapp\.com)/api/webhooks/[0-9]+/[A-Za-z0-9_.-]+$`.
A missing or non-matching value stops the bot at startup with a message that names the variable and not the
value (the same style as the other `BotConfig` problems). Rejected: **run without the audit when the URL is
bad** (the team would not know entries are missing; a loud startup failure is the cheaper outcome). The URL is
never logged, not at debug level, and is redacted from any exception text (a test asserts this).

### 6. Message and link

Each message is one embed: title = action in plain words, fields = staff (display name), surface, target
(case short id or player name), time (Discord timestamp of `occurredAt`), and a link button or an inline link to
`<panelUrl><panelPath>`. The footer carries `auditId`. `panelUrl` is the existing `BLACKHOLE_PANEL_URL`.
Discord link buttons are not interactive, so the team reads from the channel and clicks through.

### 7. Read route and panel view

`GET /admin/audit` (ADMIN only, `@Secured(Roles.ADMIN)`), query `limit` (1 to 100, default 50), `cursor` (the
`seq` of the last row of the previous page, opaque to the client), optional `action`, `actorOid`, `targetRef`.
Newest first. The response is cursor-paginated with `nextCursor`, following `standardize-api-pagination-and-problems`.
The panel has an Audit tab in the admin area (`/admin/audit`), read-only, no edit or delete control. The route is
in the controller family `AuditApi` with OpenAPI annotations on the interface; `AuditController` stays thin and
`AuditService` answers `AuditDTO.Error` for expected failures.

Rejected: **offset pagination** (rows arrive constantly; offsets skip or repeat rows). **A full-text search**
(out of scope; filters on indexed columns are enough for a team of this size).

### 8. Retention and anonymization

- Rows older than `blackhole.audit.retention` (default `P365D`, proposed) are deleted by a sweeper that runs
  every `blackhole.audit.sweep-interval` (default `PT1H`) in batches of 500, using the injected `Clock`.
- Staff anonymization (`StaffActorService`, admin route `POST /admin/staff-actor/{oid}/anonymize`) also sets
  `actor_name = NULL` on that oid's audit rows. The `actor_oid` stays, as it does in every other table, so
  the record of who did what is kept without a name.
- The NATS stream keeps events for seven days (proposed stream limit for `BLACKHOLE_EVENTS`, to be checked against
  `StreamProvisioner` at implementation time; the table is the record).

### 9. Configuration

Backend: `blackhole.audit.retention`, `blackhole.audit.sweep-interval`, `blackhole.audit.panel-base-url`
(env `BLACKHOLE_PANEL_URL`, the same variable the bot uses, one value per deployment). Each tunable has a comment
in `application.yml` and env defaults inline, as in `CLAUDE.md`. Bot: `DISCORD_AUDIT_WEBHOOK_URL` (secret, in
the bot's Secret). The channel is the webhook's, so no channel id is configured.

## Risks / Trade-offs

- **Unrecorded actions**: a failed insert leaves an action without an audit row. Mitigated by the counter, the
  `X-Audit` header and an alert on the counter; not eliminated. Accepted because the alternative blocks staff work.
- **Duplicate posts** after a crash between post and ack. Mitigated by the footer id.
- **A bot outage delays the channel, not the record**: entries wait in JetStream (seven days) and are posted when
  the bot is back. A bot outage longer than seven days loses channel posts; the table still has them.
- **Surface declaration** (`PANEL` vs `API`) is not verified (decision 3).
- **Player names in the event and the channel**: allowed by the amendment for internal use; a published page or a
  third-party event never carries them.

## Migration plan

1. `V<next>__staff_audit.sql` creates `staff_audit` and its indexes. Additive only; no existing row is backfilled
   (the history before the table is in the domain records, as before).
2. The recorder ships first, called from the services; the table fills from the first deploy.
3. The event and the bot consumer ship next. The consumer starts at `DeliverNew` so the channel does not get
   a flood of old entries on first start; a one-off backfill is an open question.
4. The panel Audit view and the client spec version ship last.
5. Rollback: the recorder can be switched off by removing its calls (a code revert). The table can stay. The
   consumer can be removed without touching the backend.

## Open questions

1. **Retention**: one year is proposed. Confirm the period with the team and the data protection record before
   the sweeper goes live.
2. **Logging reads**: should opening the Audit view or the case audit be an audited action? Proposed: no in this
   change (a read is not an action, and an audited read would make the log grow with every page view).
3. **Backfill to the channel**: post the last N days to the channel once, or not at all? Proposed: not at all;
   the Audit view holds them.
4. **Surface trust**: is the `PANEL` / `API` declaration acceptable to the team, or should the panel's surface be
   fixed by the BFF (portal) that calls the API on the staff's behalf? Decide before the Audit view is read as
   evidence.
5. **Unposted entries**: after a `4xx` from the webhook, should the maintainer have a re-post button in the Audit
   view? Proposed: later, in a separate change.
6. **Webhook per environment**: one webhook per deployment (proposed) or one per team channel? One per deployment.
