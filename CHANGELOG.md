# Changelog

## [2.0.0](https://github.com/OneLiteFeatherNET/Blackhole/compare/v1.0.0...v2.0.0) (2026-07-23)


### ⚠ BREAKING CHANGES

* remove connector/signal integration framework ([#116](https://github.com/OneLiteFeatherNET/Blackhole/issues/116))
* **api:** AppealController's endpoints move from /v1/... to their bare path, versioned instead via the X-API-VERSION header or an api-version/v query parameter (default-version 1 covers callers that send neither).
* remove JWT/@Secured auth system ([#109](https://github.com/OneLiteFeatherNET/Blackhole/issues/109))
* **api:** tenant-scoped REST endpoints change shape (tenantId moves from body/claim to a URL path variable on several routes - see updated OpenAPI spec). JWTs no longer carry a tenantId claim, so tenant isolation is no longer enforced by the API - callers must not treat a role token as tenant-restricted anymore.
* the published backend artifact's Maven groupId changes from net.onelitefeather.blackhole to net.onelitefeather (artifactId blackhole-backend is unchanged). client and the newly-published velocity module already use/adopt net.onelitefeather.
* **database:** replace Flyway with Liquibase XML changelogs ([#101](https://github.com/OneLiteFeatherNET/Blackhole/issues/101))
* every existing endpoint moves from e.g. /punishment to /v1/punishment. Callers (Velocity plugin via the generated client, any connector integration) must regenerate/update against the new paths.
* every existing endpoint moves from e.g. /punishment to /v1/punishment. Callers (Velocity plugin via the generated client, any connector integration) must regenerate/update against the new paths.
* every existing endpoint moves from e.g. /punishment to /v1/punishment. Callers (Velocity plugin via the generated client, any connector integration) must regenerate/update against the new paths.

### Features

* add pluggable player-name resolver for offline-player bans ([#114](https://github.com/OneLiteFeatherNET/Blackhole/issues/114)) ([3816b63](https://github.com/OneLiteFeatherNET/Blackhole/commit/3816b63b87a099c5de8163374660f07ce9a7afcb))
* add README and enforce conventional commits in CI ([1aa0ab9](https://github.com/OneLiteFeatherNET/Blackhole/commit/1aa0ab97f44f29c3906f6dd9a5cae57a9e7ffe24))
* **api:** version the REST API via Micronaut @Version instead of /v1 URI prefix ([#110](https://github.com/OneLiteFeatherNET/Blackhole/issues/110)) ([0e0d39f](https://github.com/OneLiteFeatherNET/Blackhole/commit/0e0d39ff8d0ec858203083eb1392fc57dfa880b3))
* **client:** Add client generation ([a703ca3](https://github.com/OneLiteFeatherNET/Blackhole/commit/a703ca322e2c710bd7afdb819389816c8926f103))
* **client:** Add client generation ([a703ca3](https://github.com/OneLiteFeatherNET/Blackhole/commit/a703ca322e2c710bd7afdb819389816c8926f103))
* **components:** Add custom Components utils ([6a15796](https://github.com/OneLiteFeatherNET/Blackhole/commit/6a1579616f50113e3679cd8d9ecd007eb4b103d9))
* **elo:** fine-tune per-tenant Elo settings ([9ad0a58](https://github.com/OneLiteFeatherNET/Blackhole/commit/9ad0a58290eabcd97be1016822108f22a07e49f4))
* publish velocity module and unify Maven groupId to net.onelitefeather ([#106](https://github.com/OneLiteFeatherNET/Blackhole/issues/106)) ([342eb6d](https://github.com/OneLiteFeatherNET/Blackhole/commit/342eb6d55bb0a7230b9b3c3a2405e32b93027c48))
* remove JWT/@Secured auth system ([#109](https://github.com/OneLiteFeatherNET/Blackhole/issues/109)) ([c66427d](https://github.com/OneLiteFeatherNET/Blackhole/commit/c66427d758245f346ce0fe8c0a1703388dcc90e4))
* sync active punishments to Redis for multi-proxy Velocity setups ([#97](https://github.com/OneLiteFeatherNET/Blackhole/issues/97)) ([67a3902](https://github.com/OneLiteFeatherNET/Blackhole/commit/67a3902251417aa617e70021a1457b442dd25b78))
* version REST API under /v1 URI prefix ([#92](https://github.com/OneLiteFeatherNET/Blackhole/issues/92)) ([e7ed3d5](https://github.com/OneLiteFeatherNET/Blackhole/commit/e7ed3d58000d8c58d8acff6a38e5cccee0850707))


### Bug Fixes

* **backend:** narrow vanilla-import dedup to the specific source entry ([#126](https://github.com/OneLiteFeatherNET/Blackhole/issues/126)) ([7d915a7](https://github.com/OneLiteFeatherNET/Blackhole/commit/7d915a7e51fcf123bab2b35243831f28b8f0ca41))
* **backend:** stop vanilla import from duplicating already-expired bans ([#120](https://github.com/OneLiteFeatherNET/Blackhole/issues/120)) ([b8ce10d](https://github.com/OneLiteFeatherNET/Blackhole/commit/b8ce10d121f6e0ec0fbb2b7cd1eabf8261abfccd))
* **deps:** update dependency com.fasterxml.jackson:jackson-bom to v2.22.1 ([#103](https://github.com/OneLiteFeatherNET/Blackhole/issues/103)) ([ff234b8](https://github.com/OneLiteFeatherNET/Blackhole/commit/ff234b8bc705fd584ce19fbc5a8f2ba78072316f))
* **deps:** update dependency com.velocitypowered:velocity-api to v3.5.1 ([#118](https://github.com/OneLiteFeatherNET/Blackhole/issues/118)) ([563836e](https://github.com/OneLiteFeatherNET/Blackhole/commit/563836e104c45cc725c392ab13abf0ed61efa8ea))
* **deps:** update dependency io.lettuce:lettuce-core to v7 ([#99](https://github.com/OneLiteFeatherNET/Blackhole/issues/99)) ([5dc54cd](https://github.com/OneLiteFeatherNET/Blackhole/commit/5dc54cda8ef7af7bf85ab22bd0e17c54b74a53b8))
* **deps:** update dependency net.logstash.logback:logstash-logback-encoder to v9 ([#108](https://github.com/OneLiteFeatherNET/Blackhole/issues/108)) ([b29710f](https://github.com/OneLiteFeatherNET/Blackhole/commit/b29710fec7f8bfc7f977e6e82c52ff8b52d89131))
* **deps:** update dependency org.incendo:cloud-annotations to v2.1.0 ([#135](https://github.com/OneLiteFeatherNET/Blackhole/issues/135)) ([bfe6b59](https://github.com/OneLiteFeatherNET/Blackhole/commit/bfe6b59df0fc29c224a7b0fbd03762372f2fbefc))
* **deps:** update dependency org.incendo:cloud-minecraft-extras to v2.0.0 ([#133](https://github.com/OneLiteFeatherNET/Blackhole/issues/133)) ([fcaead0](https://github.com/OneLiteFeatherNET/Blackhole/commit/fcaead055c3d52d177ed8623b3565242ce4c3c37))
* **deps:** update dependency org.incendo:cloud-velocity to v2.0.0 ([#134](https://github.com/OneLiteFeatherNET/Blackhole/issues/134)) ([04e0a40](https://github.com/OneLiteFeatherNET/Blackhole/commit/04e0a40db6fbc9e8d3ca25c743654ab4f191ea3c))
* **deps:** update dependency org.openapitools:jackson-databind-nullable to v0.2.11 ([#137](https://github.com/OneLiteFeatherNET/Blackhole/issues/137)) ([f9ed7dc](https://github.com/OneLiteFeatherNET/Blackhole/commit/f9ed7dca6a77a74d828eb26064341e0aef31df66))


### Documentation

* add CLAUDE.md ([#93](https://github.com/OneLiteFeatherNET/Blackhole/issues/93)) ([da6d7d4](https://github.com/OneLiteFeatherNET/Blackhole/commit/da6d7d484887b4a868d493585aa3d63661f6df4e))
* add session workflow conventions to CLAUDE.md ([#112](https://github.com/OneLiteFeatherNET/Blackhole/issues/112)) ([a9e9deb](https://github.com/OneLiteFeatherNET/Blackhole/commit/a9e9deb97601a5b7dd6af7645c1fbf9aea727357))
* **claude:** add layered backend-architecture Claude Code skills ([#111](https://github.com/OneLiteFeatherNET/Blackhole/issues/111)) ([5d708ca](https://github.com/OneLiteFeatherNET/Blackhole/commit/5d708ca69fa42d3a2b0081a552a2a109bed0ef3b))
* fix stale "no tests exist anywhere" claim in CLAUDE.md and specs ([#125](https://github.com/OneLiteFeatherNET/Blackhole/issues/125)) ([a0593ab](https://github.com/OneLiteFeatherNET/Blackhole/commit/a0593ab4f9cdd5fd76fd53386cdce388f098c884))
* no AI co-author trailer + session-start research workflow ([#94](https://github.com/OneLiteFeatherNET/Blackhole/issues/94)) ([95dcb73](https://github.com/OneLiteFeatherNET/Blackhole/commit/95dcb7360e53c4e93e31229365083b185757f30b))


### Code Refactoring

* **api:** remove the tenant concept entirely - single-network ban backend ([#105](https://github.com/OneLiteFeatherNET/Blackhole/issues/105)) ([4f67cbd](https://github.com/OneLiteFeatherNET/Blackhole/commit/4f67cbde9dbe78864443f01e0c3389379231f83c))
* **database:** replace Flyway with Liquibase XML changelogs ([#101](https://github.com/OneLiteFeatherNET/Blackhole/issues/101)) ([9051df0](https://github.com/OneLiteFeatherNET/Blackhole/commit/9051df075d4072a18c3e94ee46f35db0f6f9fc41))
* remove connector/signal integration framework ([#116](https://github.com/OneLiteFeatherNET/Blackhole/issues/116)) ([6ff3345](https://github.com/OneLiteFeatherNET/Blackhole/commit/6ff334530bd9fcd79a43c1bc70f05a8a2084ecf6))

## Changelog
