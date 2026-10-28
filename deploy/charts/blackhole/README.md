# blackhole

Helm chart for the Blackhole ban system on Kubernetes: the backend, the admin panel (`web`), the appeal
portal (`portal`), the Discord bot (`discord`) and an in-namespace ClamAV scanner for evidence uploads.
The operator runbook (first deploy, upgrades, secret changes, restores) is [`docs/kubernetes.md`](../../../docs/kubernetes.md).

The chart is pushed as an OCI artifact to `oci://<registry>/onelitefeather/charts/blackhole`. Its `version`
and `appVersion` are the release version (release-please keeps both lines marked `x-release-please-version`
in `Chart.yaml` up to date), and the default image tag of every Blackhole image is the `appVersion`, so a
chart version and its images always move together.

```shell
helm install blackhole oci://<registry>/onelitefeather/charts/blackhole --version X.Y.Z -f my-values.yaml
```

## Design rules

- **No secret value is ever in the chart.** Every secret is read from a Secret that already exists and is
  named in the values (`existingSecret`). The schema refuses a literal value for a secret-looking name
  (`*_PASSWORD`, `*_SECRET`, `*_TOKEN`, `*_KEY`, `*_SALT`, `REDIS_URI`) in an `env` map.
- **Maps, not lists.** Helm replaces lists but merges maps, so `env`, `envFrom`, `config` and the probes
  are maps: an overlay adds one variable without restating `JAVA_TOOL_OPTIONS`. Set a key to `null` to
  remove it.
- **Secret changes do not roll pods** (the cluster generates Secrets with stable names). Raise
  `<component>.secretRevision`; it is the pod annotation `blackhole.onelitefeather.net/secret-revision`.
  The backend also rolls when its ConfigMap changes (`checksum/config`).
- **The schema is the contract.** `values.schema.json` rejects unknown keys and requires every value that has
  no safe default, for the components that are enabled. A missing value fails the install with its path.
- **Event bus: NATS only.** The chart has no RabbitMQ settings. The names (`NATS_URL`, `NATS_CREDS_FILE`,
  `NATS_STREAM_REPLICAS`, `NATS_EVENTS_MAX_AGE`, `NATS_EVENTS_MAX_BYTES`) come from the change
  `switch-event-bus-to-nats` and live in the one `nats` block.
- **Velocity is outside the cluster** and gets no Dragonfly access. It reaches the backend over the
  external API host (`backend.externalApi`) and falls back to HTTP checks when Redis is unreachable.

## Required values

For an enabled component the schema requires:

| Component | Required values |
|-----------|-----------------|
| backend | `database.existingSecret`, `redis.existingSecret`, `keys.existingSecret`, `nats.url`, `nats.existingSecret`, `s3.endpoint`, `s3.bucket`, `s3.existingSecret`, `auth.tenantId`, `auth.apiClientId`, `auth.roles.{admin,moderator,viewer,metrics}` |
| web | `web.oidc.clientId`, `web.oidc.apiScope` (and `web.ingress.host` or `web.httpRoute.hostnames` when exposed) |
| portal | `portal.origin` (https unless `global.environment` is `local`) |
| web (with a security contact) | `web.origin`, the panel's public origin: security.txt names its `Canonical` URL on it |
| discord | `discord.existingSecret`, `discord.clientId`, `discord.panelUrl`, `discord.ids.{guildId,teamChannelId,staffRoleId,ticketCategoryId,archiveCategoryId}`, `nats.*`, `auth.tenantId`, `auth.apiClientId` |
| scan | with `backend.evidence.scan.enabled` (the default): `clamav.enabled` or `clamav.externalHost` |

## Secrets the chart expects

Names are chosen in the values; the keys inside are fixed.

| Values key | Suggested name | Keys |
|------------|----------------|------|
| `database.existingSecret` | `blackhole-db` | `JDBC_URL`, `JDBC_USER`, `JDBC_PASSWORD` |
| `redis.existingSecret` | `blackhole-redis` | `REDIS_URI` |
| `keys.existingSecret` | `blackhole-keys` | `BLACKHOLE_BANID_KEY`, `BLACKHOLE_EVASION_IP_SALT` |
| `s3.existingSecret` | `blackhole-s3` | `BLACKHOLE_EVIDENCE_S3_ACCESS_KEY`, `BLACKHOLE_EVIDENCE_S3_SECRET_KEY` (names changeable with `s3.accessKeyKey` / `s3.secretKeyKey`, e.g. to the `AWS_*` keys of an ObjectBucketClaim Secret) |
| `nats.existingSecret` | `blackhole-nats` | `nats.creds` (changeable with `nats.credsKey`); mounted at `nats.credsMountPath` |
| `backend.nats.existingSecret`, `discord.nats.existingSecret` | e.g. `blackhole-nats-backend`, `blackhole-nats-bot` | optional per-component NATS user: `nats.creds` or the key in `<component>.nats.credsKey`; empty values fall back to the shared `nats.*` (the backend and the bot are different NATS users) |
| `discord.existingSecret` | `blackhole-discord` | `DISCORD_TOKEN`, `BLACKHOLE_DISCORD_CLIENT_SECRET` |
| `backend.metrics.serviceMonitor.oauth2.existingSecret` | `blackhole-metrics` | `BLACKHOLE_METRICS_CLIENT_ID`, `BLACKHOLE_METRICS_CLIENT_SECRET` (only with the ServiceMonitor) |
| `global.imagePullSecrets` | `harbor-pull` | a `kubernetes.io/dockerconfigjson` Secret |

`BLACKHOLE_BANID_KEY` cannot be replaced after the first start (every Ban ID becomes unreadable). Keep an
offline copy of it before the first install.

## Values overview

| Block | What it sets |
|-------|--------------|
| `global` | `environment` (`prod`/`dev`/`local`), `image.registry`, `image.tag` (empty = `appVersion`), `imagePullSecrets`, `podLabels`, `priorityClassName` |
| `database`, `redis`, `nats`, `s3`, `keys` | the shared infrastructure; Secrets by name |
| `auth` | the Entra tenant, API client id, staff group ids and machine role names |
| `backend` | `replicaCount` (2), `env`, `envFrom`, `config` (non-secret `BLACKHOLE_*` tunables), `secretRevision`, `evidence.{maxVideoBytes,scan}`, `tracing`, `strategy`, `pdb`, probes, `resources`, security contexts, scheduling, `metrics.serviceMonitor`, `externalApi` |
| `web` | the same common keys, `oidc.*`, `evidence.*`, `ingress` or `httpRoute`, `pdb` |
| `portal` | the same common keys, `origin`, `ingress` or `httpRoute`, `pdb` |
| `discord` | the common keys, `existingSecret`, `clientId`, `authScope`, `panelUrl`, `ids.*`, `config` (intervals), `metrics.podMonitor`; always exactly one replica |
| `clamav` | `enabled`, `externalHost`, `image`, `unprivileged`, `limits.*`, `persistence.{size,storageClass}` |

Defaults and sizing (a first guess, tuned after the first week of metrics):

| Component | requests | limit | replicas | PDB |
|-----------|----------|-------|----------|-----|
| backend | 250m / 768Mi | memory 1Gi, no CPU limit | 2 | `maxUnavailable: 1` when more than one replica |
| web, portal | 25m / 64Mi | memory 128Mi | 2 | as backend |
| discord | 100m / 256Mi | memory 384Mi | 1 | none (`Recreate`) |
| clamav | 200m / 1536Mi | memory 3Gi | 1 | none (`Recreate`) |

## Security disclosure (security.txt)

`securityTxt` renders the RFC 9116 file of the portal and the panel (`/.well-known/security.txt`) as
`BLACKHOLE_SECURITY_*` variables. The chart default contact is `mailto:security@onelitefeather.net`, which the
organisation must create before the first release that uses the default. Set `securityTxt.expires` on every
deployment: there is no default, and the render fails without it. The expiry is a date an operator has looked at,
and it must be renewed at least yearly. The apps refuse to start on an expiry in the past or more than one year
ahead, so a lapsed value stops the next rollout instead of publishing a stale file. Clear `securityTxt.contact` to
turn the route off (it answers 404). The file is unsigned in this version.

## Pod security

Every pod runs with `runAsNonRoot`, `seccompProfile: RuntimeDefault`, `allowPrivilegeEscalation: false`, all
capabilities dropped and a read-only root file system; `/tmp` is an `emptyDir`. The service account token is
not mounted. These are the user ids the images run as (and the values default to):

| Component | uid:gid | Where it comes from |
|-----------|---------|---------------------|
| backend | 10001:10001 | the `optimizedDockerfile` customisation in `backend/build.gradle.kts` (the Micronaut default image runs as root) |
| web, portal | 1000:1000 | the `node` user of `node:24-alpine` |
| discord | 10001:10001 | `discord/Dockerfile` |
| clamav | 100:101 | the `clamav` user of the image; started through `/init-unprivileged` with a mounted `clamd.conf` |

Backend, web and portal are written for the `restricted` Pod Security level. ClamAV is written for it too
(`clamav.unprivileged: true`); the image's default entrypoint starts as root and drops privileges, so if the
unprivileged start misbehaves on a cluster, set `clamav.unprivileged: false` and run the namespace at
`baseline` (the documented exception). `tests/psa.sh` checks the rendered manifests.

## ClamAV

The pod keeps its signature database on a PVC (`clamav.persistence`, `/var/lib/clamav`), so a restart does
not download the roughly 300 MB again (the mirror rate-limits clients that restart often). The image runs
`freshclam` as a daemon and checks for updates every 2 hours in the pod, so ClamAV needs egress to
`database.clamav.net` and its CDN. The first start downloads the database, which can take several minutes;
the `startupProbe` allows 10 minutes. `clamd` holds a second copy of the database while it reloads, hence
the 1.5Gi request and the 3Gi limit. The backend scan is fail-closed: while the scanner is down, evidence
uploads are rejected with 503. With `clamav.externalHost` set, no ClamAV workload is rendered and the
backend uses that host (the dev environment uses the scanner of the prod namespace).

## Exposure

Ingress resources use the class `cloudflare-tunnel`; `web.ingress` serves the panel and `portal.ingress`
the portal. The Velocity proxies use the separate host of `backend.externalApi`:

- `mode: paths` (default): one Ingress path rule per host, an anchored regular expression over the proxy
  prefixes (`^/(punishment|profile|...)`). The tunnel controller passes the path to cloudflared unchanged, and
  cloudflared matches paths as an unanchored regular expression, so a plain prefix such as `/appeal` would
  also match `/public/appeal/...`. One rule cannot collide in length with another. `/public`, `/discord`,
  `/prometheus`, `/swagger`, `/health`, `/admin` and `/auth` are never routed (the schema refuses them as
  prefixes), and everything else gets the controller's default 404.
- `mode: nginx`: an allow-list proxy (`nginx-unprivileged`) in front of the backend with the same prefixes
  and `return 404` for the rest; the tunnel then routes `/`. The fallback if anchored paths ever stop
  passing through.

A tunnel backend is always a normal ClusterIP Service, never a headless one. For the dev environment
`web.httpRoute` / `portal.httpRoute` (Gateway API) replace the Ingress; the schema refuses both at once.

## Versions and publishing

`.github/workflows/release-please.yml` publishes the four images (`onelitefeather/blackhole`,
`blackhole-web`, `blackhole-portal`, `blackhole-discord`) and then the chart to
`oci://<registry>/onelitefeather/charts`:

| Event | Images | Chart `version` / `appVersion` |
|-------|--------|--------------------------------|
| release | `X.Y.Z`, `X.Y`, `X`, `sha-<7>` and `main-<unix>-<sha7>` | `X.Y.Z` / `X.Y.Z` |
| push to `main` (SNAPSHOT) | `X.Y.Z-SNAPSHOT`, `sha-<7>` and `main-<unix>-<sha7>` | `X.Y.Z-main.<unix>` / `main-<unix>-<sha7>` |
| manual run with a version | that version, without the `main-` tag | that version |

`<unix>` is the commit time, so all four images of a commit share one tag; Flux image automation orders
`^main-(?P<ts>[0-9]+)-[0-9a-f]{7}$` numerically. The backend image is published last, after the other three
exist. The dev chart version is unique per commit (Flux caches charts by version) and sorts as semver
(`main.9` below `main.10`, below the release it leads to). `.github/scripts/chart-version.sh` computes them.

## Tests

All tests are shell scripts around `helm template` and `yq`, with the fixed value files in `ci/`; they need
no cluster, no network and no registry (run `tests/run-all.sh`; tools: Helm v4.3.0, yq v4.53.6). The CI job
adds kubeconform v0.8.0.

| Script | What it checks |
|--------|----------------|
| `tests/render.sh` | every fixture lints and renders; the schema rejects unknown keys, missing required values, literal secrets, RabbitMQ settings |
| `tests/workloads.sh` | env and Secret wiring, probes, strategies, PDBs, ClamAV, `secretRevision` |
| `tests/exposure.sh` | hosts, Ingress class, the route table against the cloudflared path semantics |
| `tests/psa.sh` | the `restricted` Pod Security fields on every pod |
