# Blackhole admin web UI

A Vue 3 single page app for admins and staff (Microsoft Entra ID sign-in): dashboard, player lookup and detail (scores per
section, active punishments, history, ELO history) with apply / revoke / adjust actions, template
and section management, and the report and appeal queues. English (default) and German, light and
dark Material Design 3.

## Sign-in

Staff sign in with Microsoft Entra ID (OpenID Connect, Authorization Code flow with PKCE, no
implicit flow). The browser sends the access token as `Authorization: Bearer` with every API call and
the backend verifies it; the UI only decides what to offer.

- **Tokens stay in memory.** Access and refresh tokens are never written to `localStorage`,
  `sessionStorage`, IndexedDB or cookies. Only the short-lived OIDC `state` and PKCE verifier sit in
  `sessionStorage` across the redirect and are removed when the callback completes. A reload loses
  the token, so the app redirects to Entra again, which signs you in without a prompt while your
  Entra session lives.
- **Renewal.** The token is renewed in the background with the refresh token (`offline_access`). A
  401 renews once and repeats the request once; if that fails, you are sent to sign in again. A 403
  shows "You are not permitted to do this" and is never retried.
- **Roles.** After sign-in the UI loads `GET /auth/me` (display name and roles; it does not decode
  tokens) and shows "Signed in as ...". Actions the role cannot perform are hidden; the backend stays
  authoritative.

  | Role | Offered |
  |------|---------|
  | none (empty role list) | only a "no access" page with sign-out; no data is requested |
  | `VIEWER` | everything read-only |
  | `MODERATOR` | and: apply, revoke and adjust punishments, resolve reports, decide appeals, add and upload evidence |
  | `ADMIN` | and: create, edit and disable sections, create, edit and delete templates, delete evidence |

- **Navigation.** A side navigation groups the pages as Übersicht, Moderation (Fälle, Einsprüche, Meldungen,
  Spieler), Regelwerk (Bereiche, Vorlagen) and System (Verwaltung, `ADMIN` only; a group without a visible
  entry is left out). From 1200 px it is a drawer that can be folded into a rail (the choice is kept in
  `localStorage`); from 768 px it is a rail; below that a menu button opens a modal drawer. The top bar
  carries the product mark and the account menu (account, theme, language, sign-out). An unknown path shows
  a "Seite nicht gefunden" page after sign-in. Fälle, Einsprüche and Meldungen carry a badge with the open
  work (open cases, appeals awaiting review, unresolved reports; hidden at 0, "99+" above 99). Appeals and
  reports come from `GET /dashboard/summary`, cases from the `totalSize` of `GET /case?status=OPEN&size=1`;
  refreshed on every navigation and every 60 s.
- **Search.** The search field in the top bar (shortcut `/`) opens a player by name (`GET /player/resolve/{name}`)
  or a case by `#A1B2C3D4` (`GET /punishment/short/{shortId}`; when short ids collide the matches are listed
  to pick from) or by a full punishment id. Arrow keys select, Enter opens, Escape closes. A Ban ID lookup
  does not exist yet (see `openspec/changes/add-appeal-portal`).
- **Motion.** State changes use the MD3 motion tokens (`--md-sys-motion-easing-*`, `--md-sys-motion-duration-*`
  in `src/styles/main.css`) and only animate `opacity` and `transform`: a fade-through between pages, scale and
  fade for dialogs, menus growing from their anchor, a sliding modal drawer and snackbar, staggered list items
  (capped at 8 steps), state layers on controls. With `prefers-reduced-motion: reduce` nothing moves or scales,
  only short fades remain (`MdTransition` switches every pattern to a fade, the CSS zeroes the distances).
- **Account.** The "Konto" (*Account*) entry in the account menu of the top bar opens a dialog that shows whether the signed-in
  person's Minecraft account is linked. "Minecraft-Konto verknüpfen" issues a code with a countdown and a
  copy button for the code and for the command `/blackhole link <code>`; "Verknüpfung entfernen" unlinks
  after a confirmation. Admins also get a list of all links with an unlink action. See
  [Linking a Minecraft account](../README.md#linking-a-minecraft-account). The code is held in
  memory only and never stored in the browser.
- **Admin page.** `/admin` ("Verwaltung" in the navigation) is offered to `ADMIN` only; anyone else who opens
  the address lands on a no-access page that says it is for administrators. It lists the known staff
  (`GET /admin/staff-actor`: name, object id, kind, last seen) and can anonymize one
  (`POST /admin/staff-actor/{oid}/anonymize`) after a confirmation that names the consequences: the name is
  removed, history keeps the object id, the Minecraft link is removed, and it cannot be undone.
  It also imports a vanilla ban list: choose or paste `banned-players.json` (optionally choose `banned-ips.json`,
  whose entries are counted but not imported), run the preview (`dryRun`, changes nothing) and then the import
  (`POST /admin/import/vanilla`, multipart, behind a confirmation). The result shows entries found, imported (or
  "would be imported"), skipped (player already banned) and errors, with a description per invalid entry. The
  endpoint is hidden from the published API spec, so the UI calls it by hand; the UI server forwards at most
  1 MiB per request on this route, so larger lists have to be split. A template is required: every imported ban is
  linked to it (templates of mute sections are not offered), optionally with a table that maps a reason of the file
  to another template, and the result lists the template of each reason. "Imported punishments" lists the bans
  imported before this that still have no template, grouped by reason; pick a template per group and confirm to link
  them (`GET /admin/import/punishments`, `POST /admin/import/punishments/retemplate`). Only the template changes.
  The page also shows the CloudNet catalogue the proxies report (`GET /cloudnet/catalogue`, read-only): tasks with
  their groups, groups, services (running or stopped), the reporting proxies and when the data was last reported,
  or a hint when no catalogue exists.
- **Sign-out** clears the in-memory user and ends the Entra session (`post_logout_redirect_uri` is
  the UI's origin).
- **Evidence files** (`GET /evidence/{id}/content`) need the bearer token, which `<img>` and
  `<video>` cannot send. The UI fetches the file through the API and shows it from an object URL
  (`blob:`), which the content security policy allows for images and media.

### Entra app registration (SPA)

Register the UI as a **Single-page application** with the redirect URI
`https://<ui-host>/auth/callback` (and `http://localhost:5173/auth/callback` for development) and the
logout URL `https://<ui-host>/`. Grant it the delegated permission `access_as_user` of the API
registration. The scopes requested are `openid profile offline_access` plus the API scope.

## Privacy

The UI keeps only **theme** and **language** in the browser's local storage. Player names live in
memory for the current lookup; owner hashes are only in the URL of the player page. Staff display
names come from `GET /auth/me` and are held in memory, a freshly issued link code lives in memory only; the backend's retention of staff data is described in the
[root README](../README.md#authentication). Fonts are bundled (no requests to third
parties).

## Evidence

Player detail and the appeal decision dialog show the evidence attached to a punishment (anticheat
records, chat excerpts, replay and other links, uploaded screenshots and videos):

- **Where**: the "Evidence" button of an active punishment or a history row opens the panel; the
  appeal decision dialog shows it read-only (no upload, no link form; deleting stays possible for admins).
- **Preview**: images and videos play inline from `GET /api/evidence/{id}/content` (fetched with the token, see Sign-in), videos load their
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
  audited by the backend, now with the verified user. It is a display default, not an access
  restriction: every reader needs at least the viewer role.

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

### Local development with Entra ID

Local development signs in against the real Entra dev registration. The dev server has no Nitro
server, so it serves `/config.json` itself: from the git-ignored `public/config.local.json` when it
exists, otherwise from the placeholder fixture `public/config.json` (which points nowhere). Neither
file holds real ids in the repository.

1. Put the dev registration in `~/.config/blackhole/entra-dev.env`: `BLACKHOLE_UI_OIDC_AUTHORITY`
   (`https://login.microsoftonline.com/<tenant-id>/v2.0`), `BLACKHOLE_UI_OIDC_CLIENT_ID` (SPA app) and
   `BLACKHOLE_UI_API_SCOPE` (`api://<api-client-id>/access_as_user`), plus the `BLACKHOLE_AUTH_*`
   values of the backend.
2. Generate the dev config from it:

   ```shell
   cd web
   set -a; . ~/.config/blackhole/entra-dev.env; set +a
   npm run dev:config      # writes public/config.local.json
   ```

3. Run the backend in a shell that loaded the same file, so it trusts the same tenant:

   ```shell
   set -a; . ~/.config/blackhole/entra-dev.env; set +a
   ./gradlew :backend:run
   ```

4. Run the dev server on exactly `http://localhost:5173` (`npm run dev`). The SPA registration has
   `http://localhost:5173/auth/callback` and `http://127.0.0.1:5173/auth/callback` as redirect URIs;
   open the UI on one of these origins, not on another port or host name.

Sign in with your own Entra account (MFA included). What you can do depends on the groups the backend
maps to roles; without one you land on the "no access" page. Tests never use the network: they run
against a fake `UserManager` and a fake `fetch`.

The compose file still contains a mock OIDC server (`docker/mock-oauth`); it is an alternative for
working offline: point `public/config.local.json` at `http://localhost:18090/blackhole` with client id
`blackhole-web` and scope `blackhole-api/access_as_user`.

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
| `src/auth/`              | Sign-in (`oidc-client-ts`), role helpers; `src/router/authGuard.ts` guards the routes |
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
  -e BLACKHOLE_UI_OIDC_AUTHORITY=https://login.microsoftonline.com/<tenant-id>/v2.0 \
  -e BLACKHOLE_UI_OIDC_CLIENT_ID=<SPA application (client) id> \
  -e BLACKHOLE_UI_API_SCOPE=api://<api-client-id>/access_as_user \
  blackhole-web
```

| Variable                | Default                | Meaning                                                      |
|-------------------------|------------------------|--------------------------------------------------------------|
| `BLACKHOLE_BACKEND_URL` | `http://backend:8080`  | Where `/api/` is forwarded: an `http` or `https` URL without credentials, query or fragment; a trailing slash is dropped. Resolved per request, so the backend may start later (502 until it is reachable). |
| `BLACKHOLE_UI_OIDC_AUTHORITY` | none, **required** | Issuer URL staff sign in at, e.g. `https://login.microsoftonline.com/<tenant-id>/v2.0`. `https` only (`http` for `localhost`, the mock server), no credentials, query or fragment. Its origin is the only external origin in the CSP `connect-src`. |
| `BLACKHOLE_UI_OIDC_CLIENT_ID` | none, **required** | Application (client) id of the SPA registration. |
| `BLACKHOLE_UI_API_SCOPE` | none, **required** | The API scope to request, e.g. `api://<api-client-id>/access_as_user`; one scope, no spaces. |
| `NITRO_PORT`, `NITRO_HOST` | `8080`, `0.0.0.0` (set in the image) | Listen port and address of the server. |

`BLACKHOLE_UI_SOURCE` and `BLACKHOLE_UI_REVIEWER` no longer exist; the backend records the verified
signed-in user. The rollout order for backend, plugin and UI is in the
[migration guide](../README.md#migrating-an-existing-deployment); the Entra setup is in
[docs/entra-setup.md](../docs/entra-setup.md). The `BLACKHOLE_UI_EVIDENCE_*` variables from the Evidence section are optional. The environment is
validated once at startup: an invalid or missing value stops the container with a non-zero exit and a
message naming every offending variable, before it listens.

`docker compose -f docker/docker-compose.yml up -d web` starts it on <http://localhost:8081> against a
backend on the host (`./gradlew :backend:run`) and the mock OIDC server (`mock-oauth`, port 18090).

`GET /healthz` answers 200 `ok` (the image's healthcheck uses it). The container runs as the
unprivileged `node` user, needs no writable filesystem (the configuration lives in memory, so
`--read-only` works) and every response carries a restrictive Content-Security-Policy (`connect-src` is
`'self'` plus the origin of `BLACKHOLE_UI_OIDC_AUTHORITY`) and the other security headers. `GET /config.json` is never cached.

`node scripts/smoke.mjs <image>` (from `web/`) starts a built image against a throwaway upstream and
checks the behaviour above; CI runs it for every pull request.

### Differences from the former nginx image

- No `allow` / `deny` or basic-auth switch: staff authenticate with Entra ID (see Sign-in above).
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
