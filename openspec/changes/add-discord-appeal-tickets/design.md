# Design

## Context

From `add-appeal-portal` (this change assumes it): the Ban ID (8 chars Crockford, hashed for lookup),
`/public/**` and `PlayerSession`, the case conversation on `case_event` (`message.added|edited|deleted|
redacted`, visibility `PLAYER`/`STAFF`, trust `PLAYER`), `ban_id.leak_marked` / `ban_id.reissued`,
`case.message_*` and `case.ban_id_*` events without content, redaction of message chains, admin
transcript export, the freeze rule (a leak after filing freezes the player side), one open appeal per
punishment.

From before: `Roles` (`ADMIN > MODERATOR > VIEWER`, `PROXY`, `METRICS`) with app-only tokens carrying
`roles`; Velocity authenticates with the client credentials grant
(`ClientCredentialsTokenProvider`); the staff link (`POST /staff-link/code`, confirm by the proxy, one
to one, code stored hashed); `docs/entra-setup.sh` creates the API registration, app roles and the
Velocity client; RabbitMQ topic exchange `blackhole.events`; `@Transactional` unusable (single CTE
writes); infra owns rate limiting; the test rules of F.I.R.S.T.

## Goals / Non-Goals

**Goals:** appeal in Discord without a second account system; one conversation across portal, panel
and Discord; a complete, erasable, exportable record that outlives the channel; a bot that can be down
without hurting anything else.

**Non-Goals:** see the proposal.

## Decisions

### 1. Module `discord`, runtime, JDA

- **Module:** `discord/` in the Gradle build (`include("discord")`), a plain Java application (not
  Micronaut: a long-lived gateway client with a handful of collaborators), `application` + Shadow jar
  like `velocity`. Toolchain follows the repo (Java 21+, 25 in CI).
- **Dependencies** (pinned in the version catalog): `net.dv8tion:JDA:6.7.0` (released 2026-09-20,
  more than two weeks old; the latest on Maven Central at writing; verify again at implementation), voice
  excluded (`opus-java` exclusion, no audio), `com.rabbitmq:amqp-client:5.37.0` (re-verify),
  `:client` (the generated API client, which gains `/discord/**`), Jackson, Logback.
- **Packages:** `config` (env parsing, fail-fast), `auth` (client-credentials token source; reuse the
  Velocity class by moving it to `:phoca` if it has no Velocity types, else a small copy), `backend`
  (interface `BackendGateway` + adapter on the generated client), `gateway` (interface
  `DiscordGateway` + JDA adapter), `ticket`, `sync`, `leak`, `commands`, `Main`.
- **Config (env, validated at start, names listed in the README):**

| Env | Meaning |
|---|---|
| `DISCORD_TOKEN` | bot token (secret, never logged) |
| `DISCORD_GUILD_ID` | the one guild |
| `DISCORD_TICKET_CATEGORY_ID`, `DISCORD_ARCHIVE_CATEGORY_ID` | where ticket channels live and move to |
| `DISCORD_STAFF_ROLE_ID` | the staff role; only its holders **with a live staff link** are added to ticket channels (decision 6) |
| `DISCORD_STAFF_RECONCILE_INTERVAL` | how often channel access is reconciled with links and roles, default `PT5M` |
| `DISCORD_PANEL_CHANNEL_ID` | channel holding the "open appeal" button message (optional) |
| `DISCORD_LEAK_SCAN` | `PUBLIC` (default), `LIST` or `OFF`; `DISCORD_LEAK_SCAN_CHANNELS` for `LIST` |
| `DISCORD_ARCHIVE_DELETE_AFTER` | ISO-8601 duration, default `P30D` (decided 2026-10-23), `PT0S` keeps archived channels; deletion also needs the stored protocol (decision 15) |
| `DISCORD_SYNC_INTERVAL` | cursor poll, default `PT30S` |
| `BLACKHOLE_BACKEND_URL`, `BLACKHOLE_DISCORD_TENANT_ID`, `_CLIENT_ID`, `_CLIENT_SECRET`, `_AUTH_SCOPE` | backend and Entra client credentials |
| `RABBITMQ_HOST`, `_PORT`, `_USER`, `_PASSWORD` | event consumption |

- **Container:** own `Dockerfile` (`discord/Dockerfile`, build from the repo root like `web`), non-root
  user, health endpoint on a local port (JDK `HttpServer`: 200 when the gateway is `CONNECTED` and a
  token is held), compose service, CI image build and smoke check.
- **Gateway intents:** `GUILDS`, `GUILD_MESSAGES`, `MESSAGE_CONTENT` (privileged: enable it in the
  developer portal; allowed for bots in fewer than 100 guilds, otherwise needs approval). **Not**
  `GUILD_MEMBERS` or `GUILD_PRESENCES`: roles of a message author are in the message event or fetched
  by REST on demand, and the member cache is off (`MemberCachePolicy.NONE`), keeping the amount of
  stored personal data small. JDA cache flags for everything the bot does not need are off.
- **Permissions** (granted on the ticket category and the archive category, plus the scanned public
  channels; **never** Administrator): View Channel, Send Messages, Read Message History, Embed Links,
  Manage Channels (create, move, delete in the categories), Manage Permissions (per-member overwrites),
  and **Manage Messages only on scanned public channels** (to delete a leaked Ban ID). Application
  command scope `applications.commands`. The token and permissions are documented in the README.

### 2. Where state lives: the backend, not the bot

The bot has no database. Backend tables (V16):

| Table | Purpose |
|---|---|
| `case_participant` | `id uuid`, `punishment_identifier` FK (cascade), `kind` (`PLAYER`, `STAFF`, `EXTERNAL`, `BOT`), `discord_user_id varchar(24)` null, `display_name` null, `bot` bool, `created_at`, `erased_at` null. Unique `(punishment_identifier, discord_user_id)`. |
| `discord_ticket` | `id uuid`, `ticket_no bigint generated always as identity`, `punishment_identifier`, `appeal_id`, `owner_participant_id`, `state` (`PENDING_CHANNEL`, `OPEN`, `CLOSING`, `CLOSED`, `ARCHIVED`, `DELETED`), `channel_id`, `guild_id`, `frozen bool`, `ingested_cursor` (highest recorded Discord message id), `relayed_seq` (highest case `seq` relayed to Discord), `transcript_count`, `transcript_last_message_id`, `transcript_complete_at`, times. Unique open ticket per `(appeal_id, owner_participant_id)`. |
| `discord_message_link` | `ticket_id`, `message_id uuid` (the case message), `discord_message_id`, `direction` (`INBOUND` from Discord, `RELAYED` to Discord). Unique `(ticket_id, discord_message_id)`. |
| `staff_discord_link` | `oid`, `discord_user_id`, `created_at`; one to one both ways. |

The link table maps ids only (the event rows are immutable). Because everything is in the backend, a
second bot instance or a restart needs only the env.

### 3. Binding a Discord user to a ticket

The player proves the Ban ID **once**, in an ephemeral modal (a modal and its reply are visible only to
the invoker; the Ban ID never appears in a channel or the audit log). `POST /discord/tickets` (the bot
passes the typed value in the body) validates it exactly like the portal login (uniform failure), checks
the appeal state, and on success:

1. creates or finds the `case_participant` for that `discord_user_id` (kind `PLAYER`);
2. files the appeal if none is open and a statement was given (same service and rules as the portal,
   409 with `availableFrom` otherwise), or attaches to the open appeal;
3. creates the `discord_ticket` (`PENDING_CHANNEL`) bound to that participant and returns its number.

The channel then gets a member overwrite for exactly that user id. **The binding is the participant on
the ticket**: only that user's messages are `PLAYER`. A second Discord user presenting the same Ban ID
while a ticket is bound gets the uniform failure (no oracle: it is the same reply as a wrong ID); a
player who lost their Discord account needs staff: an admin closes the ticket, then a new one can be
opened. A leaked Ban ID (`LIMITED` scope) cannot open a ticket and gets the forfeiture text. Staff are
not bound: they see the channel through the staff role.

Why not Discord OAuth: the user decided the Ban ID is the only credential. The cost is accepted: the
Discord user id is bound to the ticket by Ban ID possession.

### 4. Channel lifecycle and mapping to the case

- **Mapping:** one `discord_ticket` per appeal; a punishment can have several tickets over time (after a
  denial and the cooldown), all on the same case conversation, each tagged by `appealId`.
- **Create:** the bot first calls the backend (ticket `PENDING_CHANNEL`, `ticket_no`), then creates
  `appeal-<ticket_no>` (zero padded, **no Ban ID, name or hash in the name or topic**; the topic is
  `blackhole-ticket:<ticketId>`) in the ticket category with overwrites: `@everyone` denied View, the
  **no role overwrite for staff**; each linked staff member who holds the staff role gets a member overwrite to View, Send and Read History (decision 6), the bot is allowed, the player's member id is allowed to View, Send, Read History but
  **denied Attach Files and Embed Links** (no attachments in v1), and finally posts the case's earlier
  `PLAYER`-visible messages in order, then reports `channel_id` to the backend (`OPEN`). Startup finds
  `PENDING_CHANNEL` tickets (a crash between the two steps) and finishes them, matching an existing
  channel by its topic so no duplicate is made.
- **Freeze:** `case.ban_id_leak_marked` on an open ticket sets `frozen`, denies Send for the player and posts
  a notice; `case.ban_id_reissued` restores it. Staff keep writing.
- **Decision:** `appeal.resolved` (the event the backend already publishes) triggers: post the decision
  message (the `PLAYER`-visible staff message), lock (deny Send for the player), final catch-up (decision
  7), `POST .../close`, which records `ticket.transcript_complete` **and stores the ticket protocol** (decision 15),
  and only when that answer carries the protocol version move the channel to the archive category
  (`ARCHIVED`; the backend refuses the state change with 409 `PROTOCOL_REQUIRED` otherwise).
- **Delete policy:** a channel is never deleted before `transcript_complete` exists **and the protocol is
  stored and current**; after `DISCORD_ARCHIVE_DELETE_AFTER` (default 30 days, decided) the bot first calls
  `POST .../protocol/ensure` (forced, idempotent: a late edit makes a new version) and only then deletes it
  (`DELETED`, `ticket.channel_deleted`; the backend refuses `DELETED` with 409 `PROTOCOL_REQUIRED` without a
  current protocol).
  `PT0S` keeps archives forever. Deleting also removes Discord's own copy, which is the point for data
  protection; the case keeps the record. If a human deletes a channel early, the bot's channel-delete handler
  reports it and the case shows a "transcript may be incomplete from message X" banner (decision 7).
- **Re-open:** a closed ticket is not re-opened. A new appeal after the cooldown gets a new ticket
  and a new channel.

### 5. Both-way sync, idempotency, loop prevention, ordering

**Discord to backend (inbound).** For each message in a ticket channel the bot calls
`POST /discord/tickets/{id}/messages` with a batch of items `{discordMessageId, authorDiscordUserId,
isBot, hasStaffRole, createdAt, editedAt?, text, attachments[], embeds[], type}`, `text` being JDA's
`getContentDisplay()` (mentions resolved to names, no raw `<@id>`). The backend resolves the author
(decision 6), writes `message.added` with channel `DISCORD`, `occurred_at` = the Discord timestamp,
and a `discord_message_link` (`INBOUND`) in one statement.

- **Idempotency:** dedupe keys `discord:<channelId>:<messageId>` (add), `...:e:<hash of text>` (edit),
  `...:d` (delete). A replay, a catch-up overlap or a double gateway event inserts nothing twice.
- **Edits:** `MESSAGE_UPDATE` posts the new full text; the backend appends `message.edited` only when
  the text differs from the latest known (the hash in the key). **Deletes:** `MESSAGE_DELETE` (JDA gives
  only the id) posts a delete; the backend appends `message.deleted` for the linked message and keeps the
  last text. A delete of an unknown message id appends a `message.deleted` marked `contentUnavailable`.
- **Loop prevention:** the bot **never ingests messages authored by itself**; those are relays or
  system notices whose record is the backend event they came from. Messages of *other* bots are
  ingested (kind `BOT`, visibility `STAFF`: automation noise is kept in the record but not shown to
  the player in the portal).
- **Visibility of humans:** every human message in the channel is `PLAYER`-visible, because the player
  could read it there. Staff-internal talk belongs in the panel notes; the README and the channel
  topic say so.

**Backend to Discord (outbound).** The bot consumes `case.message_added`, `case.message_edited`,
`case.message_deleted`, `case.ban_id_leak_marked`, `case.ban_id_reissued`, `case.status_changed` and
`appeal.resolved` from a durable queue `blackhole.discord.case` bound to the topic exchange. Events carry no
text, so the bot reads `GET /discord/tickets/{id}/outbox?afterSeq=` (the `PLAYER`-visible events after
its `relayed_seq`, with text) and posts each in order: `**Moderation team:** <text>` for staff,
`**Player (portal):** <text>` for the portal. After posting it calls `POST .../links`
(`RELAYED`, `discord_message_id`) which also advances `relayed_seq`. Edits and deletes of a relayed
message edit or delete the Discord copy (the record keeps the history). A message that originated in
Discord is never relayed back (its link is `INBOUND`).

- **Events are a hint, the cursor is the truth.** A periodic cursor poll per open ticket
  (`DISCORD_SYNC_INTERVAL`) reads the outbox from `relayed_seq`, so a lost, reordered or duplicate broker
  message changes nothing: the queue only makes it fast.
- **At-least-once, once in practice:** if the bot crashes between posting and reporting the link, the
  message is posted twice after restart. The relay carries the case message id in the embed footer
  (`ref <first 8 of the uuid>`, not secret) and the catch-up recognises own messages by it, so it
  re-links instead of re-posting in the common case; a duplicate in that millisecond window is accepted and
  never lost, never re-ingested (own messages are skipped).
- **Ordering:** relays follow case `seq`; inbound batches follow the Discord snowflake. The timeline
  orders by `occurred_at` then `seq`, so a late-ingested message is shown at the time it was written.

### 6. Who wrote it: attribution of Discord authors

Author resolution on ingest, in this order:

| Author | Kind | Trust | Shown as |
|---|---|---|---|
| the ticket's bound participant | `PLAYER` | `PLAYER` | Player (Discord) |
| a user with the staff role **and** a live `staff_discord_link` | `STAFF`, `actor_oid` = the linked `oid` | `VERIFIED` | the staff member's name in the panel; "Moderation team" to the player |
| a user with the staff role and **no** link (only reachable in the window after a link was removed, before the next access reconcile, or for a Discord administrator who sees every channel) | `STAFF`, participant only | `UNVERIFIED` | "unverified staff (Discord)" |
| any other human | `EXTERNAL` | `UNVERIFIED` | other participant |
| a bot (not ours) | `BOT` | `SYSTEM`-like, visibility `STAFF` | bot |

**Recommended: role check plus a linked account for `VERIFIED`.** A role alone is mutable by anyone with
Manage Roles in Discord and proves nothing about who is behind the account; a link alone survives
leaving the staff team. Both together are what the in-game path already accepts (a live `staff_link`
resolved at write time) and give a verified `oid` the panel can show. The link is made with the
existing one-time code (decision 9). A message is judged **at ingest time** from the role flag the bot
sends and the link as it is then; a later role change does not rewrite history. Discord can never
perform a privileged action: no decision, no leak-reissue, no punishment change, only chat. Unverified
Discord staff messages are shown with the unverified badge, never as verified.

**Channel access needs a live link (decided 2026-10-23).** Staff without a linked Discord account are **not
allowed** in ticket channels. The staff role alone grants nothing: the ticket and archive categories give the
role no View permission, and the bot adds exactly the staff members who hold the role **and** have a live
`staff_discord_link` as per-channel member overwrites. A `StaffAccessReconciler` (every
`DISCORD_STAFF_RECONCILE_INTERVAL`, at ticket creation and when a `staff-link.removed` event arrives) reads
`GET /discord/staff-links`, checks each linked user's role by REST (no member cache, no `GUILD_MEMBERS` intent)
and adds or removes the overwrites on every non-deleted ticket channel, so an unlink or a lost role removes
access within the interval. Consequence: a new ticket is invisible to staff who have not linked; the README
and the channel topic say so, and `/link` is the way in. `PermissionSelfCheck` warns when the staff role holds
View on a ticket category. Discord administrators and the guild owner bypass overwrites by design; their
messages are `UNVERIFIED` unless linked.

### 7. Transcript completeness: catch-up, close, completeness event

- **Continuous:** a message is recorded when it is written (the gateway event), not when the ticket
  closes. A cursor `ingested_cursor` (highest recorded message id) is advanced by the backend with each
  accepted batch.
- **Startup catch-up:** on `ReadyEvent` the bot lists tickets in `OPEN`, `CLOSING`, `CLOSED` and
  `PENDING_CHANNEL` and, per channel, reads the history **after** `ingested_cursor` in ascending
  batches of 100 and posts it. Idempotent by message id, so overlap is harmless. This also covers an
  outage of the bot, the backend or the broker: Discord is the durable buffer for everything except
  deletions.
- **Close catch-up (full rescan):** on a decision or admin close, the bot locks the channel and then reads the
  **entire** history from the first message and posts it with `complete: true` and the list of message ids
  present. The backend (a) inserts what is missing, (b) appends `message.edited` for a changed text (an edit
  made while the bot was down), (c) appends `message.deleted` (marked `detectedAtCatchup`) for linked
  messages that are no longer in Discord (deleted while it was down).
- **Completeness:** `POST .../close` `{count, lastMessageId}` is accepted only when the backend's own tally
  of inbound-linked Discord messages equals `count` and `lastMessageId` is the highest id seen; it then appends
  `ticket.transcript_complete` (`ticketId`, `count`, `lastMessageId`, `edits`, `deletes`), sets `transcript_*`
  and, **in the same request**, generates and stores the ticket protocol (decision 15); the answer carries
  `protocolVersion`. If storing the protocol fails the request answers 503 and the event stays; the bot retries
  `close` (idempotent) and does not archive until it gets a version.
  A mismatch answers 409; the bot rescans up to three times, then marks the ticket `CLOSED` **without**
  the event, does not delete the channel and raises a metric and an alert log. The panel shows a banner
  for a ticket with no `transcript_complete`.
- **Limits, stated:** a message that was written and deleted while the bot (not Discord) was down is
  unrecoverable; a channel deleted by a human before a close leaves the gap from `ingested_cursor`. Both are
  visible in the panel (`ticket.channel_deleted` with `transcriptComplete: false`, a banner with the last
  recorded message time) rather than hidden.
- **After the channel is gone:** nothing reads Discord. The panel case page and the portal (player-visible,
  allow-listed types) render the conversation from `case_event`, the admin export includes it and the stored
  protocol (decision 15) is the durable record of the whole ticket.

### 8. Content stored: text, attachments, embeds

- **Text:** `getContentDisplay()`, up to 4000 characters (Discord's limit with Nitro), no `PiiGuard`
  (the record must be complete; the guard is for what our own routes accept).
- **Attachments: metadata only, no file copy in v1 (recommended).** Stored per attachment:
  Discord attachment id, file name, content type, size, and width/height for images. Not stored: the CDN
  URL (Discord signs and expires these links, so a stored one is dead and a URL with a signature is
  itself a credential-like value) and the bytes. Reasons: a copy needs malware scanning, metadata
  stripping, type detection and storage with its own erasure path (the evidence pipeline is built for
  staff uploads, not for strangers' files), the player is denied Attach Files so only staff and other
  bots can post files, and staff who need a file kept for a decision attach it as **evidence** in the panel
  (an audited, retained route). A file name can be personal data, so it is part of redaction. Consequence:
  after the channel is deleted the file is gone; the record shows that and what it was.
- **Embeds:** `{type, urlHost}` only (no title or description from third-party pages).
  **Stickers:** `{kind: sticker, name}`. Reactions are not recorded.
- The portal shows an attachment entry to the player as "attachment (type, size)" without a link.

### 9. Staff linking and attribution routes

The panel's link dialog (code from `POST /staff-link/code`) also works for Discord: in Discord the staff
member runs `/link <code>` (ephemeral); the bot calls `POST /discord/staff-link/confirm` with the code and the
user id. The same rules as the Minecraft link apply: the code is consumed on success, wrong/expired/used are
indistinguishable, one to one in both directions (a new pairing replaces the old and publishes
`staff-link.removed` with reason `REPLACED`). The code kind is not fixed at issue: whichever channel confirms
first claims it, and it is consumed. `/unlink` removes it; the existing anonymize clears it. Both also remove the member's access to ticket channels
at the next access reconcile (decision 6), and anonymising regenerates the stored protocols (decision 15).

### 10. Leak scanning of public channels

- **Scope:** by default every channel and thread of the guild where `@everyone` has View Channel, **except**
  the ticket and archive categories and any channel the bot cannot see; `DISCORD_LEAK_SCAN=LIST` restricts
  it to configured ids, `OFF` disables it. New and edited messages are scanned; history is not.
- **Match:** a candidate is `#` followed by 8 Crockford characters, case-insensitive, optionally with one
  space or hyphen in the middle (`#K7M2 9QXA`), at a word boundary; at most 5 per message. **The `#` is
  required** (a bare 8-character token is every English word and hex number: false positives would be the
  common case). Bare tokens are not detected; that gap is accepted and documented. This is the same
  display form the Velocity screen and the portal use.
- **Verification before acting:** the bot posts the normalised candidates (body of `POST /discord/leak-check`,
  with the channel kind and the author's participant data) and the **backend** looks them up. Only a real,
  current Ban ID has an effect: the backend marks it leaked (`POSTED_PUBLIC`, source `DISCORD_BOT`,
  `poster` = participant id, `posterIsOwner` when the poster is the bound player), deletes sessions, and
  answers `matched: n`. The bot deletes the message **only when `matched > 0`** and posts a short notice
  without any identifier ("A message with a ban code was removed"). A candidate that is not a Ban ID causes
  nothing: no deletion, no event, no record of the token.
- **Never logged:** the bot logs no message content and no candidate; the backend body is not logged.
- **Failure:** the backend being down means no verification and so no action; the bot retries a bounded
  in-memory queue for five minutes, then drops the candidate with a warning counter (no content).
- **Edits and races:** the ID was visible for some time, so it counts as leaked even if it is deleted at once.
  Posting a valid ID is itself the oracle for guessing; the exposure is bounded by Discord's own limits and a
  global infra limit on the route (decision 16).
- **Griefing:** anyone who has a valid Ban ID can forfeit its owner's right by posting it, but could also log in
  with it; the `poster` fields let staff judge and an admin reissue for a leak that was not the player's fault.

### 11. Identifiers, retention, erasure for Discord data

- **Identity stays hashed where the system holds it:** the player's punishment identity is the owner hash as
  before. **Discord user ids are personal data**: stored only in `case_participant.discord_user_id` (and
  `staff_discord_link` for staff), never in `case_event` payloads, domain events, logs, metrics, the export
  or file names. Events reference the opaque `participantId`. `display_name` is a snapshot kept only as long as
  needed to render the panel.
- **Erasure:** `POST /case/{reference}/participants/{id}/erase` (`ADMIN`) nulls `discord_user_id` and
  `display_name` and sets `erased_at`; the player-side erase of the portal change also does it for the bound
  participant. Timeline entries keep their structure with an anonymous author. Message text is redacted by the
  portal change's routes. The panel offers both. Staff anonymize clears `staff_discord_link`. Deleting the
  punishment record cascades participants, tickets and links.
- **Retention:** the transcript and the stored protocol live as long as the case and punishment (evidence rule),
  **pending legal confirmation**. Discord's own copy lives until the archived channel is deleted (30 days,
  decided) and is governed by Discord.
- **Export:** the portal change's ADMIN transcript export includes Discord entries with channel, author kind
  and trust, redaction markers, `ticket.opened`/`ticket.transcript_complete` markers, and never a Discord id.

### 12. Entra and backend integration API

- **App role** `Blackhole.Discord` (allowed member type Applications) on the API registration; a
  `Blackhole Discord (<env>)` client registration with the application permission and a client secret.
  `docs/entra-setup.sh` creates both, assigns the role and writes `BLACKHOLE_DISCORD_*` lines to the env
  file; `docs/entra-setup.md` documents them in its table. `BlackholeRolesFinder` maps the role to
  `Roles.DISCORD` (implies nothing).
- **Least privilege:** `DISCORD` is allowed **only** on `/discord/**`; it has no `/case`, `/punishment` or
  `/appeal` rule. The facade (all `@Version`ed, thin controllers, sealed DTOs):

| Route | Purpose |
|---|---|
| `POST /discord/tickets` | open (Ban ID + statement in body) |
| `GET /discord/tickets?state=` | tickets to sync at start |
| `PATCH /discord/tickets/{id}` | channel id, state, frozen mirror |
| `GET /discord/tickets/{id}/outbox?afterSeq=` | player-visible events with text, for relaying |
| `POST /discord/tickets/{id}/messages` | ingest a batch (idempotent), `complete` flag for rescans |
| `POST /discord/tickets/{id}/links` | relay link, advances `relayed_seq` |
| `POST /discord/tickets/{id}/close` | completeness proof; stores the protocol, answers `protocolVersion` |
| `POST /discord/tickets/{id}/protocol/ensure` | forced idempotent regeneration, answers the current version (deletion gate) |
| `GET /discord/staff-links` | Discord user ids of live staff links, for the access reconcile |
| `POST /discord/leak-check` | candidates, channel kind, poster |
| `POST /discord/staff-link/confirm`, `DELETE /discord/staff-link/{discordUserId}` | link and unlink |

  The actor of every write is the bot's `oid` (`recorded_by_oid`); Discord authors are carried as participants.
- **Domain events:** `discord.ticket_opened|channel_ready|closed|archived|deleted` (ids, no content). Nothing
  else consumes them yet. `case.*` events now have a consumer.
- **Timeline types added:** `ticket.opened`, `ticket.transcript_complete`, `ticket.archived`,
  `ticket.channel_deleted` (visibility `STAFF`; the portal shows "ticket opened/closed" through its own
  allow-list entries only for opened/closed).

### 13. Failure modes

| Failure | Behaviour |
|---|---|
| Bot down | Panel and portal unaffected; relay queue builds in the backend (cursor); on start catch-up reads missed Discord history and relays missed outbox. Deleted-while-down is the only loss. |
| Backend down | Bot retries with backoff, answers modals with a generic "try again later", skips relays; gateway events are not dropped silently: the catch-up covers them. Leak candidates retried for 5 min. |
| RabbitMQ down | Cursor poll keeps sync running at poll latency; the consumer reconnects with backoff. |
| Discord outage / rate limit | JDA queues and backs off; relay and ingest are idempotent so retries are safe. |
| Token expiry (Entra) | Provider refreshes before expiry; 401 forces a refresh and one retry. |
| Duplicate gateway events | Dedupe keys. |
| Channel creation fails | Ticket stays `PENDING_CHANNEL`, retried at start and on a timer; the player is told it is being prepared. |
| Player left the guild | Overwrite is gone; messages stop; the ticket stays open; staff can still decide in the panel; the portal still works. |
| Staff role removed | Next message is judged without the role (unverified); history unchanged. |
| Bot lacks a permission | Startup self-check logs the missing permission by channel and the health endpoint reports it; no partial silent failure. |
| Incomplete transcript | No `transcript_complete`, no protocol, channel not archived or deleted, panel banner. |
| Protocol cannot be stored | `close` answers 503; the bot retries, does not archive and raises a metric; the backend reconciler also retries. |
| Late edit after the protocol | The reconciler makes a new version; the deletion gate forces a current one first. |
| Edge answers 429 on `POST /discord/tickets` or `/discord/leak-check` | Treated like backend-unavailable: generic "try again later" to the player, no tight retry loop, no local cooldown. |
| Staff link removed or role lost | Access removed at the next reconcile; messages before it stay as recorded. |

### 14. Tests (F.I.R.S.T.)

- **Fake JDA layer:** the core never imports JDA; it uses interfaces `DiscordGateway` (create/move/delete
  channel, overwrites, send/edit/delete message, history iterator, member roles) and `BackendGateway`, with
  in-memory fakes and a `ManualScheduler`/`TestClock` the test advances (`runDue()`); no sleeps, no network, no
  Discord. The JDA adapters are thin and covered by a contract test of the interface against the fake plus a
  composition test that builds the JDA builder without connecting (intents, cache flags, permissions).
- **Protocol and access:** `close` stores a protocol and answers its version, 503 when storing fails, archive and delete refused with
  409 without a current protocol, forced `ensure` after a late edit, staff access reconcile table (linked and
  role, linked without role, role without link, unlink, role removed, administrator), 429 treated as unavailable
  with no cooldown state.
- **Cases:** open (success, wrong Ban ID uniform, bound to another user, leaked ID, crash between create and
  channel), relay (order, cursor, restart without duplicate, edit, delete, own-message skip), ingest (author
  table, other bot visibility, edit and delete, replay), catch-up (startup after cursor, close full rescan,
  deleted-while-down, edit-while-down), completeness (mismatch retries then incomplete, success), freeze and
  thaw, delete policy (never before complete), leak (candidate regex table incl. false positives, backend says no
  match so nothing happens, match deletes and notifies, backend down), failure table above.
- **Backend:** integration tests (Testcontainers) for the facade with a self-signed `roles=Blackhole.Discord`
  JWT, least-privilege matrix (DISCORD denied everywhere else, staff denied on `/discord`), dedupe, completeness
  409, participant erasure, cascade, no Discord id or Ban ID in events, logs or export; migration test.
- A bug found while implementing is first reproduced by a failing test.

### 15. The ticket protocol (requirement of 2026-10-23)

A Discord ticket channel is **turned into a protocol and stored with the case before it is archived or
deleted**. The generator, the content, the idempotent versioning, the storage and the GDPR handling are defined
once in the portal change (`appeal-protocol`, `punishment-evidence`, design decision 16 there) and are the same
for every ticket whatever channel it used. This change adds the Discord triggers and gates:

- **Stored as:** evidence of kind `TRANSCRIPT` (not a case attachment): it reuses the S3 `BlobStore`, the audit,
  the access rules and the "lifetime = punishment record" rule of evidence; see the portal design for the reasons
  and for what is deliberately not reused (sniffing, scanner, `PiiGuard`).
- **Generated by the backend from the stored `case_event` rows**, never from Discord, so it equals the record.
  Authors: players pseudonymised as in the case timeline (opaque participant id in the JSON), staff by display
  name; edits, deletes, attachment metadata and the completeness check (count and last Discord message id from
  `ticket.transcript_complete`) are in it.
- **When:** in `POST /discord/tickets/{id}/close`, in the same request that verifies the transcript complete. No
  verified transcript, no protocol.
- **Gates:** archive (`ARCHIVED`) only after the protocol is stored (the answer carries `protocolVersion`;
  409 `PROTOCOL_REQUIRED` otherwise). Delete (`DELETED`) only when a protocol exists **and is current**: the bot
  calls `protocol/ensure` first, which regenerates when the source digest changed (a staff message or edit in the
  archived channel before deletion, a late ingest). The backend enforces both gates, so a buggy or hostile bot
  cannot skip them.
- **Staff:** open or download text or JSON from the case page. **Players:** the portal offers the player-visible
  view only, rendered on demand (portal design decision 16).
- **GDPR:** a redaction or erasure (including erasing the Discord participant, which anonymises the author in
  the next version) purges the stored versions and regenerates, defined in the portal change.

### 16. Bot ingress rate limiting: infra only (decided 2026-10-23)

The bot is one caller, so the backend cannot tell players apart and there is **no per-user cooldown in the
bot or the backend** (repo rule: no app-level rate limiting). Operator docs (task 6.2) state the required edge
rule on the backend host, applied to the bot's client-credentials traffic:

| Route | Required edge rule (suggested starting value) |
|---|---|
| `POST /discord/tickets` | global limit 30 requests per minute, answer 429 |
| `POST /discord/leak-check` | global limit 120 requests per minute, answer 429 |
| everything else under `/discord/**` | no extra rule (chatty by design: ingest, outbox, links) |

The bot treats 429 like "backend unavailable" (failure table). The numbers are a starting point for the
operator to tune; the docs also say that a global limit on a single caller caps the guessing rate of the modal
path as a whole.

## Flyway migrations

`V16__discord_tickets.sql` (next free version): the four tables of decision 2. No existing rows are
affected (new tables); no backfill.

## Risks / Trade-offs

- [The bot is a guessing path for Ban IDs, bypassing the portal's edge rules] -> ingress limit on
  `POST /discord/tickets` and `/discord/leak-check` at the edge only (global, one caller), documented as an
  operator task; no app-level limiter or per-user cooldown (decision 16).
- [Message content privilege] -> `MESSAGE_CONTENT` intent is required; scoped by category/public channels,
  no content logged.
- [Deleted-while-down is unrecoverable] -> stated, bannered, rare.
- [Discord can show staff internal chatter to the player] -> the channel is for the player's conversation; internal
  talk goes to notes; documented in the channel topic.
- [Attachments vanish with the channel] -> staff use evidence upload for what matters; stated in the README.
- [Staff who have not linked cannot see new tickets] -> intended (decision 6); `/link` documented; unverified
  fallback only for administrators.
- [A protocol is a second stored copy of the conversation] -> same redaction purge and regenerate as the portal
  change (decision 15); same lifetime and audit as evidence.
- [Duplicate relay in a crash window] -> accepted, footer marker mitigates.
- [JDA/Discord API churn] -> pinned version, adapter isolates it.

## Migration Plan

Deploy backend (migration, role, facade), create the Entra registration with the script, configure and start
the bot container, create the categories and the staff role, the "open appeal" button message is created at
start when `DISCORD_PANEL_CHANNEL_ID` is set. Rollback: stop the container; the new tables are
ignored; panel and portal are unaffected; open tickets keep their record.

## Decisions (settled 2026-10-23)

These were open questions; the team decided:

1. **Rate limiting of the bot ingress:** at infra only; no per-user cooldown in the bot or backend (decision 16).
2. **Archive retention:** archived channels are deleted after 30 days, and only when the protocol is stored (decision 4, 15).
3. **Staff without a Discord link:** not allowed in ticket channels; the bot adds only role holders with a live link (decision 6).
4. **New requirement:** a ticket is turned into a protocol stored with the case before archive or deletion (decision 15).

No open questions remain except the legal confirmation of the case retention period, which is shared with the portal change.
