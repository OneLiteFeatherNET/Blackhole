# Proposal

**Ships as:** `feat(vulcan): deliver vulcan detections to blackhole` (one type, one scope; additive, no BREAKING change).

Staff learn about a Vulcan flag on a game server only when someone reads the server log or a Discord message, and
then re-types it into a ticket. `add-integration-evidence` gives Blackhole a place for such detections: a push route, a
registry, a typed `vulcan/DETECTION` v1 schema and a staff card. Nothing on the game server yet sends them. This change
specifies that sender: a bridge plugin that runs next to Vulcan on a Paper server, listens to Vulcan's flag events,
maps them to the contract and delivers them to Blackhole without ever slowing the game.

The bridge is the only code that can see both sides: Vulcan's events and the server's own ping and TPS. It never
decides anything. A detection is a signal for staff, as `add-integration-evidence` states.

## What Changes

- **New Gradle module `bridge-paper`**, a Paper plugin (`blackhole-bridge-paper`) with a `vulcan` adapter package. It
  builds one shaded jar. It does not depend on the `client` module (see design, decision 1).
- **Event capture.** The bridge listens to Vulcan's flag events (`VulcanFlagEvent`, and `VulcanPostFlagEvent` where
  present) through a runtime probe, so it compiles and starts against any Vulcan version and never references a Vulcan
  class at compile time (design, decision 4).
- **Version independence** exactly as `add-integration-evidence` decision 7: runtime detection of event classes and
  getters, missing optional fields sent as null, only a missing `check` drops a flag, any failure disables the Vulcan
  integration once without crashing the server, one INFO line with the detected features, WARN and idle on an unknown
  version.
- **Delivery.** Asynchronous, bounded in-memory queue, batches of at most 50 detections and 64 KiB, exponential
  backoff with jitter, one idempotency key (`detectionId`) per detection, drop-oldest with a counter, a short flush on
  shutdown. The main thread only copies fields into the queue.
- **Credentials.** The bridge authenticates with an Entra client-credentials token (app role `Blackhole.Integration`).
  The client secret comes from an environment variable or a config file on the game server, is delivered by the
  CloudNet task template or by the server operator, is cached in memory with refresh, and is never logged.
- **Server label.** The envelope's `server` is the CloudNet service name when the bridge can read it, otherwise the
  configured `server-name`. It is reported, not trusted (`add-integration-evidence`, registration granularity).
- **Observability.** Counts only (queued, sent, accepted, duplicate, rejected by reason, dropped, token refreshes,
  Vulcan features) as a metrics surface on the game server and as a status line in the log. No player name, UUID,
  detail or payload is logged.
- **Operator status command** `/blackhole-bridge status`, permission-gated (`blackhole.bridge.status`, default `op`).
- **Distribution.** A versioned jar built by Gradle, released through release-please as its own package, and placed
  into the CloudNet task template by the operator (design, decision 7).
- **Operator documentation** for the credential setup and the template fields, in `bridge-paper/README.md`.

Out of scope:

- **The backend ingestion, registry, schema and panel card.** These are `add-integration-evidence` (PR #556). This
  change depends on its `POST /integration/detections` route, the `integration` registration and the `vulcan/DETECTION`
  v1 schema. The bridge cannot be tested end to end until that backend ingestion is merged and deployed to a test
  environment.
- **A Minestom variant.** Vulcan does not run on Minestom, so there is nothing to bridge there (design, decision 1).
- **Velocity or proxy-side delivery.** The proxy sees neither Vulcan's flags nor the server's TPS.
- **Other anticheats** (Grim and others). Grim's shape maps to the same envelope, but a Grim adapter is a later
  change with its own schema version and its own proposal.
- **Any punishment, ELO input or player-facing message.** The bridge never calls the punishment API and never tells
  the player anything.
- **Automatic Vulcan configuration changes.** The bridge does not change Vulcan's config, alerts or toggles.
- **Shipping Vulcan.** The bridge does not bundle or download Vulcan; it runs next to an installed Vulcan.
- **Disk buffering** of detections on the game server (a second copy of personal data; design, decision 3).

## Capabilities

### New Capabilities

- `vulcan-bridge`: the Paper plugin that captures Vulcan flags, maps them to the `vulcan/DETECTION` v1 envelope,
  delivers them in batches with idempotency and bounded buffering, holds its credential without logging it, adapts to
  Vulcan versions at runtime, and exposes counts and an operator status command.

### Modified Capabilities

None. The backend contract is defined by `integration-evidence` in `add-integration-evidence`.

## Impact

- Build: new module `bridge-paper` in `settings.gradle.kts`; a `release-please-config.json` package entry for it with
  its own tag prefix; a CI job that builds it.
- Game servers: one jar in the CloudNet task template per Paper task, one environment variable for the client secret,
  one Entra app registration per task (dynamic services) or per instance (static servers), as
  `add-integration-evidence` decides.
- Backend: none in this change. It needs `POST /integration/detections` from `add-integration-evidence` in place.
- Depends on: `add-integration-evidence` (PR #556) being merged. Its PR 2 (ingestion) is the first one the bridge can
  exercise.
