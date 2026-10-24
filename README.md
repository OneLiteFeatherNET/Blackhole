# Blackhole

Blackhole is a ban system for a single Minecraft network, built to be GDPR-compliant by design.
One backend deployment serves one network's staff, players, punishment history and
integrations, while keeping personally identifiable data (IPs, raw player UUIDs) hashed or
tokenized wherever it's stored or published.

Punishment decisions are driven by a **dual-ELO system**: every player carries a separate
`chatElo` and `gameplayElo` score. Reports and a pluggable chat-toxicity scorer feed deltas into
these scores, and crossing a configurable soft/hard threshold automatically applies a temporary
or permanent punishment — without requiring a moderator to be online. A structured appeal
workflow lets players contest both automatic and manual punishments.

## Architecture

| Module     | What it is                                                                                  |
|------------|----------------------------------------------------------------------------------------------|
| `backend`  | The Micronaut HTTP API — punishments, reports, ELO, appeals, dashboard.                     |
| `velocity` | A Velocity proxy plugin that enforces punishments and feeds chat/session data to the backend. |
| `client`   | A Java API client generated from `client/specs/blackhole-api-*.yml` via OpenAPI Generator.   |
| `phoca`    | Shared utility library used across modules.                                                  |
| `web`      | The admin web UI (Vue 3, Material Design 3): dashboard, players, templates, sections, reports, appeals. |

The backend is event-driven: RabbitMQ backs a domain event bus (`blackhole.events`) that other
services can consume. Hot reads (punishment profiles, ELO profiles, templates and player
resolutions) are served from one shared Redis-protocol cache (Dragonfly in production), so every
replica reads and evicts the same entries and the API can be scaled horizontally.

Every route except `GET /health` needs a Microsoft Entra ID access token; see
[Authentication](#authentication).

### Admin web UI

`web/` is a browser UI for admins and staff. Run it with `cd web && npm ci && npm run dev` (proxies
`/api/` to a backend on `localhost:8080`), or as a container with `docker compose -f
docker/docker-compose.yml up -d web` (http://localhost:8081). Staff sign in with Entra ID. See
[web/README.md](web/README.md) for the container variables and development.

### Deployment on Kubernetes

The Helm chart in [`deploy/charts/blackhole`](deploy/charts/blackhole/README.md) runs the backend, the admin
panel, the appeal portal, the Discord bot and a ClamAV scanner; the images and the chart are published to Harbor
by the release workflow. The operator runbook is [docs/kubernetes.md](docs/kubernetes.md).

### Tech stack

- **Backend**: Java 21+, [Micronaut](https://micronaut.io/), Hibernate/JPA, Flyway (PostgreSQL),
  RabbitMQ, Dragonfly (Redis protocol) as the shared cache, Micrometer + OpenTelemetry.
- **Proxy plugin**: [Velocity](https://velocitypowered.com/).
- **Build**: Gradle (Kotlin DSL), multi-module.
- **Releases**: [release-please](https://github.com/googleapis/release-please), driven by
  [Conventional Commits](https://www.conventionalcommits.org/) — see [Contributing](#contributing).

## Authentication

Blackhole signs staff in with Microsoft Entra ID (the Free tier is enough). Every route except
`GET /health` needs an access token as `Authorization: Bearer`; the backend verifies it against the
tenant's published keys and keeps no session. There is no setting that turns authentication off.

- **Staff** sign in to the admin UI (Authorization Code flow with PKCE). Their role comes from the
  `groups` claim: the object ids of the Entra security groups are mapped to roles with
  `BLACKHOLE_AUTH_ROLE_ADMIN`, `_MODERATOR`, `_VIEWER` and `_METRICS`.
- **The Velocity plugin** and a **Prometheus scraper** use app-only tokens (client credentials) whose
  `roles` claim holds the app role `Blackhole.Proxy` or `Blackhole.Metrics`.
- A signed-in person in no mapped group has no role: every protected route answers 403 and the UI shows
  a "no access" page. `GET /auth/me` returns the caller's display name and roles.

| Role | May |
|------|-----|
| `VIEWER` | read everything |
| `MODERATOR` | and: apply, revoke and adjust punishments, resolve reports, decide appeals, add evidence |
| `ADMIN` | and: configure sections and templates, delete evidence and profiles, import, anonymize staff data |
| `PROXY` | the routes the proxy needs (profiles, punishments, reports, evidence) with `onBehalfOf` |
| `METRICS` | `GET /prometheus` |

The full route matrix is in `openspec/changes/archive/2026-10-23-add-team-sso/design.md` and enforced by a test.

**Verified actor.** The actor of every write is the `oid` of the token and is stored as
`recorded_by_oid`; the former client-supplied `source`, `resolvedBy` and `reviewerId` fields are gone
(an old client that still sends them is ignored). Rows written before this release have no
`recorded_by_oid` and are shown as unverified. The proxy may send `onBehalfOf` for actions that
staff took in game; see [Linking a Minecraft account](#linking-a-minecraft-account) for how an in-game
action is traced to a person. Staff cannot review their own punishment.

**Staff data and retention.** *(Pending legal confirmation.)* Stored per staff member: `oid`,
`display_name`, the linked Minecraft account (see below) and timestamps only. Retention: as long as
records referencing the person exist (punishment history is itself retained); a link lasts as long as
the person is staff and ends when it is removed or the person is anonymized. On a leaving or erasure
request an admin calls `POST /admin/staff-actor/{oid}/anonymize`, which nulls `display_name` and
removes the person's Minecraft link (the `oid` stays; once the Entra account is deleted it no longer
resolves to a person). The purpose of the link is accountability of moderation decisions.

### Linking a Minecraft account

A punishment issued in game by `/ban` and the like reaches the backend from the proxy, which only
knows a Minecraft UUID. Staff link that UUID to their sign-in once, so that the action is attributed to
the person and not to a bare UUID.

1. Sign in to the admin UI, open **Konto** (en: *Account*) in the top bar and choose **Minecraft-Konto
   verknüpfen** (*Link Minecraft account*). The panel shows a code of 8 characters (for example
   `ABCD-EFGH`) with a countdown.
2. In game, with the account to link, run `/blackhole link <code>`. A code is single use and valid for
   `BLACKHOLE_STAFF_LINK_TTL` (default `PT5M`); issuing a new one invalidates the previous one.
3. Check with `/blackhole whoami`, which names the staff account the Minecraft account is linked to.

Both commands are player-only and need the Velocity permission `blackhole.link`.

**Operator rule: grant `blackhole.link` to the LuckPerms groups of staff only**, never to a default or
player group. The code proves that the person typing it holds the staff member's session, but the
protection against someone else guessing a live code rests on the permission: there is deliberately no
failed-attempt limit in the application (guessing is bounded by the 40 bits of the code and its lifetime;
rate limiting belongs to the infrastructure). Anyone who may run the command may try codes.

- One person has one Minecraft account and one account belongs to one person. Linking another account
  replaces the old link, and the replacement is published as an event.
- **Attribution.** From the link on, in-game punishments, reports and evidence created by that account
  carry the person (`actor_oid`), and the punishment timeline in the UI shows the name.
- **No backfill.** Linking does not rewrite earlier entries: the account was only proven to belong to the
  person now, and history stays as it was recorded. Older entries keep showing the Minecraft UUID.
- **Self-review.** A person cannot decide the appeal of a punishment they issued: the check compares the
  reviewer with the recorded actor, with `actor_oid` and with the current link of the punishment's
  issuer, so it also covers in-game punishments from before the link existed. Another moderator can
  decide it.
- **Unlinking.** Staff remove their own link in the same panel (`DELETE /staff-link/me`); an admin lists
  all links under *Linked Minecraft accounts* (`GET /admin/staff-link`) and removes one
  (`DELETE /admin/staff-link/{oid}`). Earlier entries keep their attribution. Anonymizing a person
  removes the link as well.
- The code and its hash never appear in logs or events; `staff-link.created` and `staff-link.removed`
  carry the `oid` only.

**Setup.** [docs/entra-setup.md](docs/entra-setup.md) explains the app registrations and groups and
ships `docs/entra-setup.sh`, an idempotent Azure CLI script.

### Migrating an existing deployment

This is a breaking release: callers without a token get 401. Move everything in one window, in this
order (between steps 2 and 4 the plugin and UI cannot reach the backend):

1. **Entra.** Register the apps and groups ([docs/entra-setup.md](docs/entra-setup.md)).
2. **Backend.** Set `BLACKHOLE_AUTH_TENANT_ID`, `BLACKHOLE_AUTH_AUDIENCE` (API client id),
   `BLACKHOLE_AUTH_ISSUER` (`https://login.microsoftonline.com/<tenant-id>/v2.0`),
   `BLACKHOLE_AUTH_JWKS_URL` (`https://login.microsoftonline.com/<tenant-id>/discovery/v2.0/keys`) and
   `BLACKHOLE_AUTH_ROLE_ADMIN`, `_MODERATOR`, `_VIEWER`, `_METRICS` (group object ids). The defaults
   point at a local mock server and are refused outside dev. Optional: `BLACKHOLE_AUTH_ROLES_CLAIM_HUMAN`
   (`groups`), `BLACKHOLE_AUTH_ROLES_CLAIM_MACHINE` (`roles`), `BLACKHOLE_AUTH_MACHINE_ROLE_PROXY`,
   `BLACKHOLE_AUTH_MACHINE_ROLE_METRICS`, `BLACKHOLE_DOCS_ENABLED` (Swagger UI, default `false`), `BLACKHOLE_STAFF_LINK_TTL` (link code lifetime,
   default `PT5M`). The database migrations (`V9`, `V10`) run on start and keeps old actor values.
3. **Velocity plugin.** Add the `auth` object to `config.json`:
   `{"auth": {"tenantId": "", "clientId": "", "clientSecret": "", "scope": "api://<api-client-id>/.default"}}`.
   Each key can be overridden by `BLACKHOLE_TENANT_ID`, `BLACKHOLE_CLIENT_ID`,
   `BLACKHOLE_CLIENT_SECRET` and `BLACKHOLE_AUTH_SCOPE`, which win over the file; prefer the environment
   for the secret. `auth.tokenUrl` (`BLACKHOLE_TOKEN_URL`) overrides the token endpoint for a mock server.
   With incomplete credentials the plugin logs an error and keeps enforcing from Redis, but backend-bound
   commands are refused. Grant the permission `blackhole.link` to the staff LuckPerms groups so that staff
   can [link their Minecraft account](#linking-a-minecraft-account); grant it to nobody else.
4. **Admin UI.** Set `BLACKHOLE_UI_OIDC_AUTHORITY`, `BLACKHOLE_UI_OIDC_CLIENT_ID` and
   `BLACKHOLE_UI_API_SCOPE` on the web container (required; it refuses to start without them) and drop
   `BLACKHOLE_UI_SOURCE` and `BLACKHOLE_UI_REVIEWER`.
5. **Other callers.** Any script that calls the API directly now needs a token. Regenerate clients from
   the current `client/specs/blackhole-api-*.yml`. Prometheus scrapes `/prometheus` with a
   `Blackhole.Metrics` token.

## Staff workflow

Staff never decide that someone is banned forever. They apply a **temporary** punishment from a
template; each application costs ELO in the template's **section**, and when a section score falls
below its permanent threshold the system itself replaces the active punishment with a permanent one.

### Sections

A section owns one ELO score per player, its thresholds, its permanent-ban reason and its pardon
score, and says how it is enforced:

| Enforcement | Effect on the proxy                                                                |
|-------------|-------------------------------------------------------------------------------------|
| `BAN`       | The player cannot log in.                                                           |
| `MUTE`      | The player's chat messages are not delivered.                                       |
| `SERVER`    | The player cannot connect to backend servers the section targets: glob patterns, or CloudNet [tasks, groups or services](#cloudnet-sections). |

Two sections are seeded: `chat` (MUTE) and `network` (BAN). Operators add more through
`POST /section`, for example `bedwars` as SERVER with the pattern `bedwars-*` (`*` matches any run
of characters, `?` one character, case-insensitive). A player stays on the network when a SERVER
section punishes them; if they stand on a matching server when it arrives they are moved to the
`fallbackServer` of the proxy config (default `lobby`), or disconnected if there is none.
There is one active punishment per player and section: a mute never lifts a ban.

### CloudNet sections

A `SERVER` section normally lists glob patterns for the Velocity server names. On a network that runs
[CloudNet](https://cloudnetservice.eu/) it can instead name CloudNet **tasks, groups or single
services**, so a ban on `BedWars` covers every `BedWars-<n>` instance without a pattern.

**Requirements.** CloudNet 4.0.0-RC17 or newer (4.x has no stable release yet, all versions are
release candidates), and Velocity running as a CloudNet service with the Bridge module, which
registers every started CloudNet service in Velocity under its service name. The plugin only reads
CloudNet's driver API; it does not bundle it and the backend never talks to CloudNet. Without
CloudNet the plugin behaves as before and `PATTERN` sections keep working.

**Target kinds.** A section has one `targetKind` (set it through `POST /section` or the "Target"
selector of the admin UI); `servers` then holds the patterns or the exact names:

| `targetKind`       | UI label       | `servers` holds         | Covers a server when                                 |
|--------------------|----------------|-------------------------|------------------------------------------------------|
| `PATTERN` (default)| Namensmuster   | glob patterns (`bedwars-*`) | the pattern matches the server name (needs no CloudNet) |
| `CLOUDNET_TASK`    | CloudNet-Task  | exact task names (`BedWars`) | the service belongs to one of the tasks              |
| `CLOUDNET_GROUP`   | CloudNet-Gruppe | exact group names (`Games`) | the service is in one of the groups                  |
| `CLOUDNET_SERVICE` | CloudNet-Service | exact service names (`BedWars-3`) | the service name equals one of the names        |

CloudNet names are letters, digits, `_`, `.` and `-` without wildcards, and are compared ignoring
case. The backend does not check them against CloudNet: a section may name a task that does not
exist yet, and then simply covers nothing. The admin UI lists the names that the proxies report
and marks a name that is not among them as "Nicht im Katalog" / "Not in catalogue"; it also accepts
typed names, and it is the only way to enter names when no catalogue has been reported.

**Service names are not stable.** A `CLOUDNET_SERVICE` target is the service *name*. After a restart
the name can belong to a different instance (dynamic services get the lowest free number), so the
ban follows the name, not the instance, and survives restarts. Use a task or a group for lasting
bans; the UI shows this warning when you pick the service kind.

**Unknown servers are not covered (fail open).** If CloudNet does not know the server a player is
connecting to, for example a server registered by hand in `velocity.toml`, or the plugin runs without
CloudNet, a CloudNet section does not match and the player may connect. The proxy logs a warning once
per server name (and at startup when CloudNet sections exist but CloudNet is unavailable). This is
deliberate: the same rule as for a state outage, where an outage never locks players out, and the
Bridge only registers CloudNet services, which CloudNet knows. Cover hand-registered servers with a
`PATTERN` section. `BAN` and `MUTE` sections are network-wide and unaffected. Editing a section does
not move players who are already online; a player who stands on a server that a new punishment
covers is moved to the `fallbackServer`, or disconnected if the fallback is covered too.

**Configuration** (the `cloudnet` object in the plugin's `config.json`):

```json
{
  "cloudnet": {
    "integration": "auto",
    "proxyId": "",
    "reportDebounce": "PT5S",
    "heartbeat": "PT5M"
  }
}
```

| Key              | Default | Meaning                                                                                       |
|------------------|---------|------------------------------------------------------------------------------------------------|
| `integration`    | `auto`  | `auto` uses CloudNet when the plugin runs as a CloudNet service; `off` never probes for it. Any other value counts as `auto`. The environment variable `BLACKHOLE_CLOUDNET_INTEGRATION` wins over the file. |
| `proxyId`        | empty   | The id this proxy reports its catalogue under. Empty: the proxy's own CloudNet service name (`Proxy-1`). Set it when the proxy has none; without either, nothing is reported (one warning). |
| `reportDebounce` | `PT5S`  | How long changes are coalesced before one report goes out (ISO-8601, at least one second).       |
| `heartbeat`      | `PT5M`  | Keep-alive period of the report (ISO-8601, at least one second).                                 |

A missing or invalid value falls back to its default.

**Catalogue.** For the picker in the admin UI each proxy reports its CloudNet tasks, groups and
services to the backend (`PUT /cloudnet/catalogue`, always a full snapshot): at startup, shortly
after any change (debounced by `reportDebounce`) and as a heartbeat every `heartbeat` (5 minutes).
The backend keeps one snapshot per proxy in the shared cache for 15 minutes
(`blackhole.cloudnet.catalogue.ttl`, default `PT15M`), so the entries of a stopped proxy disappear on
their own, and `GET /cloudnet/catalogue` merges the live ones. A failed report is only logged and
never affects enforcement; if the cache is down the UI falls back to free text.

| Route                          | `VIEWER` | `MODERATOR` | `ADMIN` | `PROXY` |
|--------------------------------|:--------:|:-----------:|:-------:|:-------:|
| `PUT /cloudnet/catalogue`      | no       | no          | no      | yes     |
| `GET /cloudnet/catalogue`      | yes      | yes         | yes     | no      |

Only the proxy may report, so a human token cannot forge what the editor trusts; any signed-in staff
role may read the catalogue (it holds service names, no player data). Creating and editing sections
stays `ADMIN`-only.

**Rollout.** Deploy the backend first (the new `target_kind` column defaults to `PATTERN`), then
upgrade the proxies, and only then create CloudNet sections. A proxy from before this release reads
the names of a CloudNet section as globs, which do not match `BedWars-1`, so it does not enforce them.
Delete or disable CloudNet sections before rolling back.

### Templates and escalation

A template has a `key` (what staff type), a `section` and ordered **steps**, each with a `duration`
(ISO-8601, greater than zero) and an `eloCost` (zero or more). The step is chosen by how many earlier
punishments the player has in the template's escalation group (default: the template key) within the
escalation window (default 365 days, `blackhole.punishment.escalation.default-window`) that were not
revoked or lifted by an appeal; beyond the last step the last step repeats. Applying a template
deducts the step's cost; if the section score is then below its permanent threshold and no permanent
punishment is active, the player is punished permanently. A staff revoke of a temporary punishment
does **not** refund its ELO cost; a granted appeal that fully lifts it does. Lifting a permanent
punishment (revoke or appeal) sets the section score to the section's pardon score.

### Proxy commands

| Command                              | Permission                      | What it does                                                        |
|--------------------------------------|---------------------------------|---------------------------------------------------------------------|
| `/ban <player> <template>`           | `blackhole.ban`                 | Applies a template of a `BAN` or `SERVER` section.                  |
| `/mute <player> <template>`          | `blackhole.mute`                | Applies a template of a `MUTE` section.                             |
| `/punish <player> <template>`        | `blackhole.punish`              | Applies a template of any section.                                  |
| `/unban <player> [section]`          | `blackhole.unban`               | Lifts the active ban (section defaults to `network`; `BAN`/`SERVER`). |
| `/unmute <player> [section]`         | `blackhole.unmute`              | Lifts the active mute (section defaults to `chat`).                 |
| `/elo <player>`                      | `blackhole.elo.view`            | Shows the score, thresholds and permanent state of every section.   |
| `/blackhole <player> info`           | `blackhole.user.info`           | Shows the active punishments.                                       |
| `/blackhole <player> history <sort>` | `blackhole.ban.history`         | Shows the punishment history.                                       |
| (lifting a permanent punishment)     | `blackhole.pardon.permanent`    | Additionally required by `/unban` and `/unmute` for permanent ones. |

`<template>` is the template key, tab-completed from the templates eligible for the command. The
player may be offline (a name the backend can resolve, or a UUID). No command accepts a duration or a
permanent flag. The former `--server` flag is gone: use a `SERVER` section. After applying, the sender
sees the section, how long it lasts, the step (`n/m`), the ELO change and, if the cost triggered it,
the permanent escalation. Upgrading from earlier releases: templates are selected by key instead of
by reason, and the proxy reads the Redis keys `blackhole:punish:<section>:<owner>`; deploy backend and
proxies together.

## Punishment evidence

Staff and tools can attach evidence to a punishment: anticheat records, chat excerpts, replay links
and uploaded screenshots or videos. Evidence can be sent before the punishment exists (with an owner
hash and a `correlationId`) and is claimed when the punishment is applied with the same
`evidenceCorrelationId`. Punishments and appeals only expose an `evidenceCount`; the content is read
through `/punishment/{reference}/evidence` and `/evidence/{id}` (see the Swagger UI).

### Storage options

Uploaded files go through a `BlobStore`. S3-compatible object storage is the default
(`BLACKHOLE_EVIDENCE_FILES_STORAGE=s3`); a directory on disk is available for tiny single-replica
setups (`local`). The docker compose stack runs SeaweedFS for development.

| Server                          | Licence    | Notes                                                                              |
|---------------------------------|------------|------------------------------------------------------------------------------------|
| SeaweedFS                       | Apache-2.0 | Used in `docker/docker-compose.yml`; the documented self-hosted choice.            |
| RustFS                          | Apache-2.0 | Upstream does not recommend it for production yet.                                 |
| Ceph RGW                        | LGPL-2.1   | Production grade but heavy; fine if you already run Ceph.                          |
| Garage                          | AGPL-3.0   | Works through the same adapter; not recommended here because of the licence.       |
| MinIO                           | AGPL-3.0   | Community edition is archived upstream (2026); not recommended.                    |
| AWS S3, Cloudflare R2, Backblaze B2, ... | n/a | Operator's choice; set `BLACKHOLE_EVIDENCE_S3_ENDPOINT` (empty for AWS S3).        |

Every upload is scanned by ClamAV (`clamd`) before it is stored. The scan fails closed: while it is
enabled, an infected file is rejected (422) and an unreachable scanner rejects the upload (503).
ClamAV needs about 1 GB of RAM and downloads its signature database on first start, which takes a few
minutes. Only set `BLACKHOLE_EVIDENCE_SCAN_ENABLED=false` if uploads are scanned elsewhere.

| Variable                                    | Purpose |
|---------------------------------------------|---------|
| `BLACKHOLE_EVIDENCE_FILES_STORAGE`          | `s3` (default) or `local`. |
| `BLACKHOLE_EVIDENCE_S3_ENDPOINT`            | S3 endpoint; empty for AWS S3. |
| `BLACKHOLE_EVIDENCE_S3_BUCKET`              | Bucket name (default `blackhole-evidence`). |
| `BLACKHOLE_EVIDENCE_S3_REGION`              | S3 region. |
| `BLACKHOLE_EVIDENCE_S3_ACCESS_KEY`          | S3 access key. |
| `BLACKHOLE_EVIDENCE_S3_SECRET_KEY`          | S3 secret key. |
| `BLACKHOLE_EVIDENCE_S3_PATH_STYLE`          | Path-style addressing (needed by most self-hosted servers). |
| `BLACKHOLE_EVIDENCE_FILES_LOCAL_PATH`       | Directory for the `local` backend. |
| `BLACKHOLE_EVIDENCE_FILES_MAX_IMAGE_BYTES`  | Largest image upload in bytes. |
| `BLACKHOLE_EVIDENCE_FILES_MAX_VIDEO_BYTES`  | Largest video upload in bytes; raise `BLACKHOLE_MAX_REQUEST_SIZE` with it. |
| `BLACKHOLE_EVIDENCE_FILES_MAX_IMAGE_PIXELS` | Images with more pixels are rejected before decoding. |
| `BLACKHOLE_EVIDENCE_FILES_ALLOWED_TYPES`    | Allowed types (PNG, JPEG, MP4, WebM); remove `video/*` to disable video. |
| `BLACKHOLE_EVIDENCE_SCAN_ENABLED`           | Virus scan of uploads (default on). |
| `BLACKHOLE_EVIDENCE_SCAN_CLAMD_HOST`        | Host of the ClamAV `clamd`. |
| `BLACKHOLE_EVIDENCE_SCAN_CLAMD_PORT`        | Port of `clamd` (default 3310). |
| `BLACKHOLE_EVIDENCE_SCAN_CLAMD_TIMEOUT`     | How long one scan may take (default `PT30S`). |
| `BLACKHOLE_EVIDENCE_MAX_PAYLOAD_BYTES`      | Largest structured payload in bytes. |
| `BLACKHOLE_EVIDENCE_MAX_CHAT_LINES`         | Most lines of one chat log item. |
| `BLACKHOLE_EVIDENCE_PENDING_TTL`            | How long unclaimed evidence is kept (default `P1D`). |
| `BLACKHOLE_EVIDENCE_HOUSEKEEPING_INTERVAL`  | How often the housekeeping sweep runs (default `PT1H`). |
| `BLACKHOLE_EVIDENCE_AUDIT_RETENTION`        | Purge audit rows older than this; empty keeps them (default). |

### Lifetime and access

- **Evidence lives as long as its punishment record.** There is no time-based expiry. It is deleted
  when the punishment record is deleted, or by an admin through `DELETE /evidence/{id}` or
  `DELETE /evidence/owner/{owner}`, which require a `reason`, are audited and publish
  `evidence.deleted`. The housekeeping sweep only removes evidence nobody claimed within
  `BLACKHOLE_EVIDENCE_PENDING_TTL` and stored files without a database row.
- When staff revoke a punishment, its evidence is marked `REVOKED`; when an appeal fully lifts it,
  the evidence is marked `LIFTED`. Nothing is deleted. Such evidence is not counted and is hidden
  unless a request passes `includeInactive=true`; revealing it is audited.
- **Access follows the role.** Every reader (`VIEWER` and up) can read all evidence, including lifted
  and revoked evidence through `includeInactive=true`; the default hiding is not access control, but
  revealing it is audited with the verified actor.
- **Video metadata is not stripped.** Images are re-encoded, which drops EXIF/GPS data; MP4 and WebM
  files are stored as uploaded. Ask staff to prefer screen recordings of the game, or disable video.

### Capacity planning

Because evidence is never expired, storage grows with the punishment history. Size the bucket or
volume for the expected history (for example the 100 MiB video limit times the videos you expect per
month times how long you keep punishment history), watch its usage, and lower
`BLACKHOLE_EVIDENCE_FILES_MAX_VIDEO_BYTES` or disable video if needed. **Do not use S3 lifecycle
expiry rules on the bucket**: deleting objects behind the application's back leaves rows that point at
missing files and breaks the audit trail. Delete through the admin path instead.

### Notes for operators (GDPR)

The software ties evidence to the punishment record; you decide how long the record is kept. You
should:

- document the retention period of your punishment history and its justification, and the lawful
  basis for storing evidence (typically legitimate interest, Art. 6(1)(f), with a balancing test);
- name the evidence categories (chat excerpts, recordings, anticheat data, links) in your privacy
  notice and your records of processing (Art. 30);
- answer access requests (Art. 15) with `GET /evidence?owner=<hash>&includeInactive=true`, and
  erasure or objection requests (Art. 17, 21) through the admin delete path, deciding each case
  individually (for example when the evidence is needed for legal claims).

Evidence refers to players only by hash or per-evidence alias; raw UUIDs and IP addresses in payloads
are rejected, and IP addresses in chat text are redacted.

### Muting and signed chat (SignedVelocity)

Since Minecraft 1.19.1 chat is signed, and Velocity disconnects a player whose signed message a
plugin cancels ("A plugin tried to cancel a signed chat message"). To block a muted player's chat
without that, the proxy plugin works together with
[SignedVelocity](https://github.com/4drian3d/SignedVelocity) by 4drian3d. It has no API to call:
Blackhole denies the chat event as usual, and SignedVelocity, which runs after all other handlers,
turns that into an "allowed" message plus a cancel order that its backend plugin carries out.

1. Install `SignedVelocity-Proxy` in the Velocity `plugins` folder.
2. Install `SignedVelocity-Paper` in the `plugins` folder of **every** backend server (Sponge,
   Fabric and Minestom builds exist as well). A server without it would still show the message.
3. Restart the proxy and the servers.

Required versions (SignedVelocity 1.5.0): Java 21+, Velocity 3.4.0+, Paper 26.1.2+ on the backend.
Releases: <https://github.com/4drian3d/SignedVelocity/releases>.

SignedVelocity is an optional dependency of Blackhole. Without it the proxy logs one warning at
startup and never cancels a signed message (so nobody is kicked): a muted player is still shown the
mute notice, but their message is delivered. Messages from clients older than 1.19.1 are unsigned
and are always denied directly. Blackhole does not block commands for muted or banned players, so
signed commands need no handling.

### Chat capture on the proxy (optional)

The Velocity plugin can attach the punished player's recent chat as `CHAT_LOG` evidence, so staff do
not have to screenshot. **It is off by default.** Enable it in the plugin's `config.json`:

```json
{
  "evidence": {
    "chatCapture": {
      "enabled": true,
      "lines": 20,
      "contextLines": 5,
      "maxAge": "PT10M",
      "sourceSystem": "velocity",
      "timeout": "PT2S"
    }
  }
}
```

| Key            | Default    | Meaning                                                                                              |
|----------------|------------|-------------------------------------------------------------------------------------------------------|
| `enabled`      | `false`    | Buffer public chat at all. Off: nothing is stored, the listener is not even registered.               |
| `lines`        | `20`       | Public chat lines kept per player, 1 to 50.                                                           |
| `contextLines` | `5`        | Lines of other players that preceded the punished player's lines, 0 to 50; `0` switches the third-party context off. |
| `maxAge`       | `PT10M`    | How long a line stays buffered (ISO-8601 duration).                                                   |
| `sourceSystem` | `velocity` | The `sourceSystem` of the evidence.                                                                   |
| `timeout`      | `PT2S`     | How long the proxy waits for the backend (uploading evidence, reading the evidence count).            |

A missing or invalid value falls back to its default. How it works:

- The buffer lives in the proxy's memory only: it is never written to disk or logged, is lost on
  restart and is dropped when the player disconnects. Commands and messages that were not delivered
  (a muted player's) are never buffered.
- On `/ban`, `/mute` or `/punish` the proxy takes the target's lines and up to `contextLines`
  preceding lines of other players on the same server. Every speaker, the target included, is
  replaced by an alias (`Spieler A`, `Spieler B`, ...) assigned in order of first appearance within
  that one evidence item, so aliases cannot be matched across items; names, UUIDs and hashes never
  leave the proxy in the lines. URLs, IP addresses and UUIDs in the text are replaced by `[url]`,
  `[ip]` and `[uuid]`, and a line is cut at 256 characters. Names that players type into their text
  cannot be recognised and are kept.
- The evidence is uploaded first, under a fresh `correlationId`, and the punishment then claims it
  with `evidenceCorrelationId`. If the upload fails or exceeds `timeout`, the punishment is applied
  anyway and the staff member is warned; evidence of a punishment that then failed expires on the
  backend (`pending-ttl`).
- A punished player sees how many pieces of evidence exist on the login and chat denial screens
  ("Beweise: 3" / "Evidence: 3"), never their content. The count is read from
  `GET /punishment/{reference}/evidence/count`, cached for 30 seconds (also when it failed) and left
  out if the call fails or times out. This works for evidence from any source, whether or not chat
  capture is enabled.

**GDPR.** Enabling capture means the proxy temporarily processes everyone's public chat, including
third parties' lines used as context. Before enabling it you should: rely on a lawful basis (typically
legitimate interest, Art. 6(1)(f), with a documented balancing test; the plugin does not make that
decision for you), mention chat capture for moderation and its short RAM-only buffering in your
privacy notice and records of processing (Art. 30), and decide whether the third-party context is
needed (`contextLines: 0` switches it off). Evidence created from it follows the retention rules above.

## Local development

A `docker-compose.yml` under `docker/` provides PostgreSQL, RabbitMQ and Dragonfly (Redis protocol,
used for proxy sync and the shared cache), plus SeaweedFS and ClamAV for evidence uploads (ClamAV needs
about 1 GB of RAM and a few minutes on its first start):

```shell
cd docker
docker compose up -d
```

Then run the backend against the real Entra dev registration (create it once with
`docs/entra-setup.sh dev`, which writes `~/.config/blackhole/entra-dev.env`):

```shell
set -a; . ~/.config/blackhole/entra-dev.env; set +a
./gradlew :backend:run
```

For the admin UI see [web/README.md](web/README.md#local-development-with-entra-id). Tests never reach
Entra: they use self-signed JWTs. The compose file also starts a mock OIDC server (port 18090) whose
values are the defaults of `application.yml`, for working offline.

Key environment variables (all have local-dev defaults, see
`backend/src/main/resources/application.yml` for the full list):

| Variable                          | Purpose                                                             |
|------------------------------------|-----------------------------------------------------------------------|
| `JDBC_URL` / `JDBC_USER` / `JDBC_PASSWORD` | PostgreSQL connection (defaults match `docker/docker-compose.yml`). |
| `RABBITMQ_HOST` / `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | RabbitMQ connection.                              |
| `REDIS_URI`                       | Redis-protocol server (Dragonfly) for proxy sync and the shared cache. |
| `BLACKHOLE_EVASION_IP_SALT`       | Required for ban-evasion detection; that feature returns `503` until it's set. |
| `BLACKHOLE_AUTH_*`                | Entra ID sign-in, see [Authentication](#authentication). |

Swagger UI is served at `/swagger/views/swagger-ui` when `BLACKHOLE_DOCS_ENABLED=true` (off by default).

### Building

```shell
./gradlew clean build
```

### Upgrading from the in-process cache

- Run Dragonfly in place of Redis: the compose service now uses the Dragonfly image. `REDIS_URI`
  is unchanged, so proxies keep working. Deploy the backend and the proxies together.
- The `blackhole.cache.invalidate` fanout exchange is no longer declared or used. Existing
  RabbitMQ brokers keep it as an orphan exchange, which is harmless and can be deleted.
- Cache keys are versioned (`blackhole:cache:v1:`), so entries written by an older release are
  never read and expire on their own. Rolling back to the previous release is safe.

## Contributing

Commit messages and pull request titles must follow
[Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `chore:`, ...) —
this is enforced in CI (`.github/workflows/pr-lint.yaml`) and is what `release-please` uses to
determine the next version and generate `CHANGELOG.md`. Since this repository only allows squash
merges, the PR title becomes the commit on `main`, so it's linted the same as individual commits.

Allowed types: `build`, `chore`, `ci`, `docs`, `feat`, `fix`, `perf`, `refactor`, `revert`,
`style`, `test`.

## License

Blackhole is licensed under the [GNU Affero General Public License v3.0](LICENSE).
