---
paths:
  - "backend/src/main/java/**/cloudnet/**/*.java"
  - "velocity/src/main/java/**/cloudnet/**/*.java"
---

# CloudNet sections and catalogue

- Direction is proxy -> backend only. The backend never calls CloudNet; the proxy reads CloudNet's
  driver API and reports a catalogue (`PUT /cloudnet/catalogue`, full snapshot, role `PROXY` only).
  `GET /cloudnet/catalogue` is `VIEWER` and up and refused for `PROXY`; `PermissionMatrixIntegrationTest`
  and `RouteCoverageIntegrationTest` hold both rows.
- Catalogue storage is the shared cache, one key per proxy (`CacheKeys.cloudnetCatalogue`), TTL
  `blackhole.cloudnet.catalogue.ttl` (default `PT15M`). A cache outage means `PUT` 503 and `GET`
  `available: false`; nothing in enforcement may depend on the catalogue. Names are never validated
  against it.
- `ServerTargetKind` (`PATTERN`, `CLOUDNET_TASK`, `CLOUDNET_GROUP`, `CLOUDNET_SERVICE`) rides on the
  section; `servers` holds globs or exact names. `EnforcementKind` stays `MUTE | BAN | SERVER`.
- In the Velocity module only `DriverCloudNetView` may import `eu.cloudnetservice.*` (the driver is
  `compileOnly`, never shaded); everything else uses `CloudNetView`, and tests use `FakeCloudNetView`.
  Matching is the pure `ServerTargets.covers`; a server CloudNet does not know is not covered (fail
  open, one warning per name). Config keys are `cloudnet.integration` (`BLACKHOLE_CLOUDNET_INTEGRATION`),
  `proxyId`, `reportDebounce`, `heartbeat`.
- The user-facing description is the "CloudNet sections" part of the root `README.md`; keep it in step.
