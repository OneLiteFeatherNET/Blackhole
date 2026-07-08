# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Blackhole is a decentralized, multi-tenant ban system for Minecraft networks, built to be
GDPR-compliant by design. A single backend deployment serves multiple independent organizations
("tenants"), each with their own staff, players, punishment history and integrations, while
keeping personally identifiable data (IPs, raw player UUIDs) hashed or tokenized wherever it's
stored or published.

Punishment decisions are driven by a **dual-ELO system**: every player carries a separate
`chatElo` and `gameplayElo` score. Reports, a pluggable chat-toxicity scorer, and signals from
external anti-cheat/moderation tools all feed deltas into these scores, and crossing a
configurable soft/hard threshold automatically applies a temporary or permanent punishment —
without requiring a moderator to be online. A structured appeal workflow lets players contest
both automatic and manual punishments.

## Modules

Gradle multi-module project (Kotlin DSL), Java 21+ (backend toolchain targets 25):

| Module     | What it is                                                                                    |
|------------|------------------------------------------------------------------------------------------------|
| `backend`  | The Micronaut HTTP API — tenants, punishments, reports, ELO, appeals, connectors, dashboard.   |
| `velocity` | A Velocity proxy plugin that enforces punishments and feeds chat/session data to the backend.  |
| `client`   | A Java API client generated from `client/specs/blackhole-api-*.yml` via OpenAPI Generator.     |
| `phoca`    | Shared utility library (metadata/expiry/duration abstractions) used across modules.             |

`client` has no hand-written API code — `compileJava` depends on `openApiGenerate`, which
regenerates `net.onelitefeather.blackhole.client.{api,invoker,model}` from the pinned spec file
in `client/build.gradle.kts` (`inputSpec`). Bump the spec filename there when publishing a new
API version, don't edit generated sources directly.

## Commands

```shell
# Build everything (all modules)
./gradlew clean build

# Run just the backend against local infra (needs docker/docker-compose.yml running)
cd docker && docker compose up -d && cd ..
BLACKHOLE_AUTH_BOOTSTRAP_SECRET=change-me ./gradlew :backend:run

# Run a single module's tests
./gradlew :backend:test

# Run a single test class/method (JUnit 5 filter)
./gradlew :backend:test --tests "net.onelitefeather.blackhole.backend.SomeTest"
./gradlew :backend:test --tests "net.onelitefeather.blackhole.backend.SomeTest.someMethod"

# Regenerate the OpenAPI client sources explicitly
./gradlew :client:openApiGenerate

# Run the Velocity plugin against a local Velocity instance
./gradlew :velocity:runVelocity
```

There are currently no test source sets under any module (`src/test` doesn't exist yet) — when
adding tests, JUnit 5 is already wired up via the Micronaut/root `build.gradle.kts` (`useJUnitPlatform()`,
`jacocoTestReport` runs after `test`). Local dev/manual verification of the backend requires the
real MariaDB + RabbitMQ containers from `docker/docker-compose.yml`; there's no in-memory/mocked
path for those two dependencies (H2 is on the classpath but the app is wired to Liquibase+MariaDB
by default — see `application.yml`).

Swagger UI is served at `/swagger/views/swagger-ui` once the backend is running.

## Backend architecture (`backend/src/main/java/.../blackhole/backend/`)

- **`controller/`** — REST controllers. All business-facing controllers are mounted under the
  `ApiVersion.V1` (`/v1`) prefix; infra/doc endpoints (health, prometheus, swagger) are not
  versioned. Bump `ApiVersion` (and add a new constant) rather than changing `V1` in place when a
  breaking API version is introduced.
- **`security/`** — Every endpoint requires a valid JWT bearer token by default (Micronaut
  Security's secure-by-default posture); `@Secured(SecurityRule.IS_ANONYMOUS)` opts out
  individual endpoints (currently only `POST /auth/bootstrap`). `Roles` defines the role model:
  `PLATFORM_ADMIN`, `TENANT_ADMIN`, `STAFF`, `PLAYER`, `SERVICE`. Tenant scoping is **not**
  claim-based or verified in any way — every tenant-scoped endpoint takes a `tenantId` URL path
  variable and trusts it directly, with nothing checking it against the caller's token. This is a
  deliberate simplification: a role's JWT works identically against every tenant, so a leaked
  `STAFF`/`SERVICE` token issued against one tenant works against all of them. There is no
  `TenantContext` or equivalent gatekeeper — don't reintroduce one without discussing the
  trade-off, and don't assume tenant isolation is enforced anywhere in this layer.
  - **Known gap**: JWTs currently carry no per-actor identity, only a role. Don't design new
    features around a per-user identity claim existing yet — that's deferred to a dedicated
    future phase.
- **`events/`** — Domain event bus over RabbitMQ. `BlackholeRabbitTopology` declares two
  exchanges at channel-creation time (not via config, since this Micronaut RabbitMQ version lacks
  declarative exchange config): a topic exchange `blackhole.events` for domain events consumed by
  other services, and a fanout exchange `blackhole.cache.invalidate` for cross-replica Caffeine
  cache invalidation so the API can scale horizontally. `WebhookDispatchConsumer` fans domain
  events out to connector-registered webhooks using a native RabbitMQ TTL+DLX retry loop (not an
  in-process retry) — after `max-retries` a dispatch parks in `blackhole.webhook.failed` instead
  of retrying forever.
- **Connector framework** (`ConnectorController`, `ConnectorAuthController`, `ConnectorScopes`,
  `ConnectorRegistrationEntity`, `EventSubscriptionEntity`) — external systems (anti-cheat tools,
  dashboards, other backends) integrate via OAuth2 client-credentials tokens scoped to specific
  read/write permissions, a generic `SignalController` (`/signal`) ingestion endpoint, and signed
  webhook delivery for subscribed event types, rather than bespoke per-integration code.
- **`elo/`** — Dual-ELO engine. Baseline/soft/hard thresholds and chat-toxicity scoring
  parameters are all externally configured (see `application.yml`'s `blackhole.elo.*`); crossing
  soft threshold triggers an automatic temporary punishment, hard threshold a permanent one. The
  built-in `ToxicityScorer` is a placeholder keyword matcher, explicitly meant to be swapped for
  a real classifier later — don't harden or extend its keyword list as if it were production
  moderation logic.
- **`appeal/`** — Appeal workflow. `min-days-auto` is intentionally shorter than
  `min-days-manual`: auto-bans exist to save moderator time, so appealing one shouldn't cost more
  of it via a longer mandatory wait.
- **`evasion/`** — Ban-evasion detection via hashed IP correlation. Fully gated on
  `BLACKHOLE_EVASION_IP_SALT` being set — `EvasionController` returns `503` rather than silently
  computing a weak/unsalted-equivalent hash if the salt is unset. Treat the salt as
  effectively unrotatable in place; check `IpCorrelationService`'s javadoc before ever changing it.
- **`database/`** — Hibernate/JPA entities + Micronaut Data repositories. Liquibase
  (`db/changelog/db.changelog-master.xml`, a single consolidated XML changelog) is the sole
  schema owner; `jpa.default.properties.hibernate.hbm2ddl.auto` is deliberately `none`, not
  `validate` — Hibernate's own JSON-column type expectations disagree with MariaDB's physical
  representation of a JSON column, an unrelated dialect mismatch that would make `validate` fail
  spuriously. Every changeSet uses fully native Liquibase changeTypes (`<createTable>`,
  `<createIndex>`, `<addForeignKeyConstraint>`) rather than raw `<sql>`, keeping the changelog
  database-portable rather than MariaDB-specific. One consequence: native Liquibase XML has no
  changeType for arbitrary `CHECK` expressions on any database, so the JSON validity checks and
  the `tinyint` ordinal-enum range checks MariaDB previously enforced at the DB layer are not
  recreated here — enforcement of those is an application/Hibernate-layer concern only, not a
  DB-layer guarantee. Add new changes as a new `<changeSet>` appended to the same file; never edit
  an already-applied changeSet, always add a new one.
- **`imports/`** — Vanilla ban-list import support (`VanillaImportController`).

## Cross-cutting conventions

- **Multi-tenancy is URL-driven, not enforced.** Any new controller/service touching tenant-owned
  data takes a `tenantId` URL path variable and uses it directly for queries/writes — there is no
  access-check gatekeeper to route through. Don't add one without discussing the trade-off (see
  `security/` above); don't hand-roll a JWT-claim-based check either, since no tenant claim exists.
- **SSRF hardening on user-controlled URLs.** Webhook delivery URLs are set by a `TENANT_ADMIN`,
  a less-trusted party than the platform operator in this shared-deployment model.
  `WebhookUrlValidator` enforces this (`blackhole.webhook.allow-private-networks` must stay
  `false` outside local dev/loopback testing). Any new feature accepting a tenant-admin-controlled
  URL (not just webhooks) needs equivalent SSRF hardening, not just webhook delivery.
  `InvalidWebhookUrlException` is the standard rejection path.
- **Rate limiting stays out of the app.** Existing app-level rate limiting (e.g. report
  submission) is a deliberate exception already in place; don't add further app-level rate
  limiters for new endpoints — the operator handles that at the infra layer.
- **`@Transactional` is currently unusable** in this codebase due to a bean-ambiguity issue.
  Don't reach for it as the default transaction-management approach without checking the current
  state of that ambiguity first — several fix attempts have already been tried and reverted.
- Config surface is env-var driven with local-dev defaults inline in `application.yml`
  (`${ENV_VAR:default}`); when adding a new tunable, follow that pattern and document intent in
  a comment there rather than in code, consistent with how ELO/appeal/evasion/webhook config is
  documented today.

## Contributing / commit conventions

- Commit messages and PR titles **must** follow [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat:`, `fix:`, `chore:`, ...) — enforced in CI (`.github/workflows/pr-lint.yaml`, via a
  shared reusable workflow) and consumed by `release-please` to determine version bumps and
  generate `CHANGELOG.md`.
- This repo only allows squash merges, so the PR title becomes the commit on `main` and is
  linted identically to individual commits (`commitlint.config.mjs`).
- Allowed types: `build`, `chore`, `ci`, `docs`, `feat`, `fix`, `perf`, `refactor`, `revert`,
  `style`, `test`. Header max length is 100 chars.
- A `!` after the type (e.g. `feat!:`) signals a breaking change for release-please's major-version bump.
- Do not add a `Co-Authored-By: Claude` (or any other AI co-author) trailer to commits in this repo.

## Working with Claude Code on this repo

- At the start of a substantive session (implementation/design/debugging, not a quick question),
  ground yourself before acting: check Outline for anything relevant to banning/punishment
  systems and Blackhole itself (roadmap, design docs, decisions), read the actual current
  codebase rather than relying on prior context, and pull current web knowledge for whatever
  specific library/API/security topic the task touches rather than relying solely on training
  data. This keeps reasoning grounded in current state instead of stale assumptions.
