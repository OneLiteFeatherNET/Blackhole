<!--
  Ralph-loop backlog: backfill JUnit 5 coverage for the backend module.
  `backend/src/test` does not exist yet — every item below starts from zero.

  How to run this as a Ralph loop (see the ralph-loop plugin: /ralph-loop:ralph-loop):
    - Point the loop at this file as its backlog/progress source.
    - One task per iteration: pick the first unchecked box, write tests for that
      package's service-layer classes only (not controllers — those need a running
      HTTP context; leave them for a later pass), run `./gradlew :backend:test`,
      commit (Conventional Commits, per CLAUDE.md), check the box, stop the iteration.
    - Circuit breaker: if 3 consecutive iterations fail to get a green
      `./gradlew :backend:test` for their package, stop the loop and surface the
      failure instead of continuing — these packages depend on real MariaDB/RabbitMQ
      (docker/docker-compose.yml must be up; there's no in-memory/mocked path, see
      CLAUDE.md), so a red run is often infra, not code.
    - Update .claude/PROGRESS.md's "Active" section with which package is in flight
      before starting, so a fresh session/loop iteration can resume cleanly.

  Priority order below is roughly: smallest/most self-contained business logic first,
  so early loop iterations build momentum before tackling packages with more
  cross-cutting dependencies (RabbitMQ events, webhook delivery).
-->

# Backend test coverage backlog

## Service-layer unit tests (one package per task)

- [ ] `elo/service` — dual-ELO scoring, threshold crossing → auto-punishment triggers,
      `ToxicityScorer` keyword matching
- [ ] `appeal/service` — `min-days-auto` vs `min-days-manual` wait enforcement
- [ ] `evasion/service` — `IpCorrelationService` hashing behavior; must cover the
      `BLACKHOLE_EVASION_IP_SALT`-unset → 503 gate explicitly (see CLAUDE.md)
- [ ] `playerresolver/service` — pluggable player-name resolver (online vs offline players)
- [ ] `imports/service` — vanilla ban-list import parsing/mapping
- [ ] `punishment/service` — core punishment lifecycle (create/expire/lift)
- [ ] `report/service` — report submission incl. the existing app-level rate limiter
- [ ] `connector` — connector registration + `EventSubscriptionEntity` scope handling
      (scopes are descriptive metadata only, not enforced — test that assumption, don't
      accidentally start enforcing it)
- [ ] `profile/service` — player profile assembly

## Cross-cutting (needs the above first)

- [ ] `WebhookDispatchConsumer` retry/DLX parking behavior (`blackhole.webhook.failed`
      after `max-retries`) — needs a RabbitMQ test fixture, tackle after service-layer
      tests establish the test infra pattern
- [ ] `WebhookUrlValidator` SSRF hardening — private-network rejection, the
      `blackhole.webhook.allow-private-networks` flag

## Explicitly out of scope for this backlog

- Controller-layer integration tests (need a running Micronaut HTTP context + real
  Docker infra per request — separate, heavier effort; revisit once service-layer
  coverage is in place)
- `velocity` and `client` modules
