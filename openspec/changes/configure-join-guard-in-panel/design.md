## Context

Checked on `main` at e4ddd17 (2026-10-30):

- The join guard is implemented on `main` under `backend/.../guard/` (openspec `add-join-guard`, not yet archived).
  `GuardSettings` holds the unattended level, the absence threshold, the night window, the zone and the presence TTL,
  read once at start-up by `GuardSettingsFactory` from `blackhole.guard.unattended.*`. Its constructor refuses an
  unattended level of `OFF`.
- `UnattendedEvaluator` is a singleton that takes the settings from the constructor. `GuardTickJob` runs
  `GuardTransitionService.reconcileAutomatic()` every `blackhole.guard.tick-interval` (30 s). That method reads the
  guard row from the database on every tick, then writes the automatic level only when it differs (one conditional
  update, `GuardStateStore.compareAndSet`).
- Replicas reload the in-memory effective level (`GuardStateCache`) on a poll every `blackhole.guard.snapshot-interval`
  (2 min) and after their own writes. The change event `guard.level_changed` is published, but no replica consumes it.
  `EventConsumers` lists only the Redis sync consumer.
- `GET /guard/state` is VIEWER; `POST /guard/level` is MODERATOR, PROXY or DISCORD and goes through `StaffDelegation`.
  Errors are `GuardDTO.Error(errorMessage, kind)`, where `kind` is an `ErrorKind` mapped to the HTTP status.
- The panel's `JoinGuardView.vue` reads `session.moderator` and `session.administrator` (`web/src/stores/session.ts`).
  The API client is hand-written in `web/src/api/blackhole.ts` against the generated types.
- Flyway: the latest migration on `main` is `V40__join_guard_change_surfaces.sql`. The next free version is `V41`.
- Client spec: `client/build.gradle.kts` points at `blackhole-api-0.0.64.yml`, the newest on `main`.

## Goals / Non-Goals

**Goals**

- A moderator-visible and admin-editable guard configuration, so the team changes the unattended behaviour without a
  deployment.
- No change in behaviour for an existing deployment at the moment of the upgrade.
- Every change is attributed to the token's `oid` and visible in the audit log and in the event stream.
- A change is in force within one evaluation tick, on every replica.

**Non-Goals**

- A second evaluator, a second loop or a per-replica copy of the settings that could drift.
- Editing the presence TTL, the burst rule or the tick interval from the panel. They are not part of this decision.
- Discord or Velocity editing.

## Decisions

### 1. The unattended level is `STRICT` or `LOCKDOWN`; `OFF` is refused

The automatic level is either `OFF` (nobody is unattended, or staff are on the proxy) or the configured unattended
level (`add-join-guard`, decisions 3 and 5). If the configured level were `OFF`, the unattended evaluator would compute
`OFF` in every case, so the unattended mode would be a no-op that looks configured. The panel would then accept a
setting that silently does nothing, and the team would believe the night window protects the network when it does
not. Refusing `OFF` makes that impossible. The current `GuardSettings` constructor already refuses it at start-up; the
same rule now applies to the request body (`400`, kind `VALIDATION`).

`LOCKDOWN` remains allowed, because `add-join-guard` says it may be set by the operator. In the panel only ADMIN may set it
(decision 5), and the confirmation dialog in the panel names it (decision 8).

### 2. Bounds for the other fields

| Field | Accepted | Refused and why |
|---|---|---|
| Absence threshold | Whole minutes, 5 to 240 | Below 5: a proxy restart or a heartbeat gap would close the network. Above 240: a threshold that long is a night window in disguise and belongs to that setting. |
| Night window | Enabled flag; start and end as `HH:mm`, 00:00 to 23:59; end exclusive | Start equal to end (an empty or full-day window is ambiguous). Anything not `HH:mm` (for example `7:00`) is refused so the stored text is canonical. |
| Night window crossing midnight | Allowed, for example `23:00-02:00` | Not refused. Existing `NightWindow` already handles it. |
| Time zone | An IANA region id from the runtime's zone list, at most 64 characters | Offsets (`+01:00`), `UTC` alone and unknown ids. The zone decides when night starts, so a fixed offset would drift at DST. |

The bounds are checked by a pure rules class (`GuardSettingsRules`), used by the service and tested without a database.
The database CHECK constraints repeat the level and the minute bounds as a second line of defence, but the messages come
from the rules class.

### 3. Storage: one row, seeded from `application.yml` on first read

`join_guard_settings` has one row (`id = 1`, `CHECK (id = 1)`), the same single-row pattern as `join_guard_state`.
Columns: `unattended_level`, `absence_threshold_minutes`, `night_window_enabled`, `night_window_start_minute`,
`night_window_end_minute` (minutes after midnight, 0 to 1439), `time_zone`, `updated_at` (epoch milliseconds) and
`updated_by_oid`.

**Defaults migration.** Flyway cannot read `application.yml`, so `V41__join_guard_settings.sql` creates the table with
no row. The first read seeds it: `JdbcGuardSettingsStore.current()` reads the row, and when it is missing, inserts the
values from `GuardSettingsFactory` with `INSERT ... ON CONFLICT (id) DO NOTHING`, then reads again. The insert is
idempotent, so two replicas that seed at the same time both succeed and store the same values. The seed uses
`updated_by_oid = NULL`, so the first row is recognisable as the configured one.

Existing deployments therefore keep their behaviour: the first tick after the upgrade reads the seeded row, which holds
the values the deployment already runs with. `join_guard_state` is not touched.

**After the first start the row wins.** A later change to `BLACKHOLE_GUARD_UNATTENDED_*` has no effect. This is the one
operational change, and the `application.yml` comment will say so. Rejected: falling back to the configuration whenever a
column is null. That makes two sources of truth for every field and the panel could not show which one is in force.

Rejected: a seed in the Flyway migration with hard-coded values. The defaults would then live in two places, in the SQL
and in `application.yml`, and they would drift.

### 4. Evaluation and propagation: the tick reads the row, and reloads the level

Each `GuardTickJob` run does three things, in order:

1. Reads the settings row (seeding it on the first read).
2. Computes the automatic level with `UnattendedEvaluator.automaticLevel(settings, presence, now)`. The evaluator takes the
   settings as an argument; it is no longer a singleton holding them.
3. Writes the automatic level when it differs (existing `reconcileAutomatic`), then reloads `GuardStateCache` so that the
   effective level of this replica follows the row within the same tick.

A changed setting therefore takes effect on the next tick, at most 30 seconds later, on every replica. The tick reads
both the settings and the guard row. That is two small reads per replica per 30 seconds, which the existing design already
accepts for the guard row alone.

**Deviation from the request.** The request asked for an event so that other replicas reload, following the existing
propagation of level changes. The existing propagation is a poll (`snapshot-interval`) plus a publish that no replica
consumes. A replica-side consumer would add a durable subscription for a gain of at most 30 seconds, when the tick
already bounds the delay. This change therefore publishes `guard.settings_changed` for alerting and audit consumers, and
relies on the tick for replica reload. Open question 1 asks whether the team wants the consumer anyway.

### 5. Routes, roles and DTO shape

- `GET /guard/settings`: `@Secured(Roles.VIEWER)`. Returns the settings and the next night window.
- `PUT /guard/settings`: `@Secured(Roles.ADMIN)`. Replaces the whole settings object. `MODERATOR`, `PROXY` and `DISCORD`
  are refused with `403`, which `RouteCoverageIntegrationTest` asserts.

Both routes live in `guard/controller/GuardSettingsApi` (OpenAPI annotations) and `GuardSettingsController` (thin, one service
call per route), under the existing `@Version(ApiVersion.V1)` and `/guard` prefix.

The DTOs follow the sealed-marker convention. The settings resource is a singleton with no identifier, so the request
is `UpdateRequest` with the whole body and no id; the response is `Response`; failures are `Error`. This deviates from the
convention's "UpdateRequest carries an identifier" only because a singleton has none.

Request: `unattendedLevel`, `absenceThresholdMinutes`, `nightWindowEnabled`, `nightWindowStart` (`HH:mm`),
`nightWindowEnd` (`HH:mm`), `timeZone`.

Response: the same fields, `updatedAt` (epoch milliseconds), `updatedByOid` (nullable, for the seeded row), and
`nextNightWindowStart` and `nextNightWindowEnd` as epoch milliseconds, or both null when the window is disabled. If the
window is open now, the current window is returned (its start is in the past). The calculation takes the injected `Clock`
and the zone, so the panel never does time-zone arithmetic itself.

Errors: `VALIDATION` (`400`) for any rule of decision 2, `NOT_FOUND` is not used because the row is seeded on read, and
`CONFLICT` is not used (decision 9).

### 6. Audit and event

- One `StaffAuditRecorder` entry with the new `AuditAction.GUARD_SETTINGS_SET` (`guard.settings.set`), attributed to the
  token's `oid` through the existing `Attribution` of a panel caller, with no target.
- One `guard.settings_changed` domain event after a successful write. Its payload is the new values and `changedAt`:
  `unattendedLevel`, `absenceThresholdMinutes`, `nightWindowEnabled`, `nightWindowStart`, `nightWindowEnd`, `timeZone`,
  `changedAt`, `source` (always `PANEL`). It never carries an actor name, an IP address, an owner hash or a player name.
  The payload test in `tasks.md` asserts the whitelist, as `GuardAlertPayloadTest` does for the level events.

The change is audited even when the values are equal to the stored ones, because the attempt by an admin is itself
worth recording. The event is published only when at least one value changed, so the Discord channel is not told about a
no-op save.

### 7. Spec deltas

The `join-guard` capability is not in `openspec/specs/` yet, so this change uses ADDED requirements. The requirement
"Unattended automatic level" in `add-join-guard` reads its threshold, window and zone from configuration. Once
`add-join-guard` is archived, that requirement and the night-window requirement must be changed to MODIFIED, with the
configuration wording replaced by "the stored settings, seeded from configuration on first start". The archive step of
this change is therefore the sync of those two requirements. Open question 2 asks whether to fold this change into
`add-join-guard` before it is archived instead.

### 8. The panel

- `JoinGuardView.vue` gets a settings section below the level section. Viewers and moderators see the values read-only,
  with the next night window. Admins see the form.
- The next night window is shown with `formatDateTime` on the two epoch values, so it is in the viewer's own zone, with
  the configured zone named next to it (for example "01:00 to 07:00, Europe/Berlin, which is 02:00 to 08:00 for you").
  When the window is disabled, the section says so and shows no times.
- The time zone selector lists `Intl.supportedValuesOf('timeZone')` where the browser provides it. The fallback is a single
  `Europe/Berlin` option, so the field is never empty. The server validation is the authority.
- Setting the unattended level to `LOCKDOWN` opens the same confirmation dialog pattern as the manual level. Saving any other
  change does not ask again.
- The form validates the bounds client-side with the same numbers as the server (5 to 240, start not equal to end). The
  server still answers the authority; a `400` shows its message in the form.
- German and English texts are added to `web/src/locales/de.json` and `en.json`; `i18n.keys.test.ts` checks that both
  files have every key.

### 9. Concurrency: last writer wins

`PUT` replaces the row unconditionally. Two admins saving the settings at the same moment get the last save, and each save
is audited, so the loss is visible in the audit log. Rejected: a version column with a conditional update and `409`. The
settings are edited a few times a year by two or three people, and the panel already shows the saved values after a save.
Open question 3 asks the team to confirm.

### 10. Rollback

Revert the code. The table stays, since it is additive, and the older code ignores it and reads the configuration.
Dropping the table is not needed to roll back, and the configuration values then apply again, which may differ from the stored
values an admin set in the meantime. The rollback note says so: write the desired values into `application.yml` before reverting.

## Risks / Trade-offs

- **Seed surprise.** An operator who changes `BLACKHOLE_GUARD_UNATTENDED_*` after the first start expects an effect and gets
  none. Mitigation: the `application.yml` comment, the proposal's impact note, and a log line at seed time that names the
  values written.
- **Night window typo.** A window set to the wrong hours closes the network at the wrong time. Mitigation: bounds and the
  next-window display in the viewer's zone, and the level history, which shows every change of the effective level.
- **Admin-only LOCKDOWN.** A moderator who wants `LOCKDOWN` unattended must ask an admin. That is intended by the request.
- **Two reads per tick.** Negligible at one replica per deployment, and bounded by the 30-second interval.

## Migration plan

1. Confirm `add-join-guard` code and the next free migration number (V41) and client spec version (0.0.65, or the next free
   one at implementation time).
2. `V41__join_guard_settings.sql`: additive, creates `join_guard_settings` with CHECK constraints and no row.
3. Deploy the backend. The first tick seeds the row from `application.yml`. Check with `GET /guard/settings` that the values
   are the ones the deployment ran with before the upgrade.
4. Deploy the web panel. Until it is deployed, the settings are still editable through the API by an admin.
5. Rollback: see decision 10.

## Open questions

1. Should replicas also consume `guard.settings_changed` and reload on the event, in addition to the tick? The tick already
   bounds the delay to 30 seconds (decision 4). The consumer would be a durable subscription for a gain of at most 30 seconds.
2. Should this change be folded into `add-join-guard` before that change is archived, so that `join-guard` is a single
   capability with MODIFIED requirements, or stay separate with ADDED requirements as written (decision 7)?
3. Is last-writer-wins acceptable for the settings (decision 9), or should a save fail with `409` when someone else saved in
   between?
4. Should a change to the unattended level or to the night window require a second admin, as `add-join-guard` open question 5
   asks for `LOCKDOWN` on the manual level?
5. Should the presence TTL and the tick interval join the panel later? They are excluded here (see `proposal.md`).
