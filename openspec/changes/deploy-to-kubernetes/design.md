# Design

This design was checked against the code on `origin/main` (2026-10-24), not against older notes:
PostgreSQL + Flyway, Dragonfly (Redis protocol), RabbitMQ (replaced by NATS in `switch-event-bus-to-nats`),
S3 for evidence, ClamAV `clamd`, Entra ID. Cluster facts come from `Kubernetes-FLUX` (read-only) and the
Outline page "Explanation: Betriebsfallen im feather-core-Cluster"; the chart pattern from `Apus-helm`.

Flyway migrations appended by this change: **none** (no schema change).

## 1. Components, hosts, images

| Component | Image (Harbor, `harbor.onelitefeather.dev/`) | Port | Exposure | Notes |
|-----------|----------------------------------------------|------|----------|-------|
| `backend` | `onelitefeather/blackhole` (exists; name kept so existing pulls keep working) | 8080 | internal Service; Velocity host (below) | Micronaut, JVM, 2 replicas prod, 1 dev |
| `web` (panel) | `onelitefeather/blackhole-web` (new) | 8080 | `blackhole.onelitefeather.net` via Cloudflare Tunnel; Entra login only, no Cloudflare Access | Nitro; forwards `/api/` to the backend in-cluster, so the backend needs no public host for staff |
| `portal` | `onelitefeather/blackhole-portal` (new) | 8080 | `appeal.onelitefeather.net` via Cloudflare Tunnel | Nitro BFF; calls only `/public/**` of the backend |
| `discord` | `onelitefeather/blackhole-discord` (new) | 8082 (health, metrics) | none | JDA, stateless, exactly 1 replica (two bots would double-post) |
| `clamav` | `docker.io/clamav/clamav:1.5.4` (pinned like compose) | 3310 | internal Service | in-namespace scanner |
| external API (Velocity) | routes to `backend` | | `blackhole-api.onelitefeather.net` via Cloudflare Tunnel (decided 2026-10-24) | only proxy routes, see section 3 |

Dev (`blackhole-dev`): panel and portal are reached internally through the Envoy Gateway `HTTPRoute`
(`blackhole-dev.apps.onelite.feather`, `appeal-dev.apps.onelite.feather`); no tunnel hosts, no external API
host. The portal checks that its origin is https in production (`BLACKHOLE_PORTAL_ORIGIN`), so dev sets the
internal https origin.

**Host deviation.** The panel is on `.net` although ADR-ORG-0009 says `.net` for players and `.dev` for the
team. The user decided this on 2026-10-24; this design records it rather than the ADR rule. The Entra SPA
redirect URIs must be `https://blackhole.onelitefeather.net/auth/callback` and `/` (`docs/entra-setup.sh prod
https://blackhole.onelitefeather.net`).

### Images and the build

- The backend image is already built by `release-please.yml`: `./gradlew jar optimizedBuildLayers
  optimizedDockerfile -Pversion=$VERSION`, context `backend/build/docker/optimized`, pushed by the shared
  `OneLiteFeatherNET/workflows/docker-publish.yml@v2.9.0` to `${HARBOR_REGISTRY}/onelitefeather/blackhole`
  with the tags `X.Y.Z`, `X.Y`, `X` and `sha-<7>`, on release and (for `-SNAPSHOT`) on every push to `main`.
  Gap: the snapshot tag `1.0.1-SNAPSHOT` is mutable and `sha-<7>` is not orderable, so neither works for Flux
  image automation (Spegel also caches mutable tags).
- `web`, `portal` and `discord` have Dockerfiles (repository root as context) and a PR smoke test for web
  and portal, but no publish job. Add them with the same shared workflow (`context: .`,
  `dockerfile: <module>/Dockerfile`, no `artifact-name`).
- Dev tag: pass `extra-tags: type=raw,value=main-<commit unix time>-<sha7>` on every `main` push (the commit
  time, `git show -s --format=%ct`, so every component gets the same number). Flux orders it numerically
  exactly like Vulpes' `develop-<ts>-<sha7>`. The four images are published in one workflow run and the
  backend job `needs` the other three, so the backend tag, which the ImagePolicy watches, appears only
  after the others exist.
- cosign keyless signing stays on (the shared workflow does it).

## 2. The chart (`deploy/charts/blackhole`)

Layout (Apus style): `Chart.yaml` (`version` and `appVersion` carry `# x-release-please-version`, added to
`release-please-config.json` `extra-files` like Apus does), `values.yaml`, `values.schema.json`,
`templates/{_helpers.tpl,backend-*,web-*,portal-*,discord-*,clamav-*,ingress*.yaml,httproute.yaml,
NOTES.txt}`, `ci/*-values.yaml` (test value sets for lint/template), `README.md`.

Rules that come from the cluster's known traps (Outline page):

- **Helm replaces lists.** All env is a *map* (`backend.env: {KEY: value}`) rendered into the container
  list, plus `envFrom` as a map keyed by Secret name, so an overlay can add one variable without
  re-stating the rest (the JVM heap flag included). `extraEnv` lists are not offered.
- **Secret changes do not roll pods** (Kustomize `disableNameSuffixHash: true`). Each component has
  `secretRevision`, rendered as the pod annotation `blackhole.onelitefeather.net/secret-revision`; the
  runbook says to raise it with every secret change. (The cluster has no stakater Reloader.)
- **JVM needs a startupProbe.** Backend: `startupProbe` on `/health/liveness` (`periodSeconds: 10`,
  `failureThreshold: 60`, i.e. 10 minutes: JVM start with OTel agent plus a Flyway migration), then
  `readinessProbe` `/health/readiness` and `livenessProbe` `/health/liveness`. `/health/**` is anonymous in
  the backend (`intercept-url-map`), `details-visible: NEVER`. Discord: same with `/healthz` on 8082
  (200 only while connected to Discord; a missing Discord permission makes it 503, so it is used for
  startup and liveness but **not** readiness-gating anything, there is no Service traffic). Web and portal:
  `/healthz` (Nitro) readiness and liveness, no startup probe needed.
- **Bump the chart version.** release-please does it for releases; the snapshot chart (below) carries a
  unique pre-release version, because Flux caches by chart version.
- **Tunnel path rules collide by length; no headless Service as a tunnel backend.** All Services are
  normal ClusterIP; see section 3 for the rule lengths.
- **Namespace PSA:** `enforce: baseline`, `warn/audit: restricted` (the Vulpes and Apus convention). All
  Blackhole pods are written to pass `restricted`.

Per component values (all schema-validated; `additionalProperties: false` at the top level of each block):

| Block | Keys |
|-------|------|
| global | `image.registry`, `image.tag` (default `appVersion`), `imagePullSecrets`, `podLabels`, `priorityClassName` |
| each component | `enabled`, `replicaCount`, `image.repository/tag/pullPolicy`, `env` (map), `envFrom` (map name->Secret/ConfigMap), `secretRevision`, `resources`, `podSecurityContext`, `securityContext`, `podLabels`, `podAnnotations`, `nodeSelector`, `tolerations`, `affinity`, `topologySpreadConstraints`, probe overrides |
| `backend` | `config.*` (non-secret settings rendered into a ConfigMap), `jvm.maxRamPercentage`, `strategy`, `pdb`, `metrics.serviceMonitor.{enabled,oauth2}`, `tracing.enabled`, `externalApi.{enabled,host,paths}` |
| `web`, `portal` | `ingress` or `httpRoute` (exactly one), `oidc.*` (web), `origin` (portal), `backendUrl` |
| `discord` | `config.*` (ids and intervals), `metrics.podMonitor.enabled` |
| `clamav` | `enabled`, `externalHost`, `persistence.{size,storageClass}`, `limits.*` (`StreamMaxLength` etc.) |
| `database` | `existingSecret` (keys `JDBC_URL`, `JDBC_USER`, `JDBC_PASSWORD`) |
| `s3` | `endpoint`, `bucket`, `region`, `pathStyle`, `existingSecret` (keys `BLACKHOLE_EVIDENCE_S3_ACCESS_KEY`, `..._SECRET_KEY`) |
| `redis`, `nats` | `existingSecret` holding the connection URI (password inside it) / NATS credentials file |

Defaults and sizing (a first guess, tuned after the first week of metrics):

| Component | requests | limits | Replicas | PDB |
|-----------|----------|--------|----------|-----|
| backend | 250m / 768Mi | memory 1Gi, no CPU limit | 2 prod, 1 dev | `maxUnavailable: 1` when >1 |
| web | 25m / 64Mi | 128Mi | 2 prod, 1 dev | `maxUnavailable: 1` when >1 |
| portal | 25m / 64Mi | 128Mi | 2 prod, 1 dev | `maxUnavailable: 1` when >1 |
| discord | 100m / 256Mi | 384Mi | 1 | none (`Recreate` strategy) |
| clamav | 200m / 1536Mi | memory 3Gi | 1 | none (`Recreate`) |

The JVM flag `-XX:MaxRAMPercentage=70` is set in `env.JAVA_TOOL_OPTIONS` together with `-Duser.timezone=UTC`;
`LOG_JSON=true` switches logback to JSON and the pod label `logs.onelitefeather.net/format: json` plus
`logs.onelitefeather.net/env: prod|dev` tells Alloy to parse it. Security: `runAsNonRoot`, uid/gid 10001 for
discord (matches its image) and the image's `node` user (1000) for Nitro, `readOnlyRootFilesystem: true` with
`emptyDir` for `/tmp` (JVM) and none needed for Nitro. The optimized backend image's user must be checked
(task 1.4): if it runs as root the Dockerfile template gets a `USER`.

### Observability

- Logs: stdout JSON, Alloy labels as above. The bot and web/portal never log content or Ban IDs (existing
  rule); do not raise the HTTP server loggers to DEBUG on the backend.
- Traces: `OTEL_TRACES_EXPORTER=otlp`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://alloy-receiver.grafana.svc:4317`
  (the endpoint Apus uses), `OTEL_SERVICE_NAME=blackhole` (`blackhole-dev` in dev).
- Metrics: the cluster scrapes `ServiceMonitor`/`PodMonitor` through Alloy. The backend's `/prometheus`
  needs a bearer token with the `Blackhole.Metrics` app role (it is **not** anonymous), so the backend
  `ServiceMonitor` carries an `oauth2` block (client id and secret from a Secret, token URL
  `https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token`, scope `api://<api client id>/.default`).
  Whether Alloy's `prometheus.operator.servicemonitors` honours `oauth2` is verified first (task 5.3); the
  fallback is a `prometheus.scrape` block with `oauth2` in the Alloy config in Kubernetes-FLUX.
  The Discord bot's `/metrics` (port 8082) is a `PodMonitor`; web and portal export no metrics.
- Dashboards and alerts are Kubernetes-FLUX/Grafana work and a follow-up.

## 3. Velocity outside the cluster

### What Velocity uses (read from `velocity/` and the controllers)

| Need | How | Evidence |
|------|-----|----------|
| Backend HTTP API | client credentials (`BLACKHOLE_TENANT_ID`, `_CLIENT_ID`, `_CLIENT_SECRET`, `_AUTH_SCOPE`) -> Bearer; `baseUrl` in `config.json` | `velocity/.../auth`, `AuthConfig` |
| Dragonfly | Lettuce client, `redisUri` in `config.json`: reads `blackhole:punish:<section>:<owner>`, `blackhole:cache:v1:sections:all`/`templates:all`, **subscribes** to 4 pub/sub channels (punishment sync, section changed, report created, report feedback) and **publishes** `blackhole.report.created` (proxy to proxy staff notice) | `RedisSyncService`, `RedisTopology` |
| Event bus | **none.** Nothing in `velocity/` uses RabbitMQ (or NATS); it learns of changes through Redis pub/sub, which the backend's `RedisSyncConsumer` fills from the domain events | grep, `RedisSyncService` |

When Redis is unavailable the plugin logs an error and falls back to per-request HTTP checks
(`connect()` catches the failure, reads "unavailable" and calls the backend): the design relies on this.

### Routes the proxy needs (all `@Secured` with `PROXY`)

Prefixes: `/punishment` (apply, revoke, lookups, Ban ID reveal, evidence list/count), `/profile`, `/elo`,
`/section`, `/template`, `/report` (submit, feedback), `/evasion`, `/evidence`, `/appeal`, `/staff-link`,
`/player` (name lookup, also covers `/player-discord-link`), `/cloudnet/catalogue`.

Never for the proxy, and they must not be reachable on the external host: `/public/**` (the player portal
API; **`POST /public/session` is anonymous**, it is the Ban ID login, whose guess limit is an edge rule at
the portal host), `/discord/**` (bot), `/prometheus` (METRICS role), `/health/**` (probes only), `/swagger`,
`/auth/**`, `/admin/**`, `/template` writes (ADMIN, harmless because a proxy token cannot call them, but they
are not needed).

### Recommendation

1. **API: a separate external host `blackhole-api.onelitefeather.net`** (the `.net` suffix follows the user's
   host decision; name decided 2026-10-24) through a second Ingress (`cloudflare-tunnel`) to the normal
   backend ClusterIP Service. The security boundary is the backend's JWT check: an Entra token for the API
   audience with the app role `Blackhole.Proxy`; no new auth is added. On top, the host is **restricted by path**
   so the anonymous `/public/session` and the operational routes are not exposed twice.
   - Tunnel path rules are an unanchored regular expression, sorted by length, first match wins (Outline).
     A plain prefix such as `/appeal` would also match `/public/appeal/...`. So the rules are written
     anchored (`^/punishment`, `^/profile`, ...) with distinct lengths if the controller passes the path
     through unchanged (v0.0.23); this is verified first on dev with a table of allowed and blocked routes
     (task 4.2), and a chart test renders the same table.
   - If anchoring does not work, fall back to a tiny allow-list proxy (nginx-unprivileged with
     `location ~ ^/(punishment|profile|...)(/|$)`, `return 404` otherwise) as one more chart component in
     front of the backend and make **it** the tunnel backend. Not built unless needed.
   - Defence in depth that stays an operator task: a Cloudflare WAF custom rule on that host with the same
     allow-list, and a rate limit for `POST /punishment` and `POST /evasion/record`.
   - Cloudflare's request cap is 100 MB; Velocity's evidence uploads are small. See the evidence limits below.
2. **Dragonfly: not exposed.** Dragonfly has one shared password, no per-key ACL in use, and holds the punishment
   mirror the proxy trusts. Anyone who can reach it from outside can write or delete `blackhole:punish:*` keys
   (lift or forge bans) and publish on the sync channels. It sits on an internal-only MetalLB range and
   Cloudflare Tunnel carries only HTTP(S) and WebSocket. Also, Redis pub/sub is not scoped by database
   number, so a shared instance would let a dev backend publish onto prod's channels.
3. **Event bus: NATS over WebSocket**, the pattern the cluster already has for Corvus
   (`foundation/messaging/clusters/feather-core/nats/ingress.yaml`): `wss://nats.onelitefeather.dev`, a JWT user
   restricted to WebSocket and to *subscribe* only on the Blackhole subjects, in a new `BLACKHOLE` account.
   The proxy's Redis pub/sub use (punishment sync, section changed, report feedback) is replaced by those
   subscriptions; the proxy-to-proxy report notice needs a publish permission on one subject or a backend relay.
   That is a Velocity code change and therefore coordinated with the parallel change `switch-event-bus-to-nats`, not done here.
4. **Until the follow-up lands**, the production Velocity runs with Redis unreachable (its documented fallback):
   every login/chat check is an HTTP call and staff report notices stay per-proxy. This is correct but
   chattier; the API host must be able to carry it (backend sized for it; rate limits only at the edge for
   writes). If the load or the missing cross-proxy sync turn out to be a problem before the follow-up, the
   fallback is to expose Dragonfly through a TLS-terminating endpoint with an ACL user that is read-only on
   `blackhole:*` and subscribe-only (Dragonfly ACL support must be verified first) - not recommended and not
   planned.

Velocity's `config.json` for production: `baseUrl: https://blackhole-api.onelitefeather.net`, `redisUri`
empty or unreachable (the follow-up adds an "off" switch), credentials in the proxy's environment
(`BLACKHOLE_CLIENT_SECRET`), never in the file.

## 4. Database and migrations

- PostgreSQL is the shared CloudNativePG cluster `feather-core-cluster-pg` (namespace `cnpg-system`, 3
  instances, PostgreSQL 17). Per environment: a `Database` CR (`blackhole` / `blackhole_dev`), a
  `managed.roles` entry (`blackhole` / `blackhole_dev`, `passwordSecret: role-blackhole[-dev]` with the
  `cnpg.io/reload: "true"` label) and the same credentials in the app's `JDBC_*` Secret. The role owns the
  database, which Flyway needs (DDL).
- The backend connects to the read-write Service `feather-core-cluster-pg-rw.cnpg-system.svc:5432` directly.
  Not the `...-pooler-rw-tx` (transaction pooling breaks Flyway's advisory lock and `SET`); the session pooler
  `...-pooler-rw` would also work, but a direct connection is the simplest and matches n8n's setup.
- **Flyway decision: migrate on start, no pre-upgrade Job.** Flyway takes a PostgreSQL advisory lock (and
  the schema history table) for the migration, so replicas starting together serialise: one migrates, the
  others wait and then see nothing to do (Micronaut Flyway runs `migrate` at context start; Hibernate
  `hbm2ddl=validate` then runs after it). Reasons against a Job: the application has no migrate-only
  entrypoint (it would need one), a hook Job needs separate DDL credentials and Flux/Helm hook ordering, and
  it adds a failure mode for little gain at two replicas.
  Rules that go with it:
  - **Expand/contract.** During a rolling update the old pods run against the new schema; migrations that
    break the previous release (drop, rename, tighten) are split over two releases, or the release notes say
    "strategy: Recreate". Migrations are already append-only (config principle 6).
  - **Startup budget.** The `startupProbe` allows 10 minutes; a migration longer than that (a large backfill)
    is run by hand in a maintenance window first.
  - **Failure.** A failed migration keeps the new pods unready; with `maxUnavailable: 0` the old pods keep
    serving, and `helm upgrade --atomic` is not used (Flux `remediation.retries: 3` as in the other releases).
  - **Revisit** if a migration regularly exceeds the budget or the app role must lose DDL rights.
- **Backups.** Confirmed in Kubernetes-FLUX: `ScheduledBackup feather-core-cluster-pg-daily` (02:00, barman
  cloud plugin, object store `s3-store`, prefer-standby) and WAL archiving through the same plugin. Blackhole
  adds nothing; the runbook documents a restore test of the `blackhole` database. The CNPG backup does not
  contain the Ban ID key, the evidence blobs or Entra.

## 5. Config and secrets

Config is non-secret and lives in the chart values (or the overlay); secrets live in SOPS files in
Kubernetes-FLUX, turned into Secrets by `secretGenerator` and referenced by name. Secret values never appear
in the chart or in `kubectl get helmrelease`. "Default" is the `application.yml` or code default; `-` means none.

### Backend (`backend`)

| Variable | Kind | Default / production value | Note |
|----------|------|----------------------------|------|
| `JDBC_URL` | config | `jdbc:postgresql://feather-core-cluster-pg-rw.cnpg-system.svc:5432/blackhole` | `blackhole_dev` in dev |
| `JDBC_USER`, `JDBC_PASSWORD` | **secret** | role name / `role-blackhole` password | same value as the CNPG role secret |
| `JDBC_DRIVER` | config | `org.postgresql.Driver` | leave |
| `REDIS_URI` | **secret** | `redis://:<password>@dragonfly.dragonfly.svc:6379/<db>` | password in the URI (Shlink lesson); DB index 10 prod, 11 dev (to be registered) |
| `RABBITMQ_*` | removed | - | gone with `switch-event-bus-to-nats` |
| NATS URL and credentials | **secret** (creds) / config (URL) | `nats://nats.nats.svc:4222`, `.creds` file mounted from a Secret | exact names defined by `switch-event-bus-to-nats`; subjects separated per environment there |
| `PORT` | config | 8080 | |
| `BLACKHOLE_AUTH_TENANT_ID` | config | - | Entra tenant id |
| `BLACKHOLE_AUTH_ISSUER` | config | `https://login.microsoftonline.com/<tenant>/v2.0` | |
| `BLACKHOLE_AUTH_JWKS_URL` | config | `https://login.microsoftonline.com/<tenant>/discovery/v2.0/keys` | |
| `BLACKHOLE_AUTH_AUDIENCE` | config | API app client id | |
| `BLACKHOLE_AUTH_ALLOW_INSECURE_ISSUER` | config | `false` | never true here |
| `BLACKHOLE_AUTH_ROLES_CLAIM_HUMAN`, `_MACHINE` | config | `groups`, `roles` | |
| `BLACKHOLE_AUTH_ROLE_ADMIN/_MODERATOR/_VIEWER/_METRICS` | config | Entra group object ids (`sg-blackhole-prod-*`) | ids, not secrets |
| `BLACKHOLE_AUTH_MACHINE_ROLE_PROXY/_METRICS/_DISCORD` | config | `Blackhole.Proxy`, `.Metrics`, `.Discord` | |
| `BLACKHOLE_STAFF_LINK_TTL`, `BLACKHOLE_DISCORD_LINK_TTL` | config | `PT5M`, `PT10M` | |
| `BLACKHOLE_BANID_KEY` | **secret** | 32 random bytes, base64 | **Critical.** Losing or changing it makes every Ban ID unreadable. Without it the backend refuses to start outside dev/test. Back it up (below) |
| `BLACKHOLE_EVASION_IP_SALT` | **secret** | random | unset disables evasion detection (503). Rotation breaks correlation of old hashes, see `IpCorrelationService` |
| `BLACKHOLE_FINGERPRINT_KEY` | **secret (placeholder)** | - | only used once `add-client-fingerprint-rules` lands; the Secret key is reserved now, the chart does not require it |
| `BLACKHOLE_EVIDENCE_S3_ENDPOINT` | config | `http://rook-ceph-rgw-feather-s3.rook-ceph-fr01.svc:80` | the internal RGW service (not the external hostname) |
| `BLACKHOLE_EVIDENCE_S3_BUCKET`, `_REGION`, `_PATH_STYLE` | config | bucket from the OBC, `us-east-1`, `true` | |
| `BLACKHOLE_EVIDENCE_S3_ACCESS_KEY`, `_SECRET_KEY` | **secret** | from the OBC Secret | see section 6 |
| `BLACKHOLE_EVIDENCE_FILES_STORAGE` | config | `s3` | |
| `BLACKHOLE_EVIDENCE_FILES_MAX_VIDEO_BYTES` | config | `94371840` (90 MiB) | below Cloudflare's 100 MB request cap (default is 100 MiB, which cannot pass the tunnel); keep `BLACKHOLE_MAX_REQUEST_SIZE` at `110MB` or higher |
| `BLACKHOLE_EVIDENCE_FILES_MAX_IMAGE_BYTES`, `_MAX_IMAGE_PIXELS`, `_ALLOWED_TYPES` | config | defaults | |
| `BLACKHOLE_EVIDENCE_MAX_PAYLOAD_BYTES`, `_MAX_CHAT_LINES`, `_PENDING_TTL`, `_REPORT_PENDING_TTL`, `_AUDIT_RETENTION`, `_HOUSEKEEPING_INTERVAL` | config | defaults | |
| `BLACKHOLE_EVIDENCE_SCAN_ENABLED` | config | `true` | fail closed; never off in prod |
| `BLACKHOLE_EVIDENCE_SCAN_CLAMD_HOST/_PORT/_TIMEOUT` | config | `<release>-clamav`, `3310`, `PT30S` | |
| `BLACKHOLE_PLAYER_RESOLVER_OTIS_ENABLED/_BASE_URL` | config | `true`, `http://otis.<ns>.svc:<port>` (Otis in-cluster, confirm) | no default URL; resolver loads only when set |
| `BLACKHOLE_PLAYER_RESOLVER_MOJANG_ENABLED/_NAMEMC_ENABLED/_CACHE_TTL` | config | `true`, `false`, `PT5M` | needs egress to Mojang |
| `BLACKHOLE_DOCS_ENABLED` | config | `false` | `true` in dev only |
| `BLACKHOLE_API_DEFAULT_VERSION` | config | `1` | |
| Tunables with defaults: `BLACKHOLE_REPORT_RATE_LIMIT_*`, `BLACKHOLE_REPORT_FEEDBACK_*`, `BLACKHOLE_PUNISHMENT_*`, `BLACKHOLE_CASE_RECONCILE_*`, `BLACKHOLE_PROTOCOL_*`, `BLACKHOLE_ELO_*`, `BLACKHOLE_APPEAL_*`, `BLACKHOLE_EVASION_*` (not the salt), `BLACKHOLE_CACHE_*`, `BLACKHOLE_PUBLIC_*`, `BLACKHOLE_DISCORD_*` ticket limits | config | `application.yml` defaults | exposed as `backend.config` keys, rendered as env |
| `OTEL_TRACES_EXPORTER`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME` | config | `otlp`, `http://alloy-receiver.grafana.svc:4317`, `blackhole` | |
| `LOG_JSON`, `JAVA_TOOL_OPTIONS` | config | `true`, `-XX:MaxRAMPercentage=70 -Duser.timezone=UTC` | |

The ELO chat toxic-pattern list default is an obviously placeholder keyword list (see `application.yml`);
the prod value is an operator decision and out of scope here.

### Web (panel)

| Variable | Kind | Value |
|----------|------|-------|
| `BLACKHOLE_BACKEND_URL` | config | `http://<release>-backend:8080` |
| `BLACKHOLE_UI_OIDC_AUTHORITY` | config | `https://login.microsoftonline.com/<tenant>/v2.0` |
| `BLACKHOLE_UI_OIDC_CLIENT_ID` | config | SPA registration client id (public) |
| `BLACKHOLE_UI_API_SCOPE` | config | `api://<api client id>/access_as_user` (see the gotcha below) |
| `BLACKHOLE_UI_EVIDENCE_MAX_IMAGE_BYTES`, `_MAX_VIDEO_BYTES`, `_ALLOWED_TYPES` | config | video `94371840`, equal to the backend |
| `NITRO_HOST`, `NITRO_PORT` | config | baked in the image (`0.0.0.0`, `8080`) |

No secret: the SPA is a public client (PKCE).

### Portal

| Variable | Kind | Value |
|----------|------|-------|
| `BLACKHOLE_BACKEND_URL` | config | `http://<release>-backend:8080` |
| `BLACKHOLE_PORTAL_ORIGIN` | config | `https://appeal.onelitefeather.net` (must be https in production) |

No secret. The portal calls only `/public/**`. Cloudflare rate limits for `POST /public/session` and the
write routes are required (the backend deliberately has none) and are an operator task in the runbook.

### Discord bot

| Variable | Kind | Value |
|----------|------|-------|
| `DISCORD_TOKEN` | **secret** | bot token |
| `BLACKHOLE_DISCORD_CLIENT_SECRET` | **secret** | Entra client secret of `Blackhole Discord (prod)` (1 year, rotate) |
| `BLACKHOLE_DISCORD_TENANT_ID`, `_CLIENT_ID`, `_AUTH_SCOPE` | config | tenant, client id, `api://<api client id>/.default` |
| `DISCORD_GUILD_ID`, `_TEAM_CHANNEL_ID`, `_STAFF_ROLE_ID`, `_TICKET_CATEGORY_ID`, `_ARCHIVE_CATEGORY_ID` | config | ids (not secret, but personal to the guild; kept in the overlay) |
| `DISCORD_APPEAL_CHANNEL_ID`, `_ARCHIVE_DELETE_AFTER`, `_SYNC_INTERVAL`, `_STAFF_RECONCILE_INTERVAL`, `_LEAK_SCAN`, `_LEAK_SCAN_CHANNELS`, `DISCORD_HEALTH_PORT` | config | optional, defaults per `discord/README.md` |
| `BLACKHOLE_BACKEND_URL` | config | `http://<release>-backend:8080` (in-cluster; see rate-limit note) |
| `BLACKHOLE_PANEL_URL` | config | `https://blackhole.onelitefeather.net` |
| NATS URL and credentials | **secret** (creds) | replace `RABBITMQ_HOST/_USER/_PASSWORD`; names from `switch-event-bus-to-nats` |
| `BLACKHOLE_DISCORD_TOKEN_URL` | not set | development mock only |

The bot's documented edge limits (`POST /discord/tickets` 30/min, `POST /discord/leak-check` 120/min) only
apply when its traffic passes a gateway. In-cluster it goes straight to the Service, and the bot is a trusted
caller with its own role, so no limit is set at first; routing it through the internal Envoy Gateway with a
`BackendTrafficPolicy` rate limit is an optional later task.

### Velocity (for the runbook, configured outside the cluster)

`BLACKHOLE_TENANT_ID`, `BLACKHOLE_CLIENT_ID`, `BLACKHOLE_AUTH_SCOPE` (config), `BLACKHOLE_CLIENT_SECRET`
(**secret**), `baseUrl` and `redisUri` in `config.json` (see section 3).

### Secret inventory and ownership (SOPS files in Kubernetes-FLUX, per namespace)

`blackhole-db` (JDBC), `blackhole-redis` (URI), `blackhole-s3` (from the OBC), `blackhole-nats` (creds),
`blackhole-keys` (`BLACKHOLE_BANID_KEY`, `BLACKHOLE_EVASION_IP_SALT`, reserved `BLACKHOLE_FINGERPRINT_KEY`),
`blackhole-discord` (`DISCORD_TOKEN`, `BLACKHOLE_DISCORD_CLIENT_SECRET`), `blackhole-metrics` (Entra metrics
client secret), `harbor-pull`. Dev and prod use different values for every one of them, in particular different
Ban ID keys and IP salts (no cross-decrypting, no shared hashes).

**Ban ID key backup.** It is the one secret whose loss cannot be repaired from the database: Ban IDs are stored
as an HMAC and an AES-GCM ciphertext derived from it. It exists in the SOPS file (age recipients: maintainer,
cluster, CI) and must additionally be kept offline in the team password manager (a second person can read it).
The runbook has a yearly restore drill: decrypt a copy, compare its fingerprint (first 8 characters of
`sha256` of the base64 value), never print the key. Rotation is not supported in place.

## 6. Supporting infrastructure

- **Redis/Dragonfly.** `dragonfly.dragonfly.svc:6379`, password in the URI, one database index per environment
  (above); `cache_mode` is off (a data store, snapshots every 6 hours to its own PVC). Blackhole's use is a
  derived mirror (punishment state) and a TTL cache; the database is the source of truth, so the loss of
  Dragonfly costs latency, not data. (Whether the backend rebuilds the punishment mirror after a flush is
  checked in task 5.4.) Backend readiness includes the Redis indicator of Micronaut: confirm that a Dragonfly
  outage does not make the backend `unready` cluster-wide (task 5.4); if it does, disable that indicator in
  the chart's config.
- **S3 evidence.** Ceph RGW through an `ObjectBucketClaim` in the app namespace
  (`storageClassName: ceph-bucket-fr01`, `bucketName: blackhole-evidence[-dev]`, `bucketOwner: blackhole`),
  like Apus' bundle bucket (the claim must be in the namespace that reads the Secret, not in `rook-ceph-fr01`).
  Rook creates the Secret (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`) and a ConfigMap (`BUCKET_NAME`,
  `BUCKET_HOST`) named after the claim; the chart maps them to the `BLACKHOLE_EVIDENCE_S3_*` variables with
  `valueFrom`. Endpoint is the **internal** RGW service (`advertiseEndpoint` lesson: no NAT hairpin to the public
  name), path-style on. Evidence has no time-based expiry and **no S3 lifecycle rules** may delete it
  (application rule). Backup of the bucket: the Ceph object store's own redundancy only; there is no off-cluster
  copy (decided 2026-10-24; the runbook documents this, since evidence is the record behind punishments and
  appeals).
- **ClamAV.** Own Deployment (strategy `Recreate`) in the namespace; the backend's scan is fail-closed, so a
  scanner outage rejects uploads with 503 (accepted, uploads are rare). Image `clamav/clamav:1.5.4`; env
  `CLAMD_CONF_StreamMaxLength=110M`, `CLAMD_CONF_MaxFileSize=110M`, `CLAMD_CONF_MaxScanSize=220M` (as in
  compose, above the video cap). Signature database on a PVC (`/var/lib/clamav`, 2Gi `ceph-rbd-fr01`), so a
  restart does not refetch ~300 MB (the mirror rate-limits clients that restart often). The image runs
  `freshclam` as a daemon, so updates continue in the pod (default: checks every 2 hours). Memory: clamd
  loads the database (~1.2 GB) and holds a second copy during reload, hence request 1.5Gi and limit 3Gi.
  Startup: `startupProbe` `clamdcheck.sh`, 10-minute budget (first start downloads); readiness the same
  command. Needs egress to the ClamAV mirror (`database.clamav.net`). If the image cannot run under
  `restricted` (it starts as root and drops privileges), the pod gets a documented exception or a `USER`
  override verified in task 3.5; the namespace stays `baseline` for that reason.
  Dev uses the prod namespace's ClamAV (decided 2026-10-24) to save 1.5 GB.
- **PDB and spreading.** Backend and web/portal use `topologySpreadConstraints` on `kubernetes.io/hostname`
  (`ScheduleAnyway`), `priorityClassName: feather-standard` for prod, none for dev (dev yields under pressure,
  like Vulpes dev).
- **Entra** (`docs/entra-setup.sh prod https://blackhole.onelitefeather.net`): the same tenant as Apus. The
  **`access_as_user` gotcha** applies: the panel must request `api://<api client id>/access_as_user` in
  `BLACKHOLE_UI_API_SCOPE`; asking only `openid profile email` yields a Microsoft Graph token (wrong
  audience, v1 issuer, no roles) and the backend rejects every call while the UI shows nothing useful. The
  script also needs the SPA redirect URIs for the `.net` host and the group ids copied into
  `BLACKHOLE_AUTH_ROLE_*`. Client secrets (Velocity, Discord, Metrics) expire after one year: calendar the
  rotation (`docs/entra-setup.md`).

## 7. Release and environment flow

- **Release (prod).** release-please opens the release PR; on merge `publish-charts` (after the image jobs, like
  Apus' last-link-of-chain) runs `helm package deploy/charts/blackhole` and `helm push` to
  `oci://${HARBOR_REGISTRY}/onelitefeather/charts` with the credential-file trick Apus documents (no `helm registry
  login`). Chart `version` = `appVersion` = release; the default image tag is `appVersion`, so chart and images move
  together. Kubernetes-FLUX pins `OCIRepository blackhole` to `ref.tag: "X.Y.Z"` (the Apus convention); a prod
  upgrade is a PR raising that tag (Renovate may propose it).
- **Dev.** `main` pushes images tagged `main-<ts>-<sha7>` and a snapshot chart `X.Y.(Z+1)-main.<ts>` (unique,
  semver-ordered pre-release). `OCIRepository blackhole-dev` follows `semver: ">=0.0.0-0"`; an `ImageRepository`/
  `ImagePolicy` (filter `^main-(?P<ts>[0-9]+)-[0-9a-f]{7}$`, numerical ascending) and an `ImageUpdateAutomation`
  with `update.path` limited to the dev overlay write the tag into `global.image.tag` of the dev `HelmRelease`
  (setter comment on that one line). Prod is never touched by an image policy.
- **Namespaces.** `blackhole` and `blackhole-dev`, PSA labels as Vulpes. Separate database, bucket, Dragonfly
  index, NATS subjects/account and Entra app registrations (`dev` and `prod` of `entra-setup.sh`) per environment.
- **Runbook** `docs/kubernetes.md` (Diataxis: how-to and reference). Outline: first deploy, upgrading, secret
  rotation (raise `secretRevision`), restoring a database, the Ban ID key backup and restore drill, adding a
  Discord guild id, ClamAV signature trouble, the Cloudflare WAF/rate-limit rules (portal `POST /public/session`
  and write routes, API host allow-list), Entra secret rotation, how to read logs/traces/metrics, and a known
  failures list copied from the Outline gotchas page. A short Outline note links to the repository doc (the
  repository stays the source for chart behaviour).

## 8. Risks and trade-offs

| Risk | Mitigation |
|------|------------|
| Tunnel path rules route `/public/session` on the API host | anchored allow-list verified with a route table on dev; chart test; WAF rule; nginx fallback |
| Velocity without Redis is chattier and loses cross-proxy sync | documented fallback in the plugin; NATS move coordinated with `switch-event-bus-to-nats`; sizing in the chart |
| Lost Ban ID key | SOPS plus offline copy plus a yearly restore drill |
| Cloudflare 100 MB cap vs 100 MiB video limit | video cap 90 MiB in prod values; documented |
| Rolling update and `hbm2ddl=validate` on old pods | expand/contract rule; `Recreate` for breaking releases |
| ClamAV memory/startup | PVC for signatures, 10-minute startup budget, 3Gi limit |
| Two sources of truth (chart in repo, overlay in FLUX) | overlay holds only environment values; schema rejects unknown keys |
| NATS change slips | chart work proceeds; prod waits; NATS variable names isolated in one values block |

## 9. Decisions (2026-10-24), no open questions remain

1. **Velocity and Dragonfly.** Velocity never gets direct Dragonfly access and Dragonfly is not exposed.
   For now Velocity uses only the API through its HTTP fallback. Later it moves to NATS over WebSocket
   (`wss://nats.onelitefeather.dev`); that move is coordinated with the parallel change
   `switch-event-bus-to-nats`, not a separate change.
2. **API host.** `blackhole-api.onelitefeather.net`, with anchored tunnel paths that cover only the proxy
   prefixes; the nginx allow-list of section 3 is the fallback if anchoring does not pass through (task 4.2).
3. **Evidence bucket.** Ceph redundancy is enough; there is no off-cluster copy. The runbook documents this.
   `BLACKHOLE_BANID_KEY` keeps its separate offline backup (section 5).
4. **Dev ClamAV and video limit.** Dev reuses the prod namespace ClamAV; the prod video limit is 90 MiB.
