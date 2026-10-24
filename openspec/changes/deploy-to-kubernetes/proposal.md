# Proposal

**Ships as:** `build(deploy)` (chart skeleton, image builds), `feat(deploy)` (per-component templates),
`ci(deploy)` (chart checks, image and chart publishing), `docs(deploy)` (runbook). One type per commit,
see the PR plan in `tasks.md`. The cluster side (Kubernetes-FLUX) is a separate PR in another repository
and is only described here as tasks.

**Depends on:** `switch-event-bus-to-nats` (parallel change). RabbitMQ is removed there; the chart is
written for NATS only and does not ship a RabbitMQ option. The chart work can start before it lands, but
nothing is deployed to prod before it has.

## Why

Blackhole runs only on a developer's docker-compose today. The team operates `feather-core` through
Flux GitOps and wants Blackhole there: the admin panel for staff, the appeal portal for players, the
backend, the Discord bot, and a way for the Velocity proxies (which run outside the cluster) to reach
the backend. Nothing in the repository describes how the images other than the backend are built, how
the components are configured in a cluster, or how a release reaches it.

## What Changes

- **A Helm chart `deploy/charts/blackhole`** in this repository (Apus style: `values.schema.json`,
  release-please keeps `version` and `appVersion` equal to the release, pushed as an OCI artifact to
  Harbor). Components: `backend`, `web` (admin panel), `portal`, `discord` (bot) and an in-namespace
  `clamav` (feather-core has no ClamAV). Each can be switched off.
- **Images for all four app components in Harbor.** Only the backend image is published today
  (`onelitefeather/blackhole`); `web`, `portal` and `discord` have Dockerfiles but no publish job.
  Dev images get a unique, orderable tag so Flux image automation can follow `main`.
- **Hosts:** the panel at `blackhole.onelitefeather.net` and the portal at `appeal.onelitefeather.net`,
  both through the Cloudflare Tunnel. The panel is protected by the Entra login only (no Cloudflare
  Access). The backend API stays internal, except one separate external API host for Velocity
  (proposed `blackhole-api.onelitefeather.net`, an open question) that exposes only the routes the
  proxy uses.
- **Velocity (outside the cluster):** reaches the backend through that host with its Entra client
  credentials (`PROXY` role). It does **not** get direct Dragonfly access; see the design for the
  recommendation (HTTP now, NATS over WebSocket in a follow-up change).
- **Database migrations:** Flyway keeps running on backend start (its PostgreSQL lock makes several
  replicas safe); no migration Job.
- **Config and secrets** as a table per component; secret values come from SOPS-encrypted files in
  Kubernetes-FLUX, never from the chart.
- **A runbook** (`docs/kubernetes.md`) and the Kubernetes-FLUX task list (namespaces `blackhole` and
  `blackhole-dev`, `OCIRepository`, `HelmRelease`, SOPS secrets, CNPG `Database` and role,
  `ObjectBucketClaim`, NATS account, image automation for dev).

Not breaking: additions only. One deliberate deviation: the panel host is under `.net`, whereas
ADR-ORG-0009 says `.net` for players and `.dev` for the team. The user chose `.net` for the panel
deliberately on 2026-10-24.

## Out of scope

- Editing Kubernetes-FLUX (a separate PR; this change only lists its tasks).
- Changing the Velocity plugin to use NATS instead of Redis (follow-up change `velocity-use-nats`).
- Cloudflare WAF and rate-limit rules as code (operator tasks in the runbook).
- Multi-tenancy, several networks per deployment, autoscaling (HPA), multi-cluster.
- New application features or API changes; backend, web, portal and discord code stay as they are,
  except the Dockerfile and CI changes named in `tasks.md`.
- Removing RabbitMQ (done by `switch-event-bus-to-nats`).

## Impact

New: `deploy/charts/blackhole/**`, `docs/kubernetes.md`, workflow jobs in
`.github/workflows/release-please.yml` and `build-pr.yml`, the capability spec `kubernetes-deployment`.
Changed: `release-please-config.json` (extra file for the chart). `docker/docker-compose.yml` is untouched.
