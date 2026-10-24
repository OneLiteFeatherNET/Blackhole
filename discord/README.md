# Blackhole Discord bot

A small, stateless JDA application. It links Discord accounts to Blackhole staff and players, runs the appeal
tickets (one private channel per appeal, kept in sync with the player portal and the panel) and
posts new reports and appeals to the team channel and edits the message when they are resolved. It talks to the backend with the OAuth2
client credentials grant (Entra app role `Blackhole.Discord`, which only reaches `/discord/**`) and
keeps no data of its own; when it is down, the panel and the game keep working.

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
3. Create two categories (tickets, archive) and, optionally, an appeal channel for the button message.
4. Copy the ids (developer mode): the guild, the team channel, the staff role, the categories and the appeal channel.

## Team notifications

The bot declares the durable queue `blackhole.discord.notify` (messages kept 7 days) on the topic exchange
`blackhole.events`, bound to `report.created`, `report.resolved`, `appeal.submitted` and `appeal.resolved`.
For each event it reads the display facts from `GET /discord/notifications/report|appeal/{id}` and posts one
embed to the team channel: player (name if the proxy recorded one, otherwise a short reference), category or
section, short reason, time, the staff role ping and an "Open in panel" button. When the item is resolved the
same message is edited to show the outcome. The message is found again by a marker in its footer, so a
redelivered event, an event that arrives late and a restart never create a second message. Needs View Channel,
Send Messages, Embed Links and Read Message History in the team channel. If the broker is down the bot keeps
serving the commands and reconnects every 30 seconds.

## Appeal tickets

A player runs `/appeal` (or presses the button), types the ban ID into an ephemeral form and gets a private channel
`appeal-<number>` in the ticket category. The channel name and topic hold no ban ID, name or hash. Every reason a ticket cannot be opened
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

The RabbitMQ queue `blackhole.discord.case` (bound to the case message, leak, reissue and status events, `appeal.resolved`, `staff-link.removed`
and `discord-link.removed`) is a **hint** only: the bot polls the backend's cursors every `DISCORD_SYNC_INTERVAL` anyway, so a lost or duplicated
event, a broker outage or a restart delays but never loses anything. After a start the bot reads what was written while it was down.
A message written and deleted while the bot itself was down cannot be recovered; the panel shows the gap.

Health and metrics: `GET /healthz` is 200 only while connected and without a permission problem (a failed self-check names the category and the
permission); `GET /metrics` serves counters (relays, ingests, catch-ups, incomplete transcripts, backend and Discord errors, broker hints,
dropped leak candidates). Logs are JSON lines on stdout and never contain message content, ban IDs or Discord ids.

## Entra setup

`docs/entra-setup.sh <env>` creates the app role `Blackhole.Discord` and the client
`Blackhole Discord (<env>)` with a secret and admin consent and writes the `BLACKHOLE_DISCORD_*` lines
(see `docs/entra-setup.md`).

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
| `DISCORD_STAFF_RECONCILE_INTERVAL` | Optional, default `PT5M` (at least `PT30S`): how often channel access is reconciled with staff links and the role. |
| `RABBITMQ_HOST`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | The broker the backend publishes its events to. Optional: `RABBITMQ_PORT` (5672), `RABBITMQ_VHOST` (`/`). |
| `BLACKHOLE_DISCORD_TOKEN_URL` | Optional, development: a mock token endpoint instead of Entra. |
| `DISCORD_HEALTH_PORT` | Optional, default `8082`, `0` turns the health endpoint off. |

A wrong or missing value stops the bot at start (exit code 2) with every problem named.

## Run

```shell
./gradlew :discord:run                                  # needs the environment above
docker build -f discord/Dockerfile -t blackhole-discord .   # from the repository root
```

The image runs as a non-root user on a Java 25 JRE; `GET /healthz` on the health port is 200 while the
bot is connected to Discord. `docker/docker-compose.yml` has a commented `discord` service.

## Code layout

`ticket` (channel lifecycle, staff access reconcile, archive sweeper, permission self-check), `sync` (Discord to backend and back: ingest, outbox relay,
catch-up, the conductor, the broker hint handler), `sched` (the scheduler the tests advance by hand), `metrics`, `log` (the JSON layout),
`notify` (event parsing, embed rendering, the handler, the RabbitMQ adapter), `config` (environment, fail-fast), `auth` (client credentials token, a copy of the Velocity plugin's
provider because that one depends on the plugin's config class), `backend` (`BackendGateway` interface
and the adapter on the generated client), `command` (the command logic and texts, no Discord types),
`gateway` (`DiscordGateway` interface, the JDA adapter and the health endpoint). Only `gateway` imports
JDA, so tests use fakes and need no Discord connection.
