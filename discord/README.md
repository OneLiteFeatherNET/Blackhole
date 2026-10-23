# Blackhole Discord bot

A small, stateless JDA application. It links Discord accounts to Blackhole staff and players and (next
step) posts new reports and appeals to the team channel. It talks to the backend with the OAuth2
client credentials grant (Entra app role `Blackhole.Discord`, which only reaches `/discord/**`) and
keeps no data of its own; when it is down, the panel and the game keep working.

## Slash commands

English first, German second (the reply follows the Discord client language). All replies are ephemeral.

| Command | What it does |
|---------|--------------|
| `/link <code>` | Confirms a one-time code. Staff get a code with "Link Discord" in the admin panel, players with `/blackhole discord` in game. |
| `/unlink` | Removes the link; the stored Discord user id is deleted in the backend. |
| `/whoami` | Shows what the Discord account is linked to. |

## Discord setup

1. Create an application and a bot in the Discord developer portal. No privileged intent is needed
   (`MESSAGE_CONTENT`, `GUILD_MEMBERS` and `GUILD_PRESENCES` stay off).
2. Invite it with the scopes `bot` and `applications.commands`. For the link commands no channel
   permission is required; the team notifications need View Channel, Send Messages and Embed Links in
   the team channel. Never grant Administrator.
3. Copy the ids (developer mode): the guild, the team channel, the staff role.

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

`config` (environment, fail-fast), `auth` (client credentials token, a copy of the Velocity plugin's
provider because that one depends on the plugin's config class), `backend` (`BackendGateway` interface
and the adapter on the generated client), `command` (the command logic and texts, no Discord types),
`gateway` (`DiscordGateway` interface, the JDA adapter and the health endpoint). Only `gateway` imports
JDA, so tests use fakes and need no Discord connection.
