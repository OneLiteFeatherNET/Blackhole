# Blackhole Discord bot

A small, stateless JDA application. It links Discord accounts to Blackhole staff and players, runs the appeal
tickets (one private channel per appeal, kept in sync with the player portal and the panel) and
posts new reports and appeals to the team channel and edits the message when they are resolved. It talks to the backend with the OAuth2
client credentials grant (Entra app role `Blackhole.Discord`, which only reaches `/discord/**`) and
keeps no data of its own; when it is down, the panel and the game keep working.

Operators: the step-by-step setup (developer portal, server layout, permissions, deployment, edge rules) is in
[`docs/discord-bot.md`](../docs/discord-bot.md). This file describes what the bot does and holds the environment table.

## Slash commands

English first, German second (the reply follows the Discord client language). All replies are ephemeral.

| Command | What it does |
|---------|--------------|
| `/link <code>` | Confirms a one-time code. Staff get a code with "Link Discord" in the admin panel, players with `/blackhole discord` in game. |
| `/unlink` | Removes the link; the stored Discord user id is deleted in the backend. |
| `/whoami` | Shows what the Discord account is linked to. |
| `/appeal` | Opens a form (also behind the button in the appeal channel) for the ban ID and a statement. The reply is ephemeral and never repeats the ban ID. |

## Discord setup

1. Create an application and a bot in the Discord developer portal. Turn the privileged intent
   **Message Content** on (the text of ticket messages is the record; bots in fewer than 100 guilds may
   use it, larger ones need Discord's approval). `GUILD_MEMBERS` and `GUILD_PRESENCES` stay **off**. The bot connects with the
   intents `GUILD_MESSAGES` and `MESSAGE_CONTENT` only; there is no member cache.
2. Invite it with the scopes `bot` and `applications.commands`. **Never grant Administrator.** Permissions
   by place (grant them on the category or channel, not as server-wide permissions):

   | Where | Permissions |
   |-------|-------------|
   | Ticket category and archive category | View Channel, Send Messages, Read Message History, Embed Links, Manage Channels (create, move, delete), Manage Permissions (per-member overwrites). The staff role gets **no** View here. |
   | Team channel | View Channel, Send Messages, Embed Links, Read Message History. |
   | Appeal channel (optional) | View Channel, Send Messages, Embed Links, Read Message History. |
   | Scanned public channels | View Channel, Read Message History, Send Messages (for the notice) and **Manage Messages** (to remove a posted ban ID). |
3. Create two categories (tickets, archive) and, optionally, an appeal channel for the button message.
4. Copy the ids (developer mode): the guild, the team channel, the staff role, the categories and the appeal channel.

## Team notifications

The bot declares the durable queue `blackhole.discord.notify` (messages kept 7 days) on the topic exchange
`blackhole.events`, bound to `report.created`, `report.resolved`, `appeal.submitted` and `appeal.resolved`, and to the
join guard events `guard.level_changed`, `area_ban.created`, `area_ban.lifted` and `guard.denials.summary`.
The guard events are rendered from their payload alone and posted once each, without a backend read.
For each report or appeal event it reads the display facts from `GET /discord/notifications/report|appeal/{id}` and posts one
embed to the team channel: player (name if the proxy recorded one, otherwise a short reference), category or
section, short reason, time, the staff role ping and an "Open in panel" button. When the item is resolved the
same message is edited to show the outcome. The message is found again by a marker in its footer, so a
redelivered event, an event that arrives late and a restart never create a second message. Needs View Channel,
Send Messages, Embed Links and Read Message History in the team channel. If the broker is down the bot keeps
serving the commands and reconnects every 30 seconds.

## Appeal tickets

A player runs `/appeal` (or presses the button), types the ban ID into an ephemeral form and gets a private channel
`appeal-<number>` in the ticket category. The channel name and topic hold no ban ID, name or hash; the topic reads
`blackhole-ticket:<ticket id> - Messages here are recorded with the appeal case. Staff need a linked Discord account (/link).`
and its marker is how the bot finds the channel again after a crash. Every reason a ticket cannot be opened
(wrong ID, an ID bound to another Discord user, a leaked ID) gets the same answer. Only the player, the bot and linked
staff can see the channel: staff need the staff role **and** a live staff link (`/link <code>` first), granted as per-member
overwrites; the role itself has no access and there is no role overwrite. The reconcile runs when a ticket is created, every
`DISCORD_STAFF_RECONCILE_INTERVAL` and when a staff link is removed. A Discord administrator or the guild owner sees every channel regardless;
that is Discord's rule.

Everything written in the channel is recorded in the backend (text, attachment and embed metadata; no files, no CDN links),
and what the portal and the panel write is posted into the channel, in order. The bot never records its own messages. When a leaked ban ID
freezes the case the player can no longer write; a reissue opens the channel again. When the appeal is decided the bot relays the decision,
locks the player, rescans the whole channel, proves the transcript complete to the backend (which stores the protocol) and only when a
protocol version exists moves the channel to the archive category. After `DISCORD_ARCHIVE_DELETE_AFTER` (default 30 days, counted from the
channel's last message, which is the bot's archive notice) the bot first has the backend make sure the protocol is current and then deletes the
channel; a delay of `PT0S` keeps archives. A channel is never archived or deleted without a verified transcript and a stored protocol. Attachments
vanish with the channel; the record keeps their metadata.

The durable NATS consumer `blackhole-discord-case` (on the stream `BLACKHOLE_EVENTS`; it filters the case message, leak, reissue and status events, `appeal.resolved`, `staff-link.removed`
and `discord-link.removed`) is a **hint** only: the bot polls the backend's cursors every `DISCORD_SYNC_INTERVAL` anyway, so a lost or duplicated
event, a broker outage or a restart delays but never loses anything. After a start the bot reads what was written while it was down.
A message written and deleted while the bot itself was down cannot be recovered; the panel shows the gap.

Health and metrics: `GET /healthz` is 200 only while connected and without a permission problem (a failed self-check names the category and the
permission), 503 otherwise; `GET /metrics` serves the counters `blackhole_discord_*` (relays, ingests, catch-ups, incomplete transcripts, tickets opened,
archived and deleted, freezes, backend and Discord errors, broker hints, leak candidates dropped, messages removed and checks). Both are on `DISCORD_HEALTH_PORT`
and meant for the internal network. Logs are JSON lines on stdout and never contain message content, ban IDs or Discord ids.

## Edge rules for the operator

Rate limiting is infrastructure, not application code: there is no limiter in the backend for the bot's routes. Suggested starting values for the
reverse proxy or gateway in front of the backend (tune them): `POST /discord/tickets` global 30 per minute and `POST /discord/leak-check` global
120 per minute, answering `429` over the limit. The bot treats `429` as "backend unavailable": a player is asked to try again later, and leak
candidates wait in the bounded retry queue. See `docs/discord-bot.md`.

## Leak scanning

The bot removes Ban IDs that are posted in public channels. A candidate is `#` followed by eight Crockford base32 characters (case-insensitive,
at most one space or hyphen in the middle, at a word boundary, at most five per message; `I L O` are read as `1 1 0`). A token without the `#` is not a
candidate, because every English word and hex number would be a false positive; that gap is accepted. The bot never decides alone: it sends the
normalised candidates to `POST /discord/leak-check` and the **backend** looks them up. Only when a candidate is a current Ban ID is the message deleted
(the ID counts as leaked even though it stood for a moment) and a short notice without any identifier is posted; the backend marks the case leaked with the
poster as an opaque participant, and the ticket channel of that case is frozen for the player (the same poll that handles `case.ban_id_leak_marked`).
A look-alike causes nothing: no deletion, no event, no record of the token.

Scope: new and edited messages (never history) in every channel or thread where `@everyone` can view the channel, except the ticket and archive categories.
`DISCORD_LEAK_SCAN=LIST` restricts it to the channels or categories in `DISCORD_LEAK_SCAN_CHANNELS`, `OFF` sends no message content to the backend. The bot needs
**Manage Messages** (and Send Messages for the notice) in every scanned channel, and nowhere else. If the backend is unreachable the candidates wait in a
bounded in-memory queue (1000) and are retried with a growing delay for five minutes, then dropped with the counter
`blackhole_discord_leak_candidates_dropped_total`; a 429 from the edge counts as an outage. Nothing about a message or a candidate is logged.

## Entra setup

`docs/entra-setup.sh <env>` creates the app role `Blackhole.Discord` and the client
`Blackhole Discord (<env>)` with a secret and admin consent and writes the `BLACKHOLE_DISCORD_*` lines
(see `docs/entra-setup.md`). Run it as an Entra admin after `az login`:

```shell
docs/entra-setup.sh dev                                  # or: prod https://blackhole.example.org [env file]
```

It is safe to run again and keeps other lines of the env file, such as `DISCORD_TOKEN`.

## Environment

| Variable | Meaning |
|----------|---------|
| `DISCORD_TOKEN` | Bot token (secret, never logged). |
| `DISCORD_GUILD_ID` | The one guild. |
| `DISCORD_TEAM_CHANNEL_ID` | Channel for the team notifications. |
| `DISCORD_STAFF_ROLE_ID` | Role that is pinged by the team notifications. |
| `BLACKHOLE_BACKEND_URL` | Backend base URL, e.g. `http://backend:8080`. |
| `BLACKHOLE_PANEL_URL` | Admin panel base URL for the links into the panel. |
| `BLACKHOLE_DISCORD_TENANT_ID` | Entra tenant id. |
| `BLACKHOLE_DISCORD_CLIENT_ID` | Client id of `Blackhole Discord (<env>)`. |
| `BLACKHOLE_DISCORD_CLIENT_SECRET` | Its secret. |
| `BLACKHOLE_DISCORD_AUTH_SCOPE` | `api://<api client id>/.default`. |
| `DISCORD_TICKET_CATEGORY_ID` | Category where ticket channels are created. Required. |
| `DISCORD_ARCHIVE_CATEGORY_ID` | Category decided tickets move to; must differ from the ticket category. Required. |
| `DISCORD_APPEAL_CHANNEL_ID` | Optional channel that holds the "open an appeal" button message (posted once, found again by a marker). |
| `DISCORD_ARCHIVE_DELETE_AFTER` | Optional ISO-8601 duration, default `P30D`; `PT0S` keeps archived channels. Deletion also needs a stored, current protocol. |
| `DISCORD_SYNC_INTERVAL` | Optional, default `PT30S` (at least `PT5S`): how often the backend's cursors are polled. |
| `DISCORD_LEAK_SCAN` | Optional: `PUBLIC` (default), `LIST` or `OFF`. |
| `DISCORD_LEAK_SCAN_CHANNELS` | With `LIST` (required there): comma separated channel or category ids. |
| `DISCORD_STAFF_RECONCILE_INTERVAL` | Optional, default `PT5M` (at least `PT30S`): how often channel access is reconciled with staff links and the role. |
| `NATS_URL` | The NATS server the backend publishes its events to: `nats://`, `tls://`, `ws://` or `wss://`, comma separated for several. |
| `NATS_CREDS_FILE` | Optional: path of the `.creds` file of the bot's NATS user (readable; empty for a server without authentication). |
| `BLACKHOLE_DISCORD_TOKEN_URL` | Optional, development: a mock token endpoint instead of Entra. Must be `https`; `http` to loopback only with `MICRONAUT_ENVIRONMENTS=dev` or `test`. |
| `DISCORD_HEALTH_PORT` | Optional, default `8082`, `0` turns the health endpoint off. |

A wrong or missing value stops the bot at start (exit code 2) with every problem named.

## Run

```shell
./gradlew :discord:run                                  # needs the environment above
docker build -f discord/Dockerfile -t blackhole-discord .   # from the repository root
```

The image runs as a non-root user on a Java 25 JRE; `GET /healthz` on the health port is 200 while the
bot is connected to Discord. `docker/docker-compose.yml` has a `discord` service behind the compose profile `discord`
(`docker compose --profile discord up -d discord` with the variables above set), so a plain `docker compose up` does not start it.

## Code layout

`ticket` (channel lifecycle, staff access reconcile, archive sweeper, permission self-check), `sync` (Discord to backend and back: ingest, outbox relay,
catch-up, the conductor, the broker hint handler), `leak` (the candidate format and the scanner), `sched` (the scheduler the tests advance by hand), `metrics`, `log` (the JSON layout),
`notify` (event parsing, embed rendering, the handler, the NATS adapter), `config` (environment, fail-fast), `auth` (client credentials token, a copy of the Velocity plugin's
provider because that one depends on the plugin's config class), `backend` (`BackendGateway` interface
and the adapter on the generated client), `command` (the command logic and texts, no Discord types),
`gateway` (`DiscordGateway` interface, the JDA adapter and the health endpoint). Only `gateway` imports
JDA, so tests use fakes and need no Discord connection.
