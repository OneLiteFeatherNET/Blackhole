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
  (`*_PASSWORD`, `*_SECRET`, `*_TOKEN`, `*_KEY`, `*_SALT`, `REDIS_URI`, `*WEBHOOK_URL`) in an `env` or `config` map.
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
| `discord.existingSecret` | `blackhole-discord` | `DISCORD_TOKEN`, `BLACKHOLE_DISCORD_CLIENT_SECRET`, and `DISCORD_AUDIT_WEBHOOK_URL` (the staff audit channel's webhook; the key name is `discord.auditWebhookKey`). The key is optional (empty `discord.auditWebhookKey` turns the audit off); add it, then raise `discord.secretRevision` to roll the bot |
| `backend.metrics.serviceMonitor.oauth2.existingSecret` | `blackhole-metrics` | `BLACKHOLE_METRICS_CLIENT_ID`, `BLACKHOLE_METRICS_CLIENT_SECRET` (only with the ServiceMonitor) |
| `publicBanKey.existingSecret` | `blackhole-public-ban` | `BLACKHOLE_PUBLIC_BAN_KEY` (the HMAC key of the public ban entries; see Public ban list) |
| `banList.existingSecret` | `blackhole-ban-list` | one key per client, named after the client, holding its Argon2id hash (see Public ban list) |
| `global.imagePullSecrets` | `harbor-pull` | a `kubernetes.io/dockerconfigjson` Secret |

`BLACKHOLE_BANID_KEY` cannot be replaced after the first start (every Ban ID becomes unreadable). Keep an
offline copy of it before the first install.

## Values overview

| Block | What it sets |
|-------|--------------|
| `global` | `environment` (`prod`/`dev`/`local`), `image.registry`, `image.tag` (empty = `appVersion`), `imagePullSecrets`, `podLabels`, `priorityClassName` |
| `database`, `redis`, `nats`, `s3`, `keys` | the shared infrastructure; Secrets by name |
| `auth` | the Entra tenant, API client id, staff group ids and machine role names |
| `backend` | `replicaCount` (2), `env`, `envFrom`, `config` (non-secret `BLACKHOLE_*` tunables, e.g. `BLACKHOLE_AUDIT_RETENTION`), `secretRevision`, `evidence.{maxVideoBytes,scan}`, `tracing`, `geoip.*` (off by default, see GeoIP below), `strategy`, `pdb`, probes, `resources`, security contexts, scheduling, `metrics.serviceMonitor`, `externalApi` |
| `web` | the same common keys, `oidc.*`, `evidence.*`, `ingress` or `httpRoute`, `pdb` |
| `portal` | the same common keys, `origin`, `ingress` or `httpRoute`, `pdb` |
| `discord` | the common keys, `existingSecret`, `auditWebhookKey`, `clientId`, `authScope`, `panelUrl` (the panel base URL, `BLACKHOLE_PANEL_URL`, also the base of the audit links), `ids.*`, `config` (intervals), `metrics.podMonitor`; always exactly one replica |
| `banList`, `publicBanKey` | the public ban list: `clients` (empty = off), `existingSecret`, `mountPath`, `trustedIngress` (required with clients); and the key's Secret. See Public ban list |
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

## GeoIP databases (player sessions)

Off by default; nothing is rendered. With `backend.geoip.enabled`, the backend mounts the PVC
`backend.geoip.existingClaim` read-only at `backend.geoip.mountPath` (`/data/geoip`) and reads `asn.mmdb` and
`country.mmdb` from it. Missing files only mean sessions are stored without country and ASN.

With `backend.geoip.refresh.enabled` as well, a monthly CronJob (`0 4 2 * *`, UTC) downloads the free DB-IP
Lite ASN and country files into the same claim. It holds no secret, its image is pinned by tag
(`backend.geoip.refresh.image`), and it writes each file under a `.part` name before the rename. The
DB-IP files are CC BY 4.0, so the attribution must be shown wherever the data is (the design puts it in the
panel footer).

- The claim must be ReadWriteMany (or the refresh job and the backend pods on one node): the job writes what
  the backend pods read. Leave about 300 MB free for the two files.
- Kubernetes 1.27 or later for the CronJob `timeZone`.
- The chart creates no PVC; the operator provides the claim and its storage class.

## Public ban list

Off by default: with `banList.clients` empty nothing of it is rendered. The list is read by named clients with
HTTP Basic Auth; the backend checks the password against an Argon2id hash per client.

- `banList.clients`: the client names (`[a-z0-9-]{1,32}`). They render into `BLACKHOLE_BAN_LIST_CLIENTS`.
- `banList.existingSecret`: a Secret with one key per client, the key being the client's name, the value its PHC
  string (`$argon2id$v=19$m=19456,t=2,p=1$...`). Each key is mounted read-only as a file of that name in
  `banList.mountPath` (default `/etc/blackhole/ban-list`, `BLACKHOLE_BAN_LIST_SECRETS_DIR`). Only the listed keys
  are mounted. Hashes only: the chart refuses any plaintext value, and no password exists in the chart.
- `banList.trustedIngress`: required with clients (the render fails without it). The peers whose `X-Forwarded-Proto`
  the backend trusts, as comma separated addresses or CIDRs (`BLACKHOLE_BAN_LIST_TRUSTED_INGRESS`). Any other peer
  gets 403 `HTTPS_REQUIRED`, so an empty or wrong value refuses every client. Which value fits:
  - tunnel mode (`backend.externalApi.mode: paths`): the cloudflared pods connect straight to the backend Service, so
    the source of their requests is their pod IPs. Use the pod CIDR of the cluster (or the CIDR the cloudflared
    deployment runs in), e.g. `10.42.0.0/16`. Only a peer in that range can set the header.
  - nginx mode (`mode: nginx`): the backend sees the api-proxy pods as the peer, and the proxy sets
    `X-Forwarded-Proto https` itself. Use the pod CIDR the api-proxy runs in, which is usually the same cluster pod
    CIDR.
  Use the pod network, never a Service or node range: those would let a node or another workload set the header.
- `publicBanKey.existingSecret`: the Secret with `BLACKHOLE_PUBLIC_BAN_KEY`, passed as `secretKeyRef`. It renders
  whether or not clients are set, because the public entries are hashed with it. It is required with clients.
  The backend requires it only once `banList.clients` is set, so with the list off no key is needed. Create the
  Secret before enabling the list (and before rolling a backend release that has it). Changing the key changes
  every entry hash.
- Rotation: write the new hash into the Secret key, then raise `backend.secretRevision`. Removing a client: remove
  its name from `banList.clients` and raise the revision. The backend reads the files once at start.

The external API host routes `/public/bans` (the list, one entry, the summary) only while `banList.clients` is set:
the same anchored expression gets one more alternative, `public/bans(/|$)`, in both the tunnel and the nginx mode.
Everything else under `/public` (`/public/session`, `/public/appeal/...`, `/public` itself) stays refused, because
the schema refuses `public` as a prefix. With the list off, `/public/bans` is refused too.

### Ingress and HTTPS (ban list)

The backend's external host (`backend.externalApi`) is a Cloudflare tunnel Ingress with plain `http` to the
Service. The chart sets neither an HTTP-to-HTTPS redirect nor `X-Forwarded-Proto`: TLS ends at the Cloudflare edge,
which redirects HTTP to HTTPS and adds `X-Forwarded-Proto: https` to proxied requests. Both are zone settings of
the edge, not of the chart, so they cannot be checked here. The `HTTPS_REQUIRED` check of the backend (403 over
plain HTTP) relies on that header, and the backend trusts it only from the peers of `banList.trustedIngress`. The `web` and `portal`
HTTPRoutes (dev) use the `https` listener of the Envoy Gateway, whose redirect lives in the cluster's Gateway
configuration. The chart adds no change for this.

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
| `tests/banlist.sh` | the public ban list is off by default; client names, hash files (read-only, one per client) and the public key only as Secret references; the trusted ingress is required with clients; plaintext refused |
| `tests/geoip.sh` | the GeoIP volume (read-only in the backend), the paths, the refresh job (pinned, no secrets, restricted) and the switches |
