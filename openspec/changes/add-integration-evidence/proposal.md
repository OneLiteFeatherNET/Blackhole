# Proposal

**Ships as:** `feat(integration): ingest third-party detections as evidence` (one type, one scope; additive, no BREAKING change).

Vulcan (and later Grim, other anticheats, a chat filter, a server log scanner) can say "this player was flagged for
this check on this server at this time", and staff currently re-type that into a ticket or a Discord message. The
Blackhole staff panel has no place for it. Evidence today accepts a generic `ANTICHEAT` item with a free-form payload
and a `sourceSystem` string, but the panel renders it as a flat key-value list, the data is not validated per source,
and nothing from a game server reaches Blackhole without a human copying it.

The maintainer wants third-party systems to bring their own UI elements, so that a staff member sees for example
"Vulcan: KillAura, VL 14, on survival-2, 14:32, ping 48 ms, TPS 19.8" as a card, not as a row of text. This change
defines how a detection gets into Blackhole safely, how it is stored and attached to a punishment, and how the panel
renders it. The panel renders reviewed components that live in this repository, never code that a third party ships.

## What Changes

- **Machine principal `Blackhole.Integration`** (new app role, new `Roles.INTEGRATION`). One Entra app registration per
  integration instance (bridge plugin on a game server). Its only permission is to submit detections. It cannot read
  players, punishments or evidence.
- **Integration registry** (admin-managed): an integration is `{sourceSystem, entraClientId, enabled, allowedKinds,
  allowedTasks, allowedStaticServers}`. Dynamic CloudNet services share one credential per task; static servers get
  one per instance (design, decision 1).
  A registration is written only by an ADMIN and is audited. A detection is accepted only when the token's client id
  maps to an enabled registration, and the `sourceSystem` of the detection is taken from that registration.
  The body cannot name another source.
- **Ingestion API** `POST /integration/detections` (batch, up to 50 detections per request), answering per item with
  `ACCEPTED`, `DUPLICATE` or `REJECTED` plus a reason code. Idempotency key per detection. No caller-supplied URL,
  no webhook, no callback: the integration only pushes.
- **Typed, versioned kinds**: a common envelope plus a kind-specific payload checked against a JSON Schema registered
  per `(sourceSystem, kind, schemaVersion)`. Kinds without a registered schema are rejected, not stored.
- **Storage**: detections land in `integration_detection` (staff-only, retention 180 days by default and env-tunable, swept). Nothing is attached
  automatically. Staff attach one or several detections to a punishment or an open case; an attach creates an
  `evidence_items` row of the new kind `INTEGRATION` (payload = validated snapshot), so the existing evidence lifetime,
  erasure, audit and `LIFTED`/`REVOKED` rules apply unchanged.
- **Case timeline**: attaching writes one staff-only case event `detection.attached` (ids and source only, no content).
- **Inbox signal**: an open case of a player with new detections since the staff member's last view shows a count
  (no content) in the inbox.
- **Vulcan version independence**: the bridge detects the Vulcan API at runtime and maps what exists; missing fields are
  null; it never crashes the server and is tested against two API versions (design, decision 7).
- **Detections never feed ELO or punishments** (a requirement, not an open question).
- **Panel renderer registry** keyed by `(sourceSystem, kind, schemaVersion)`, mapping to a Vue component in
  `web/src/components/integration/`, loaded lazily. A generic renderer shows any validated payload as a labelled
  key-value table, so a new integration works before it has its own component.
- **First custom component**: `VulcanDetectionCard` for `vulcan/DETECTION` v1 (check, violation level, server, time,
  ping and TPS as context, a small violation-level-over-time chart as inline SVG with a text alternative).
- **Trust wording on every integration element**: who reported it, that it is a signal and not a verdict, and that
  staff decide.
- **Domain events**: none new in this change. `evidence.attached` and `evidence.deleted` already cover the evidence
  side; `detection.attached` is a case event, not a bus event.
- **Flyway** `V<n>__integration_detections.sql` (next free version on `origin/main` at implementation time, V43 today; appended, never editing an old one) and the `evidence_items`
  kind `INTEGRATION` (next ordinal, CHECK updated in the same migration). Existing rows are untouched.
- **OpenAPI client spec bump** (`client/specs`), since the HTTP API changes.

Out of scope:

- **The Vulcan bridge plugin** (a Paper/Folia plugin that listens to Vulcan's events and posts them). It is a separate
  change, `add-vulcan-bridge`, with scope `vulcan`, proposed after this one is merged. This change only defines the
  contract the bridge must meet and the `vulcan/DETECTION` v1 schema, which the bridge change confirms against a real
  Vulcan install.
- **Any automatic punishment, ELO score or appeal decision from integration data.** Integration detections never move
  `chatElo` or `gameplayElo` and never trigger a punishment. Whether a detection should later feed the ELO engine is a
  separate decision with its own change (it touches principle 4).
- **Remote or third-party-provided UI components**, and any HTML from an integration.
- **Integrations that push to Blackhole over the Velocity proxy.** The bridge runs on the game server, where the
  anticheat's events are; the proxy does not see them.
- **Publishing detections** to the public ban list, the Discord bot or any third party. Detections stay staff data.
- **Per-player visibility for players.** Players learn nothing; the existing `evidenceCount` covers the count of
  attached evidence only.
- **A generic connector or webhook framework.** This change adds one push route with a registry; no outbound
  requests, no SSRF surface (see `.claude/rules/events.md`).
- **Player-facing or appeal-reviewer-facing detection cards.** Appeal reviewers see attached evidence through the
  existing evidence path, not through the integration cards.

## Capabilities

### New Capabilities

- `integration-evidence`: machine principal and registry, detection ingestion and idempotency, the kind/schema
  registry, staff-only storage and retention, attaching detections as evidence, the timeline event, the inbox count,
  and the panel renderer contract (registry, generic fallback, `VulcanDetectionCard`, trust wording, accessibility).

### Modified Capabilities

- `staff-authentication`: a new app role `Blackhole.Integration` maps to `INTEGRATION`. No existing role changes.
- `punishment-evidence`: evidence kind `INTEGRATION` is added. The lifetime and erasure rules are unchanged.

## Impact

- Backend: new `integration/` feature package (controller, `*Api` interface, service, sealed DTOs, schema registry,
  retention sweeper), `Roles.INTEGRATION`, `BlackholeRolesFinder` and `application.yml` (`blackhole.auth.machine-integration`,
  `blackhole.integration.*`), a Flyway migration, an evidence kind, a case event type `detection.attached`.
- Web: `web/src/components/integration/` (registry, generic renderer, `VulcanDetectionCard`), hooks in the evidence
  list, case timeline, player page (new "Detections" tab) and inbox.
- Ops: one Entra app registration per integration instance and one app role assignment; the bridge's secret is
  an operator concern (see design).
- Client: `client/specs` bump; the generated client gains the integration calls.
- Depends on: `add-punishment-evidence` (archived), `add-punishment-case-timeline` (for `case_event`) and
  `add-staff-panel-read-endpoints`. It does not depend on `capture-proxy-chat-evidence` or `structure-case-evidence`,
  but both touch the evidence screens, so the web tasks rebase onto them.
