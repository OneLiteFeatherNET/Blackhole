# Blackhole admin web UI

A Vue 3 single page app for admins and staff: dashboard, player lookup and detail (scores per
section, active punishments, history, ELO history) with apply / revoke / adjust actions, template
and section management, and the report and appeal queues. German (default) and English, light and
dark Material Design 3.

## Security: this UI has no login

Neither the UI nor the backend API it talks to authenticates anybody (a deliberate project
decision, see the root README). **Whoever can reach the UI can ban, unban, change thresholds and
decide appeals.** Deploy it like the API:

- on a private network or behind a VPN, **or**
- behind a reverse proxy that authenticates users (OIDC proxy, basic auth, ...).

The container's nginx config (`nginx/default.conf.template`) documents an `allow` / `deny` block
and a basic-auth block to enable, and `docker/docker-compose.yml` only publishes the UI on
`127.0.0.1`. Do not publish the port on a public interface.

### Who is acting?

Because there is no login there is no verified identity. Every write carries a per-deployment
operator UUID (`BLACKHOLE_UI_SOURCE`) as `source` / `resolvedBy`, and the optional free text
"Acting as" in the top bar is prefixed to the notes you write (adjustments, report resolutions,
appeal decisions). Both are unverified and can be changed by anyone with access.

The backend refuses an appeal review whose `reviewerId` equals the `source` of the punishment.
Punishments applied through this UI all carry the same operator UUID, so by default the UI cannot
decide appeals against them. Set `BLACKHOLE_UI_REVIEWER` to a second UUID to let the UI decide
those appeals; this deliberately sidesteps the self-review check, which cannot protect anything
until the system has a verified identity.

## Privacy

The UI keeps only **theme**, **language** and the optional **acting as** label in the browser's
local storage. Player names live in memory for the current lookup; owner hashes are only in the
URL of the player page. Fonts are bundled (no requests to third parties).

## Development

Node 22 or newer.

```shell
cd web
npm ci
npm run dev        # http://localhost:5173, proxies /api/ to http://localhost:8080
npm test           # Vitest + Vue Test Utils
npm run typecheck  # vue-tsc
npm run build      # type check + production build into dist/
```

Point the dev server at another backend with `BLACKHOLE_BACKEND_URL=http://host:port npm run dev`.
The dev operator UUID comes from `public/config.json`.

`./gradlew :web:build` runs `npm ci`, the build and the tests, so `./gradlew build` covers the UI.

### API types

`npm run generate` (run before `dev`, `build` and `test`) writes the Material Design 3 color tokens
and `src/api/schema.d.ts`, which `openapi-typescript` generates from the same pinned spec file as
the Java client: `client/specs/blackhole-api-<version>.yml`. When the backend API changes and a new
spec is published, bump the file name in the `generate:api` script of `package.json` together with
`client/build.gradle.kts`; `vue-tsc` then points at everything that no longer fits.

The spec describes paged endpoints (`/template`, `/report`, `/appeal`, ELO history) as arrays and
their `pageable` parameter as an object; on the wire they take `page` / `size` and answer with
Micronaut's `Page`. `src/api/blackhole.ts` handles that in one place.

### Structure

| Path                     | What                                                                  |
|--------------------------|-----------------------------------------------------------------------|
| `src/api/`               | Typed client (`openapi-fetch`), runtime config, one method per use case |
| `src/components/md/`     | The small Material Design 3 component set                             |
| `src/components/*/`      | Feature components (player, templates, sections, reports, appeals)    |
| `src/views/`             | One view per route                                                    |
| `src/locales/`           | `de.json` and `en.json`; a test fails if their keys differ            |
| `scripts/`               | Generates the MD3 color tokens from the seed color                    |
| `nginx/`                 | Container config: SPA fallback, `/api/` proxy, runtime `config.json`  |

Colors, shapes, elevation and the type scale are MD3 tokens (`src/styles/main.css`); the color
roles are generated from the seed `#6750A4` in `scripts/generate-md3-tokens.mjs` for light and dark.

### Texts

Add a key to **both** `src/locales/de.json` and `src/locales/en.json`. Tests check that both files
have the same keys and that every `t('literal.key')` in a component exists.

## Container

The image serves the built files and proxies `/api/` to the backend, so the browser sees one origin
and the backend needs no CORS configuration. Build from the repository root (the API spec is in
`client/specs`):

```shell
docker build -f web/Dockerfile -t blackhole-web .
docker run --rm -p 127.0.0.1:8081:8080 \
  -e BLACKHOLE_BACKEND_URL=http://backend:8080 \
  -e BLACKHOLE_UI_SOURCE=3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b \
  blackhole-web
```

| Variable                | Default                | Meaning                                                      |
|-------------------------|------------------------|--------------------------------------------------------------|
| `BLACKHOLE_BACKEND_URL` | `http://backend:8080`  | Where `/api/` is forwarded (without a trailing slash). Must be resolvable when nginx starts. |
| `BLACKHOLE_UI_SOURCE`   | none, **required**     | Operator UUID attributed to writes. The container refuses to start without a valid UUID. |
| `BLACKHOLE_UI_REVIEWER` | `BLACKHOLE_UI_SOURCE`  | UUID used as `reviewerId` for appeal decisions.              |

`docker compose -f docker/docker-compose.yml up -d web` starts it on <http://localhost:8081> against a
backend on the host (`./gradlew :backend:run`).

The image runs nginx as an unprivileged user, sets a restrictive Content-Security-Policy and
`/healthz` answers 200 for health checks.

## Accessibility

Interactive elements are native elements with visible focus rings (MD3 state layers), dialogs keep
focus inside and close on Escape, tables and groups are labelled, the score bars are `role="meter"`
with a spoken value and name their zone in text (never by color alone), errors are announced with
`role="alert"` and every field has a visible label. `tests/contrast.test.ts` checks the WCAG
contrast of the color pairs the UI uses (4.5:1 for text, 3:1 for borders and the focus ring) in the
light scheme and both dark schemes.
