# Design

## Context

Binding principles (`openspec/config.yaml`) and the contract this bridge must meet (`add-integration-evidence`):

- **P1 GDPR by design.** The bridge sends the player's Minecraft UUID over TLS only, because the backend hashes it on
  receipt. It never sends an IP, a host name, a world, coordinates, inventory or chat. No disk buffer.
- **P2 Entra, machine principal.** The bridge holds one client-credentials registration with app role
  `Blackhole.Integration`. It never sends a user token and never asks for one.
- **P4 Dual ELO.** Detections never feed ELO or punishments; the bridge has no path to either.
- **P5 Rate limiting at the infra layer.** The bridge does not rate-limit itself beyond its own batch and queue bounds.
- **Contract.** Envelope and `vulcan/DETECTION` v1 as in `add-integration-evidence` decision 2. Ingestion answers per
  item `ACCEPTED`, `DUPLICATE` or `REJECTED` with a reason code, and a batch is at most 50 items and 64 KiB.

## What Vulcan and the server expose

Verified against public sources on 2026-11-02, unless marked **unverified**. Vulcan's developer documentation was not
reachable (the SpigotMC resource page returned HTTP 403 to the fetch tool), so the names come from the changelog text
found by search.

| Fact | Status | Source |
| --- | --- | --- |
| Vulcan lists Minecraft 1.8 through 26.1.2 on its SpigotMC page | Verified (listing title) | <https://speedgot.ajg0702.us/resources/83626> |
| Full functionality is guaranteed on Spigot, PaperSpigot, Pufferfish and Purpur; Folia is "supported experimentally" | Verified (listing text, via search summary) | <https://speedgot.ajg0702.us/resources/83626>, <https://minecraftservershq.com/plugin/vulcan> (third party, may lag) |
| Vulcan needs PacketEvents installed as a separate plugin | Verified (listing text) | <https://speedgot.ajg0702.us/resources/83626> |
| `VulcanFlagEvent` exists, is async, and `setCancelled` was fixed in a release | Named in changelog; **signature unverified** | <https://speedgot.ajg0702.us/resources/83626/updates> |
| `VulcanPostFlagEvent` is fired when an alert message is sent, not on every violation unless configured | Named in changelog; exact release **not pinned**; **signature unverified** | same |
| `getChecks()`, `getMaxViolations()`, `Check#isExperimental` were added to the API | Named in changelog (2021 update); **signatures unverified** | same |
| `CheckData` system and `VulcanAPI#toggleAlerts` / `toggleVerbose` | Named in changelog; **signatures unverified** | same |
| A third-party addon hooked the flag event on Paper/Folia, lost flags to a classloader mismatch, and kept a legacy event package working | Changelog of a third-party addon; **unverified in detail** | <https://builtbybit.com/resources/donutsus-donutsmp-anticheat.97726/update/240472/> |
| The addon's changelog says Vulcan's `enable-api` option must be true for listeners to receive events | Third-party changelog; **unverified** | same |
| Check name, violation level (VL), check type, `detail` getter names | **Unverified.** No source found. The runtime probe (decision 4) decides which exist | none |
| Paper `Player#getPing()` and `Bukkit#getTPS()` | Paper API, to be confirmed against the pinned `paper-api` javadoc in task 2 | Paper javadoc (verify when the version is pinned) |
| CloudNet exposes the service name to the process (env or system property) | **Unverified.** The config fallback covers its absence | CloudNet 4 docs (to check during the CloudNet template task) |

The consequence for the design: nothing about Vulcan's classes or getters is compiled into the bridge. Names are
probed at runtime, and the only compile-time dependency on Vulcan is none. The unit tests use stub classes that mimic two
API shapes; these stubs are labelled as doubles, never as Vulcan's real signatures.

## Decision 1: Platform, module and language level

**Chosen.**

- **Target: Paper and its Paper-compatible forks (Purpur, Pufferfish).** These are the forks Vulcan guarantees. The
  bridge is a Paper plugin using `paper-plugin.yml` where the server supports it and `plugin.yml` otherwise, so one
  jar works on the Paper line.
- **Folia: declared, not promised.** The bridge declares `folia-supported: true` so Folia does not refuse it. It does
  not touch region-owned state on the wrong thread; TPS is reported as null on Folia because there is no global TPS
  there. Vulcan on Folia is experimental, and this is stated in the operator README.
- **Spigot: not in v1.** Spigot-only servers are not in this network's CloudNet setup (open question 2). Calls that
  only exist on Paper are isolated behind one class so Spigot support is a small follow-up if it is needed.
- **Minestom: no.** Vulcan does not run there. Nothing to bridge.
- **Module:** `bridge-paper` in `settings.gradle.kts`, package `net.onelitefeather.blackhole.bridge.paper`, with
  subpackages `config`, `delivery`, `vulcan`, `command`, `metrics`. The `vulcan` adapter is the only package that names
  Vulcan, and it names it only in strings.
- **Java:** `--release 21`. The current Paper line (1.20.5 and later) runs on Java 21. Older Paper versions need Java 17
  and are out of scope (open question 1).
- **`client` module: not reused.** The generated client is a Jakarta/Jackson-based library that pulls `httpmime`,
  Jackson and the `*Api` interfaces of the whole backend API. The bridge needs one POST and one token call. It uses
  `java.net.http.HttpClient` and a shaded, relocated Gson for the response parse. The integration routes are not in
  the generated client until `add-integration-evidence` PR 2 bumps the spec, so reuse would wait for a spec that the
  bridge does not need in full.
- **Shading:** `com.gradleup.shadow` (same plugin as `velocity`), with Gson relocated to
  `net.onelitefeather.blackhole.bridge.paper.libs.gson`. Paper, Vulcan and PacketEvents are never shaded.

**Rejected alternatives.**

- *Reuse `client` for the HTTP call.* Pulls the whole generated API surface and Jakarta/Jackson onto the game server.
  Rejected (above).
- *A Minestom bridge.* Vulcan does not run there. Rejected.
- *Spigot in v1.* Adds a second API surface (for example `Player#getPing`, which may not exist on every Spigot
  build; unverified) and no network server is known to need it. Rejected for now.
- *Java 17 target for old Paper.* Needs a second build and a second test matrix for servers the network does not
  appear to run. Rejected until open question 1 says otherwise.

## Decision 2: Token handling and configuration

**Chosen.**

- **Config file** `plugins/BlackholeBridge/config.yml`, written with defaults on first start, containing:
  `backend-url`, `entra.tenant-id`, `entra.client-id`, `entra.scope`, `server-name` (optional), `queue.max-detections`
  (default 5 000), `queue.max-bytes` (default 8 MiB), `flush.interval` (2 s), `flush.shutdown-deadline` (2 s),
  `vulcan.enabled` (default true).
- **Secret** `entra.client-secret` is **not** read from the file when the env var is set. The order is: env
  `BLACKHOLE_BRIDGE_CLIENT_SECRET`, then the file key. If neither is set the bridge stays idle and logs one WARN. The
  secret is wrapped in a type whose `toString()` returns `***`, and it is never passed to a logger, a metric or the
  status command.
- **Token.** Client-credentials request to the Entra token endpoint for `entra.scope`, held in memory. Refresh when 80%
  of `expires_in` has passed, through an injected clock. A failed refresh keeps the old token until it expires, then the
  batch is retried with backoff (decision 3). A 401 from the backend forces one refresh per batch, not a loop.
- **Create by template.** The CloudNet task template writes the env var from its own secret store (operator step, see
  decision 7). The file is written by the plugin with an empty secret, so a missing secret is visible at first start.
- **The operator provides:** the backend URL, one client id and one secret per task (dynamic services) or per instance
  (static servers), the allowed tasks or static names in the registration (`add-integration-evidence`), and the
  Vulcan API switch in Vulcan's own config.

**Rejected alternatives.**

- *Secret in `config.yml` only.* Puts the secret in a file the operator copies between templates. Env first is what the
  template can inject without writing a file. The file is still allowed for a static server.
- *A shared secret per platform.* Rejected in `add-integration-evidence`; each bridge has its own registration.
- *Token fetched per batch.* Multiplies identity-provider calls by the flush rate. Cached with refresh instead.

## Decision 3: Delivery

**Chosen.**

- **Hand-off.** The Vulcan listener copies the mapped envelope into a bounded in-memory queue and returns. It never
  waits on a lock held by the network thread, never calls the network and never throws. The queue is a
  `ArrayDeque` guarded by a short lock or a lock-free queue; the design requires only that the main thread's hold time
  is O(1) and bounded.
- **Bounds.** At most 5 000 detections or 8 MiB of serialised envelopes, whichever is first. When full, the **oldest**
  detection is removed, and `dropped_total{reason="queue_full"}` increases. The newest detection is kept because it is
  the one staff can still act on.
- **Batching.** A sender thread flushes every 2 s or when a batch reaches 50 items or 64 KiB, whichever is first. A
  body over 64 KiB is never sent; the batch is split. A single envelope above the limit is dropped and counted as
  `reason="too_large"` (the schema caps the payload at 4 KiB, so this is a bug guard).
- **Retry.** Exponential backoff from 1 s to 60 s with full jitter (`random` injected). A network failure or a 5xx
  retries the whole batch. A 4xx other than 401 does not retry; its items are counted as rejected by the backend's
  per-item answer or, if the whole request is refused, as `reason="http_<code>"`.
- **Per-item answers.** `ACCEPTED` and `DUPLICATE` remove the item. `REJECTED` removes it and counts the reason code.
  The bridge logs the reason code and the check name count, never the owner or `detail`.
- **Idempotency.** `detectionId` is a UUID generated once when the event is mapped, kept through retries, so a repeated
  batch after a lost response is answered `DUPLICATE`.
- **Timeouts.** Connect 2 s, request 5 s, from `java.net.http`.
- **Shutdown.** `onDisable` stops the listener, then flushes once with a 2 s deadline. Anything left is counted as
  `reason="shutdown"` and the bridge does not block the server stop beyond the deadline.
- **Clock.** `occurredAt` is taken when the event is handled on the server (the server clock is the only time source
  for the staff timeline). Injected `Clock` for tests.
- **Scheduler.** The sender runs on a dedicated thread created by the plugin, not on the Bukkit or Folia scheduler, so a
  slow backend cannot stall ticks. Tests inject a fake scheduler and a fake `HttpSender`.

**Rejected alternatives.**

- *Disk buffer.* A second copy of personal data outside retention control (design of `add-integration-evidence`).
- *Drop-newest.* Keeps stale detections and loses the latest signal. Rejected.
- *Blocking the main thread when the queue is full.* Violates "never block the main thread". Rejected.
- *Bukkit scheduler for sending.* Ties delivery to the tick rate; a slow backend would show as lag. Rejected.

## Decision 4: Vulcan adapter and version independence

This implements `add-integration-evidence` decision 7 exactly. Rules here are the bridge's concrete form of it.

**Probe at enable.**

1. Resolve the flag event class by name through the plugin's classloader (`Class.forName(name, false, loader)`). The
   candidate names are a list in the adapter, for example `VulcanFlagEvent` and the legacy package variant named in the
   third-party changelog. The list is data, not a compile-time reference. If the class exists, the probe records the
   name that matched.
2. Resolve the post-flag event class the same way. It is optional: its absence only disables the post-event path.
3. For each **feature** (check name, check type, VL, experimental, detail, cancelled), try a list of candidate getter
   names on the event class (reflection, `Method#trySetAccessible` not needed for public methods). A match is turned
   into a `MethodHandle` with `MethodHandles.publicLookup()` and cached once. Getters are called with no arguments and
   their return types are checked (`String`, `int`, `double`, `boolean`) before use.
4. Ping and TPS are not Vulcan features; they come from the server API (decision 5).
5. Record each feature as available or missing.

**Mapping.** `check` from the check-name getter, `checkType` and `detail` from their getters when present,
`violationLevel` from the VL getter, `experimental` from the experimental getter. Missing optional fields are sent as
`null`. A flag without a `check` is dropped and counted as `dropped_total{reason="unmapped"}`, because `check` is the
only required field. A flag that is cancelled by another plugin is still reported; the cancelled state is not part of
`vulcan/DETECTION` v1.

**Guard.** The probe and every mapping call run inside a single guard. Any throwable (including `LinkageError`,
`ReflectiveOperationException`, `ClassCastException`, `NullPointerException`) disables the Vulcan integration for the
rest of the run: the listener is unregistered, the queue keeps draining, and one ERROR line with the exception class
name is logged. The server keeps running.

**Handler discipline.** The handler copies fields only. It never waits for the server thread. Vulcan's flag event is
async (changelog), so the server-side values (ping, TPS) are read from a snapshot that a main-thread task refreshes
every second; if the snapshot is missing or stale by more than 5 s, the field is `null` and the handler does not wait.

**One INFO line at enable.** Example:
`vulcan bridge: event VulcanFlagEvent; features check, vl, detail; missing checkType, experimental; ping and tps from the server api`.
Nothing is logged per flag.

**Unknown version.** No flag event class found: the adapter stays idle, logs one WARN (`flag event not found, bridge
idle`) and sends nothing. It does not guess.

**Known versions list.** The adapter ships a table of candidate names and getter names with the versions that were
tested. At least two shapes are tested (below). The table lists "tested against" and "candidates; not verified"
separately. Adding a name is a code change with its own test double.

**Test strategy.**

- **Doubles, not Vulcan.** The test sourceset has stub classes `FakeFlagEventV1` (full getter set) and `FakeFlagEventV2`
  (a getter renamed, one absent, the event class under the legacy package). They are labelled in their Javadoc as test
  doubles modelled on changelog names, not Vulcan's signatures. Both must map the shared fields to the same envelope
  (the same test table runs against both).
- **Unknown-version double.** A package with no flag event class; the adapter must idle with one WARN.
- **Guard double.** A getter that throws; the integration must disable once and the handler must not throw.
- **Real jar, optional.** If a Vulcan jar can be downloaded in CI under its licence and without an interactive login,
  the same tests run against two real releases as fixtures, and the tested versions are named in the README. The
  pinned fixtures are not committed as binaries. Otherwise the real-jar check is a documented manual step on a Paper
  test server (task 9), with its result recorded in the README.
- **No Vulcan dependency at build time.** Nothing in `bridge-paper`'s compile classpath depends on Vulcan, so the
  build is independent of Vulcan's availability.

**Rejected alternatives.**

- *Compile against one Vulcan jar.* Breaks on the other versions and needs the jar in the build. Rejected by the
  maintainer's decision 7.
- *Generated proxies or bytecode weaving.* More moving parts than reflection for a handful of getters. Rejected.
- *Guessing names on unknown versions.* Would send wrong data to staff. Rejected: WARN and idle.

## Decision 5: Envelope and server context

- **`detectionId`**: UUID v4 generated once per mapped flag.
- **`kind`** `DETECTION`, **`schemaVersion`** `1`, **`sourceSystem`** not sent (taken from the registration).
- **`occurredAt`**: server clock instant when the flag event is handled.
- **`server`**: the CloudNet service name when it can be read from the process (open question 4 and unverified), else
  `server-name` from the config. The value is validated locally against `^[A-Za-z0-9_-]{1,64}$`; a value that fails
  is not sent and the config fallback is used. It is never an IP or host name.
- **`owner`**: the player's Minecraft UUID as a string, sent over TLS only. The backend derives the owner hash. The
  bridge keeps the UUID in memory only for the queued envelope.
- **`severity`**: omitted. Vulcan has no severity that the bridge can map without verification; the schema allows it
  to be absent.
- **`summary`**: built by the bridge as `"<check> flagged"` with the check name, at most 200 characters. The
  `detail` text is not repeated in the summary.
- **`payload`**: `check`, `checkType`, `violationLevel`, `ping`, `tps`, `experimental`, `detail` as in the
  `vulcan/DETECTION` v1 draft. `ping` is `player.getPing()` on the main thread at the sample, `tps` is the server TPS
  from the snapshot (null on Folia). No IP, no world, no coordinates, no inventory.

**Rejected alternatives.**

- *Ping and TPS taken on the async handler thread.* Not thread-safe on Paper; rejected.
- *Sending the player name.* Not needed for the contract, and a name is personal data the backend does not require.
  Rejected.

## Decision 6: Observability and the status command

- **Counters** (in memory, exposed through the plugin's own log line every 5 minutes and the status command):
  `queued`, `sent`, `accepted`, `duplicate`, `rejected{reason}`, `dropped{reason}`, `token_refresh`, `vulcan_features`.
  Names follow `blackhole_integration_*` so the same dashboard covers any bridge. Counts only.
- **Logs.** INFO on enable (features) and on each state change (token configured, Vulcan integration disabled). WARN
  for an unknown version and a missing secret. ERROR once per disabled integration. No line contains a UUID, a name,
  a `detail` or a token.
- **`/blackhole-bridge status`** (permission `blackhole.bridge.status`, default `op`). Prints the enabled state, the
  Vulcan features, the queue size and the counters. It never prints the secret, the token, the config values that
  contain the secret, or any player data.
- **Tests** capture log output through an appender in the test (the `F.I.R.S.T.` rule: a log line that matters is
  asserted through a captured appender).

## Decision 7: Distribution and the CloudNet template

- **Artifact.** `bridge-paper/build/libs/blackhole-bridge-paper-<version>.jar` from the `shadowJar` task. The version
  comes from the root `gradle.properties` like the other modules; the bridge has its own release tag.
- **Versioning.** release-please gets a second `packages` entry for `bridge-paper` with `release-type: java`, its own
  changelog and tag prefix, so a bridge release does not bump the backend. Commits touching only `bridge-paper/` are
  attributed by path. The config change is part of task 1 of the code PR.
- **Build in CI.** The existing Gradle workflow builds it with `./gradlew :bridge-paper:build`; no Docker image.
- **CloudNet template (operator step).** The operator adds the jar to the task template and sets the environment
  variable `BLACKHOLE_BRIDGE_CLIENT_SECRET` from the template's secret store. The bridge is not updated from inside the
  game server; a new template version rolls the jar. The README contains the exact template checklist.
- **Upgrades.** A new bridge version is one template change. The bridge keeps `vulcan/DETECTION` v1 as long as the
  schema is v1; a changed field requires `v2` in `add-integration-evidence`, not an edit.

**Rejected alternatives.**

- *Hosting the jar on the backend and downloading it at start.* A download on boot is a supply-chain path into the
  game server and a second network dependency. Rejected.
- *Bridge inside the Velocity jar.* The proxy is not where Vulcan runs. Rejected.

## Open questions

These need an answer from the maintainer before the matching task starts. They do not block the proposal.

1. **Minimum Paper version.** Is every server on the Paper line 1.20.5 or later (Java 21)? If not, the bridge needs a
   Java 17 build for older servers, which this design does not include.
2. **Spigot and Folia.** Does any server in the network run Spigot or Folia? The design says Paper family only, Folia
   declared with TPS null, Spigot out of v1.
3. **Real Vulcan jar in CI.** Can a Vulcan release be downloaded under its licence without a login or a purchase? If
   not, task 9 is a manual check.
4. **Server name.** Does CloudNet expose the service name to the process (environment variable or system property)? If
   not, every instance needs `server-name` in its config, which the template writes.
