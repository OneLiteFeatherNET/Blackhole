# Design

## Context

- A SERVER section lists glob patterns (`punishment_section_servers.pattern`); the backend
  validates them (`ServerPatterns`), the proxy matches them against the Velocity server name
  (`Enforcement.serverBan` -> `ServerPatterns.matchesAny`) on `ServerPreConnectEvent`
  (`ServerConnectListener`) and for the move-off when a SERVER punishment arrives
  (`Enforcement.coversServer`, `BlackholeConfig.getFallbackServer`).
- The section list reaches proxies through the shared cache (`blackhole:cache:v1:sections:all`,
  invalidated on change by `SectionChangeNotifier`) with the API as fallback (`SectionRegistry`).
- The Velocity plugin (`blackhole-velocity`, Velocity 4.3) is a normal Velocity plugin. The network
  runs the proxy as a CloudNet service; CloudNet's Bridge registers every started CloudNet service
  in Velocity under its **service name** (`proxyServer.registerServer(new ServerInfo(service.name(), ...))`,
  `VelocityBridgeManagement`), where `ServiceId.name()` is `taskName + nameSplitter + taskServiceId`
  (default `BedWars-3`).

## Goals / Non-Goals

**Goals:** ban by CloudNet task, group or single service through sections; no new network paths
(no backend -> CloudNet); optional integration; picker in the UI; testable without CloudNet.

**Non-Goals:** see the proposal. In particular no CloudNet REST client, no webhook/connector
framework, no per-player or per-instance (unique id) bans.

## CloudNet v4 facts (verified 2026-10-23)

| Fact | Source |
|---|---|
| Latest published 4.x is **`4.0.0-RC17`** (pre-release, 2026-08-09, requires Java 25 since RC15). **There is no stable 4.0.0 yet**; all 4.x tags are release candidates. | https://github.com/CloudNetService/CloudNet/releases ; `gh api repos/CloudNetService/CloudNet/releases` |
| Artifacts are on **Maven Central**, group `eu.cloudnetservice.cloudnet`: `driver-api`, `wrapper-jvm-api`, `bridge-api`, `node-api`, `bom`. For plugins the project documents `compileOnly 'eu.cloudnetservice.cloudnet:driver-api:<ver>'`. | https://repo1.maven.org/maven2/eu/cloudnetservice/cloudnet/driver-api/maven-metadata.xml ; RC14-RC17 release notes |
| API docs (Javadoc) for the pinned version. | https://cloudnetservice.eu/cloudnet/docs/4.0.0-RC17/ |
| In a service the API is obtained from the extension injection layer: `InjectionLayer.ext().instance(CloudServiceProvider.class)` (also `ServiceTaskProvider`, `GroupConfigurationProvider`, `EventManager`). `instance` is `@UnknownNullability`. | `eu.cloudnetservice.driver.inject.InjectionLayer` in `driver-api-4.0.0-RC17-sources.jar` |
| `ServiceTaskProvider.serviceTasks()` -> `ServiceTask` (`name()`, `groups()`); `GroupConfigurationProvider.groupConfigurations()` -> `GroupConfiguration` (`name()`); `CloudServiceProvider.services()` / `runningServices()` / `serviceByName(String)` -> `ServiceInfoSnapshot`. | same jar, package `eu.cloudnetservice.driver.provider` |
| `ServiceInfoSnapshot`: `serviceId()` (`taskName()`, `name()`, `uniqueId()`, `taskServiceId()`), `configuration().groups()`, `lifeCycle()` (`PREPARED`, `RUNNING`, `STOPPED`, `DELETED`). | `eu.cloudnetservice.driver.service.*` |
| Events: `CloudServiceLifecycleChangeEvent` (`lastLifeCycle()`, `newLifeCycle()`), `CloudServiceUpdateEvent`, `ServiceTaskAddEvent`/`ServiceTaskRemoveEvent`, `GroupConfigurationAddEvent`/`GroupConfigurationRemoveEvent`; listeners are objects with `@EventListener` methods registered via `EventManager.registerListener(Object)`. | `eu.cloudnetservice.driver.event.*` |
| Velocity server name == CloudNet service name (Bridge `VelocityBridgeManagement` registers `service.name()`), restricted to Java server environments. | https://github.com/CloudNetService/CloudNet (`modules/bridge/impl/.../platform/velocity/VelocityBridgeManagement.java`, nightly) |

Not verified here, to be checked in the manual task (6.2): the Velocity plugin id of the
`CloudNet-Bridge` plugin (we declare no hard dependency, see decision 4), and that the plugin class
loader of a Velocity plugin sees the driver classes the wrapper puts on the service classpath. The
docs site (docs.cloudnetservice.eu) was not reachable while researching; the Javadoc and source
jars above are the authority. Because 4.x has no stable release, the version is pinned in the
Gradle catalogue and bumped deliberately (Renovate PRs are reviewed against the RC notes).

## Decisions

### 1. Section model: a typed target kind, not prefixed patterns

Chosen: **(a)** a `targetKind` on SERVER sections. Rejected: **(b)** `task:BedWars` style prefixed
patterns.

Reasons: the backend can validate each kind with its own rules (globs for `PATTERN`, exact names
for CloudNet kinds); the UI can offer a kind selector and the matching picker; the proxy decides
how to resolve a server by switch, not by string parsing; a typo cannot silently turn a task ban
into a glob that matches nothing. Prefixes would also collide with legal glob characters in
future and leak the CloudNet concept into the stored data.

- Enum `ServerTargetKind { PATTERN, CLOUDNET_TASK, CLOUDNET_GROUP, CLOUDNET_SERVICE }`.
  `EnforcementKind` stays `MUTE | BAN | SERVER`: a CloudNet target is still "SERVER enforcement",
  the proxy implements one more way to decide whether a server is covered.
- One kind per section; `servers` (existing list, existing table
  `punishment_section_servers`) holds the patterns or the exact CloudNet names. The API keeps the
  field name `servers` so existing clients and the shared-cache JSON stay compatible; the new
  field is `targetKind` (optional on write, default `PATTERN`; always present on read).
- Validation (`ServerPatterns.validate` gains the kind): `targetKind` other than `PATTERN` is only
  allowed for SERVER sections; CloudNet names are non-blank, <= 64 chars, match
  `[A-Za-z0-9_.-]+` (no wildcards), at most 50 per section; a `CLOUDNET_SERVICE` name should carry
  the task/number shape but this is not enforced (a custom `nameSplitter` is legal). Names are
  compared case-insensitively, like Velocity looks servers up. Existing rules (>= 1 target for
  SERVER, none for other kinds) stay.
- Overlapping sections (a task section and a service section covering the same server) are allowed
  and a connect is denied if any matches, as for patterns today.
- **Flyway** (append-only): `V11__section_target_kind.sql` (next free number at implementation
  time) adds `punishment_sections.target_kind varchar(24) NOT NULL DEFAULT 'PATTERN'`. Existing
  rows get `PATTERN` through the default; no data is rewritten. The allowed values are enforced in
  the application layer (`ServerTargetKind`), per the project's no-CHECK rule. No new table.
- **DTO** (`SectionDTO`): `CreateRequest`/`UpdateRequest` gain `@Nullable ServerTargetKind targetKind`
  (null -> `PATTERN`), `Response` gains `@NonNull ServerTargetKind targetKind`. `SectionSpec`,
  `SectionEntity.apply`/`toResponse` carry it. The Redis section list changes shape additively; the
  cache key prefix `v1` stays because an old reader ignores the new field (it is the rollout caveat
  in decision 8, not a parse failure).
- **Client**: new spec `client/specs/blackhole-api-0.0.17.yml`, `inputSpec` in
  `client/build.gradle.kts` bumped; generated `SectionResponse` gets `targetKind`.

### 2. Resolving a server through CloudNet (proxy)

The proxy never calls CloudNet on the connect path in the normal case. A `CloudNetView` keeps an
immutable snapshot map `serverName (lower case) -> ServerFacts(serviceName, taskName, groups,
uniqueId)` built from `CloudServiceProvider.runningServices()` at startup and updated by
`CloudServiceLifecycleChangeEvent` (`RUNNING` adds or replaces, `STOPPED`/`DELETED` removes) and
`CloudServiceUpdateEvent` (replaces). Task and group lists come from `ServiceTaskProvider` /
`GroupConfigurationProvider` and are refreshed by the task/group add/remove events (only the
catalogue needs them; matching needs only the service's own `taskName` and `configuration().groups()`).

```
interface CloudNetView {
    boolean available();                                  // false outside CloudNet
    Optional<ServerFacts> facts(String serverName);       // cached; one direct lookup on a miss
    CatalogueSnapshot catalogue();                        // tasks, groups, services
    void onChange(Runnable listener);                     // for the reporter
}
```

Implementations: `DriverCloudNetView` (the only class importing `eu.cloudnetservice.*`),
`NoCloudNetView` (unavailable), and a `FakeCloudNetView` in tests. Matching is a pure function
`ServerTargets.covers(SectionInfo, serverName, Optional<ServerFacts>)`:

| `targetKind` | covers when |
|---|---|
| `PATTERN` | glob matches the server name (unchanged, needs no CloudNet) |
| `CLOUDNET_TASK` | facts present and `taskName` equals any target (ignore case) |
| `CLOUDNET_GROUP` | facts present and any of the service's `groups` equals any target |
| `CLOUDNET_SERVICE` | facts present and `serviceName` equals any target |

`Enforcement.serverBan` and `coversServer` take the facts; `ServerConnectListener` and
`PunishmentSyncHandler` pass `cloudNet.facts(serverName)`.

### 3. Unknown server and no CloudNet: fail open, loudly once

If the proxy runs without CloudNet, or the target server is not known to CloudNet (a static
`velocity.toml` server, a service that appeared between event and connect), a CLOUDNET section
**does not match**, and the proxy logs a warning once per server name (and once at startup when
the integration is unavailable but CLOUDNET sections exist). On a cache miss `DriverCloudNetView`
does one `serviceByName` lookup before giving up; the connect path is not the Velocity main thread
(event handlers run on the event executor), and the lookup only reads the driver's local state.

Safety implication, stated plainly: a player with a CloudNet-targeted ban could connect to a
server that CloudNet does not know. That is the same fail-open stance as today's state outage
(`PunishmentState.activeServerBan`: "an outage never locks players out"). It is acceptable
because the only servers the Bridge registers are CloudNet services, which are known; the gap is
servers hand-registered outside CloudNet, for which operators should use a `PATTERN` section.
BAN and MUTE sections (network-wide) are unaffected by any of this.

### 4. Optional integration, detection

`BlackholeVelocity` builds the view once at startup:

1. Config `cloudnet.integration` = `auto` (default) | `off`. `off` -> `NoCloudNetView`, no probe.
2. `auto`: probe with `Class.forName("eu.cloudnetservice.driver.inject.InjectionLayer", false, loader)`
   and then `InjectionLayer.ext().instance(CloudServiceProvider.class) != null`. A missing class
   (`ClassNotFoundException`/`LinkageError`) or a null instance -> `NoCloudNetView`.
3. Only then `DriverCloudNetView` is loaded, so a plugin on a proxy without CloudNet never touches
   a missing class. The driver is `compileOnly`; it is not shaded (the wrapper provides it at
   runtime; shading would load a second copy and break the shared injection layer).

We declare **no** Velocity `@Dependency` on the Bridge plugin: Velocity would fail the plugin
when the dependency is absent or the id is wrong, and the plugin must run without CloudNet. The
Bridge being loaded first is not required for correctness: the view reads CloudNet state, not
Velocity's registered servers, and server names are matched as strings.

### 5. Single-service bans: bound to the name, allowed, with a warning

A `CLOUDNET_SERVICE` target is the **service name** (`BedWars-3`). A restart can reuse the name for
a different instance (dynamic services get the lowest free number; static services keep the
name), so the ban follows the name, not the instance. Recommended and chosen: **allow it**, show a
warning in the UI and in the docs: "A service name can belong to a different instance after a
restart. Use a task or group for lasting bans."

Alternative considered: bind to the CloudNet `uniqueId` and drop the target when that instance stops.
Rejected: it makes a punishment's reach depend on runtime state (a ban silently disappears when the
service restarts, including for a player currently banned for abusing that very server), it needs
a second identifier in the section model, and instance ids are not something staff can type or see.
Tipping condition: if operators report service bans that unintentionally hit later instances, add
an optional "valid while the instance lives" flag later; it does not conflict with this design.

Consequences made explicit in the spec: removing or renaming the service does not delete the
section target; a target naming a non-existing service is inert (matches nothing) and the UI shows
it as "not in catalogue".

### 6. Moving a player off a newly banned server

The existing logic (open change `staff-temp-bans-elo-escalation`, task 5.2: when a SERVER
punishment arrives the proxy moves a player on a covered server to `getFallbackServer()` or
disconnects them) is reused unchanged, with the covers-check from decision 2 using the facts of the
player's **current** server. Added rules: if the fallback server itself is covered by the same
punishment (e.g. a group ban that includes the lobby), the player is disconnected instead of being
moved into a deny loop; a player whose current server has no facts is left in place (same
fail-open as decision 3). Edits to a section's targets do not move players already online.

### 7. Catalogue: reporting, storage, API

Direction: proxy -> backend only. No CloudNet access from the backend.

- **Reporter (proxy).** `CatalogueReporter` pushes a **full snapshot** (never a delta) `PUT
  /cloudnet/catalogue` on startup, after any CloudNet task/group/service event, and every
  `cloudnet.heartbeat` (default `PT5M`) as a keep-alive. Debounce is client behaviour: events set a
  dirty flag; a scheduled task coalesces them and sends at most one push per `cloudnet.report-debounce`
  (default `PT5S`, trailing edge). A failed push is retried at the next tick and never blocks or
  fails enforcement. Tests use an injected `Clock` and a manual scheduler; no sleeping.
- **Identity.** The body carries `proxyId` = the proxy's own CloudNet service name (`Proxy-1`), not
  personal data. Several proxies each report their own snapshot.
- **Wire.** `PUT /cloudnet/catalogue` (`@Version(V1)`), request `{ proxyId, tasks: [{name, groups}],
  groups: [name], services: [{name, task, groups, running}] }`. Bounds (validation, not rate
  limiting): `proxyId` <= 64 chars `[A-Za-z0-9_.-]+`, <= 500 tasks, <= 500 groups, <= 5000 services;
  over -> 400. Idempotent, so retries are safe. `GET /cloudnet/catalogue` answers the merged view:
  `{ available, reportedAt, tasks: [{name, groups}], groups: [name], services: [{name, task, running}], proxies: [proxyId] }`.
  `available` is false when no live snapshot exists (empty lists), so the UI can tell "none known"
  from "nothing configured".
- **Storage.** Shared cache (Dragonfly), key `blackhole:cache:v1:cloudnet:catalogue:<proxyId>`,
  value the snapshot plus `reportedAt`, **TTL 15 minutes** (three missed heartbeats). Reasons: the
  data is ephemeral and re-derivable (the proxy re-pushes), no migration, every backend replica sees
  it (the cache is shared), and a dead proxy's entries expire by themselves. `GET` reads all
  `...catalogue:*` keys and merges: tasks/groups/services by name, `running` if any proxy says so.
  If the cache is unavailable `PUT` answers 503 (the proxy retries) and `GET` answers `available:false`
  (the UI falls back to free text); nothing about enforcement depends on the catalogue.
- **Validation against the catalogue** never rejects a section: the catalogue can be empty, stale or
  a proxy can be down, and a section may legitimately name a task that does not exist yet.
- **No Redis push to proxies**: proxies are the producers, so the section sync channels are unchanged.

### 8. Redis sync, Dragonfly cache, rollout

Sections reach proxies exactly as today (`sections:all` key + change notification); the added
`targetKind` field rides along, and `SectionChangeNotifier` fires on update as before. Punishment
mirror keys are per section and unchanged. The catalogue uses its own key family and is never read
by proxies. Rollout order: (1) backend, (2) proxies (the proxy tolerates a missing `targetKind` as
`PATTERN`), (3) only then create CloudNet sections. An old proxy would read the names of a CloudNet
section as globs: `BedWars` as a glob does not match `BedWars-1`, so it does not enforce until
upgraded. The release notes and docs say so.

### 9. Messages

`PunishmentScreens` keeps title `punishment.screen.title.server` and replaces the "Gesperrte
Server" line for CloudNet kinds with a kind-specific line naming the targets (joined with `, `),
followed by the existing "moved to fallback" line. New keys in `blackhole_en.properties` and
`blackhole_de.properties` (same MiniMessage style as `punishment.screen.servers`):

| key | de | en |
|---|---|---|
| `punishment.screen.target.task` | `Du bist auf <white><arg:0></white> gesperrt` | `You are banned from <white><arg:0></white>` |
| `punishment.screen.target.group` | `Du bist auf der Gruppe <white><arg:0></white> gesperrt` | `You are banned from the group <white><arg:0></white>` |
| `punishment.screen.target.service` | `Du bist auf dem Server <white><arg:0></white> gesperrt` | `You are banned from the server <white><arg:0></white>` |

`I18nBundlesTest` already fails if one locale lacks a key. The warning log lines of decision 3 are
English-only (operator logs), as other log lines are.

### 10. Permission matrix

| Route | Method | Minimum human role | `PROXY` |
|---|---|---|---|
| `/cloudnet/catalogue` | `PUT` | none (humans may not write) | **yes, only role** |
| `/cloudnet/catalogue` | `GET` | `VIEWER` | no |
| `/section*` | unchanged | unchanged | unchanged |

`PUT` is `PROXY`-only (`Roles.PROXY`) so a human token cannot forge the catalogue that the editor
trusts; `GET` is `VIEWER` like the other section/template reads (the catalogue holds service names,
no player data). `PermissionMatrixIntegrationTest` and `RouteCoverageIntegrationTest` get the two
rows; the `add-team-sso` matrix is amended by the `staff-authentication` delta. Controllers stay
thin (`CatalogueController` -> `CatalogueService`, sealed `CatalogueDTO`, `CatalogueApi` interface),
per the micronaut-* skills.

### 11. Admin UI

`SectionsView` gets a "Target" selector for SERVER sections (Pattern / CloudNet task / CloudNet
group / CloudNet service). `Pattern` keeps today's free-text chips. A CloudNet kind shows a
multi-select fed by `GET /cloudnet/catalogue` (tasks, groups or services as chosen), marks entries
not in the catalogue, and always allows adding a free-text name (also the only mode when
`available` is false, with a hint "no CloudNet data reported"). Choosing `CLOUDNET_SERVICE` shows
the volatility warning of decision 5. Strings in both locales; `i18n.keys.test.ts` enforces parity.
The list view shows the kind next to the targets.

### 12. Tests (F.I.R.S.T.)

- `CloudNetView` hides every `eu.cloudnetservice` type; unit tests use `FakeCloudNetView`
  (mutable, in memory). No CloudNet, no network, no sleeps; the reporter takes a `Clock` and an
  injected scheduler it can drive by hand; `DriverCloudNetView` itself is thin glue verified by the
  manual task (and by a compile-time check that only it imports the driver).
- Backend: validation per kind, migration default (`PATTERN` for pre-existing rows), DTO round trip,
  catalogue merge and TTL (shared cache fake with an injected `Clock`), bounds -> 400, route and
  permission-matrix coverage.
- Velocity: `ServerTargetsTest` (each kind, case, unknown server, no CloudNet), enforcement on
  connect and move-off with the fallback-also-covered case, service restart (lifecycle event
  replaces facts), screen text in both locales, reporter debounce/heartbeat/failed push.
- Web: component tests for the kind selector, picker with and without catalogue, volatility warning.
- Final manual task: verify against a real CloudNet node (see tasks).

## Flyway migrations

`V11__section_target_kind.sql` (next free version when implemented; V10 is the latest now). Existing
rows: default `PATTERN`. No other migration: the catalogue lives in the shared cache.

## Risks / Trade-offs

- [4.x has no stable release; API may change between RCs] -> pin the version, keep every driver
  type behind `CloudNetView`/`DriverCloudNetView` (one file to adapt), check RC notes on bumps.
- [Fail-open for servers unknown to CloudNet] -> documented; `PATTERN` sections cover hand-registered
  servers; one warning per server makes it visible.
- [Service name reuse] -> allowed with warning (decision 5).
- [Classloader visibility of the driver inside a Velocity plugin] -> assumed (the Bridge itself is a
  Velocity plugin using the same classes); verified in the manual task; fallback is `off`.
- [Stale catalogue entries after a service vanishes without an event] -> 15 min TTL plus the
  heartbeat always sends a full snapshot, so entries of a live proxy self-heal within one heartbeat.
- [Old proxies fail open for CloudNet sections] -> rollout order in decision 8.

## Migration Plan

Deploy backend (additive column, default `PATTERN`), then upgrade proxies, then create CloudNet
sections in the UI. Rollback: previous release ignores `targetKind` and the catalogue keys; sections
created with a CloudNet kind keep their names but are then read as globs (not enforced) until the
upgrade is restored, so delete or disable them before a rollback if enforcement matters.

## Open Questions

- Does the network want `GET /cloudnet/catalogue` restricted to `ADMIN` (only the editor uses it)?
  Proposed `VIEWER`, matching section reads.
- Is `CLOUDNET_SERVICE` wanted at all given its volatility? The user decided yes; the warning
  keeps it honest.
- Should the proxy id come from config (`cloudnet.proxy-id`) instead of the service name when the
  proxy is not named by CloudNet? Proposed: service name, config override optional.
