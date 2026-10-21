# Proposal

## Why

Sections, templates, thresholds, ELO histories, reports and appeals are only reachable through
raw REST calls or Swagger today. Admins need a management UI to configure the ban system and
review players without crafting HTTP requests, and staff outside the game need to work the
report and appeal queues.

## What Changes

- New top-level `web/` module: a Vue 3 single-page app styled with Tailwind CSS using Material
  Design 3 tokens (color roles generated from a seed color, light/dark scheme, MD3 type scale,
  shape and elevation) and a small in-repo MD3 component set.
- Screens: dashboard, player lookup + player detail (section scores with thresholds, active
  punishments, history, ELO history, manual adjustment, revoke), templates CRUD, sections
  configuration, reports queue, appeals queue.
- Typed API client generated from the same OpenAPI spec file as the Java client.
- German (default) and English UI texts.
- Container image (static files + reverse proxy to the backend under one origin) and a
  docker-compose service; Gradle wiring so `./gradlew build` also builds and tests the UI.
- Backend: one read endpoint for the dashboard summary and an owner-hash field on the player
  lookup response so the UI never hashes UUIDs itself.

Out of scope: authentication/roles (the UI is protected by the network boundary like the API,
see project principles), real-time push updates, mobile-native apps, editing the toxicity
scorer.

## Capabilities

### New Capabilities

- `admin-web-ui`: the browser-based management interface - its screens, behaviours, theming,
  localisation and deployment contract.

### Modified Capabilities

- `player-resolution`: the lookup response additionally carries the hashed owner identifier.

## Impact

- New `web/` directory (Node toolchain, `package.json`, Vite config), `settings.gradle.kts`
  (Gradle wrapper task for the UI build), `.github/workflows/build-pr.yml` (Node setup).
- Backend: dashboard summary endpoint, player lookup DTO; OpenAPI client spec bump.
- `docker/docker-compose.yml` (web service), README.
- Depends on `staff-temp-bans-elo-escalation` (section/template/ELO API shape).
