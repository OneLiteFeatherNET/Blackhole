## Context

Checked on `main` at 3115484 (2026-10-28):

- `openspec/specs/proxy-enforcement` already runs a synchronous backend call per login (evasion sighting,
  then the ban check). `add-vpn-proxy-detection` adds `POST /intel/login-check` after those checks. This
  change puts area bans and the guard into that same call, so a login still costs one backend round trip.
- `openspec/specs/staff-account-link` maps a Minecraft UUID to a staff `oid`, and attributes in-game actions
  through `onBehalfOf`. Linked staff are identified on the backend by their owner hash.
- `openspec/specs/verified-actor` takes the actor of panel writes from the token's `oid`.
- Discord notifications exist (`discord/.../notify/`): `NotificationEvent` parses backend events by routing
  key and today handles only `report.*` and `appeal.*`.
- Dragonfly is the shared cache and proxy-sync store (`shared-cache`, archived `adopt-dragonfly-cache`).
- `@Transactional` is unusable here (`config.yaml`, principle 6). Every multi-step write below is correct
  without a transaction, because each step is either a single conditional `UPDATE` or an idempotent insert.

## Goals / Non-Goals

**Goals**

- Close a provider or a country in one action, from the panel or in game, effective within one login.
- Tighten the network while nobody from the team is online, and open it again the moment someone is.
- Give the team a morning view: every level change, every area ban and a count of refused joins per ASN.
- Keep staff and the VPN allow-list usable, so the guard does not lock out the people who run the network.

**Non-Goals**

- A second login path or a second copy of the rules in the proxy. The proxy only asks, and enforces the answer.
- Certainty about who is an attacker. Refusals are blunt by design, which is why the default level is
  `STRICT` and not `LOCKDOWN`, and why every refusal is visible.
- Surviving every failure without a gap. Decision 8 states the gaps and who accepts them.

## Decisions

### 1. Area bans are one record per ASN or country, not an extension of IP bans

`area_ban` rows hold `kind` (`ASN` or `COUNTRY`), `value` (the ASN as digits without `AS`, or an uppercase
ISO code), `reason` (1 to 200 characters), `source` (`MANUAL` or `AUTO_BURST`), `actor_oid` (NULL for
automatic and Discord actions), `created_at`, `expires_at` (nullable), `lifted_at`, `lift_reason` and
`lifted_by_oid`. A partial unique index on `(kind, value) WHERE lifted_at IS NULL` allows one active ban per
area. Expired and lifted rows stay as history. An ASN or a country code is not personal data, so there is no
retention sweeper for them.

Rejected: putting ASNs into `ip_bans`. That table holds addresses and CIDR ranges that the firewall alias
enforces, and an ASN has no range the firewall can use. Rejected: country bans as lists of CIDR ranges. That
needs a geo-IP range file and a firewall alias per country, and a range list goes stale.

### 2. Enforcement in the existing login check, in a fixed order

`POST /intel/login-check` (from `add-vpn-proxy-detection`) evaluates these steps in this order and stops at
the first refusal:

1. A malformed owner or address is refused, as today.
2. A linked staff owner gets `ALLOW`. Staff are exempt from guard and area bans.
3. An active area ban for the address's ASN or country (not lifted, not expired) gets `DENY` with
   `denialReason = AREA_BAN`. The VPN allow-list does not exempt from this step (decision 9).
4. An allow-listed owner skips the guard (step 5) and goes to step 6.
5. The effective guard level (decision 3): at `STRICT` or `LOCKDOWN`, a non-`CLEAN` classification gets `DENY`
   with `denialReason = GUARD_STRICT`. At `LOCKDOWN`, an owner who is not known (decision 7) gets `DENY` with
   `denialReason = GUARD_LOCKDOWN`. A refusal here is counted for the burst rule and the summary (decisions 10, 11).
6. The VPN policy from `add-vpn-proxy-detection`, unchanged.

Rejected: a separate `POST /guard/check`. It is a second round trip per login and a second place where the
order can drift. Rejected: copying area bans and the level into each proxy. A level change would then need a
proxy release or a fan-out to every proxy, and a stale list would keep refusing after an area ban was lifted.

`denialReason` is an additive field. `VPN_POLICY` is set for the existing `DENY` of the VPN policy.

### 3. Two sources, one effective level: effective = max(manual, automatic)

`join_guard_state` is a single row with `manual_level`, `automatic_level` and `effective_level`. Levels are
ordered `OFF` < `STRICT` < `LOCKDOWN`. The effective level is the higher of the manual and the automatic level.

- The manual level changes only when a staff member sets it (panel, `/guard`, Discord). It stays until someone
  changes it. A manual `LOCKDOWN` is not undone by staff coming online.
- The automatic level is written only by the unattended evaluator (decision 5). It is `OFF` or the
  configured unattended level.

Rejected: one level field that the timer writes. Then a timer tick would overwrite a manual `LOCKDOWN` set
at 01:00, and staff who set `OFF` while the network is unattended would see it flip back on the next tick
without any record of who did what. With two fields, the panel can show both and say which one is in force.

### 4. Staff presence: heartbeats from each proxy, held in Dragonfly

Each Velocity proxy sends `POST /guard/presence` every 30 seconds with its proxy id and the number of online
players with the permission node `blackhole.staff`. The backend stores a Dragonfly hash `guard:presence`
(field: proxy id, value: count and the timestamp in milliseconds from the injected `Clock`). Entries older than
`blackhole.guard.presence.ttl` (default 5 minutes) are ignored, so a proxy that dies stops counting. A separate
key `guard:last-staff-seen` holds the last time any proxy reported a count above zero.

Staff are present when any non-stale entry has a count above zero. The heartbeat response carries the effective
level, so each proxy learns a change within 30 seconds.

Rejected: join and quit events over the bus. A lost event, or a restart of the backend, would leave presence
wrong with no way to repair it. Rejected: a database row per heartbeat. It is a write every 30 seconds per proxy
for state that is worth nothing after five minutes. Rejected: deciding on each proxy alone. Each proxy sees only its
own players, and a multi-proxy network would close while staff were on the other proxy.

If Dragonfly loses its data, `guard:last-staff-seen` is missing. The evaluator treats a missing value as long
absent. The effect is a guard level that is on for up to 30 seconds longer than needed, until the next heartbeat with
staff. This fails toward the guard, and it is the accepted direction.

### 5. Unattended evaluator: absence threshold or night window, never LOCKDOWN by default

A scheduled tick runs every 30 seconds in the backend. It computes the automatic level from pure inputs: staff
present, the last-staff-seen time, the injected `Clock`, the configured zone and night window, and the configuration:

- Staff present: automatic level `OFF`.
- Staff absent and (absent for at least `blackhole.guard.unattended.absence-threshold`, default `PT20M`, or the local
  time in the zone is inside `blackhole.guard.unattended.night-window`, default `01:00-07:00`): automatic level is
  `blackhole.guard.unattended.level`, default `STRICT`. The level must not be `OFF`. `LOCKDOWN` is allowed only
  when the operator sets it.
- Otherwise: automatic level `OFF`.

The night window and the absence threshold are both triggers, so a night with a staff member online at 03:00 stays
open, and a night with nobody online from 01:00 closes at 01:00, not at 01:20. The zone default is `Europe/Berlin`
(to be confirmed, see open questions).

Transitions are written with one conditional update, `UPDATE join_guard_state SET effective_level = :new, ...
WHERE id = 1 AND effective_level = :old`. Every replica may run the tick, and only the replica whose update changes
the row publishes the event and writes the history row. No transaction and no lock are needed.

### 6. Refused joins are summarised, not posted one by one

Every refusal increments a Dragonfly hash `guard:summary` (field: ASN, value: count). A scheduled job every
`blackhole.guard.summary-interval` (default `PT10M`) reads and deletes the hash in one `MULTI`, and publishes one
`guard.denials.summary` event with the window, the total and the top 10 ASNs with their count, organisation and
country. An empty window publishes nothing.

This deviates from "one post per refused login". A wave of several hundred joins would otherwise produce several
hundred messages in the staff channel, which hides the one line that matters. Level changes, area bans and burst
bans are still posted at once. Open question 2 asks the maintainer to confirm the cadence.

### 7. What counts as a known player

A player is known when the owner's recorded playtime (from `add-player-intelligence`) is at least
`blackhole.guard.lockdown.min-playtime`, default `PT60M`. An owner with no recorded sessions is not known. The owner
hash is the key, as elsewhere. No name or address is needed for the decision.

Rejected: "has joined before". One earlier session of two minutes would then open the network to a bot account
that a wave can create at will. Rejected: "on the allow-list". The allow-list is for VPN users and is kept small,
so it would make `LOCKDOWN` nearly absolute.

### 8. Fail mode per level when the backend does not answer

The proxy keeps the last effective level it received, from the heartbeat or from a login-check response, in memory.
It starts at `OFF`. When the login check times out, errors or returns an unknown verdict:

| Cached level | Non-staff player | Staff permission holder | Why |
|---|---|---|---|
| `OFF` | Allowed (as `add-vpn-proxy-detection`) | Allowed | A broken backend must not lock out everyone. |
| `STRICT` | Allowed, counter `blackhole.guard.failopen` increments | Allowed | Fail-open. The guard is a filter, and a backend outage must not lock every player out. Area bans are not enforced during the outage; accepted. |
| `LOCKDOWN` | Refused with the `GUARD_LOCKDOWN` screen, counter `blackhole.guard.failclosed` increments | Allowed (checked on the proxy by permission) | Fail-closed. `LOCKDOWN` is set by staff for the case where unknown players must not get in. During an outage the proxy cannot tell known from unknown, and it chooses the stated intent. |

Two gaps are accepted and recorded here. A proxy that starts during an outage does not know the level and fails
open until its first heartbeat. A `LOCKDOWN` that was set while the backend was up is enforced by the proxies that
still hold it in memory, so a proxy restart during an outage removes it. The alert on `blackhole.guard.failclosed`
and on the heartbeat failure counter tells the team when either happens.

### 9. Exemptions: staff everywhere, the allow-list only from guard levels

- Linked staff are exempt from guard levels and area bans. A staff member who must be kept off the network gets a
  player ban, not an area ban. A staff member who is caught by an area ban can still reach the panel and lift it.
- The VPN allow-list exempts a player from guard levels, but not from area bans. An area ban is a decision that
  staff took about a whole provider or country. An allow-listed player on that provider is exactly the case the
  ban was made for, and staff can lift the ban from the panel. Open question 3 asks whether this is right.

### 10. Burst rule: sliding window in Dragonfly, off by default

When `blackhole.guard.burst.enabled` is `false` (default), nothing is counted and nothing is created. When it is
`true`, each guard refusal (decision 2, step 5 only) adds an entry to the sorted set `guard:burst:{asn}` scored by the
time from the injected `Clock`. Entries older than `blackhole.guard.burst.window` (default `PT5M`) are removed. If the
set holds at least `blackhole.guard.burst.threshold` (default 10) entries and no active area ban exists for the ASN, an
`AREA_BAN` row is inserted with `source = AUTO_BURST`, `actor_oid = NULL` and `expires_at = now + ban-duration` (default
`PT2H`). The partial unique index makes a second replica's insert a no-op, and the event is published only by the
replica whose insert succeeded.

A sliding window is used instead of fixed buckets, because a burst that straddles a bucket boundary would be
counted as two smaller bursts and could miss the threshold. Refusals by area bans and by the VPN policy are not
counted, because they are decisions already taken.

### 11. Alerting: one path, no identity data in any message

Level changes (`guard.level_changed`), manual or automatic, and area bans (`area_ban.created`, `area_ban.lifted`) are
published on the existing domain event bus (`blackhole.events`, whichever transport `switch-event-bus-to-nats` leaves
in place). The Discord bot routes these routing keys through the existing `NotificationEvent` path and renders them
from the payload only. Summaries (decision 6) use the same path.

Payload whitelist: ASN number, organisation name, country code, level, source (`MANUAL`, `UNATTENDED`, `CLEARED`,
`AUTO_BURST`, `MANUAL_BAN`), counts, window, expiry, and the actor's display name if the actor is a linked staff
member. No IP address, no owner hash, no player name, no UUID. The same whitelist applies to log lines written for these
events, so the appender test in tasks asserts it.

### 12. Who may do what

| Action | MODERATOR | ADMIN |
|---|---|---|
| Set the guard level to `OFF` or `STRICT` (panel, `/guard`, Discord) | Yes | Yes |
| Set the guard level to `LOCKDOWN` | Yes | Yes |
| Area ban on an ASN, up to 7 days | Yes | Yes |
| Area ban on an ASN, permanent or longer than 7 days | No | Yes |
| Area ban on a country | No | Yes |
| Lift any area ban (reason required) | Yes | Yes |

Lowering a level is not restricted, because it only opens the network. Country bans are ADMIN-only because one country
can cover a large share of the player base.

Attribution: panel actions take the token's `oid` (`verified-actor`). In-game actions go through `onBehalfOf` and the
staff link (`staff-account-link`), with `actor_oid` NULL for an unlinked UUID. Discord actions use the Discord staff role
check in the bot, and record `source = DISCORD` with `actor_oid` NULL. Discord users are not linked to Entra identities
today (open question 4).

### 13. Ban durations and the defaults that protect the team

- Area bans without an expiry are possible only for ADMIN (decision 12).
- Automatic bans always expire (`ban-duration`).
- The default unattended level is `STRICT`. `LOCKDOWN` is not automatic.
- Burst is off until the maintainer turns it on.

These defaults fail toward letting players in, except for the unattended `STRICT` level, which is the intended protection.

## Risks / Trade-offs

- **Collateral damage from an ASN.** A large ISP can share an ASN with hosting ranges, and a country ban hits
  everyone there. Mitigation: ADMIN-only for country bans and permanent ASN bans, a visible reason on the panel, a
  summary line with counts per ASN, and a one-click lift.
- **Stale unattended state.** A heartbeat outage makes staff look absent. Mitigation: the presence TTL, the heartbeat
  failure counter, and a fail toward the guard that lasts at most a few minutes.
- **Outage gap.** Decision 8 accepts a gap for `STRICT` and for proxies that start during an outage.
- **Load.** One extra Dragonfly write per refusal and one per heartbeat. No database write per login, except when a
  burst creates a ban.
- **Staff abuse.** A moderator can close the network. Mitigation: every level change is in the history and posted to
  Discord at once. Whether that is enough is open question 5.

## Migration plan

1. Prerequisites merged: `add-vpn-proxy-detection`, `add-player-intelligence`.
2. Migration `V<next>__join_guard.sql`, additive: `area_ban` with the partial unique index, `join_guard_state` (one row
   with all levels `OFF`), `join_guard_change`. Rollback drops the three tables. No existing table changes.
3. Deploy the Velocity release first. It needs the three new screens, the heartbeat and the fail mode. Until it is
   deployed, a proxy shows the VPN screen for any `DENY` without a `denialReason`.
4. Deploy the backend with the level at `OFF`, burst disabled and no area bans. The heartbeat and the history start to
   fill, so the team sees real presence before any setting changes.
5. Set `STRICT` as the unattended level for one week of observation, with the Discord summaries. Only then add
   area bans. Enable the burst rule after a week of summaries without false alarms.
6. Rollback: set `manual_level` and `automatic_level` to `OFF` (the proxy follows within 30 seconds), lift the area bans
   through the panel, then drop the tables if the feature is removed for good.

## Open questions

1. Is `Europe/Berlin` the right default zone, and is `01:00-07:00` the night window the team wants?
2. Is a 10-minute summary per ASN acceptable instead of one message per refused login (decision 6)?
3. Should the VPN allow-list also exempt a player from area bans (decision 9)?
4. Discord `/guard` actions record no actor, because Discord users are not linked to Entra identities. Is that enough
   until a Discord link exists, or should the link be part of `add-team-sso`?
5. Should a `LOCKDOWN` set by a moderator require a second staff member to confirm, or is the Discord alert enough?
6. Should `add-staff-audit-log` replace `join_guard_change` when it lands, or should the history stay separate?
