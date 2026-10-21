# Proposal

## Why

Every backend replica keeps its own Caffeine caches (profiles, player resolutions) and has to
broadcast invalidations over a RabbitMQ fanout exchange to stay consistent. Proxies hit the API
for template lists on every tab-completion keystroke. The network runs Dragonfly (Redis
protocol, multi-threaded, drop-in for Redis) as its cache, and Blackhole already speaks Redis
to its proxies - one shared cache removes the per-replica invalidation machinery and gives
proxies and the upcoming admin UI a fast read path.

## What Changes

- Dragonfly replaces the `redis` service in `docker/docker-compose.yml`; `REDIS_URI` keeps
  working unchanged (Dragonfly is wire-compatible), so the proxy sync channel and keys stay as is.
- A shared cache in Dragonfly replaces the in-process Caffeine caches for punishment profiles
  and player resolutions, and newly caches punishment templates and ELO profiles.
- Cache writes are invalidated by deleting the shared key; the RabbitMQ
  `blackhole.cache.invalidate` fanout exchange and its per-replica consumer are removed.
  RabbitMQ stays for domain events.
- If Dragonfly is unreachable, reads fall through to PostgreSQL (cache is never a source of truth).
- Proxies read the template list from the shared cache for tab completion instead of calling
  the API per keystroke.

Out of scope: moving domain events off RabbitMQ, Dragonfly clustering/persistence tuning,
caching anything that contains raw PII (only hashed owners are cache keys).

## Capabilities

### New Capabilities

- `shared-cache`: what is cached in the shared Redis-protocol cache, key namespace, TTLs,
  invalidation and fail-open behaviour.

### Modified Capabilities

- `punishment-management`: cache invalidation becomes a single shared-key eviction instead of
  local-plus-broadcast eviction.
- `player-resolution`: resolution cache moves from per-replica memory to the shared cache.

## Impact

- `backend/.../profile/` (ProfileCache, CacheInvalidation* removed/replaced),
  `backend/.../playerresolver/PlayerResolveCache`, `backend/.../events/BlackholeRabbitTopology`
- `backend/build.gradle.kts` (drop `micronaut-cache-caffeine` if unused), `application.yml`
  (`blackhole.cache.*` TTLs)
- `velocity/` template suggestions read from Dragonfly
- `docker/docker-compose.yml`, README, CLAUDE.md (`events/` description), `.claude/rules/events.md`
