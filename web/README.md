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

The Nitro server has no built-in allow list or basic auth (the former nginx image had commented
examples of both, never enabled): put a reverse proxy in front if you need either.
`docker/docker-compose.yml` only publishes the UI on `127.0.0.1`. Do not publish the port on a
public interface.

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

## Evidence

Player detail and the appeal decision dialog show the evidence attached to a punishment (anticheat
records, chat excerpts, replay and other links, uploaded screenshots and videos):

- **Where**: the "Evidence" button of an active punishment or a history row opens the panel; the
  appeal decision dialog shows it read-only (no upload, no link form, deleting stays possible).
- **Preview**: images and videos play inline from `GET /api/evidence/{id}/content`, videos load their
  metadata only. Links open in a new tab with `rel="noopener noreferrer"`; only `http` and `https`
  values are links, and the UI never fetches a link target. Chat speakers are shown by the name this
  browser session looked up, otherwise as "Player N" (nothing is stored).
- **Upload**: drag and drop or the keyboard-operable drop zone, one file after the other, with
  progress and cancel. The checks in the UI (type and size) are advisory, the backend decides and its
  answer (too large, type not allowed, rejected, scanner unavailable) is shown on the file.
- **Delete**: asks for a reason (erasure request, wrong evidence, other) and an optional note; the
  backend records both in its audit trail.
- **Lifted evidence**: evidence of a punishment lifted by appeal or revoked is hidden by default and
  not counted. The "Show lifted evidence" switch reveals it (labelled), is not remembered and is
  audited by the backend. It is a display default, not an access restriction: like the rest of the UI
  it relies on the deployment boundary (see above).

The upload limits come from `/config.json` and fall back to the backend defaults. The server builds
them from optional variables (set them to the values of the backend's `blackhole.evidence.files.*`):

| Variable                                | `/config.json` key         | Default                                  |
|-----------------------------------------|----------------------------|------------------------------------------|
| `BLACKHOLE_UI_EVIDENCE_MAX_IMAGE_BYTES` | `evidence.maxImageBytes`   | `10485760` (10 MiB)                      |
| `BLACKHOLE_UI_EVIDENCE_MAX_VIDEO_BYTES` | `evidence.maxVideoBytes`   | `104857600` (100 MiB)                    |
| `BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES`   | `evidence.allowedTypes`    | `image/png,image/jpeg,video/mp4,video/webm` |

The server's `/api/` proxy accepts request bodies up to 128 MiB on `/api/evidence/file` (the rest of
the API is capped at 1 MiB); raise `EVIDENCE_UPLOAD_MAX_BYTES` in `server/lib/proxy.ts` if the backend allows
larger videos.

## Development

Node 24 (CI and the image use it; Nitro needs at least 22.12).

```shell
cd web
npm ci
npm run dev        # http://localhost:5173, proxies /api/ to http://localhost:8080
npm test           # Vitest + Vue Test Utils
npm run typecheck  # vue-tsc
npm run build      # type check + Vite build into dist/ + Nitro server into .output/
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
| `server/`                | Nitro server: SPA fallback, `/api/` proxy, runtime `config.json`, security headers (tests in `server/lib/` and `server/__tests__/`) |

Colors, shapes, elevation and the type scale are MD3 tokens (`src/styles/main.css`); the color
roles are generated from the seed `#6750A4` in `scripts/generate-md3-tokens.mjs` for light and dark.

### Texts

Add a key to **both** `src/locales/de.json` and `src/locales/en.json`. Tests check that both files
have the same keys and that every `t('literal.key')` in a component exists.

## Container

The image runs a Nitro (Node) server that serves the built files and proxies `/api/` to the backend,
so the browser sees one origin and the backend needs no CORS configuration. Build from the repository
root (the API spec is in `client/specs`):

```shell
docker build -f web/Dockerfile -t blackhole-web .
docker run --rm -p 127.0.0.1:8081:8080 \
  -e BLACKHOLE_BACKEND_URL=http://backend:8080 \
  -e BLACKHOLE_UI_SOURCE=3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b \
  blackhole-web
```

| Variable                | Default                | Meaning                                                      |
|-------------------------|------------------------|--------------------------------------------------------------|
| `BLACKHOLE_BACKEND_URL` | `http://backend:8080`  | Where `/api/` is forwarded: an `http` or `https` URL without credentials, query or fragment; a trailing slash is dropped. Resolved per request, so the backend may start later (502 until it is reachable). |
| `BLACKHOLE_UI_SOURCE`   | none, **required**     | Operator UUID attributed to writes. |
| `BLACKHOLE_UI_REVIEWER` | `BLACKHOLE_UI_SOURCE`  | UUID used as `reviewerId` for appeal decisions.              |
| `NITRO_PORT`, `NITRO_HOST` | `8080`, `0.0.0.0` (set in the image) | Listen port and address of the server. |

The `BLACKHOLE_UI_EVIDENCE_*` variables from the Evidence section are optional. The environment is
validated once at startup: an invalid or missing value stops the container with a non-zero exit and a
message naming every offending variable, before it listens.

`docker compose -f docker/docker-compose.yml up -d web` starts it on <http://localhost:8081> against a
backend on the host (`./gradlew :backend:run`).

`GET /healthz` answers 200 `ok` (the image's healthcheck uses it). The container runs as the
unprivileged `node` user, needs no writable filesystem (the configuration lives in memory, so
`--read-only` works) and every response carries a restrictive Content-Security-Policy and the other
security headers. `GET /config.json` is never cached.

`node scripts/smoke.mjs <image>` (from `web/`) starts a built image against a throwaway upstream and
checks the behaviour above; CI runs it for every pull request.

### Differences from the former nginx image

- No `allow` / `deny` or basic-auth switch: access control stays with the deployment boundary (see
  Security above).
- No WebSocket proxying (the UI uses none).
- No access log and no dynamic response compression.
- The image is larger (a Node runtime instead of nginx); the runtime stage contains only `.output`.
- The proxy runs in the Node process; request bodies are streamed, not buffered.

## Accessibility

Interactive elements are native elements with visible focus rings (MD3 state layers), dialogs keep
focus inside and close on Escape, tables and groups are labelled, the score bars are `role="meter"`
with a spoken value and name their zone in text (never by color alone), errors are announced with
`role="alert"` and every field has a visible label. `tests/contrast.test.ts` checks the WCAG
contrast of the color pairs the UI uses (4.5:1 for text, 3:1 for borders and the focus ring) in the
light scheme and both dark schemes.
