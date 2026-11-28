## Why

A small community has no moderator online at night. Attackers arrive in waves from a few hosting
providers and countries, and staff read about them in the morning, after the damage is done. ELO
punishments already act without a moderator, but nothing stops a wave from getting in.

Staff want to close a whole area in one action (a provider or a country), want the network to
tighten by itself while nobody from the team is on the proxy, and want to see in the morning what
was refused and why. The network must open again as soon as staff come back.

## What Changes

- **Area bans.** One action bans an autonomous system (ASN, for example a hosting provider) or a
  country (ISO 3166 alpha-2), with a reason and an optional expiry. It can be done from the panel,
  or in game with `/areaban asn <number> [duration] <reason>` and `/areaban country <ISO> [duration] <reason>`.
  Area bans are checked in the same login check as the VPN policy (`add-vpn-proxy-detection`).
  CIDR ranges stay in `add-ip-bans-via-firewall`.
- **Join guard levels.** The network runs at one of three levels:
  - `OFF`: only area bans and the VPN policy apply.
  - `STRICT`: a login from a `VPN`, `HOSTING` or `PROXY` address is refused, whatever the VPN policy says.
  - `LOCKDOWN`: `STRICT`, and additionally only players already known to the network (at least
    `blackhole.guard.lockdown.min-playtime` of recorded playtime) may join. Others get a screen that says
    the network is currently only open to known players.
- **Two sources for the level.** A manual level, set from the panel, by `/guard <level>` in game or by a
  Discord command. An unattended level, which the backend sets on its own while no staff member is on
  the proxy, either after `absence-threshold` (default 20 minutes) or inside a configured night window.
  The effective level is the higher of the two. The unattended level defaults to `STRICT`. `LOCKDOWN`
  is never set automatically unless the operator configures it.
- **Staff presence.** Each proxy reports every 30 seconds how many players with the staff permission
  node are online. Staff are present while a proxy reported them within the last five minutes.
  When staff come back, the unattended level clears at once.
- **Alerting in staff Discord.** Every level change and every area ban (manual or automatic) posts to the
  existing staff notification channel, through the existing notification path and no new webhook.
  Refused joins are summarised every 10 minutes per ASN instead of one message per login, so a wave of
  several hundred joins does not flood the channel. Messages carry ASN, organisation, country, level and
  counts. They never carry an IP address, an owner hash or a player name.
- **Burst rule (off by default).** When `blackhole.guard.burst.enabled` is set, N guard refusals from one ASN
  within M minutes create an automatic area ban for that ASN. It is marked `AUTO_BURST`, expires after
  `blackhole.guard.burst.ban-duration` (default 2 hours) and is announced at once.
- **Exemptions.** Linked staff members are never blocked by guard levels or area bans. Players on the VPN
  allow-list are never blocked by guard levels, but an area ban still applies to them.

## Capabilities

### New Capabilities

- `join-guard`: the guard levels, the manual and unattended sources and their effective level, staff
  presence, the known-player rule, the fail mode per level, the level history and the alerting rules.
- `area-bans`: the ASN and country ban records, who may create and lift them, enforcement in the login
  check, the `/areaban` command, and the burst rule that creates automatic area bans.

### Modified Capabilities

- `proxy-enforcement`: the area-ban and guard denial screens, the fail mode on timeout per level, the
  staff presence heartbeat and the `/guard` command.
- `admin-web-ui`: the join guard page, the area bans page and a guard tile on the dashboard.

## Out of scope

- CIDR and single-address bans. Those are `add-ip-bans-via-firewall`.
- Per-login calls to an external provider or to a geolocation service. Classification uses the offline
  data that `add-player-intelligence` provides.
- Any new notification webhook or a Discord ban command. Staff Discord notifications reuse the existing
  path, and the Discord `/guard` command only sets the level.
- Automatic punishments of players. Guard refusals and burst rules block connections; they never create a
  player punishment. Staff who want a player ban create one through the usual flow.
- An appeal form for area bans. The screen points to staff contact, as the VPN screen does.
- A proxy-side copy of the level or of the area bans that survives a proxy restart during a backend outage.
  See `design.md`, decision 8.
- Rate limiting of joins or of the heartbeat. Infra owns rate limiting (`config.yaml`, principle 5).
- Behaviour-based detection of attacks. The burst rule counts refusals only.

## Prerequisites

- `add-vpn-proxy-detection`: the `POST /intel/login-check` route, the VPN allow-list and the proxy login
  check. Not yet on `main`. This change does not start before it is merged.
- `add-player-intelligence`: the offline ASN, organisation and country for a login address, and the
  recorded playtime per owner (the known-player rule). It has no proposal folder on `main` yet. Stop and report
  if it is still missing when this change is picked up.
- Already on `main`: `staff-account-link` (linked staff are exempt and attribute in-game commands) and
  `verified-actor` (panel actions are attributed to the token's `oid`).
- Not a prerequisite: `add-staff-audit-log` (not on `main`). The `join_guard_change` table in this change
  is the level history; it is mirrored to the audit log when that change lands.

## Impact

- **Backend**: a new `guard/` feature package (level evaluator, unattended evaluator, presence store,
  change history, summary job, burst rule) and a new `areaban/` feature package (controller, service, DTOs,
  JDBC store). The login check service of `intel/` calls both. `application.yml` (`blackhole.guard.*`).
- **Database**: one Flyway migration `V<next>__join_guard.sql` (`area_ban`, `join_guard_state`,
  `join_guard_change`), additive, the next free version at implementation time. Dragonfly holds the presence
  hash and the burst and summary counters; nothing is written to the database per login or per heartbeat.
- **API**: `GET /guard/state`, `POST /guard/level` (MODERATOR, ADMIN), `POST /guard/presence` (role PROXY),
  `GET /area-bans`, `POST /area-bans`, `POST /area-bans/{id}/lift`. The login-check response gets an
  additive `denialReason` field. `client/specs/blackhole-api-*.yml` gets the next free version.
- **Velocity**: `/guard` and `/areaban` commands, the 30-second heartbeat, the three new screens, and the
  fail mode per level in the login check.
- **Discord**: the bot routes `guard.*` and `area_ban.*` events and renders them from their payload, and a
  `/guard` slash command gated by the staff Discord role.
- **Web**: a join guard page, an area bans page and a dashboard tile.
- **Licence / privacy**: no new personal data. Area bans hold an ASN or a country code. The level history
  holds an actor `oid` for panel and in-game actions only.

**BREAKING for proxies**: a Velocity build without this change shows the VPN screen for any `DENY` verdict
that has no `denialReason`. Deploy the Velocity release before the first area ban or guard level above `OFF`
is set. The migration notes in `design.md` state the order.

Ships as: feat(guard): add area bans and an unattended join guard
