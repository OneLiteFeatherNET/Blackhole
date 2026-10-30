## Why

The unattended join guard is configured only in `application.yml`. Its settings are the absence threshold (20 minutes),
the night window (01:00-07:00), the zone (Europe/Berlin) and the unattended level (`STRICT`). Changing any of them needs
a configuration change and a restart of every replica, so the team cannot adjust the guard to a wave or to a quiet
season from the panel. A moderator who wants a longer night window, or an admin who wants `LOCKDOWN` while the
network is unattended over a holiday, has to ask someone with deployment access.

The team has decided that these settings belong in the panel, so that the people who run the network can change them
when they need to, and so that every change is attributed and visible.

## What Changes

- **Settings in the database.** The unattended settings move into one database row. The row is seeded on first start
  from the existing `blackhole.guard.unattended.*` values, so an existing deployment keeps its behaviour. After that
  first start the row is the source of truth, and later changes to those environment variables have no effect.
- **Editable fields and their bounds:**
  - unattended level: `STRICT` or `LOCKDOWN`. `OFF` is refused (see `design.md`, decision 1).
  - absence threshold: 5 to 240 minutes, whole minutes.
  - night window: an enabled flag, and a start and an end time in `HH:mm` (end exclusive; a window may cross midnight;
    start and end may not be equal).
  - time zone: an IANA region id that the runtime knows (for example `Europe/Berlin`). Offsets such as `+01:00` are refused.
- **API.** `GET /guard/settings` (VIEWER) returns the settings, the updated time and the next night window as epoch
  milliseconds. `PUT /guard/settings` (ADMIN only) replaces the settings. Errors use the same error body and kinds as the
  existing guard routes. A moderator who sends `PUT` gets `403`.
- **Audit and event.** A successful `PUT` writes one staff audit entry (`guard.settings.set`) attributed to the token's
  `oid`, and publishes one `guard.settings_changed` domain event carrying the whitelisted values only.
- **Effect.** The unattended evaluator reads the stored settings on every tick. A change is evaluated by the next tick
  (`blackhole.guard.tick-interval`, 30 seconds by default), and each replica reloads the effective level in the same tick.
- **Panel.** A settings section on the existing join guard page. Admins edit it; viewers and moderators see it read-only.
  Setting `LOCKDOWN` as the unattended level asks for confirmation, as the manual `LOCKDOWN` already does. The page shows
  the next night window in the viewer's own time zone. Texts are in German and English.
- **OpenAPI client.** The spec gets the two routes and their schemas at the next free version.

## Capabilities

### New Capabilities

None. The settings belong to the existing join guard.

### Modified Capabilities

- `join-guard`: the unattended settings are stored and editable, with bounds, a seed on first start and a
  propagation rule. This is written as ADDED requirements because the `join-guard` capability is not yet in
  `openspec/specs/` (it is pending in `add-join-guard`). When `add-join-guard` is archived, the requirements that read
  the unattended settings from configuration become MODIFIED. See `design.md`, decision 7.
- `admin-web-ui`: the join guard page gets the settings section and the next night window.

## Out of scope

- Discord editing of the settings. The Discord bot keeps only `/guard <level>` (the manual level).
- Velocity editing of the settings, and any change to the proxy. The proxy receives the effective level as before.
- The presence TTL, the summary interval, the tick interval and the burst rule. They stay in `application.yml`.
  Only the unattended level, the absence threshold, the night window and the zone move to the panel.
- Per-day or per-weekday night windows, and more than one night window.
- Changes to the manual level, to the area bans or to the burst rule.
- A history table for the settings. The audit entry and the event are the record.

## Impact

- **Backend**: `guard/` gets the settings record and its rules, the next-window calculation, a JDBC store that seeds the row
  on first read, the settings service, the controller and the DTOs. The unattended evaluator takes the settings as an
  argument instead of a singleton. The tick reloads the effective level.
- **Database**: one Flyway migration `V41__join_guard_settings.sql` (next free version at implementation time). It creates
  `join_guard_settings` with CHECK constraints and no seed row. The application inserts the row from `application.yml`
  values. No existing table changes.
- **API**: `GET /guard/settings` (VIEWER) and `PUT /guard/settings` (ADMIN). Additive; the existing routes do not change.
  `client/specs/blackhole-api-0.0.65.yml` (or the next free version at implementation time) and `client/build.gradle.kts`.
- **Web**: `web/src/views/JoinGuardView.vue`, a settings form component, the API client, and the German and English locales.
- **Configuration**: `blackhole.guard.unattended.*` keeps its names and defaults. Its comment in `application.yml`
  changes to say that the values seed the first start only.
- **Licence / privacy**: no personal data. The settings hold a level, minutes, times and a zone. The audit entry holds the
  actor's `oid`, as every panel write does.

**Not BREAKING**: the change only adds routes and a table, so generated clients keep compiling. The one behavioural
change, that later edits of the environment variables have no effect after the first start, is an operations note,
not an API change.

Ships as: feat(guard): adjust the unattended join guard in the panel
