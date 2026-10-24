# Run the Discord bot

How an operator sets up and runs the Blackhole Discord bot (`discord` module): the Discord side, the
server layout, the deployment, and the rules the infrastructure has to enforce. What the bot does is in
[`discord/README.md`](../discord/README.md), which also holds the table of every environment variable.

All ids, tokens and secrets below are placeholders. Never commit real ones; the bot token and the Entra
client secret belong in a secret store or an untracked env file.

## 1. Create the bot in the developer portal

1. Open the [Discord developer portal](https://discord.com/developers/applications), create an application and
   add a bot (**Bot** tab).
2. Reset the token once and put it into `DISCORD_TOKEN`. It is shown only once.
3. Turn **Public Bot** off, so only you can invite it.
4. Under **Privileged Gateway Intents** turn **Message Content Intent** on. The text of ticket messages is the
   record, so the bot cannot work without it. A bot in fewer than 100 guilds may use it freely; a larger one
   needs Discord's approval. Leave **Server Members** and **Presence** off: the bot connects with the intents
   `GUILD_MESSAGES` and `MESSAGE_CONTENT` only and keeps no member cache.
5. Invite it with the OAuth2 URL generator: scopes `bot` and `applications.commands`. **Do not grant
   Administrator** and do not tick server-wide permissions; give the permissions per category and channel as
   in section 2. (An Administrator bot would also bypass every channel overwrite the staff access relies on.)

## 2. Prepare the server

Turn on developer mode (User settings, Advanced) to copy ids.

| Create | Used for | Variable |
|--------|----------|----------|
| The guild | the one guild the bot serves | `DISCORD_GUILD_ID` |
| A team channel | notifications of new reports and appeals | `DISCORD_TEAM_CHANNEL_ID` |
| A staff role | pinged by notifications; **not** a channel permission | `DISCORD_STAFF_ROLE_ID` |
| A ticket category | one private channel per appeal | `DISCORD_TICKET_CATEGORY_ID` |
| An archive category | decided tickets wait here | `DISCORD_ARCHIVE_CATEGORY_ID` (must differ from the ticket category) |
| An appeal channel (optional) | holds the "open an appeal" button message | `DISCORD_APPEAL_CHANNEL_ID` |

### Permissions the bot needs, by place

Grant them on the category or channel, not on the role at server level. The bot checks them at start and
whenever it matters; `/healthz` answers 503 and names the place and the permission when one is missing.

| Where | Permissions |
|-------|-------------|
| Ticket category and archive category | View Channel, Send Messages, Read Message History, Embed Links, Manage Channels (create, move, delete), Manage Permissions (per-member overwrites) |
| Team channel | View Channel, Send Messages, Embed Links, Read Message History |
| Appeal channel | View Channel, Send Messages, Embed Links, Read Message History |
| Each scanned public channel | View Channel, Read Message History, Send Messages (the notice) and **Manage Messages** (removes a posted Ban ID) |

The staff role gets **no** View Channel on the ticket or archive category, and nobody gets a role overwrite
there. Each channel the bot creates denies View Channel to `@everyone` and adds the player and the linked
staff per member. A Discord administrator or the guild owner still sees every channel: that is Discord's rule, not
something the bot can change.

### Who can open a ticket channel

Only the player, the bot and **linked staff**. A staff member needs the staff role **and** a live Discord
link: they request a code with "Link Discord" in the panel's account menu and type `/link <code>` in
Discord. Without `/link` they do not see the channel at all, even with the role. The bot grants and
withdraws the per-member access when a ticket is created, every `DISCORD_STAFF_RECONCILE_INTERVAL` (default
`PT5M`), and at once when a staff link is removed. Tell new staff to run `/link` before their first ticket.

### What a ticket channel looks like

The channel is named `appeal-<number>`; the name holds no Ban ID, player name or hash. Its topic is

```
blackhole-ticket:<ticket id> - Messages here are recorded with the appeal case. Staff need a linked Discord account (/link).
```

The marker `blackhole-ticket:<ticket id>` is how the bot finds the channel again after a crash; do not edit
the topic.

## 3. Entra

The bot signs in to the backend with the client credentials grant and holds the app role `Blackhole.Discord`,
which reaches only `/discord/**`. [`docs/entra-setup.sh`](entra-setup.md) creates the role, the
`Blackhole Discord (<env>)` registration, its secret and the admin consent, and writes the
`BLACKHOLE_DISCORD_*` lines:

```shell
az login                      # as an Entra admin
docs/entra-setup.sh dev       # or: docs/entra-setup.sh prod https://blackhole.example.org
```

The script is safe to run again and keeps every other line of the env file, such as `DISCORD_TOKEN`. Put
`DISCORD_TOKEN` and the other `DISCORD_*` ids into the same untracked file (or your secret store) and pass it
to the container. The backend maps the role through `BLACKHOLE_AUTH_MACHINE_ROLE_DISCORD`.

## 4. Run it

The compose file has a `discord` service behind the profile of the same name, so `docker compose up` does not
start it without a bot:

```shell
cd docker
export DISCORD_TOKEN=<bot token> DISCORD_GUILD_ID=<guild id> DISCORD_TEAM_CHANNEL_ID=<channel id> \
       DISCORD_STAFF_ROLE_ID=<role id> DISCORD_TICKET_CATEGORY_ID=<category id> \
       DISCORD_ARCHIVE_CATEGORY_ID=<category id> \
       BLACKHOLE_DISCORD_TENANT_ID=<tenant> BLACKHOLE_DISCORD_CLIENT_ID=<client id> \
       BLACKHOLE_DISCORD_CLIENT_SECRET=<secret> BLACKHOLE_DISCORD_AUTH_SCOPE='api://<api client id>/.default'
docker compose --profile discord up -d discord
```

Or load the variables from the env file the Entra script wrote (`docker compose --env-file <file> ...`).
The backend and NATS must be reachable (`BLACKHOLE_BACKEND_URL`, `NATS_URL`; `NATS_CREDS_FILE` where the server needs a credential, see [nats-event-bus.md](nats-event-bus.md)). A wrong or missing
value stops the bot at start with exit code 2 and names every problem. The image can also be built on its own
from the repository root: `docker build -f discord/Dockerfile -t blackhole-discord .`.

The bot is stateless and optional: the backend, the panel and the portal keep working while it is down, and
what was written meanwhile reaches Discord when it is back.

On Kubernetes the Helm chart runs the bot as one pod (strategy `Recreate`, never two bots): the ids and
intervals are chart values, the token and the Entra client secret are read from a Secret, and the health
endpoint on port 8082 is the startup and liveness probe. See [Run Blackhole on Kubernetes](kubernetes.md).

## 5. Rules for the edge (infrastructure only)

Rate limiting stays out of the application: the operator sets it where the bot reaches the backend (reverse
proxy, ingress, gateway). There is deliberately no limiter in the backend for these routes. Suggested
starting values, tune them to your traffic:

| Route | Limit (global, all callers together) | Over the limit |
|-------|--------------------------------------|----------------|
| `POST /discord/tickets` | 30 per minute | answer `429` |
| `POST /discord/leak-check` | 120 per minute | answer `429` |

The bot treats `429` like any other "backend unavailable" answer: it does not open the ticket or drop the
message, it tells the player to try again later (tickets) or keeps the candidates in its bounded in-memory
queue and retries with a growing delay (leak checks; after five minutes a candidate is dropped and counted in
`blackhole_discord_leak_candidates_dropped_total`). So a limit that is too low slows the bot down but loses
nothing the bot has not already given up on.

## 6. Archive and deletion

When an appeal is decided the bot locks the player, rescans the whole channel, proves the transcript complete
to the backend (which stores the protocol) and only when a protocol version exists moves the channel into the
archive category. The archive is deleted **30 days** after the channel's last message
(`DISCORD_ARCHIVE_DELETE_AFTER`, default `P30D`; `PT0S` keeps archives forever). Before it deletes, the bot has
the backend make sure the stored protocol is current, so a late edit in the archive is part of it. A channel
is never archived or deleted without a verified transcript and a stored protocol: if the backend cannot store
one, the channel stays where it is and the panel shows a banner.

**Attachments vanish with the channel.** The record keeps only their metadata (kind, name, type, size); no
file and no CDN link is stored. If staff need a file to outlive the channel for a decision, they attach it as
evidence in the panel.

## 7. Health and metrics

The bot serves two plain endpoints on `DISCORD_HEALTH_PORT` (default `8082`, `0` turns them off):

- `GET /healthz` is `200 ok` only while the bot is connected to Discord and the permission self-check found
  nothing missing; otherwise `503` with the problems named (a place and a permission, never content). The
  image's `HEALTHCHECK` uses it.
- `GET /metrics` serves Prometheus counters, all prefixed `blackhole_discord_`: `relays_total`,
  `ingests_total`, `catch_ups_total`, `incomplete_transcripts_total`, `tickets_opened_total`,
  `tickets_archived_total`, `tickets_deleted_total`, `freezes_total`, `backend_unavailable_total`,
  `discord_errors_total`, `broker_hints_total`, `leak_candidates_dropped_total`,
  `leak_messages_removed_total` and `leak_checks_total`.

Keep both ports on the internal network. Logs are JSON lines on stdout and never contain message content, Ban
IDs or Discord ids. Alert on `incomplete_transcripts_total` rising and on `/healthz` staying `503`.
