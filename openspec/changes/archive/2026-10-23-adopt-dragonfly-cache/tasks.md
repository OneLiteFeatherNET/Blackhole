# Tasks

## 1. Infrastructure

- [x] 1.1 Replace the `redis` service in `docker/docker-compose.yml` with Dragonfly (`docker.dragonflydb.io/dragonflydb/dragonfly`, port 6379); verify `docker compose config` parses and the backend connects via the unchanged `REDIS_URI`
- [x] 1.2 Add `blackhole.cache.timeout` and per-resource TTLs (`profile`, `elo`, `template`) to `application.yml` with comments; verify the backend starts with defaults

## 2. Shared cache core

- [x] 2.1 Implement the shared cache component (versioned key builder, JSON get/put-with-TTL/evict over Lettuce, fail-open with timeout) with unit tests for key building and fail-open behaviour; verify `./gradlew :backend:test` passes
- [x] 2.2 Add an `integration`-tagged test against a real Redis-protocol server (Testcontainers) covering put/get/expiry/evict; verify it passes in CI

## 3. Migrate caches

- [x] 3.1 Replace the Caffeine `ProfileCache` with the shared cache and make every profile write evict the key; remove `CacheInvalidationPublisher`/`Consumer`/`Message`/Rabbit publisher and the fanout exchange declaration; verify with a test that apply/revoke evict the profile key
- [x] 3.2 Move `PlayerResolveCache` to the shared cache (misses not cached); verify with a unit test using a stubbed cache
- [x] 3.3 Cache templates (single + `templates:all`) and ELO profiles with eviction on every write path; verify with tests that template create/update/delete and ELO deltas evict the keys
- [x] 3.4 Remove `micronaut-cache-caffeine` if no longer referenced; verify `./gradlew :backend:build`

## 4. Proxy

- [x] 4.1 Make Velocity template suggestions read `blackhole:cache:v1:templates:all` with API fallback on miss; verify `./gradlew :velocity:build` and manually via `:velocity:runVelocity` that suggestions work with the backend stopped after the cache is warm

## 5. Documentation

- [x] 5.1 Update README, CLAUDE.md (`events/` bullet no longer mentions the cache-invalidation exchange) and `.claude/rules/events.md`; verify `grep -rn "cache.invalidate" --exclude-dir=.git .` only finds the migration note
