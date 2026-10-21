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

- **Backend**: Java 21+, [Micronaut](https://micronaut.io/), Hibernate/JPA, Liquibase (MariaDB),
  RabbitMQ, Dragonfly (Redis protocol) as the shared cache, Micrometer + OpenTelemetry.
- **Proxy plugin**: [Velocity](https://velocitypowered.com/).
- **Build**: Gradle (Kotlin DSL), multi-module.
- **Releases**: [release-please](https://github.com/googleapis/release-please), driven by
  [Conventional Commits](https://www.conventionalcommits.org/) — see [Contributing](#contributing).

## Local development

A `docker-compose.yml` under `docker/` provides MariaDB, RabbitMQ and Dragonfly (Redis protocol,
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
| `JDBC_URL` / `JDBC_USER` / `JDBC_PASSWORD` | MariaDB connection (defaults match `docker/docker-compose.yml`). |
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
