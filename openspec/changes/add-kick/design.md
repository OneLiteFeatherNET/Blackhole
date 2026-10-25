## Context

The code this design builds on (checked on `main`, 2026-10-25):

- `backend/.../punishment/PunishmentEntity.java`: `punishments` rows carry `section_key`, `scope`,
  `permanent`, `lifted`, `escalation_group`, `step_index`, `step_count`, `elo_cost`, `meta_data`.
  The old `type` column is no longer mapped. The profile keeps active slots per section
  (`PunishmentProfileEntity.active`).
- `PunishmentApplicationService.plan` counts earlier punishments with
  `countByOwnerAndEscalationGroupAndLiftedFalseAndCreatedAtGreaterThanEquals` and picks the step with
  `StepSelector.select` (step `min(n, stepCount-1)`).
- `TemplateStep` is an `@Embeddable` (`duration`, `elo_cost`, table from V5).
- `EnforcementKind` is `MUTE | BAN | SERVER` and is shared by sections and the proxy.
- `velocity/.../enforcement/EnforcementActions.java`: `findPlayer(owner)` hashes every online player,
  `enforce` dispatches BAN (disconnect) and SERVER (`moveOffCoveredServer`, lines ~89-110, fallback
  server filtered by `activeServerBan`, else disconnect).
- `velocity/.../enforcement/Enforcement.java`: `coversServer` decides whether a server punishment
  covers the current server (lines ~57-74).
- `velocity/.../component/PunishmentScreens.java`: disconnect and chat screens, localized.
- `velocity/.../command/PardonCommand.java`: Cloud commands, `requiredSender = Player.class`,
  `@Argument(parserName = "target", suggestions = "players")`.
- `velocity/.../punish/BackendGateway.java` / `HttpBackendGateway.java`: the proxy's HTTP client.
- NATS (`switch-event-bus-to-nats`, D6/D7): backend is the only publisher; the proxy is subscribe-only
  over WebSocket and never publishes; subjects `blackhole.events.<type>`, stream `BLACKHOLE_EVENTS`.
- Flyway: `V24__player_seen.sql` is the head on `main`. `V25` is taken by the CSRF hash migration and
  `V26` by the staff profile migration (redesign-ux #374). The kick migration takes the next free version
  at implementation time (V27 or higher). No number is fixed in this change.
- `openspec/config.yaml` principles: GDPR (owner hashes only, no raw UUID or IP), layered backend,
  dual-ELO stays automatic, infra owns rate limiting, migrations append-only.

## Goals / Non-Goals

**Goals**: a recorded disconnect that staff can do from game, panel or template (a kick is a moment,
not a sanction, and cannot be undone); an honest online result; mass kicks that leave no per-player
trace.

**Non-Goals**: a kick that bans; a kick that waits for the player to come online; per-player records
for mass kicks; localized kick screens; any change to ban, mute or section semantics.

## Decisions

### 1. Storage: `KICK` is a punishment kind, not a new table

Add to `punishments`: `kind VARCHAR(16) NOT NULL DEFAULT 'PUNISHMENT'` (values `PUNISHMENT`, `KICK`),
`kick_scope VARCHAR(16) NULL` (`NETWORK`, `SERVER`, `SECTION`), `kick_target VARCHAR(255) NULL`
(server name or section key; never a player name). A kick is a row in `punishments` so that it sits
in the same history, the same case (`punishment_case`) and the same escalation count as every other
punishment. `identifier` is the kick's identifier; `permanent = false`, `lifted = false`, no expiry.

"Never active" is enforced in three places, each covered by a test:

- kicks are never written to `PunishmentProfileEntity.active` (the apply path for kicks does not call
  `placeAndRecord`);
- every active-state read filters `kind = 'PUNISHMENT'`: `JdbcActivePunishmentReader`,
  `ActivePunishmentExpirer`, `PunishmentExpirySweeper`, `hasActivePermanent`, the evidence-count
  join, and the proxy mirror writer (`PunishmentMirror`/`PunishmentRedisWriter`) which never sees a
  kick;
- `applyForRule`, `revoke` and unban do not accept a kick identifier as an active punishment (409).

Escalation keeps `lifted = false` and does **not** filter on `kind`, so a kick counts as an earlier
step of its escalation group. This is the decision the user asked for ("escalation counts kicks").

### 2. Template steps: `kind` on the step

`TemplateStep` gains `kind` (`PUNISH` | `KICK`). Migration adds `kind VARCHAR(16) NOT NULL DEFAULT
'PUNISH'` to the step table from V5 and makes `duration` nullable. Compatibility: every existing
step reads as `PUNISH`, so existing templates, their JSON and their generated client are unchanged
for them. Validation (`TemplateValidator`): `PUNISH` needs a valid ISO-8601 duration (as today);
`KICK` must have no duration (400 otherwise). A `KICK` step applies at the scope the apply request
names (`NETWORK` default; `SERVER` or `SECTION` with a target). The template's section is used only
for the escalation group and for the section check, so a kick template keeps a real section key.

### 3. Mass kick: one audit record, no per-player punishment

Recommendation (asked for in the brief): **one audited admin action, no per-player KICK rows.**
Reasons: maintenance is not misconduct, so a player's history must not show a punishment for it;
a mass kick of 500 players would write 500 rows, 500 case timelines and 500 ELO-free events that
say nothing a single line does not; and the backend does not know who is on a server (only the
proxies do), so per-player rows would need the proxies to write them, which the proxy cannot do
(subscribe-only). The action is recorded in a new `kick_action` table (id, scope, target, reason
code/text, actor, created_at, deadline_at) and in a `kick.mass` domain event. Its outcome (players
moved, proxies answered) lives in `kick_delivery`, counts only. It is visible in the admin audit
view, not in any player's history.

### 4. Routes

All routes are JSON, use Problem Details (`application/problem+json`, `standardize-api-pagination-and-problems`)
and are in `kick/controller/KickApi` (OpenAPI annotations on the interface; `KickController` thin;
`KickService` answers `KickDTO.Error`).

| Method and route | Who | Purpose |
|---|---|---|
| `POST /kick` | MODERATOR and up (`PROXY` denied) | Kick one player: body `KickDTO.CreateRequest {owner, scope, target?, reason, templateKey?}`. Header `Idempotency-Key` required. Answers **202** `KickDTO.Accepted {id, status: PENDING, deadlineAt, warnings[]}`. |
| `POST /kick/mass` | ADMIN | Mass kick: body `KickDTO.MassCreateRequest {scope: SERVER|SECTION, target, reason}`. Header `Idempotency-Key`. Answers **202** `KickDTO.Accepted` with `kind: MASS`. |
| `GET /kick/{id}` | VIEWER and up, and PROXY | Status and reason: `KickDTO.Response {id, kind, owner?, scope, target?, reason, actor, createdAt, status, deliveredCount, deadlineAt}`. The proxy uses it to read the reason before it shows the screen. |
| `POST /kick/{id}/deliveries` | PROXY only | Acknowledgement from one proxy: body `{proxyId, found, moved}`. Idempotent per `(id, proxyId)`; **204**. |

Errors: 400 `VALIDATION` (reason 1 to 200 characters, `PiiGuard`; a UUID, IP or Ban ID is refused),
404 `NOT_FOUND` (owner hash unknown, template unknown), 409 `CONFLICT` (a disabled section),
422 `KICK_TARGET_INVALID` (scope SERVER/SECTION without a target, or a target that is not a
server/section), 503 when the event could not be published after the write (see decision 5).
A player already permanently banned in the target section is **not** refused: the kick is accepted
and the response carries a `warnings` entry (decision 2026-10-25, point 3). The ban is not changed.

Status values: `PENDING`, `DELIVERED` (at least one proxy reports `moved` or `found` for the player),
`NOT_ON_TARGET` (a proxy found the player but not on the target server or section, nothing moved),
`NOT_ONLINE` (deadline passed, no proxy found the player). For mass kicks: `DELIVERED` with
`deliveredCount` = players moved, or `NO_PROXY_ANSWER` after the deadline when no proxy answered.
`NOT_ONLINE` is never written before `deadlineAt`.

The wait: `blackhole.kick.ack-timeout` (env `BLACKHOLE_KICK_ACK_TIMEOUT`, default `PT5S`), documented
in `application.yml`. The backend does not wait in the request: the status is derived when read
(`now >= deadlineAt` and no positive delivery means `NOT_ONLINE`), so no thread is held and the
clock is injected.

Idempotency: `Idempotency-Key` (1 to 80 of `[A-Za-z0-9._:-]`) derives the kick identifier, so a
repeat answers the same kick with `repeated: true` and writes nothing. Without the header the request
is refused (400), because a kick repeated by a double click is a second punishment.

### 5. NATS flow and the online feedback

Subjects (decision D1 of `switch-event-bus-to-nats`): `blackhole.events.punishment.kick` and
`blackhole.events.kick.mass`.

- Payload `punishment.kick`: `{eventId, kickId, owner, scope, target?, occurredAt}`. No reason text,
  no actor name, no IP or raw UUID. The owner is the hash the backend already publishes.
- Payload `kick.mass`: `{eventId, actionId, scope, target, occurredAt}`.
- `eventId` is the `Nats-Msg-Id` (D5). The backend publishes after the write, in a try/catch, as
  the other domain events do. A failed publish does not fail the request; the kick still exists and
  its status becomes `NOT_ONLINE` at the deadline (honest: no proxy was told). Counter
  `blackhole.kick.publish.failures`.

**Online feedback path (recommendation).** The proxy is subscribe-only, so it cannot publish a result
back over NATS. **Recommendation: the proxy reports over HTTP** with `POST /kick/{id}/deliveries`,
after it has acted on the event (or found nothing), using its machine token (client credentials, as
for every backend call). Reasons:

- The result is persisted in `kick_delivery` (PK `(subject_id, proxy_id)`), so any backend replica
  answers the status read; an in-memory waiter would break with two replicas.
- It uses the path the proxy already has for every write, so the proxy needs no new credential.
- A proxy that is down or cut off from HTTP causes no harm: the kick simply shows `NOT_ONLINE`.

`kick_delivery` stores `proxy_id` (a configured proxy name, not PII), `found`, `moved`, `acked_at`.
Counts are derived; no owner hash is stored there.

Proxy behaviour on the event (`blackhole.events.punishment.kick`):
1. `findPlayer(owner)`. Not connected here: ack `found=false, moved=false`. Done.
2. Connected, scope NETWORK: disconnect with the kick screen; ack `found=true, moved=true`.
3. Connected, scope SERVER or SECTION: if the current server is covered by the target (`ServerTargets`,
   `Enforcement.coversServer`), move off per decision 6; ack `found=true, moved=<result>`. Not covered:
   ack `found=true, moved=false`.

The proxy reads `GET /kick/{id}` for the reason before showing the screen; if that call fails the
proxy still kicks with the default English text ("You were removed from the network") and acks.

Cache rule: a `punishment.kick` event does not change the proxy's punishment cache (a kick is not a
punishment for the cache); the proxy's subscription to `blackhole.events.punishment.*` therefore
ignores the `kick` suffix explicitly, so no eviction runs for kicks.

### 6. Velocity behaviour

- Commands, in `velocity/.../command/KickCommand.java`:
  - `@Command("blackhole <user> kick [server|section] <reason>")` and `@Command("kick <user> [target] <reason>")`
    with `requiredSender = Player.class`, `@Permission("blackhole.kick")`; `target` completes from
    configured server names and section keys (`suggestions = "kickTargets"`), `user` from connected
    players and the backend (`target` parser, as `/unban`).
  - `KickAllCommand.java`: `@Command("kickall <target> <reason>")`, `blackhole.kick.mass`.
- The command sends `POST /kick` through `BackendGateway.kick(...)` on the async pool, then polls
  `GET /kick/{id}` with a delay until the status is settled or the local deadline passes. It never
  blocks the server thread. The message shows `DELIVERED`, `NOT_ON_TARGET` or `NOT_ONLINE`.
- `KickHandler` (new, `velocity/.../enforcement/KickHandler.java`) reuses the building blocks:
  `EnforcementActions.findPlayer`, `Enforcement.coversServer`, `ServerTargets.covers`,
  `PunishmentScreens` (new `Surface.KICK`, English only), and the fallback move. To reuse without
  duplication, extract `moveOffCoveredServer`'s fallback part into `EnforcementActions.moveToFallback(player, screenFactory)`
  and call it from both `enforce` and `KickHandler`. The extraction is a refactor in the same PR
  (`feat(velocity)` commit, its own test).
- Fallback behaviour: move to `config.fallbackServer` if it exists and the player is not banned from
  it (`activeServerBan`); if the fallback is missing, unreachable or is the server being kicked from,
  disconnect with the kick screen. A move that fails is logged (player name only) and becomes a
  disconnect.
- `PunishmentScreens` gains `Surface.KICK`, `en` only, `Component`-based, no localized variants.

### 7. Panel (`web/`)

- **Kick dialog** (`MdDialog`, two fields, so a dialog and not a page): scope picker
  (network / server / section, a segmented control), target select appears for server or section
  (values from the existing sections and server lists), reason text field (1 to 200 characters,
  inline PII hint). Submit sends `POST /kick` with a generated `Idempotency-Key`, then polls
  `GET /kick/{id}` every second for up to the ack timeout plus one second. The result is shown as a
  chip: `Delivered`, `Not on that server`, `Not online`, `No proxy answered`.
- Entry points: a `RowMenu` action "Kick" on the player row and on the player page header; the
  kick action in the case page's actions. Redesign-ux components only (`RowMenu`, `MdDialog`,
  `FilterToolbar` for the filter if the history list is filtered by kind).
- **Mass kick** in the admin area (`web/src/.../admin/`): a page section with a single form, not a
  dialog (it is rare and needs a confirmation step of its own: the target name typed back, no second
approval; decision 2026-10-25, point 4). Visible
  to ADMIN; the server enforces the role anyway. Shows the outcome counts.
- The history list shows kick rows with a "Kick" badge; they are not counted in "active" counters.

### 8. ELO and escalation

- A `KICK` step applies its `eloCost` through the existing template ELO path (`EloScoring`,
  `template ELO delta`), with the same dedupe and history event as a ban step.
- A kick without a template costs no ELO and has no escalation group.
- Mass kicks cost no ELO and create no punishment, so no ELO.
- Escalation: a kick counts as an earlier step in its group (decision 1). Example: group
  `chat-spam`, step 1 `KICK`, step 2 `PUNISH P1D`. First offence: kick; second offence within the
  window: the one-day ban; the kick does not expire the window.

### 9. Compatibility and generated client (BREAKING)

Adding `KICK` to `kind` (punishment) and `kind` (template step) is an additive schema change, but
the generated Java client maps enums strictly: an unknown value fails to deserialize. Kick rows
appear in the player history (decision 1), so an older Velocity build that reads a history containing
a kick would fail to parse it. Mitigation, in order:

1. The client spec adds `KICK` to both enums in the next free `client/specs/blackhole-api-*.yml`
   version, and the generated client is regenerated in the same PR as the API.
2. The Velocity PR (`feat(velocity)`) depends on that client version and is not merged before it.
3. The release note of the Velocity plugin says that it needs this backend and the kick-aware client.
4. No compatibility switch (no version header, no filtered history for old clients). The control is
   the PR order and the release note; this is why the change is marked **BREAKING** in the proposal.

### 10. Migration

`V<next>__kick.sql`, where `<next>` is the next free Flyway version at implementation (V27 or
higher; V25 and V26 are taken). Append-only:
- `ALTER TABLE punishments ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'PUNISHMENT'`, `kick_scope`, `kick_target`.
  Existing rows: `kind = 'PUNISHMENT'` via the default, nothing rewritten.
- `ALTER TABLE <template step table> ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'PUNISH'`; make
  `duration` nullable. Existing steps keep their values.
- `CREATE TABLE kick_action (...)`, `CREATE TABLE kick_delivery (subject_id UUID, proxy_id VARCHAR(64),
  found BOOLEAN, moved BOOLEAN, acked_at BIGINT, PRIMARY KEY (subject_id, proxy_id))`.
- Index `punishments (kind, owner)` for the history read.
- A CHECK constraint on `kind` and `kick_scope` values. Liquibase is not used here (Flyway); the
  `liquibase-checker` agent does not apply.

### 11. Case timeline

New case event type `kicked` (`CaseEventPayload.Kicked(scope, target?, eloCost?)`), recorded by
`CaseTimeline.kicked(...)` after the kick is written, dedupe key `kicked:<id>`. No duration, no
section step fields beyond `eloCost`. Delivery outcome is not a timeline event (it would make the
timeline follow the proxies, which is noise); it is on the kick's status read. The mass kick has no
case event.

### 12. Tests (F.I.R.S.T.)

- Backend unit (no DB, injected `Clock`): `StepSelector` with kicks in the count; `TemplateValidator`
  (KICK without duration ok, KICK with duration refused, PUNISH without duration refused); status
  derivation (`PENDING` before the deadline, `NOT_ONLINE` after, `DELIVERED` on a positive ack, never
  `NOT_ONLINE` before the deadline); `KickValidator` (scope and target rules, PII refused).
- Backend integration (Postgres via the existing test support, no sleeps; the clock is a fixed
  instant): a kick is not in `active` and not enforced on login; a kick counts in escalation; a repeat
  `Idempotency-Key` writes nothing; an ack twice from the same proxy stores one row; mass kick writes
  no `punishments` row; `KickEventsNoLeakTest` (like `BanIdNoLeakTest`) scans the published payloads
  for raw UUIDs, IPs and reason text.
- Velocity unit (no server): command argument parsing; `KickHandler` decision function (given a
  connected player, a server and a covered set, returns the action) with a stub `ProxyServer` facade;
  fallback selection; `PunishmentScreens` kick screen is English for any locale.
- Web component tests (Vitest, no network): kick dialog validation and status chips; mass kick
  requires the typed target name; the polling stops at the settled status (fake timers).
- Every backend and Velocity task group lands its own JUnit tests; every web group its own component
  tests (`config.yaml` rules).

## Risks / Trade-offs

- **A kick cannot be undone.** Accepted: it is a moment, not a sanction. Documented in the dialog.
- **Players on a fallback server are kicked to the same server** if the target is the fallback. The
  handler disconnects instead (decision 6).
- **Kicks inflate escalation** if staff kick often. Accepted by the user decision; the template
  window (`escalation-window`) bounds it.
- **`NOT_ONLINE` is not proof the player is offline**: a proxy may be unreachable. The status text
  says "not found on any answering proxy" in the UI.
- **Two replicas, two proxies**: the status is DB-derived; no in-memory state.
- **Actor**: every write's actor is the Entra ID token's `oid`, never a client-supplied field
  (`config.yaml` principle 2; `@Secured`; authoritative since PR #363). The kick record stores that oid.

## Decisions (2026-10-25)

The user decided the four open questions of the first draft. The specs and `tasks.md` follow them.

1. **Mass-kick command is `/kickall <server|section> <reason>`** (permission `blackhole.kick.mass`).
   `/kick all ...` is not used.
2. **A kick step with no scope in the apply request defaults to `NETWORK`.** This applies to template
   `KICK` steps (decision 2) and to any kick whose request names no scope.
3. **Kicking a player who is already permanently banned in the target section is accepted, with a
   warning.** The kick is recorded and answered `202` with a `warnings` entry. No `409`, and the ban is
   not changed.
4. **Mass-kick confirmation requires typing the target name. There is no second approval.** The panel
   enables submit only when the typed name matches exactly; the server still enforces ADMIN.

Other items settled on the same date:

- **Migration**: the kick migration takes the next free Flyway version at implementation time (V27 or
  higher). V25 is the CSRF hash migration and V26 the staff profile migration (redesign-ux #374). No
  fixed number appears in this change.
- **Auth**: the auth conflict in the first draft is resolved. PR #363 removed the stale "no auth" text.
  Entra ID with `@Secured` is authoritative (`openspec/config.yaml`, principle 2), and the actor of a
  kick is the token's `oid`. The former "identity gap" risk and open question are withdrawn.
