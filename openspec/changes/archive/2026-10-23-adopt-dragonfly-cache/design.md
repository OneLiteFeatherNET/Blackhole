# Design

## Context

Today: `ProfileCache` (Caffeine, 30 s TTL, 10k entries) + `CacheInvalidationPublisher` which
evicts locally and publishes to the `blackhole.cache.invalidate` fanout; `PlayerResolveCache`
(Caffeine, TTL/size from config). The backend already has `micronaut-redis-lettuce` and writes
proxy mirror keys `blackhole:punish:ban:<owner>` / `blackhole:punish:chatban:<owner>` and the
pub/sub channel `blackhole.punishment.sync`. The proxy uses raw Lettuce.

## Goals / Non-Goals

**Goals:** one shared, consistent cache for all replicas; no cache-invalidation broadcast;
graceful degradation when the cache is down.

**Non-Goals:** using Dragonfly as a primary store, Dragonfly-specific commands (stay on the
Redis command subset so plain Redis keeps working for local dev/tests).

## Decisions

1. **Own small `SharedCache` abstraction over Lettuce** (get/put with TTL/evict, JSON values
   via Micronaut serde) instead of `micronaut-cache` `@Cacheable` with the Redis cache
   provider. Reason: explicit fail-open handling and explicit keys are easier to reason about
   than annotation-driven caching on services that also publish events; and the abstraction is
   the extension point for future cached resources.
2. **Key namespace** `blackhole:cache:<resource>:<id>` (`profile`, `elo`, `template`,
   `templates:all`, `player:<lowercased-name>`), separate from the existing proxy mirror keys
   which are a sync contract, not a cache.
3. **Invalidate by delete, not by update.** Writers delete the key after the DB write; the next
   read repopulates it. Avoids caching an object graph mid-rotation.
4. **Fail-open.** Any Lettuce error/timeout on read counts as a miss, on write/evict is logged
   and swallowed; a short command timeout (`blackhole.cache.timeout`, default 200 ms) bounds the
   cost. Stale data is bounded by per-resource TTLs.
5. **Remove the fanout exchange** declaration, publisher and consumer. Existing brokers keep
   the orphan exchange harmlessly; documented in the migration notes.
6. **Proxy template suggestions** read `blackhole:cache:v1:templates:all`; on a miss, error or unreadable payload they call the
   API once (which repopulates). The cached value is the plain JSON array of template responses;
   the API wraps the same objects in a page (`content`).

## Risks / Trade-offs

- [Network hop per cached read instead of in-process] -> Dragonfly sub-millisecond on the
  private network; profile reads already crossed processes via HTTP from proxies.
- [Dragonfly outage makes the DB carry full load] -> fail-open plus existing connection pool;
  acceptable for a single network's size.
- [Serialization drift between cached JSON and DTOs after an upgrade] -> keys carry a schema
  version segment (`blackhole:cache:v1:...`) so a deploy with a new shape ignores old entries.

## Migration Plan

Swap the compose image to `docker.dragonflydb.io/dragonflydb/dragonfly`, deploy backend and
proxies together. Rollback: previous release; it ignores the new cache keys.
