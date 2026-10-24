# Spec Delta

## Purpose
Defines how Blackhole is packaged and run on the `feather-core` Kubernetes cluster: one Helm chart
published from this repository, images for every component, the exposed hosts, and the rules for
configuration, secrets, migrations and releases.

## ADDED Requirements

### Requirement: One chart, versioned with the release
The repository SHALL contain the Helm chart `deploy/charts/blackhole` with a `values.schema.json`. Its
`version` and `appVersion` SHALL equal the release version (maintained by release-please), and every
release SHALL push the chart as an OCI artifact to Harbor next to the images of the same version.

#### Scenario: Release publishes chart and images together
- **WHEN** release-please creates the release `v1.2.0`
- **THEN** the chart `1.2.0` and the images `1.2.0` of backend, web, portal and discord are in Harbor
- **AND** the chart's default image tag is its `appVersion`

#### Scenario: A chart change is checked
- **WHEN** a pull request changes anything under `deploy/charts/blackhole`
- **THEN** CI lints and renders the chart and validates the output with kubeconform, and fails on any error

### Requirement: Components can be enabled separately
The chart SHALL deploy `backend`, `web`, `portal`, `discord` and `clamav` as separate workloads, each
switchable with `<component>.enabled`. The schema SHALL reject values that enable the evidence virus scan
without naming a scanner (`clamav.enabled` or an external `clamd` host).

#### Scenario: Scan enabled without a scanner
- **WHEN** the values enable the scan and name no scanner
- **THEN** `helm template` fails with a message naming the missing setting

### Requirement: Secure pod defaults
Every workload SHALL run as a non-root user with `seccompProfile: RuntimeDefault`,
`allowPrivilegeEscalation: false`, all capabilities dropped and a read-only root file system (with
`emptyDir` mounts for writable paths), SHALL set CPU and memory requests and a memory limit, and the JVM
workloads (backend, discord) SHALL set a `startupProbe`. Backend and web SHALL get a
`PodDisruptionBudget` when they run with more than one replica.

#### Scenario: Restricted pod security
- **WHEN** the rendered manifests are checked against the `restricted` Pod Security level
- **THEN** no workload violates it (ClamAV only if its image cannot meet it, documented in the design)

### Requirement: Secrets stay out of the chart
The chart SHALL NOT contain or generate secret values. Every secret value SHALL be read from a Secret
named in the values, and a `secretRevision` value per component SHALL be rendered as a pod annotation, so
that raising it rolls the pods after a Secret change.

#### Scenario: Secret changed
- **WHEN** an operator changes a secret and raises `backend.secretRevision`
- **THEN** the backend pods are replaced on the next release upgrade

### Requirement: Public exposure is limited to the agreed hosts
The chart SHALL create Ingress resources (class `cloudflare-tunnel`) only for the panel
(`blackhole.onelitefeather.net`), the portal (`appeal.onelitefeather.net`) and, when enabled, the external
API host for the proxy. The external API host SHALL route only the path prefixes the proxy uses and
SHALL NOT route `/public`, `/discord`, `/prometheus`, `/swagger`, `/health`, `/admin` or `/auth`. No tunnel
backend SHALL be a headless Service.

#### Scenario: Anonymous player login not reachable on the API host
- **WHEN** a request for `/public/session` reaches the external API host
- **THEN** the edge answers 404 and the backend never sees it

#### Scenario: Path rules do not collide
- **WHEN** the Ingress for the external API host is rendered
- **THEN** a test checks every proxy route and every blocked route against the rules, taking the cloudflared rules (unanchored regular expression, longest first) into account

### Requirement: Migrations run on backend start
The backend SHALL run its Flyway migrations on start, connecting to PostgreSQL directly or through a
session-mode pooler (never a transaction-mode pooler), and the backend `startupProbe` SHALL allow for a
long migration. A migration SHALL be compatible with the previous release for one rolling update
(expand/contract); a release that cannot be SHALL say in its notes that `strategy: Recreate` is needed.

#### Scenario: Two replicas start together
- **WHEN** two new backend pods start against a database that needs a migration
- **THEN** one applies it, the other waits for the lock, and both become ready

### Requirement: Evidence scanning is part of the deployment
A ClamAV `clamd` SHALL be reachable by the backend inside the namespace and keep its signature database
on a persistent volume. The evidence upload size limits SHALL be at or below what the public edge accepts.

#### Scenario: Scanner restarts
- **WHEN** the ClamAV pod is rescheduled
- **THEN** it starts from the stored signatures and becomes ready without downloading the whole database

### Requirement: Dev follows main, prod is pinned
The dev environment SHALL follow new images from `main` through Flux image automation, and prod SHALL
reference an exact chart version whose images have the same version.

#### Scenario: Dev rollout
- **WHEN** a commit lands on `main`
- **THEN** images tagged `main-<commit unix time>-<sha7>` are pushed and dev is updated by image automation

#### Scenario: Prod rollout
- **WHEN** an operator raises the prod chart pin to a released version
- **THEN** prod runs the images of that version and no other
