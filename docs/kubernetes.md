# Run Blackhole on Kubernetes

Blackhole runs on the `feather-core` cluster through Flux GitOps. This page is the operator runbook: what runs
where, which Secrets and settings it needs, and how to do the recurring jobs. The chart is
[`deploy/charts/blackhole`](../deploy/charts/blackhole/README.md) (values and tests are documented there); the
cluster side (namespaces, `HelmRelease`, SOPS Secrets, database, bucket, NATS account) lives in the
Kubernetes-FLUX repository. The decisions behind both are in
[`openspec/changes/deploy-to-kubernetes`](../openspec/changes/deploy-to-kubernetes/design.md).

The page has four parts: [Reference](#reference), [How to](#how-to), [Operator tasks outside any
repository](#operator-tasks-outside-any-repository) and [Explanation](#explanation). A list of
[known failures](#known-failures) closes it.

## Reference

### Components

| Component | Image (`harbor.onelitefeather.dev/`) | Port | Reached through |
|-----------|--------------------------------------|------|-----------------|
| backend | `onelitefeather/blackhole` | 8080 | in-cluster Service; the proxy API host below |
| web (admin panel) | `onelitefeather/blackhole-web` | 8080 | `blackhole.onelitefeather.net` (Cloudflare tunnel, Entra login only) |
| portal (player appeals) | `onelitefeather/blackhole-portal` | 8080 | `appeal.onelitefeather.net` (Cloudflare tunnel) |
| discord (bot) | `onelitefeather/blackhole-discord` | 8082 (health, metrics) | no Service; it connects out to Discord |
| clamav (evidence scanner) | `docker.io/clamav/clamav` (pinned) | 3310 | in-cluster Service |
| Velocity API host | routes to the backend | | `blackhole-api.onelitefeather.net` (Cloudflare tunnel), only the proxy routes |

Two environments, each in its own namespace: `blackhole` (prod, chart version pinned) and `blackhole-dev`
(follows `main`). Each has its own database, bucket, Dragonfly index, NATS subjects and Entra app registrations,
and different Ban ID keys and IP salts. Dev has no tunnel hosts: its panel and portal are internal routes
(`blackhole-dev.apps.onelite.feather`, `appeal-dev.apps.onelite.feather`), and it uses the scanner of the prod
namespace.

The commands below assume the `HelmRelease` is called `blackhole`, so the objects are `blackhole-backend`,
`blackhole-web`, `blackhole-portal`, `blackhole-discord` and `blackhole-clamav`.

### Versions and images

A release publishes the four images and the chart with the same version `X.Y.Z`; the chart's default image tag is
its own version. A push to `main` publishes images tagged `main-<commit unix time>-<sha7>` and a chart
`X.Y.Z-main.<unix time>`. Prod pins `OCIRepository blackhole` to an exact chart version, so an upgrade is a pull
request that raises that tag. Dev follows the newest chart and an image policy that writes the tag into
`global.image.tag`. Details: [chart README](../deploy/charts/blackhole/README.md#versions-and-publishing).

### Secrets the cluster must provide

Secret values live in SOPS files in Kubernetes-FLUX and become Secrets with stable names. The chart reads them
by name and never contains a value. Names are the suggestions of the chart README; the keys are fixed.

| Secret | Keys | Source |
|--------|------|--------|
| `blackhole-db` | `JDBC_URL`, `JDBC_USER`, `JDBC_PASSWORD` | the CloudNativePG role of the environment; the same password as the role Secret |
| `blackhole-redis` | `REDIS_URI` | `redis://:<password>@dragonfly.dragonfly.svc:6379/<index>` (10 prod, 11 dev), password inside the URI |
| `blackhole-keys` | `BLACKHOLE_BANID_KEY`, `BLACKHOLE_EVASION_IP_SALT` | generated once, see [the Ban ID key](#back-up-and-check-the-ban-id-key) |
| `blackhole-s3` | `BLACKHOLE_EVIDENCE_S3_ACCESS_KEY`, `BLACKHOLE_EVIDENCE_S3_SECRET_KEY` | the ObjectBucketClaim Secret (or its `AWS_*` keys via `s3.accessKeyKey` / `s3.secretKeyKey`) |
| `blackhole-nats` | `nats.creds` | the `.creds` file of the backend/bot user of the `BLACKHOLE` NATS account |
| `blackhole-discord` | `DISCORD_TOKEN`, `BLACKHOLE_DISCORD_CLIENT_SECRET` | the bot token; the Entra client secret of `Blackhole Discord (<env>)` |
| `blackhole-metrics` | `BLACKHOLE_METRICS_CLIENT_ID`, `BLACKHOLE_METRICS_CLIENT_SECRET` | the Entra client with the `Blackhole.Metrics` app role (only with the ServiceMonitor) |
| `harbor-pull` | `.dockerconfigjson` | a pull secret for Harbor |

Dev and prod use different values for every one of them.

### Settings of the backend

Settings are environment variables rendered into the ConfigMap `blackhole-backend` (typed values) plus
`backend.config` (any other `BLACKHOLE_*` tunable of `application.yml`). Secrets are `secretKeyRef` entries.

| Variable | Kind | Production value | Note |
|----------|------|------------------|------|
| `JDBC_URL`, `JDBC_USER`, `JDBC_PASSWORD` | secret | from `blackhole-db` | direct connection to the read-write Service, not a transaction pooler |
| `JDBC_DRIVER` | config | `org.postgresql.Driver` | |
| `REDIS_URI` | secret | from `blackhole-redis` | |
| `NATS_URL` | config | `nats://nats.nats.svc:4222` | from `switch-event-bus-to-nats` |
| `NATS_CREDS_FILE` | config | `/etc/blackhole/nats/nats.creds` | the mounted key of `blackhole-nats` |
| `NATS_STREAM_REPLICAS`, `NATS_EVENTS_MAX_AGE`, `NATS_EVENTS_MAX_BYTES` | config | `3`, `P7D`, `2147483648` | JetStream stream of the domain events |
| `PORT` | config | `8080` | |
| `BLACKHOLE_AUTH_TENANT_ID`, `BLACKHOLE_AUTH_AUDIENCE` | config | the Entra tenant and the API client id | |
| `BLACKHOLE_AUTH_ISSUER`, `BLACKHOLE_AUTH_JWKS_URL` | config | derived from the tenant | |
| `BLACKHOLE_AUTH_ALLOW_INSECURE_ISSUER` | config | `false` | never `true` outside local development; the schema refuses it |
| `BLACKHOLE_AUTH_ROLE_ADMIN`, `BLACKHOLE_AUTH_ROLE_MODERATOR`, `BLACKHOLE_AUTH_ROLE_VIEWER`, `BLACKHOLE_AUTH_ROLE_METRICS` | config | Entra group object ids | ids, not secrets |
| `BLACKHOLE_AUTH_MACHINE_ROLE_PROXY`, `BLACKHOLE_AUTH_MACHINE_ROLE_METRICS`, `BLACKHOLE_AUTH_MACHINE_ROLE_DISCORD` | config | `Blackhole.Proxy`, `Blackhole.Metrics`, `Blackhole.Discord` | app roles of the machine clients |
| `BLACKHOLE_BANID_KEY` | secret | 32 random bytes, base64 | losing or changing it makes every Ban ID unreadable |
| `BLACKHOLE_EVASION_IP_SALT` | secret | random | unset disables evasion detection; rotating it breaks correlation of old hashes |
| `BLACKHOLE_EVIDENCE_FILES_STORAGE` | config | `s3` | |
| `BLACKHOLE_EVIDENCE_FILES_MAX_VIDEO_BYTES` | config | `94371840` (90 MiB) | below Cloudflare's 100 MB request cap; must equal the panel's limit |
| `BLACKHOLE_EVIDENCE_S3_ENDPOINT`, `BLACKHOLE_EVIDENCE_S3_BUCKET`, `BLACKHOLE_EVIDENCE_S3_REGION`, `BLACKHOLE_EVIDENCE_S3_PATH_STYLE` | config | internal object-store service, bucket of the claim, `us-east-1`, `true` | no hairpin through the public name |
| `BLACKHOLE_EVIDENCE_S3_ACCESS_KEY`, `BLACKHOLE_EVIDENCE_S3_SECRET_KEY` | secret | from `blackhole-s3` | |
| `BLACKHOLE_EVIDENCE_SCAN_ENABLED` | config | `true` | fail closed; never off in prod |
| `BLACKHOLE_EVIDENCE_SCAN_CLAMD_HOST`, `BLACKHOLE_EVIDENCE_SCAN_CLAMD_PORT`, `BLACKHOLE_EVIDENCE_SCAN_CLAMD_TIMEOUT` | config | the ClamAV Service, `3310`, `PT30S` | |
| `OTEL_TRACES_EXPORTER`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME` | config | `otlp`, the Alloy receiver, `blackhole` (`blackhole-dev` in dev) | |
| `LOG_JSON`, `JAVA_TOOL_OPTIONS` | env | `true`, `-XX:MaxRAMPercentage=70 -Duser.timezone=UTC` | `LOG_JSON` makes logback write JSON |
| `BLACKHOLE_DOCS_ENABLED` | config | `false` | `true` in dev only |
| `BLACKHOLE_PLAYER_RESOLVER_OTIS_ENABLED`, `BLACKHOLE_PLAYER_RESOLVER_OTIS_BASE_URL` | config | set in the overlay | the resolver loads only when the base URL is set |

All other tunables (`BLACKHOLE_REPORT_*`, `BLACKHOLE_ELO_*`, `BLACKHOLE_APPEAL_*`, `BLACKHOLE_EVASION_*`,
`BLACKHOLE_CACHE_*`, `BLACKHOLE_PUBLIC_*`, `BLACKHOLE_DISCORD_*` limits) keep their `application.yml` default
unless an overlay sets them in `backend.config`.

### Settings of web, portal and bot

| Component | Variable | Kind | Value |
|-----------|----------|------|-------|
| web | `BLACKHOLE_BACKEND_URL` | config | `http://blackhole-backend:8080` |
| web | `BLACKHOLE_UI_OIDC_AUTHORITY`, `BLACKHOLE_UI_OIDC_CLIENT_ID` | config | the tenant's v2.0 issuer, the SPA client id (public) |
| web | `BLACKHOLE_UI_API_SCOPE` | config | the API's `access_as_user` scope |
| web | `BLACKHOLE_UI_EVIDENCE_MAX_VIDEO_BYTES` | config | `94371840`, equal to the backend |
| portal | `BLACKHOLE_BACKEND_URL`, `BLACKHOLE_PORTAL_ORIGIN` | config | the backend Service; `https://appeal.onelitefeather.net` (https is required in prod) |
| bot | `DISCORD_TOKEN`, `BLACKHOLE_DISCORD_CLIENT_SECRET` | secret | from `blackhole-discord` |
| bot | `BLACKHOLE_DISCORD_TENANT_ID`, `BLACKHOLE_DISCORD_CLIENT_ID`, `BLACKHOLE_DISCORD_AUTH_SCOPE` | config | tenant, bot client id, the API's `.default` scope |
| bot | `DISCORD_GUILD_ID`, `DISCORD_TEAM_CHANNEL_ID`, `DISCORD_STAFF_ROLE_ID`, `DISCORD_TICKET_CATEGORY_ID`, `DISCORD_ARCHIVE_CATEGORY_ID` | config | Discord ids (kept in the overlay) |
| bot | `DISCORD_APPEAL_CHANNEL_ID`, `DISCORD_SYNC_INTERVAL`, `DISCORD_ARCHIVE_DELETE_AFTER`, `DISCORD_STAFF_RECONCILE_INTERVAL`, `DISCORD_LEAK_SCAN` | config | optional |
| bot | `BLACKHOLE_BACKEND_URL`, `BLACKHOLE_PANEL_URL` | config | the backend Service; `https://blackhole.onelitefeather.net` |
| bot | `NATS_URL`, `NATS_CREDS_FILE` | config | as the backend |
| bot | `DISCORD_HEALTH_PORT` | config | `8082` |

The web and portal pods hold no secret. `BLACKHOLE_DISCORD_TOKEN_URL` is a development mock and is never set here.

### Resources and probes

| Component | Requests | Limit | Replicas (prod / dev) | Strategy | Probes |
|-----------|----------|-------|-----------------------|----------|--------|
| backend | 250m / 768Mi | memory 1Gi | 2 / 1 | RollingUpdate, `maxUnavailable: 0` | startup `/health/liveness` (10 min), readiness `/health/readiness`, liveness `/health/liveness` |
| web, portal | 25m / 64Mi | memory 128Mi | 2 / 1 | RollingUpdate | readiness and liveness `/healthz` |
| discord | 100m / 256Mi | memory 384Mi | 1 | Recreate | startup and liveness `/healthz` on 8082 (503 while not connected to Discord) |
| clamav | 200m / 1536Mi | memory 3Gi | 1 | Recreate | startup (10 min), readiness and liveness `clamdcheck.sh` |

These are a first guess; tune them after the first week of metrics. Backend, web and portal get a
PodDisruptionBudget (`maxUnavailable: 1`) with more than one replica and spread over nodes. Prod pods use
`priorityClassName: feather-standard`; dev pods have none, so dev yields under pressure.

### Routes of the proxy API host

`blackhole-api.onelitefeather.net` routes only these prefixes to the backend: `/punishment`, `/profile`, `/elo`,
`/section`, `/template`, `/report`, `/evasion`, `/evidence`, `/appeal`, `/staff-link`, `/player` (which also covers
`/player-discord-link`) and `/cloudnet/catalogue`. Everything else, in particular `/public` (the anonymous player
login), `/discord`, `/prometheus`, `/swagger`, `/health`, `/admin` and `/auth`, is answered with 404 by the edge.
The boundary behind that is the backend's own token check (Entra token with the `Blackhole.Proxy` app role).

## How to

### First deploy of an environment

1. Kubernetes-FLUX has the namespace (Pod Security `enforce: baseline`, `warn` and `audit: restricted`), the
   `OCIRepository`, the `HelmRelease` and the Secrets of [the table above](#secrets-the-cluster-must-provide).
2. The database exists: the CloudNativePG `Database` and the managed role (owner of the database, because Flyway
   needs DDL), and the daily scheduled backup covers it.
3. The evidence bucket claim exists in the same namespace as the Secret it creates.
4. The NATS account `BLACKHOLE` and the user with its credentials exist (the change `switch-event-bus-to-nats` has
   landed). Nothing is deployed to prod before that.
5. Generate the Ban ID key once and store the offline copy **before** the first start
   ([below](#back-up-and-check-the-ban-id-key)).
6. Run the Entra script for the environment, as an admin: `docs/entra-setup.sh prod https://blackhole.onelitefeather.net`
   ([details](entra-setup.md)). Copy the group ids and client ids into the overlay values.
7. Reconcile dev first. Smoke test: sign in to the panel as a member of an Entra group, `GET /health/readiness` on
   the backend Service, upload an evidence file (it goes through ClamAV), log in to the portal with a Ban ID, see
   the bot come online, and apply a test punishment from a Velocity proxy over the API host.
8. Then prod, with the chart version pinned.

The first backend start runs the Flyway migrations; the first ClamAV start downloads the signature database. Both
can take minutes, which the startup probes allow for.

### Upgrade

- **Dev** follows `main` on its own: the chart `X.Y.Z-main.<unix time>` and the image tag arrive through Flux.
- **Prod**: open a pull request in Kubernetes-FLUX that raises the `OCIRepository` tag to the released `X.Y.Z`
  (Renovate may propose it). Flux upgrades the `HelmRelease`; the backend rolls with `maxUnavailable: 0`.
- Migrations run on start and must work for the previous release for one rolling update (expand, then
  contract). If a release cannot, its notes say so: then set `backend.strategy.type: Recreate` for that upgrade.
- A failed migration keeps the new backend pods unready and the old ones keep serving. Read the new pod's log
  (`kubectl -n blackhole logs deploy/blackhole-backend`), fix forward, or roll back by pinning the previous
  chart version if the migration did not apply.
- Bump the chart version whenever a chart file changes: Flux caches charts by version.

### Change a secret

Secrets have stable names, so changing one does **not** restart the pods (the cluster has no reloader).

1. Change the SOPS file in Kubernetes-FLUX and merge it.
2. Raise `<component>.secretRevision` in the overlay (for example `"3"` to `"4"`) in the same or a follow-up pull
   request. The value is a pod annotation, so the next `HelmRelease` upgrade replaces the pods.
3. Check the rollout: `kubectl -n blackhole rollout status deploy/blackhole-backend`.

### Rotate an Entra client secret

The client secrets of the proxy, the bot and the metrics scraper expire after a year; calendar the date when you
create them.

1. Create the new secret in the app registration (keep the old one valid).
2. Put it into the SOPS file (`blackhole-discord`, `blackhole-metrics`, or the proxy's own environment),
   raise `secretRevision`, and wait for the rollout. For Velocity, set `BLACKHOLE_CLIENT_SECRET` in its
   environment and restart the proxy.
3. Check that the bot is connected and the proxy can apply a test punishment, then delete the old secret.

### Back up and check the Ban ID key

`BLACKHOLE_BANID_KEY` is the one secret whose loss cannot be repaired from the database: Ban IDs are stored as an
HMAC and an AES-GCM ciphertext derived from it. Rotation in place is not supported.

- Generate it once per environment: `openssl rand -base64 32`. Put it into the SOPS file and into the team password
  manager (a second person must be able to read it) **before the first start**.
- Yearly restore drill, without ever printing the key: decrypt a copy and compare fingerprints (the first 8
  characters of the SHA-256 of the base64 value).

```shell
# the live Secret
kubectl -n blackhole get secret blackhole-keys -o jsonpath='{.data.BLACKHOLE_BANID_KEY}' \
  | base64 -d | tr -d '\n' | sha256sum | cut -c1-8
# the password-manager copy: paste it into the command, not into a file or the shell history
printf '%s' "$KEY_FROM_PASSWORD_MANAGER" | sha256sum | cut -c1-8
```

The two fingerprints must match. The same applies to the IP salt, except that losing it only costs the correlation of
old hashes.

### Restore the database

The CloudNativePG cluster is backed up daily (barman, with WAL archiving), and the backup covers every database of
the cluster, so Blackhole adds nothing. The backup does **not** contain the Ban ID key, the evidence blobs or the
Entra configuration. To restore:

1. Scale the writers down: `kubectl -n blackhole scale deploy/blackhole-backend --replicas=0`.
2. Recover the cluster (or a temporary cluster) from the backup to the wanted point in time, as the CloudNativePG
   recovery procedure describes.
3. Dump the `blackhole` database from the recovered cluster and load it into the live one (or switch over).
4. Scale the backend back up; Flyway checks the schema on start.
5. Run the drill once per quarter against a temporary cluster, and check that the application starts against the
   restored copy with the **same** Ban ID key.

Redis (Dragonfly) needs no restore: it holds a derived mirror of the punishments and a TTL cache, so losing it costs
latency, not data.

### Connect a Velocity proxy

Velocity runs outside the cluster. It needs no Dragonfly access and gets none.

1. In `config.json` set `baseUrl` to `https://blackhole-api.onelitefeather.net`. Leave `redisUri` empty or
   unreachable: the plugin then checks each login and chat message over HTTP, which is correct but chattier, and
   staff report notices stay per proxy.
2. Put the credentials into the proxy's environment, never into the file: `BLACKHOLE_TENANT_ID`,
   `BLACKHOLE_CLIENT_ID`, `BLACKHOLE_AUTH_SCOPE` and `BLACKHOLE_CLIENT_SECRET` (client credentials of the Entra app
   with the `Blackhole.Proxy` role).
3. Check from the proxy host that a protected route answers with a token and that `/public/session` answers 404
   on this host.

### Add or change a Discord guild, channel or role

The bot serves exactly one guild. The ids are chart values (`discord.ids`) in the overlay; change them there and
the bot restarts with the new ConfigMap (the pod carries the ConfigMap checksum). Permissions the bot needs are
listed in [the bot guide](discord-bot.md).

### ClamAV trouble

- **Uploads fail with 503:** the scanner is down or still starting (the scan fails closed). Check
  `kubectl -n blackhole get pods -l app.kubernetes.io/component=clamav` and its log. The first start takes several
  minutes while it downloads the signatures.
- **Restarts redownload everything:** the PVC is missing or not mounted at `/var/lib/clamav`; the mirror
  rate-limits clients that restart often.
- **Out of memory:** `clamd` holds a second copy of the database while it reloads; do not lower the 3Gi limit.
- **No signature updates:** the pod needs egress to `database.clamav.net` and its CDN; `freshclam` runs inside the
  pod and checks every two hours.
- **The pod does not start as non-root:** the chart starts the image through `/init-unprivileged`. If that fails on
  a cluster, set `clamav.unprivileged: false` (the image's root entrypoint); the namespace then must allow
  `baseline`, which it does.

### Read logs, traces and metrics

- **Logs:** the backend writes JSON to stdout. The pod labels `logs.onelitefeather.net/format: json` and
  `logs.onelitefeather.net/env: prod|dev` tell Alloy to parse it and to separate the environments. The bot, the
  web and the portal never log message content or Ban IDs; do not raise the HTTP loggers of the backend to DEBUG.
- **Traces:** the backend exports OTLP to the Alloy receiver (`OTEL_EXPORTER_OTLP_ENDPOINT`).
- **Metrics:** the backend's `/prometheus` needs a token with the `Blackhole.Metrics` role; the optional
  ServiceMonitor asks Entra for it with the `blackhole-metrics` Secret. Whether Alloy honours the `oauth2` block of
  a ServiceMonitor is not verified yet: if it does not, scrape with a `prometheus.scrape` block that uses the same
  client credentials. The bot's `/metrics` on port 8082 is a PodMonitor. Dashboards and alerts are a follow-up.

## Operator tasks outside any repository

| Task | Owner | Notes |
|------|-------|-------|
| Cloudflare rate limit for `POST /public/session` on the portal host | Cloudflare admin | the backend deliberately has no limit for the anonymous Ban ID login; this is its only guess limit |
| Cloudflare rate limit for the portal's other write routes | Cloudflare admin | infrastructure owns rate limiting |
| Cloudflare WAF custom rule on the API host with the same allow-list as the Ingress | Cloudflare admin | defence in depth; optional rate limit for `POST /punishment` and `POST /evasion/record` |
| Entra redirect URIs `https://blackhole.onelitefeather.net/auth/callback` and `https://blackhole.onelitefeather.net/` for the panel's SPA registration | Entra admin | `docs/entra-setup.sh prod https://blackhole.onelitefeather.net` |
| Calendar entries for the client secret expiry (proxy, bot, metrics) | Entra admin | one year |
| Offline copy of the Ban ID key in the team password manager | maintainer | before the first start of each environment |

## Explanation

### Why Velocity gets no Dragonfly

Dragonfly has one shared password and no per-key access control in use, and it holds the punishment mirror that the
proxy trusts. Anyone who can reach it from outside could write or delete `blackhole:punish:*` keys (lift or forge
bans) and publish on the synchronisation channels. It sits on an internal-only address range, and the Cloudflare
tunnel carries only HTTP(S) and WebSocket. Redis pub/sub is also not scoped by database number, so a shared instance
would let a dev backend publish onto prod's channels. So Velocity uses the API over HTTP, and its documented
fallback covers the missing Redis. The planned follow-up moves the proxy's synchronisation to NATS over WebSocket
with a subscribe-only user; that change is coordinated with `switch-event-bus-to-nats` and is not part of this
deployment.

### Why migrations run on start

Flyway takes a PostgreSQL advisory lock for the migration, so replicas that start together serialise: one migrates,
the others wait and then find nothing to do. A separate migration job would need an entry point the application
does not have, separate DDL credentials and hook ordering with Flux, for little gain at two replicas. The price is the
expand and contract rule above, a startup budget of ten minutes (a longer migration is run by hand in a maintenance
window first), and a connection that is direct or through a session-mode pooler, never a transaction-mode one.

### Why the API host is a regular expression

The tunnel controller passes an Ingress path to cloudflared unchanged, and cloudflared matches paths as an
unanchored regular expression, longest rule first. A plain prefix such as `/appeal` would also match
`/public/appeal/...`. The chart therefore renders one anchored expression for the whole host, which also cannot
collide in length with another rule. If anchored paths ever stop passing through, `backend.externalApi.mode: nginx`
puts a small allow-list proxy in front of the backend; the route table in `tests/exposure.sh` runs against both.

### Evidence storage has no second copy

Evidence lives in the object store of the cluster and relies on its redundancy; there is no off-cluster copy (decided
2026-10-24). No lifecycle rule may expire evidence, because it is the record behind punishments and appeals.

## Known failures

| Symptom | Cause | Fix |
|---------|-------|-----|
| The panel shows nothing and every API call is 401 | the panel asked for `openid profile email` only and got a Microsoft Graph token | set `web.oidc.apiScope` to `api://<API client id>/access_as_user` |
| New secret value, old behaviour | Secret names are stable, so the pods did not restart | raise `<component>.secretRevision` |
| An overlay lost a setting | Helm replaces lists | use the map values (`env`, `envFrom`, `config`); set a key to `null` to remove it |
| A tunnel path rule stops working | rules of one host with equal length collide, or the backend Service is headless | one rule per host; ClusterIP Services only |
| Flux does not roll out a chart change | the chart version did not change | bump the chart version |
| Backend pods stay unready after an upgrade | a migration is running or failed | read the pod log; the old pods keep serving |
| Backend refuses to start | `BLACKHOLE_BANID_KEY` is missing | check `blackhole-keys` |
| Evidence upload larger than 90 MiB fails | Cloudflare's request cap is 100 MB | keep both video limits at 94371840 |
| The portal does not start | `BLACKHOLE_PORTAL_ORIGIN` is not an https origin | set the public https origin |
| Two bots post everything twice | more than one bot replica or a rolling update | `discord.replicaCount` is 1 and the strategy is Recreate; the schema refuses more |
