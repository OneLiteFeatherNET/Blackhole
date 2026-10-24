# Tasks

Small stacked pull requests on `main`, each squash-merged; the PR title is the Conventional Commit (one
type each). Every group starts with its tests (`helm template` assertions, `helm lint`, kubeconform or a
shell test); the production code follows. Tests follow F.I.R.S.T.: no cluster, no network, no registry,
deterministic (`helm template` with fixed value files, pinned tool versions). The Kubernetes-FLUX work (group
9) is a separate pull request in another repository and is **not** done here.

Order: 1 -> 2 -> 3/4/5 (independent after 2) -> 6 -> 7 -> 8. Group 9 starts when the first chart is published.
`switch-event-bus-to-nats` must have landed before any deployment (group 9.14), not before the chart work.

## 1. Chart skeleton and images (PR 1, `build(deploy)`)

- [ ] 1.1 Failing tests first: `deploy/charts/blackhole/tests/render.sh` (plain shell, runs `helm lint` and `helm template` over `ci/*-values.yaml`, asserts with `grep`/`yq`): default values render, unknown top-level key fails the schema, `scan.enabled` without a scanner fails, every Deployment sets `runAsNonRoot`, `seccompProfile`, dropped capabilities and a memory limit
- [x] 1.2 `Chart.yaml` (`version`/`appVersion` with `# x-release-please-version`), `values.yaml`, `values.schema.json`, `_helpers.tpl` (names, labels, image reference with the `appVersion` fallback, env-map rendering, `secretRevision` annotation), `.helmignore`, `README.md` with the values table
- [x] 1.3 Add `deploy/charts/blackhole/Chart.yaml` to `release-please-config.json` `extra-files` (generic)
- [x] 1.4 Check which user the generated `optimizedDockerfile` image runs as; if root, add the `USER`/`runAsUser` handling (Gradle `dockerfile` customisation) so `runAsNonRoot` can be enforced; record the uid in the chart README
- [ ] 1.5 Check that `web`, `portal` (user `node`, uid 1000) and `discord` (uid 10001) images run read-only with a `/tmp` `emptyDir`; fix the Dockerfiles only if not
- [x] 1.6 Verify: `tests/render.sh` green with Helm 3.19+ (pin the version in the script header)

## 2. Application workloads (PR 2, `feat(deploy)`)

- [ ] 2.1 Failing tests first (`tests/workloads.sh`): backend env map renders `JDBC_*`, `REDIS_URI`, `BLACKHOLE_BANID_KEY` and `BLACKHOLE_EVASION_IP_SALT` only through `secretKeyRef`/`envFrom` (no literal secret value in any render); `secretRevision` appears as a pod annotation; `JAVA_TOOL_OPTIONS` survives an overlay that adds one env variable (maps merge, lists do not); backend `startupProbe` allows 10 minutes; discord has `Recreate` and 1 replica; PDB exists only with more than one replica; web/portal env equals the table in `design.md`
- [ ] 2.2 Backend: Deployment (`RollingUpdate`, `maxUnavailable: 0`), Service (ClusterIP), ConfigMap from `backend.config`, PDB, ServiceAccount (`automountServiceAccountToken: false`), probes on `/health/liveness` and `/health/readiness`, JVM flags, `logs.onelitefeather.net/*` labels, `OTEL_*` env
- [ ] 2.3 Web and portal: Deployments, Services, PDBs, probes on `/healthz`, runtime env from `oidc.*` / `origin` / `backendUrl`; the portal schema requires an https origin outside `local` values
- [ ] 2.4 Discord: Deployment, ConfigMap for the ids and intervals, Secret references for the token and client secret, the NATS credentials mount (variable names from `switch-event-bus-to-nats`, kept in one values block), health on 8082
- [ ] 2.5 Evidence limits: values keep `BLACKHOLE_EVIDENCE_FILES_MAX_VIDEO_BYTES` and `BLACKHOLE_UI_EVIDENCE_MAX_VIDEO_BYTES` equal (schema check) and at most 94371840 when an Ingress for the panel is enabled
- [ ] 2.6 Verify: `tests/workloads.sh` and `tests/render.sh` green; kubeconform (group 6 wires it into CI)

## 3. ClamAV (PR 3, `feat(deploy)`)

- [ ] 3.1 Failing tests first: clamav Deployment with `Recreate`, a PVC at `/var/lib/clamav`, `CLAMD_CONF_*` env equal to compose, Service `3310`, memory limit at least 3Gi; the backend scan host resolves to that Service; with `clamav.externalHost` set no ClamAV workload is rendered and the backend uses the external host
- [ ] 3.2 Templates: Deployment, Service, PVC (`persistence.size`, `storageClass`), probes with `clamdcheck.sh` and a 10-minute `startupProbe`
- [ ] 3.3 Schema: scan on requires `clamav.enabled` or `clamav.externalHost`
- [ ] 3.4 Document the egress needed (ClamAV mirror) and the signature-update behaviour in the chart README
- [ ] 3.5 Verify against a `restricted` namespace with a server-side dry-run on a test cluster or document the exception: can `clamav/clamav:1.5.4` run non-root with `readOnlyRootFilesystem` and the PVC? If not, set the narrowest possible security context and say so in the design
- [ ] 3.6 Verify: tests green

## 4. Exposure (PR 4, `feat(deploy)`)

- [ ] 4.1 Failing tests first (`tests/exposure.sh`): panel Ingress host `blackhole.onelitefeather.net` and portal host `appeal.onelitefeather.net`, `ingressClassName: cloudflare-tunnel`, annotation `backend-protocol: http`, backends are ClusterIP Services (never `clusterIP: None`); the external API Ingress exists only with `backend.externalApi.enabled`; a route table (every proxy prefix allowed, `/public`, `/public/session`, `/public/appeal...`, `/discord`, `/prometheus`, `/swagger`, `/health`, `/auth`, `/admin` blocked) is evaluated against the rendered paths using the cloudflared semantics (unanchored regular expression, longest rule first); no two rules of that host have the same length
- [ ] 4.2 Verify the tunnel controller's behaviour before relying on anchors: read `cloudflare-tunnel-ingress-controller` v0.0.23 `pkg/cloudflare-controller/tunnel-client.go`, then check on dev with `curl` against the table that blocked paths get 404 from the edge; if anchored regular expressions do not pass through, implement the nginx allow-list component of design section 3 instead and re-run 4.1 against it
- [ ] 4.3 Templates: `ingress.yaml` for panel and portal, `ingress-api.yaml` for the proxy host, `httproute.yaml` (Envoy Gateway, `parentRefs: eg/envoy`, `sectionName: https`) for the internal dev hosts; values choose Ingress or HTTPRoute per component, never both
- [ ] 4.4 Per-path rules use distinct lengths (padding by ordering the rules, not by changing paths); document the collision rule in the template comment with a link to the Outline page
- [ ] 4.5 Verify: `tests/exposure.sh` green

## 5. Observability and dependency checks (PR 5, `feat(deploy)`)

- [ ] 5.1 Failing tests first (`tests/observability.sh`): pod labels `logs.onelitefeather.net/format` and `/env` on all workloads; `OTEL_*` env on the backend; backend `ServiceMonitor` only when enabled and with an `oauth2` block referencing a Secret, never a literal; discord `PodMonitor` on port 8082
- [ ] 5.2 Templates: `servicemonitor.yaml` (backend, `/prometheus`), `podmonitor.yaml` (discord)
- [ ] 5.3 Verify Alloy (`prometheus.operator.servicemonitors`) honours `oauth2` for the Entra client credentials; if not, remove the oauth2 block from the chart and describe the `prometheus.scrape` fallback in the Kubernetes-FLUX tasks (9.11)
- [ ] 5.4 Verify against the real stack (docker compose): after a Dragonfly `FLUSHALL` the backend rebuilds or tolerates the missing punishment mirror, and a Dragonfly outage does not make `/health/readiness` DOWN; if it does, add a config switch for the Redis health indicator to `backend.config` and a test for it (backend change only if unavoidable, as its own `fix` PR)
- [ ] 5.5 Verify: tests green

## 6. Chart checks in CI (PR 6, `ci(deploy)`)

- [ ] 6.1 Failing tests first: a PR that breaks the schema (negative fixture) turns the new job red locally (`act`-free: run the same script)
- [ ] 6.2 `build-pr.yml`: job `chart` with pinned Helm (as in Apus), `helm lint`, `helm template` over every `ci/*-values.yaml`, the negative render checks of the schema, `tests/*.sh`, and kubeconform (pinned, `-strict`, `-ignore-missing-schemas` only for the ServiceMonitor/PodMonitor/HTTPRoute CRDs with the datree CRD schema location)
- [ ] 6.3 Render the chart against the `restricted` Pod Security profile (kubeconform cannot; use `kubectl --dry-run=server` is not available offline, so a small `yq` policy check in `tests/psa.sh`: fails on missing `runAsNonRoot`, `seccompProfile`, `allowPrivilegeEscalation: false`, `drop: [ALL]`, with a documented ClamAV exception if 3.5 needs one)
- [ ] 6.4 Verify: the job passes on the branch and fails on the negative fixtures

## 7. Publishing (PR 7, `ci(deploy)`)

- [ ] 7.1 Failing tests first: a dry-run script (`.github/scripts/chart-version.sh`) computes the release chart version (`X.Y.Z`) and the dev chart version (`X.Y.(Z+1)-main.<ts>`) and the dev image tag `main-<ts>-<sha7>`, with unit-style shell tests for the ordering (`main.9` < `main.10` as semver) and for a `-SNAPSHOT` base version
- [ ] 7.2 `release-please.yml`: build and push `blackhole-web`, `blackhole-portal`, `blackhole-discord` with the shared `docker-publish.yml` (`context: .`, `dockerfile: <module>/Dockerfile`, cosign on), on release and on `main` pushes; the backend publish job `needs` the three; add `extra-tags: type=raw,value=main-<commit unix time>-<sha7>` to every `main` build, backend included
- [ ] 7.3 `release-please.yml`: job `publish-charts` after the image jobs: `helm package` and `helm push` to `oci://${HARBOR_REGISTRY}/onelitefeather/charts` using the credential-file approach from Apus (no `helm registry login`), release version on release, snapshot version on `main`
- [ ] 7.4 `workflow_dispatch` input to re-publish a given version's chart and images (extends the existing `docker_version` input)
- [ ] 7.5 Verify on a fork or with `workflow_dispatch`: the four images and the chart appear in Harbor with matching versions; `helm pull oci://.../blackhole --version X` works; record the result in the PR

## 8. Documentation (PR 8, `docs(deploy)`)

- [ ] 8.1 Check first (links, tables): a `markdown-lint`-style check of `docs/kubernetes.md`, every environment variable in the tables of `design.md` exists in `application.yml`, the Dockerfiles or the discord/web/portal config (a script greps them; fails on a variable that does not exist)
- [ ] 8.2 `docs/kubernetes.md`: reference (components, hosts, values, secrets table, ports), how-to (first deploy, upgrade, secret change with `secretRevision`, rotate Entra secrets, restore the database, Ban ID key backup and restore drill, add the Velocity host), explanation (why no Dragonfly for Velocity, why migrate on start), failure list from the Outline gotchas
- [ ] 8.3 Operator tasks outside any repo: Cloudflare rate limits for `POST /public/session` and the portal write routes, WAF allow-list on the API host, Entra redirect URIs for `blackhole.onelitefeather.net`; list them with owners
- [ ] 8.4 Link from `README.md`, `docs/discord-bot.md` (deployment section) and `CLAUDE.md` Commands (one line), and add an Outline page link in the runbook
- [ ] 8.5 Verify: `openspec validate --all --strict`

## 9. Kubernetes-FLUX pull request (separate repository, not part of this repository's PRs)

Branch from the current `main` of Kubernetes-FLUX; title `feat(blackhole): deploy blackhole to feather-core`
(types are the repository's `commitlint`: lowercase subject). Run `./.github/scripts/validate.sh` before
opening. Split into stacked PRs where the reviewer prefers (storage/db/messaging first, then the product).

- [ ] 9.1 Tests first: `validate.sh` (kustomize build + kubeconform) is green for the new paths; `check-sops-encryption.py` sees every new secret file with all recipients
- [ ] 9.2 Namespaces `blackhole` and `blackhole-dev` with PSA `enforce: baseline`, `warn/audit: restricted`
- [ ] 9.3 `foundation/sources/.../sources/blackhole.yaml`: `OCIRepository blackhole` (`oci://harbor.onelitefeather.dev/onelitefeather/charts/blackhole`, `ref.tag` pinned for prod) and `blackhole-dev` (`semver: ">=0.0.0-0"`), `layerSelector` like Apus
- [ ] 9.4 `products/blackhole/base/blackhole/` (namespace, `HelmRelease`) and overlays `products/blackhole/clusters/feather-core/blackhole/` and `blackhole-dev/` (patch `release.yaml`, `secretGenerator` with `disableNameSuffixHash`), registered in `products/layers/feather-core/prod/kustomization.yaml` and `dev/kustomization.yaml`
- [ ] 9.5 Overlay values: hosts (`blackhole.onelitefeather.net`, `appeal.onelitefeather.net`, API host), Entra ids, `secretRevision`, env label `logs.onelitefeather.net/env`, resources, `priorityClassName: feather-standard` for prod only, `video` limit 90 MiB
- [ ] 9.6 SOPS secrets per namespace (`blackhole-db`, `blackhole-redis`, `blackhole-keys`, `blackhole-discord`, `blackhole-metrics`, `blackhole-nats`, `harbor-pull`); generate the Ban ID key with `openssl rand -base64 32` and store the offline copy in the password manager before the first start
- [ ] 9.7 CNPG: `Database` CRs `blackhole` and `blackhole-dev` (owner = role), `managed.roles` entries with `role-blackhole` and `role-blackhole-dev` secrets (label `cnpg.io/reload: "true"`), added to the postgresql `kustomization.yaml`; confirm the daily `ScheduledBackup` covers the new databases
- [ ] 9.8 `ObjectBucketClaim` `blackhole-evidence` and `blackhole-evidence-dev` in the app namespaces (`ceph-bucket-fr01`, `bucketOwner: blackhole`)
- [ ] 9.9 Dragonfly: pick and register DB indexes (10 prod, 11 dev) and build the URIs with the existing password
- [ ] 9.10 NATS: account `BLACKHOLE` (with `nsc`) and users for backend, discord and (later) velocity (WebSocket-only, subscribe-only); per-environment subject separation as defined by `switch-event-bus-to-nats`; update `nats-auth.sops.conf`; keep the seeds out of git
- [ ] 9.11 Alloy: scrape with Entra client credentials for `/prometheus` (only if 5.3 says the ServiceMonitor `oauth2` block does not work); log pipeline needs nothing new (labels)
- [ ] 9.12 Image automation for dev: `ImageRepository` (`harbor.onelitefeather.dev/onelitefeather/blackhole`, `harbor-pull`), `ImagePolicy blackhole-dev` (`^main-(?P<ts>[0-9]+)-[0-9a-f]{7}$`, numerical asc), `ImageUpdateAutomation` limited to the dev overlay path, commit message template like Vulpes, setter comment on `global.image.tag`
- [ ] 9.13 Internal dev routes (`HTTPRoute` via chart values) and, optionally, an Envoy `BackendTrafficPolicy` rate limit for the bot's routes
- [ ] 9.14 Rollout order: dev first (needs the NATS change landed), run the smoke checklist (panel login with an Entra group member, `GET /health/readiness`, an evidence upload through ClamAV, a portal Ban ID login, the bot connecting, a Velocity test punishment over the API host), then prod with the pinned chart version
- [ ] 9.15 Entra: `docs/entra-setup.sh prod https://blackhole.onelitefeather.net` run by an admin; calendar entries for the client secret expiry; Cloudflare rate-limit and WAF rules from 8.3

## 10. Open the pull requests

- [ ] 10.1 Open the PRs in order with these titles (lowercase, one type, at most 100 characters), each description ending with the session link: `build(deploy): add the blackhole helm chart skeleton`, `feat(deploy): add the application workloads`, `feat(deploy): add clamav to the chart`, `feat(deploy): expose panel, portal and the proxy api`, `feat(deploy): add chart observability`, `ci(deploy): lint and render the chart`, `ci(deploy): publish images and chart to harbor`, `docs(deploy): add the kubernetes runbook`
- [ ] 10.2 Open the Kubernetes-FLUX PR (group 9) once the first chart version is in Harbor, title `feat(blackhole): deploy blackhole to feather-core`, linking this change
- [ ] 10.3 Run `gh pr checks --watch` on each PR; do not merge without the user's go-ahead
- [ ] 10.4 Archive the change after rollout: `docs(openspec): archive deploy-to-kubernetes`
