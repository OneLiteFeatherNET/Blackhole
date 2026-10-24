# Proposal

## Why

Staff have no say over which clients, client versions and commands reach the network. Cheat
clients announce themselves through their brand and plugin channels, some versions are unwanted
on some servers (a minigame that breaks on old clients, a lobby that must stay on current
versions), and some commands (`/op`-like, backend debug or world edit commands) must never be
typed by players on a given server. Today the only client facts Blackhole sees are the brand and
protocol version, stored as session info for the dashboard, and ban-evasion detection looks at a
hashed IP only, which a VPN defeats.

This change adds **client rules** that staff manage in the admin panel and the proxy enforces,
and a **hashed client fingerprint** that feeds ban-evasion recognition next to the hashed IP.
All of it is best effort: brand and channels are chosen by the client and can be faked. Rules are
therefore a deterrent and a signal, not proof, and the safe default action is "tell the team".

## What Changes

- **Client rules** (new capability `client-rules`), one model with five match kinds:
  - `BRAND`: the `minecraft:brand` string (glob, case-insensitive).
  - `MOD`: a mod id from the legacy Forge mod list (only Forge up to 1.12 sends one to a proxy).
  - `CHANNEL`: a plugin channel the client registered (`minecraft:register`, glob).
  - `PROTOCOL`: a range of protocol versions, optionally negated (allowlist).
  - `COMMAND`: the first word of a command line, for proxy commands and for commands the proxy
    forwards to a backend server.
- **Scope**: a rule is global, or limited to servers, using the **same targeting as sections**:
  section keys and/or a `ServerTargetKind` plus names (glob patterns, CloudNet task, group or
  service), resolved by the existing `ServerTargets.covers`.
- **Action per rule**, chosen by the admin:
  - `NOTIFY` (the default): tell the team, change nothing for the player;
  - `DENY`: refuse the connection or the command with an English message written on the rule;
  - `TEMPLATE`: apply a punishment template (a ban or mute, with ELO) automatically, contestable
    through the appeal workflow like every automatic punishment.
  A rule that uses `TEMPLATE` on a client-controlled signal (brand, mod, channel) needs an explicit
  acknowledgement that the signal can be faked; the panel shows the warning.
- **Client fingerprint** (new capability `client-fingerprint`): the proxy collects brand,
  non-vanilla channels and the legacy mod list; the backend turns them into an HMAC-SHA256
  fingerprint under a dedicated key. Only the keyed hash and the owner hash are stored. Evasion
  sightings gain a client-fingerprint signal that corroborates the IP signal and is never a
  punishment by itself.
- **Hits and notification**: every rule hit is recorded without any raw client trait (rule key,
  owner hash, time, action taken, server) with a short retention, published as a domain event, and
  announced to online staff in game and in the panel.
- **Catalogue** for the rule editor, aggregated and reported by the proxy like the CloudNet
  catalogue: brands and channels seen from at least three distinct players, the commands in the
  proxy's command tree (not what players typed) and the protocol versions the proxy supports.
- **Admin panel**: a "Client rules" view; rule edits `ADMIN`, reading the rules, hits and catalogue
  for every team role.
- Migrations `V19__client_rule.sql` (V18 is the Discord ticket change's), then the next free
  numbers for the hit and fingerprint tables. Client spec bumps use the next free number at
  implementation time.

Not breaking: all routes and fields are additive. A proxy older than this change ignores rules
(nothing is enforced). A new proxy against a backend without the routes treats the 404 as "no
rules" and fails open. No change to existing DTOs except one optional additive field on the
evasion sightings response.

Out of scope:
- Anything beyond brand, channels, legacy mod list, protocol version and command first word
  (no packet inspection, no client-side mod, no hardware or MAC-address style fingerprints, no
  resource pack or TLS fingerprinting, no per-argument command matching).
- Treating a fingerprint match as proof, or punishing on a fingerprint match alone.
- Detecting modern NeoForge or Fabric mod lists (the proxy cannot see them, see design).
- Hiding commands as a security measure (hiding is cosmetic, denial is the enforcement).
- App-level rate limiting (infra owns it) and per-actor identity beyond `verified-actor`.
- German or other translations of player-facing text (English only by decision).
- Banning by fingerprint alone, shadow bans, rule import and export.

## Commit type and PR plan

The change ships as small stacked pull requests, each squash-merged, each of one Conventional
Commits type (the PR title is the commit on `main`). `feat` carries all behaviour; the last PR is
`docs`. No `fix`, `refactor` or `perf` is mixed in.

1. `feat(client-rules): add the client rule model and api` (V19, rules CRUD, catalogue, role rows, client spec)
2. `feat(client-rules): record rule hits and apply their action` (hit table, intake, notice event, template application, retention, erasure, client spec)
3. `feat(evasion): correlate hashed client fingerprints` (key, HMAC, table, sightings signal, retention, erasure, client spec)
4. `feat(velocity): collect client traits and enforce client rules` (traits, rule engine, login and connect enforcement, catalogue, fingerprint submission, staff notice)
5. `feat(velocity): enforce command rules` (`CommandExecuteEvent`, SignedVelocity gating, command hiding)
6. `feat(web): manage client rules in the panel`
7. `docs(client-rules): document client rules and fingerprints` (README, `.claude/rules/client-rules.md`, evasion rule update)

Archive: `docs(openspec): archive add-client-fingerprint-rules`.

## Capabilities

### New Capabilities

- `client-rules`: the rule model, scope, actions, evaluation on the proxy, command rules, hits,
  catalogue and the routes with their roles.
- `client-fingerprint`: the collected traits, the keyed hash, storage, retention, erasure and the
  honesty limits of the signal.

### Modified Capabilities

- `evasion-detection`: a client-fingerprint signal next to the IP signal, with its own window,
  retention and a common-fingerprint cut-off.
- `proxy-enforcement`: trait collection, rule enforcement on login, connect and command, the
  staff notice.
- `admin-web-ui`: the client rules view and the fingerprint hint on the player page.
- `staff-authentication`: permission matrix rows for the new routes.

## Impact

- Backend: new `clientrule/` package (entity, service, `ClientRuleApi`, thin controller, sealed
  DTOs, hit service, catalogue, retention sweeper); `evasion/` gains `ClientFingerprintService`,
  entity, repository, key guard and sweeper; `CacheKeys`; a change notifier like the sections'
  for rule changes; the profile erasure path; `application.yml` keys with comments; route-coverage
  and permission-matrix tests.
- Flyway: `V19__client_rule.sql`, then next free numbers for the hit and fingerprint tables.
- `client/specs/blackhole-api-<next free>.yml` (three bumps, one per backend PR), `inputSpec`.
- Velocity: new `clientrule/` package, listeners for brand, channel, mod info, login, connect,
  command and available-commands events, config, messages; `PlayerClientBrandListener` keeps its
  session-info behaviour and also feeds the trait collector.
- Web: a client rules view, API types, locales (de, en), role gating.
- Depends on `integrate-cloudnet-sections` and `staff-temp-bans-elo-escalation` (sections, targets,
  templates), `add-team-sso` and `verified-actor`. Docs: README and `.claude/rules`.
