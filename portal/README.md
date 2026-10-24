# Blackhole appeal portal

The public portal where a punished player signs in with the Ban ID alone, sees the facts of the
punishment, files one appeal, talks to the team and reads the decision. Served at
`appeal.onelitefeather.net`, separate from the internal admin UI (`web/`): own origin, own cookie, own
container, no Entra configuration.

Design: `openspec/changes/add-appeal-portal/` (design decisions 4 to 7, spec `appeal-portal`).

## Parts

- **Browser app** (`src/`): Vue 3, Tailwind with the MD3 tokens (OLF Pink, Vibrant scheme), English
  (default) and German. State lives in memory only; unsent drafts survive the session-expired page and
  nothing else. All user text is rendered as plain text.
- **BFF** (`server/`, Nitro `node-server`): an allow-list of exactly the player routes. `/api/<name>`
  maps one to one onto the backend `/public/<name>`; any other path answers 404 without a backend call.
  - The session token is only in the `__Host-bh_portal` cookie (`HttpOnly; Secure; SameSite=Lax; Path=/`);
    a `__Host-bh_csrf` cookie carries the CSRF value.
  - `POST` and `DELETE` need `Origin` equal to `BLACKHOLE_PORTAL_ORIGIN` and an `X-CSRF-Token` equal to the
    CSRF cookie, checked before the backend is called.
  - Every failed login is the same `401 {"code":"INVALID_BAN_ID"}`. Backend errors are mapped to a fixed set
    of codes; backend text is never relayed.
  - The Ban ID travels only in a POST body. Bodies are never logged.
  - Security headers: CSP (`default-src 'self'`, `frame-ancestors 'none'`), `no-referrer`, `nosniff`.
- **Contract** (`src/api/contract.ts`): routes, payload shapes and error codes in one module. Adjust it
  there when the backend `/public` API differs.

## Configuration

| Variable | Meaning |
|----------|---------|
| `BLACKHOLE_PORTAL_ORIGIN` | Required. The https origin the portal is served at. `http` is accepted for `localhost` outside production only. |
| `BLACKHOLE_BACKEND_URL` | Where `/api/**` is forwarded to (default `http://backend:8080`). Never sent to the browser. |

Invalid values stop the process before it listens.

## Develop

```shell
npm ci
npm test            # Vitest, no network
npm run build       # type check, vite build, nitro build
BLACKHOLE_PORTAL_ORIGIN=http://localhost:5180 NITRO_PORT=3000 node .output/server/index.mjs
npm run dev         # Vite on :5180, forwards /api to the BFF on :3000
```

Rate limiting of the login and write routes is an edge requirement, not part of the app.
