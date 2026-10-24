# Run the appeal portal

How an operator deploys the public appeal portal (`portal` module) and the parts of the backend it depends on:
the container, the session limits, the Ban ID key, the rules the edge has to enforce, and what must never be
logged. What the portal does is in [`portal/README.md`](../portal/README.md). What staff do with Ban IDs,
conversations and protocols is in [`docs/appeal-portal-staff.md`](appeal-portal-staff.md).

All values below are placeholders. Never commit a real key.

## 1. What runs where

```
player's browser --https--> edge --> portal container (SPA + backend-for-frontend) --http--> backend /public/**
```

- The portal is served at **`appeal.onelitefeather.net`**, on its own origin, with its own cookies and its own
  container. It is separate from the admin UI and has no Entra configuration.
- A player signs in with the Ban ID alone. The portal's server part (the BFF) turns the login into an opaque
  session token that lives only in an `HttpOnly` cookie (`__Host-bh_portal`), and forwards an allow-list of
  routes to the backend: `/api/<name>` becomes `/public/<name>`, any other path is a 404 without a backend call.
- The backend has no authentication for players other than that session token. A staff or proxy token is
  refused on `/public`, and a player session reaches nothing outside it.
- Only the portal needs to reach the backend's `/public/**`. Do not publish the backend itself on the portal host.

## 2. Deploy the container

Build from the repository root (CI builds and smoke-checks the same image on every pull request):

```shell
docker build -f portal/Dockerfile -t blackhole-portal .
docker run -p 8082:8080 \
  -e BLACKHOLE_BACKEND_URL=http://backend:8080 \
  -e BLACKHOLE_PORTAL_ORIGIN=https://appeal.onelitefeather.net \
  blackhole-portal
```

| Variable | Meaning |
|----------|---------|
| `BLACKHOLE_PORTAL_ORIGIN` | Required. The https origin the portal is served at, nothing else (no path). A state-changing request is accepted only when its `Origin` equals it. `http` is accepted for `localhost` outside production only. |
| `BLACKHOLE_BACKEND_URL` | Where `/api/**` is forwarded to. Default `http://backend:8080`. Never sent to the browser. No credentials, query or fragment. |

Both values are validated at start. An invalid one stops the process before it listens and names the problem.

The image runs as the `node` user, listens on `0.0.0.0:8080` (`NITRO_HOST`, `NITRO_PORT`) and holds no state.
Terminate TLS at the edge: the cookies are `Secure` and use the `__Host-` prefix, which needs https, `Path=/` and
no `Domain`. Several replicas behind one edge work; sessions live in the backend database, not in the portal.

### Health

`GET /healthz` answers `200 ok` as plain text. It only says the process is up; it does not call the backend.
The image's `HEALTHCHECK` uses it every 30 seconds. To know that the backend is reachable, probe the backend's own
health endpoint.

## 3. Session limits

A session ends when either limit is reached:

| Limit | Default | Property (environment variable) |
|-------|---------|---------------------------------|
| Idle, sliding (each request moves it) | 30 minutes | `blackhole.public.session.idle` (`BLACKHOLE_PUBLIC_SESSION_IDLE`) |
| Absolute, from the login | 4 hours | `blackhole.public.session.absolute` (`BLACKHOLE_PUBLIC_SESSION_ABSOLUTE`) |

Values are ISO-8601 durations (`PT30M`, `PT4H`). The absolute limit is also the lifetime of the portal's cookies.
Expired sessions are deleted every minute (`BLACKHOLE_PUBLIC_SESSION_SWEEP_INTERVAL`, default `PT1M`). Marking a
Ban ID as leaked or reissuing it deletes that punishment's sessions at once. Only the SHA-256 of the session
token is stored, never the token and never the Ban ID.

Two more limits bound what a player can send: `BLACKHOLE_PUBLIC_MESSAGE_MAX_LENGTH` (default `2000` characters, the
cap of every conversation message) and `BLACKHOLE_PUBLIC_CONVERSATION_PAGE_SIZE` (default `200` entries per page).

## 4. The Ban ID key

The Ban ID is stored only as an HMAC (the login lookup) and an AES-GCM ciphertext (the staff reveal). Both keys
derive from one secret, `BLACKHOLE_BANID_KEY`.

```shell
openssl rand -base64 32     # 32 random bytes, base64
```

- **Required.** Outside the `dev` and `test` environments the backend refuses to start when the key is unset or is
  not base64 of 32 bytes. In `dev` and `test` an unset key means a random key per run (the backend logs a warning),
  so Ban IDs do not survive a restart there.
- **Back it up** like any other secret, in the secret store of the deployment. A database restore without the key
  gives you Ban IDs nobody can read or log in with; the key alone is worthless without the database. A restore
  needs both, so keep the key backup where it is not lost with the database host, and not in the database dump.
- **Never** put it in the repository, an image, a log or a ticket.

### Rotation (v1)

The key has no identifier in this version, so there is one valid key at a time. Replacing it makes every existing
Ban ID unusable: players cannot log in and staff cannot reveal. The recovery is to issue a new Ban ID to every
punishment and raise its generation. The design names this `BanIdBackfill --reissue-all` (idempotent, an
interrupted run can be repeated). Several valid keys at once come in a later change.

> **Known gap.** `BanIdBackfill` in the code at the time of writing has no `--reissue-all` mode. The backfill only
> issues Ban IDs to punishments that have none; it does not touch a Ban ID that exists but can no longer be read.
> Until the mode exists, do not rotate the key on a deployment with live punishments. A single punishment is
> reissued by an administrator in the panel (see the staff guide).

### Backfill and the gauge

After the upgrade, existing punishments have no Ban ID. `BanIdBackfill` issues them in batches of 100, first 5
seconds after start (`blackhole.banid.backfill.initial-delay`) and then every 10 minutes
(`blackhole.banid.backfill.interval`). It is idempotent and safe with several replicas. Until a punishment has a
Ban ID, a login for it fails like any unknown Ban ID.

The gauge `blackhole.banid.missing` is the number of punishments still without one. It should fall to `0` after the
first runs and stay there. A value that stays above `0` means issuing keeps failing (look for
`Ban ID backfill failed` in the backend log; the message holds no Ban ID). `NaN` means the database could not be
read. In Prometheus the dots become underscores (`blackhole_banid_missing`).

## 5. Rules for the edge (infrastructure only)

The backend has no rate limiter for these routes, and neither has the portal. This is deliberate: the operator
sets limits at the reverse proxy, ingress or gateway in front of `appeal.onelitefeather.net`. They are required,
because the login is a guess of an 8-character secret.

| Route on the portal host | Backend route | Rule |
|--------------------------|---------------|------|
| `POST /api/session` | `POST /public/session` | About 5 per minute per client IP. After repeated failures from one IP, present a managed challenge. |
| `POST /api/appeal` | `POST /public/appeal` | Limit per session (per `__Host-bh_portal` cookie). |
| `POST /api/messages` | `POST /public/messages` | Limit per session. |

Also alert on `blackhole.public.login{result="failure"}`. The metric has the single tag `result` with the values
`success`, `limited` and `failure`, and no other tag. A sudden rise of `failure` is a guessing attempt.

The backend neither reads nor stores `X-Forwarded-For`, so the per-IP rule can only be enforced where the real client
address is known: at the edge.

## 6. Never log request bodies

The login carries the Ban ID in a `POST` body, and a player message carries personal text. The Ban ID is in no URL,
query string, log line, event, metric label or response, and that must stay true:

- The backend pins its HTTP loggers (`io.micronaut.http.server`, `io.micronaut.http.client` at `INFO`,
  `io.netty` and `reactor.netty` at `WARN`) in `logback.xml`, because they would print bodies at `DEBUG` or
  `TRACE`. **Do not raise them** on a deployment that serves `/public`.
- The portal logs no request or response body.
- Do not switch on body capture in the edge: access logs with bodies, WAF payload logging, tracing agents that record
  request payloads, or a debugging proxy in front of the portal or the backend's `/public` routes. A header and path
  log is fine; the path never holds a Ban ID.
- Do not paste a Ban ID into a ticket or chat when you debug a login. If one leaked, mark it as leaked in the panel.

## 7. Check list

1. `BLACKHOLE_BANID_KEY` is set on every backend replica, and backed up.
2. `blackhole.banid.missing` is `0`.
3. The portal container started (it validates its two variables) and `/healthz` answers.
4. The edge limits `POST /api/session`, `POST /api/appeal` and `POST /api/messages`, and logs no bodies.
5. An alert watches `blackhole.public.login{result="failure"}`.
6. The sessions limits are what you want (30 minutes idle, 4 hours absolute by default).
