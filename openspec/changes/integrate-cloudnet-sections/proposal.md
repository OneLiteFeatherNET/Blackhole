# Proposal

## Why

The network runs its backend servers on CloudNet v4. A SERVER section today targets servers by
glob over the Velocity server name (`bedwars-*`). That works while the naming scheme is stable,
but staff think in CloudNet terms: "ban him from BedWars" means the **task** `BedWars`, "ban him
from all minigames" means the **group** `Minigames`, and sometimes one **service** (`BedWars-3`)
is misbehaving. Globs approximate this and break when a task is renamed or a service name does
not follow the pattern. This change lets a section target a CloudNet task, group or service
directly, with the existing templates, escalation steps and ELO unchanged.

## What Changes

- A SERVER section gets a **target kind**: `PATTERN` (today's globs, the default),
  `CLOUDNET_TASK`, `CLOUDNET_GROUP` or `CLOUDNET_SERVICE`. The section's `servers` list holds the
  patterns or the exact CloudNet names. Existing sections migrate to `PATTERN` unchanged.
  `/ban` still picks the section through the template; there is no ad-hoc `--task` flag.
- The Velocity plugin, when it runs as a CloudNet service with the Bridge module, reads tasks,
  groups and services **in-process** through the CloudNet v4 driver API (no network access from
  the backend to CloudNet, no CloudNet credentials, no SSRF surface), resolves the target server
  of a `ServerPreConnectEvent` to its task, groups and service name, and enforces CLOUDNET
  sections with it. Outside CloudNet the plugin works as before and CloudNet sections are inert
  (one warning is logged).
- The proxy **reports a catalogue** (tasks, groups, services) to a new `PROXY`-only backend
  endpoint (full snapshot, pushed on start, on service events debounced client-side, and as a
  heartbeat). The backend keeps it in the shared cache with a TTL; a new read endpoint feeds the
  admin UI.
- The admin UI section editor shows a **picker** for the chosen target kind, fed by the
  catalogue, with free-text entry when no catalogue exists. A service target shows a volatility
  warning.
- The deny screen names the task, group or service ("Du bist auf BedWars gesperrt" / "You are
  banned from BedWars"), en and de.
- Client spec `blackhole-api-0.0.17.yml` (section `targetKind`) and `0.0.18.yml` (catalogue).

Not breaking: `targetKind` is optional on write (default `PATTERN`) and additive on read. A proxy
older than this change would treat the names of a CLOUDNET section as globs and, for a plain task
name such as `BedWars`, not match `BedWars-1`: it fails open until upgraded (see design, rollout:
upgrade proxies before creating CloudNet sections).

Out of scope:
- Any backend-to-CloudNet connection, CloudNet REST API or credentials.
- Wildcards inside CloudNet names (use a `PATTERN` section for that).
- Mixing target kinds in one section (create two sections).
- Banning by CloudNet service unique id (decision in design) or ending a ban when a service stops.
- Moving players when a section's targets are edited while players are online (as for patterns).
- Other CloudNet features (permissions module, cloud commands, player management via Bridge).
- Per-actor identity beyond what `verified-actor` already provides.

## Commit type and PR plan

The change ships as small stacked pull requests, each of one Conventional Commits type:

1. `feat(sections): target cloudnet tasks, groups and services` (backend model, migration, API, client spec 0.0.17)
2. `feat(velocity): enforce cloudnet sections through the bridge api` (CloudNetView, enforcement, messages)
3. `feat(cloudnet): report the cloudnet catalogue to the backend` (endpoint, shared-cache storage, proxy reporter, client spec 0.0.18)
4. `feat(web): pick cloudnet targets in the section editor`
5. `docs(sections): document the cloudnet integration`

Archive: `docs(openspec): archive integrate-cloudnet-sections`.

## Capabilities

### New Capabilities

- `cloudnet-integration`: section target kinds, CloudNet resolution and enforcement rules, the
  optional-integration behaviour and the catalogue API.

### Modified Capabilities

- `proxy-enforcement`: CLOUDNET sections on server connect, move-off, deny screen text.
- `admin-web-ui`: the CloudNet target picker in the section editor.
- `staff-authentication`: permission matrix rows for the catalogue routes; the proxy role gains
  one route.

## Impact

- Backend `section/` (enum, validation, entity, DTO), new `cloudnet/` package (catalogue
  controller/service/DTO), Flyway `V11__section_target_kind.sql`, `CacheKeys`, route-coverage and
  permission-matrix tests.
- `client/specs/blackhole-api-0.0.17.yml` and `0.0.18.yml`, `client/build.gradle.kts` `inputSpec`.
- Velocity: new `cloudnet/` package, `ServerConnectListener`, `Enforcement`, `PunishmentScreens`,
  `SectionInfo`, config, `blackhole_{en,de}.properties`; `build.gradle.kts` gets compileOnly
  `eu.cloudnetservice.cloudnet:driver-api`.
- Web: `SectionsView`, API types, locales.
- Depends on `staff-temp-bans-elo-escalation` (sections, SERVER enforcement, `punishment_section_servers`
  are introduced there; archive it first so its deltas land in the main specs) and on
  `add-team-sso`/`verified-actor` (archived).
