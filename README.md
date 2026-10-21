# Blackhole

Blackhole is a ban system for a single Minecraft network, built to be GDPR-compliant by design.
One backend deployment serves one network's staff, players, punishment history and
integrations, while keeping personally identifiable data (IPs, raw player UUIDs) hashed or
tokenized wherever it's stored or published.

Punishment decisions are driven by a **dual-ELO system**: every player carries a separate
`chatElo` and `gameplayElo` score. Reports and a pluggable chat-toxicity scorer feed deltas into
these scores, and crossing a configurable soft/hard threshold automatically applies a temporary
or permanent punishment — without requiring a moderator to be online. A structured appeal
workflow lets players contest both automatic and manual punishments.

## Architecture

| Module     | What it is                                                                                  |
|------------|----------------------------------------------------------------------------------------------|
| `backend`  | The Micronaut HTTP API — punishments, reports, ELO, appeals, dashboard.                     |
| `velocity` | A Velocity proxy plugin that enforces punishments and feeds chat/session data to the backend. |
| `client`   | A Java API client generated from `client/specs/blackhole-api-*.yml` via OpenAPI Generator.   |
| `phoca`    | Shared utility library used across modules.                                                  |

The backend is event-driven: RabbitMQ backs a domain event bus (`blackhole.events`) that other
services can consume. Hot reads (punishment profiles, ELO profiles, templates and player
resolutions) are served from one shared Redis-protocol cache (Dragonfly in production), so every
replica reads and evicts the same entries and the API can be scaled horizontally.

There is deliberately no authentication system right now: every endpoint is open. The trust
model is that only trusted callers can reach the API at all - admins/tools calling the REST API
directly, or the Velocity plugin acting on behalf of admins/moderators - enforced at the
network/deployment level (firewalling, private networking), not by the application.

### Tech stack

- **Backend**: Java 21+, [Micronaut](https://micronaut.io/), Hibernate/JPA, Flyway (PostgreSQL),
  RabbitMQ, Dragonfly (Redis protocol) as the shared cache, Micrometer + OpenTelemetry.
- **Proxy plugin**: [Velocity](https://velocitypowered.com/).
- **Build**: Gradle (Kotlin DSL), multi-module.
- **Releases**: [release-please](https://github.com/googleapis/release-please), driven by
  [Conventional Commits](https://www.conventionalcommits.org/) — see [Contributing](#contributing).

## Staff workflow

Staff never decide that someone is banned forever. They apply a **temporary** punishment from a
template; each application costs ELO in the template's **section**, and when a section score falls
below its permanent threshold the system itself replaces the active punishment with a permanent one.

### Sections

A section owns one ELO score per player, its thresholds, its permanent-ban reason and its pardon
score, and says how it is enforced:

| Enforcement | Effect on the proxy                                                                |
|-------------|-------------------------------------------------------------------------------------|
| `BAN`       | The player cannot log in.                                                           |
| `MUTE`      | The player's chat messages are not delivered.                                       |
| `SERVER`    | The player cannot connect to backend servers matching the section's glob patterns. |

Two sections are seeded: `chat` (MUTE) and `network` (BAN). Operators add more through
`POST /section`, for example `bedwars` as SERVER with the pattern `bedwars-*` (`*` matches any run
of characters, `?` one character, case-insensitive). A player stays on the network when a SERVER
section punishes them; if they stand on a matching server when it arrives they are moved to the
`fallbackServer` of the proxy config (default `lobby`), or disconnected if there is none.
There is one active punishment per player and section: a mute never lifts a ban.

### Templates and escalation

A template has a `key` (what staff type), a `section` and ordered **steps**, each with a `duration`
(ISO-8601, greater than zero) and an `eloCost` (zero or more). The step is chosen by how many earlier
punishments the player has in the template's escalation group (default: the template key) within the
escalation window (default 365 days, `blackhole.punishment.escalation.default-window`) that were not
revoked or lifted by an appeal; beyond the last step the last step repeats. Applying a template
deducts the step's cost; if the section score is then below its permanent threshold and no permanent
punishment is active, the player is punished permanently. A staff revoke of a temporary punishment
does **not** refund its ELO cost; a granted appeal that fully lifts it does. Lifting a permanent
punishment (revoke or appeal) sets the section score to the section's pardon score.

### Proxy commands

| Command                              | Permission                      | What it does                                                        |
|--------------------------------------|---------------------------------|---------------------------------------------------------------------|
| `/ban <player> <template>`           | `blackhole.ban`                 | Applies a template of a `BAN` or `SERVER` section.                  |
| `/mute <player> <template>`          | `blackhole.mute`                | Applies a template of a `MUTE` section.                             |
| `/punish <player> <template>`        | `blackhole.punish`              | Applies a template of any section.                                  |
| `/unban <player> [section]`          | `blackhole.unban`               | Lifts the active ban (section defaults to `network`; `BAN`/`SERVER`). |
| `/unmute <player> [section]`         | `blackhole.unmute`              | Lifts the active mute (section defaults to `chat`).                 |
| `/elo <player>`                      | `blackhole.elo.view`            | Shows the score, thresholds and permanent state of every section.   |
| `/blackhole <player> info`           | `blackhole.user.info`           | Shows the active punishments.                                       |
| `/blackhole <player> history <sort>` | `blackhole.ban.history`         | Shows the punishment history.                                       |
| (lifting a permanent punishment)     | `blackhole.pardon.permanent`    | Additionally required by `/unban` and `/unmute` for permanent ones. |

`<template>` is the template key, tab-completed from the templates eligible for the command. The
player may be offline (a name the backend can resolve, or a UUID). No command accepts a duration or a
permanent flag. The former `--server` flag is gone: use a `SERVER` section. After applying, the sender
sees the section, how long it lasts, the step (`n/m`), the ELO change and, if the cost triggered it,
the permanent escalation. Upgrading from earlier releases: templates are selected by key instead of
by reason, and the proxy reads the Redis keys `blackhole:punish:<section>:<owner>`; deploy backend and
proxies together.

## Local development

A `docker-compose.yml` under `docker/` provides PostgreSQL, RabbitMQ and Dragonfly (Redis protocol,
used for proxy sync and the shared cache):

```shell
cd docker
docker compose up -d
```

Then run the backend:

```shell
./gradlew :backend:run
```

Key environment variables (all have local-dev defaults, see
`backend/src/main/resources/application.yml` for the full list):

| Variable                          | Purpose                                                             |
|------------------------------------|-----------------------------------------------------------------------|
| `JDBC_URL` / `JDBC_USER` / `JDBC_PASSWORD` | PostgreSQL connection (defaults match `docker/docker-compose.yml`). |
| `RABBITMQ_HOST` / `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | RabbitMQ connection.                              |
| `REDIS_URI`                       | Redis-protocol server (Dragonfly) for proxy sync and the shared cache. |
| `BLACKHOLE_EVASION_IP_SALT`       | Required for ban-evasion detection; that feature returns `503` until it's set. |

Swagger UI is served at `/swagger/views/swagger-ui` once the backend is running.

### Building

```shell
./gradlew clean build
```

### Upgrading from the in-process cache

- Run Dragonfly in place of Redis: the compose service now uses the Dragonfly image. `REDIS_URI`
  is unchanged, so proxies keep working. Deploy the backend and the proxies together.
- The `blackhole.cache.invalidate` fanout exchange is no longer declared or used. Existing
  RabbitMQ brokers keep it as an orphan exchange, which is harmless and can be deleted.
- Cache keys are versioned (`blackhole:cache:v1:`), so entries written by an older release are
  never read and expire on their own. Rolling back to the previous release is safe.

## Contributing

Commit messages and pull request titles must follow
[Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `chore:`, ...) —
this is enforced in CI (`.github/workflows/pr-lint.yaml`) and is what `release-please` uses to
determine the next version and generate `CHANGELOG.md`. Since this repository only allows squash
merges, the PR title becomes the commit on `main`, so it's linted the same as individual commits.

Allowed types: `build`, `chore`, `ci`, `docs`, `feat`, `fix`, `perf`, `refactor`, `revert`,
`style`, `test`.

## License

Blackhole is licensed under the [GNU Affero General Public License v3.0](LICENSE).
