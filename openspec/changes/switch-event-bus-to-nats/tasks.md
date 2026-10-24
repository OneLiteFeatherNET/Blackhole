# Tasks

Tests follow F.I.R.S.T.: unit tests use an in-memory `EventTransport`/fake subscription, injected
`Clock`, no sleeps, no broker; integration tests (tag `integration`) start a fresh
`GenericContainer("nats:<tag>").withCommand("-js")` per class and wait on `fetch(timeout)`/latches.
Each group starts with its failing tests, then the code. Each numbered group is one pull request in a
stack (each based on the previous one) with a single Conventional Commits type. Run
`./gradlew :backend:test`, `./gradlew :discord:test` and `openspec validate --all --strict` before
each PR. This change does not touch the HTTP API, so no client spec regeneration and no Flyway
migration is needed.

## 0. Prerequisites outside this repository

- [ ] 0.1 In Kubernetes-FLUX: create the NATS account `BLACKHOLE` (JetStream limits) and users `blackhole-backend`, `blackhole-discord` and `blackhole-velocity` (WebSocket-only, subscribe-only) with the permissions of `design.md` D6, add the account JWT to `resolver_preload`, deliver `.creds` as SOPS secrets; verify with `nats --creds` that a forbidden publish is rejected
- [ ] 0.2 Confirm the answers to the open questions in `design.md` and the NATS server version of chart `=2.15.1` (pins the test and compose image tag)

## 1. Transport port (PR 1: `refactor(events): put the event bus behind a transport port`)

- [ ] 1.1 Failing tests first: `DomainEventPublisher` over an in-memory `EventTransport` (envelope fields, failure logged and not thrown, never blocks), `RedisSyncConsumer` over a fake subscription (handler error logged and message acked); verify they fail to compile/run before the port exists
- [ ] 1.2 Add `EventTransport`, `EventSubscription` and a subscription factory; move the RabbitMQ code into `RabbitEventTransport`/`RabbitEventSubscription` adapters; behaviour unchanged; verify the whole backend unit suite and `DomainEventDeliveryIntegrationTest` stay green
- [ ] 1.3 `RecordingEventPublisher` drops `super(null)` and builds on the port; verify all existing tests that use it pass unchanged

## 2. Local NATS (PR 2: `build(docker): add nats with jetstream to the local compose stack`)

- [ ] 2.1 Add the `nats` service (`-js -sd /data -m 8222`, ports 4222/8222, volume `./nats/data/`) next to `rabbitmq`, ignore `docker/nats/data/` in git; verify `docker compose up -d nats` and `nats server check jetstream` (or the 8222 `/jsz` endpoint) report JetStream enabled

## 3. NATS transport in the backend (PR 3: `feat(events): move the domain event bus to nats jetstream`)

- [ ] 3.1 Failing tests first: subject builder (all published event types legal, hostile types rejected, no payload value in a subject), stream/consumer config builder (D4 values), `Nats-Msg-Id` equals `eventId`, publish failure is logged via a captured appender and not thrown; integration tests per `design.md` D11 (delivery, shared durable, dedupe, restart catch-up, permission denial)
- [ ] 3.2 `jnats` dependency (version catalog entry, version re-checked on Maven Central), `NatsSettings` bound from `blackhole.events.nats.*`, `Connection` factory (reconnect forever, creds file), `NatsEventTransport` (`publishAsync`, header, fire-and-forget), `StreamProvisioner` (create-or-update `BLACKHOLE_EVENTS`)
- [ ] 3.3 `NatsEventSubscription` for `blackhole-redis-sync` (pull, explicit ack, ack after one attempt); `RedisSyncConsumer` switched to it; verify two instances share one durable
- [ ] 3.4 Delete `BlackholeRabbitTopology`, `RabbitTopology`, `DomainEventRabbitPublisher`, the Rabbit adapters and the `rabbitmq:` block; add the `blackhole.events.nats` block to `application.yml` (comments per D9); swap `PostgresIntegrationTest` to a NATS `GenericContainer`; remove `mn.micronaut.rabbitmq` and `mn.testcontainers.rabbitmq`; verify `./gradlew :backend:build` and the integration tests
- [ ] 3.5 Update `.claude/rules/events.md` and the code comments that mention RabbitMQ in the same PR

## 4. Discord bot on NATS (PR 4: `feat(discord): consume events from nats jetstream`)

- [ ] 4.1 Failing tests first: `BotConfig` (`NATS_URL` required with exit code 2, `NATS_CREDS_FILE` optional, `RABBITMQ_*` ignored, secrets never printed), `Outcome` mapping (done/drop ack, retry `nakWithDelay(10 s)`) with a fake message, consumer config (filters, `max_ack_pending` 5, explicit ack)
- [ ] 4.2 Replace `RabbitConsumer` by `NatsConsumer` (two durables `blackhole-discord-notify`, `blackhole-discord-case`, jnats reconnect, keeps serving commands while down); `RabbitSettings` becomes `NatsSettings`; update `Main`, `SyncEngine` and `NotificationDispatcher` comments
- [ ] 4.3 Integration test against the NATS container: a published `report.created` reaches the handler, a retry is redelivered after the delay (injected short delay), the bot's creds cannot publish

## 5. Velocity receives changes over NATS (PR 5: `feat(velocity): receive punishment changes over nats`)

- [ ] 5.1 Failing tests first (fake NATS connection, fixed `Clock`, no sleeps): a punishment event updates or evicts the cached player; `appeal.resolved` evicts; a BAN event for a connected owner triggers the profile fetch and kick; `section.changed` refreshes the section list; a disconnect event empties the cache and makes every check go over HTTP; reconnect empties it again; an entry older than `cacheMaxAge` is re-read over HTTP; a missing or unreadable creds file leaves HTTP-only checks and does not throw; the connect never blocks login or chat
- [ ] 5.2 jnats in `velocity/build.gradle.kts` (version catalog, relocated in the shadow jar), `NatsSyncService` next to the existing Redis service (both active in this PR, Redis still read as before), `nats` section in `config.json` (`url`, `credsFile`, `cacheMaxAge`), `NATS_URL`/`NATS_CREDS_FILE` overrides; subscribe-only core subscriptions on the five subject groups of D7; verify `./gradlew :velocity:test`
- [ ] 5.3 Integration test against the NATS container with a subscribe-only user: receives a published `punishment.created`, a publish attempt is rejected with a permissions violation, a forbidden subscription (`blackhole.events.case.>`) is rejected
- [ ] 5.4 Report notices (open question 3): render the staff notice from `report.created` plus an HTTP lookup of display facts, or keep it local; if an endpoint is needed it is its own `feat(report)` PR before this one and regenerates `client/specs`

## 6. Remove Redis/Dragonfly from Velocity (PR 6: `refactor(velocity)!: remove redis from the proxy`)

BREAKING (plugin config): `redisUri` disappears; the backend keeps writing Redis until PR 9.

- [ ] 6.1 Failing tests first: config loading ignores a legacy `redisUri` with one warning and writes no Redis default; the proxy enforces bans and mutes with no Redis class on the classpath (HTTP plus NATS only); tab completion of sections and templates reads over HTTP with a short TTL cache
- [ ] 6.2 Delete `RedisSyncService`, `RedisTopology`, `SharedCacheReader`, `SyncCodec` use and the Lettuce dependency from the Velocity module; move the remaining message types; update `PunishmentState`, `BackendSectionSource`, `BlackholeClientModule` and `BlackholeVelocity`; verify `./gradlew :velocity:build` and that the shadow jar contains no `io.lettuce`
- [ ] 6.3 PR description carries `BREAKING CHANGE: the plugin no longer reads redisUri; operators must set nats.url and nats.credsFile`

## 7. Remove RabbitMQ (PR 7: `build: remove rabbitmq from compose and the version catalog`)

- [ ] 7.1 Remove the `rabbitmq` service and `RABBITMQ_*` from `docker/docker-compose.yml`, point the `discord` service at `nats`, drop `rabbitmq-amqp-client` from `settings.gradle.kts` and `discord/build.gradle.kts`; verify `grep -ri rabbit` finds only the migration note and the archive

## 8. Docs (PR 8: `docs: describe the nats event bus`)

- [ ] 8.1 README (stack, compose, env table), `docs/discord-bot.md`, `discord/README.md` (variables, queue names become consumer names), the Velocity plugin docs (`nats` config, no Redis), `openspec/config.yaml` stack line, RabbitMQ wording in in-flight changes; add the `nsc` account/user snippet and the exposure notes from `design.md` D6; verify `openspec validate --all --strict`
- [ ] 8.2 Run the manual check against a real stack (Docker PostgreSQL, Dragonfly, NATS) with a Velocity instance behind the WebSocket route: ban a player on one proxy and see the other kick, stop NATS and see HTTP checks take over, restart it and see the cache refill; record the result in the last PR

## 9. Remove the backend Redis punishment mirror (PR 9, follow-up after PR 6 is deployed, only if open question 2 is "yes": `refactor(punishment): remove the redis punishment mirror`)

- [ ] 9.1 Failing tests first, then delete `PunishmentMirror`, `RedisTopology`, `PunishmentSyncHandler`'s Redis writes, `RedisSyncConsumer` and the `blackhole-redis-sync` consumer; Dragonfly stays for the shared cache; update the `proxy-enforcement` "Redis punishment mirror" requirement (REMOVED) and `.claude/rules/events.md`

## 10. Open the pull requests

- [ ] 10.1 Push each branch of the stack and open one pull request per group above with exactly the title shown in its heading (single Conventional Commits type, lower case, no trailing period), each based on the previous branch, with no Co-Authored-By trailer; confirm each CI check is green and do not merge
