# Design

## Context

The API is versioned via `X-API-VERSION`, has no auth and no CORS configuration, and its
OpenAPI document is pinned in `client/specs/blackhole-api-*.yml`. Player identity is the
SHA-512 hash of the UUID; names are not stored (GDPR). Staff actions carry a client-supplied
`source` UUID.

## Goals / Non-Goals

**Goals:** a maintainable SPA with consistent MD3 look; type-safe API calls that break the
build when the API changes; one-origin deployment so the backend needs no CORS.

**Non-Goals:** server-side rendering, auth, a general-purpose component library.

## Decisions

1. **Stack**: Vue 3 (`<script setup>`, TypeScript), Vite, Vue Router, Pinia, vue-i18n,
   Tailwind CSS v4 (`@tailwindcss/vite`), Vitest + Vue Test Utils. Exact versions are pinned
   in `package-lock.json` at implementation time.
2. **MD3 via Tailwind tokens, not a component framework.** `@material/material-color-utilities`
   generates the scheme from a seed color at build time into CSS custom properties
   (`--md-sys-color-primary`, `on-primary`, `surface-container-*`, ...) for light and dark;
   Tailwind v4 `@theme` maps them to utilities (`bg-primary`, `text-on-surface`,
   `rounded-md-lg`, `shadow-md-2`, type scale `text-title-large` ...). Components
   (`MdButton` filled/tonal/outlined/text, `MdTextField`, `MdSelect`, `MdCard`, `MdDialog`,
   `MdNavigationRail`/`Drawer`, `MdTopAppBar`, `MdChip`, `MdSnackbar`, `MdDataTable`,
   `MdProgress`) live in `web/src/components/md/` and implement state layers and focus rings.
   *Alternatives:* Vuetify 4 (MD3 blueprint) - rejected, it brings its own styling system and
   conflicts with the Tailwind requirement; `@material/web` - in maintenance mode and its
   web components are awkward to style with Tailwind.
3. **API client**: `openapi-typescript` generates `src/api/schema.d.ts` from the pinned spec
   file, `openapi-fetch` performs calls; a thin wrapper sets `X-API-VERSION` and the configured
   `source` UUID. Regeneration runs before `build`, mirroring the Java client.
4. **Acting identity**: since there is no auth, the UI sends a per-deployment operator UUID
   (`BLACKHOLE_UI_SOURCE`, injected at container start into `/config.json`) as `source`, with
   an optional free-text "acting as" name stored in local storage and sent as note/metadata.
   This is explicitly unverified, matching the documented identity gap.
5. **Player lookup**: the UI resolves names via `GET /player/resolve/{name}`, which now also
   returns `owner` (the hash); all further calls use the hash. Names are shown only for the
   current lookup and never stored by the UI.
6. **Dashboard endpoint**: `GET /dashboard/summary` returns counts (active punishments per
   section, open reports, pending appeals, escalations in the last 7 days) and the latest
   escalations; computed by a service from existing repositories.
7. **Deployment**: multi-stage Dockerfile (Node build -> `nginx:alpine`) serving the SPA with
   history fallback and proxying `/api/` to the backend (stripping the prefix), so browser and
   API share one origin. Dev uses Vite's proxy the same way.
8. **Gradle integration**: `web/build.gradle.kts` with `com.github.node-gradle.node` running
   `npm ci`, `npm run build`, `npm test`; `check` depends on the UI tests. CI installs Node 22.

## Risks / Trade-offs

- [Unauthenticated admin UI exposed by mistake] -> README and container docs require
  deployment behind VPN/private network or an authenticating reverse proxy; nginx config ships
  with an `allow/deny` example.
- [Hand-built components drift from MD3] -> components are limited to what screens use and
  follow the MD3 spec tokens only; visual review checklist in the PR.
- [Spec/schema drift] -> generated types break `vue-tsc` in CI when the API changes.
