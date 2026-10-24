# Proposal

**Ships as:** `feat(discord)` (backend integration API and the `discord` module), `feat(web)` (panel),
`docs(discord)`, `ci(discord)`. One type per commit, see the PR plan in `tasks.md`.

**Builds on:** `add-appeal-portal` (Ban ID, `/public/**`, the case conversation, leak and reissue,
transcript export, redaction). That change must land first; this proposal is branched from it.

**Phase 0 (the bot MVP) ships first:** the `discord` module, the `Blackhole.Discord` role, the staff and player Discord account link (`/link`, `/unlink`, `/whoami`) and Discord notifications for new reports and appeals, as small stacked PRs. Tickets, sync, transcripts and leak scanning follow in the phases below on top of it (see design, "Phase 0").

## Why

Many players live on Discord and will not open a web portal. The team wants them to appeal there,
in a private channel, and to keep **one** conversation: what a player writes in Discord, in the
portal or what staff write in the panel must show up everywhere, and the whole history must stay
as a record after the ticket and the channel are gone.

## What Changes

- **A new Gradle module `discord`**: a JDA bot in its own container. It talks to the backend with
  **client credentials** (a new Entra app role `Blackhole.Discord`, like the proxy's), consumes the
  `case.*` events from NATS and reads or writes through a small `/discord/**` facade. The bot is
  stateless: tickets, message links and cursors live in the backend. Panel and portal keep working
  when the bot is down.
- **One private channel per ticket** (appeal), created in a ticket category after the player enters
  the Ban ID in an ephemeral modal (slash command or button). Locked when the ticket is frozen or
  decided, converted into a stored **protocol** and archived with a verified transcript, deleted 30 days later.
- **Both-way sync, idempotent and loop-free**: portal and panel messages appear in the channel;
  everything written in the channel (players, staff, other bots), with edits and deletes, is
  recorded on the case timeline as `message.added` / `message.edited` / `message.deleted`.
- **The transcript is the backend's**: every channel message is persisted as it happens; missed ones
  are backfilled on restart and on close from the Discord history (idempotent by message id); a
  closing ticket gets `ticket.transcript_complete` with the count and last message id. After the
  channel is gone the history stays in the panel and, player-visible only, in the portal.
- **The ticket becomes a protocol before the channel goes**: when the transcript is verified complete, the
  backend generates the complete protocol (human-readable text plus JSON) from the stored case events and stores
  it with the case as evidence of kind `TRANSCRIPT` (defined in `add-appeal-portal`). The channel is archived
  only after the protocol is stored and deleted (after 30 days) only while a current protocol exists. The same
  protocol is made for portal and panel appeals.
- **Attribution and access of Discord staff**: a staff member links their Discord account with the existing
  staff-link code; a message is `VERIFIED` only with a live link **and** the staff role at the time of
  writing, otherwise it is shown as unverified. Staff without a link are **not allowed** in ticket channels:
  the bot adds only role holders with a live link.
- **Settled with the team (2026-10-23):** bot ingress limited at infra only (no per-user cooldown), archived
  channels deleted after 30 days, linked staff only.
- **Leak scanning** of public channels: `#`-prefixed Ban ID candidates are checked against the
  backend; only a real Ban ID is acted on: the message is deleted and the Ban ID marked leaked.
- Discord users are stored as erasable **participants**; events reference an opaque participant id.
- Entra: `docs/entra-setup.sh` and `docs/entra-setup.md` gain the `Blackhole.Discord` role and a
  `Blackhole Discord (<env>)` registration.
- Client spec `blackhole-api-0.0.N.yml` (next free number at implementation time).

Not breaking: all additions. The generated client gains the `/discord/**` operations.

## Out of scope

- General support tickets, reports, ban requests or any non-appeal ticket type.
- Discord OAuth for players or any player account system; the Ban ID stays the only credential.
- Deciding appeals or applying punishments from Discord (decisions stay in the panel with a verified
  Entra token); slash commands that mutate punishments.
- Copying attachment files out of Discord (metadata only in v1, see design).
- Voice, threads as tickets, forum tickets, multi-guild support (one guild per deployment).
- Sharding, a bot-side database, a web dashboard for the bot.
- App-level rate limiting and per-user cooldowns (infra owns it; the required edge rule is an operator task).
- Retention sweeps of history (the case's lifetime rule applies).

## Capabilities

### New Capabilities

- `discord-appeal-tickets`: the bot module, ticket lifecycle, channel permissions, both-way sync, failure behaviour, runtime and Entra setup.
- `discord-transcript`: persistence of the full channel history, catch-up, completeness event, the protocol gates for archive and deletion, identifiers and erasure of Discord data.
- `discord-leak-scanning`: detection of Ban IDs in public channels and the reaction.

### Modified Capabilities

- `staff-authentication`: the `DISCORD` role and its least-privilege `/discord/**` routes.
- `staff-account-link`: linking a Discord account with the same one-time code; a live link is what grants channel access.
- `admin-web-ui`: Discord ticket status, link dialog, missing-transcript banner.

## Impact

- Backend: new `discord/` package (controller family `DiscordApi`, `TicketService`, `DiscordIngestService`,
  `LeakCheckService`), `security/` (`Roles.DISCORD`, finder, matrix), `casetimeline/` (new payloads).
- Flyway: `V16__discord_tickets.sql` (`case_participant`, `discord_ticket`, `discord_message_link`,
  `staff_discord_link`) (next free version at implementation time).
- New module `discord/`; `settings.gradle.kts`, version catalog (JDA, amqp-client), Dockerfile, compose
  service, CI image build, docs, Entra script.
- Config: `DISCORD_*` env for the bot, `blackhole.discord.*` for the backend facade.
