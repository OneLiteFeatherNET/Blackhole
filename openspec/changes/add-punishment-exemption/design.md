## Context

The code this design builds on (checked on `main`, 2026-10-28):

- Linked staff: `staff_link` (V10) binds a Minecraft UUID to one `oid`; `StaffLinkService` owns it and
  publishes `staff-link.created` / `staff-link.removed`. `staff_actor` (V9) holds one row per `oid` with
  kind, display name and first and last seen times, and no role.
- Roles are `ADMIN > MODERATOR > VIEWER` from the token's groups (`security/Roles.java`,
  `BlackholeRolesFinder`). The proxy is an app-only `PROXY` token.
- Owners are SHA-512 hashes. Velocity hashes online players to find them (`EnforcementActions.findPlayer`),
  so the backend and the proxy agree on the owner of a player without a raw UUID in the projection.
- Punishment writes go through `PunishmentApplicationService.applyForRule` (rule and manual apply),
  the template apply path, and the kick service (`kick/`, V27). Each has an `Actor` with the verified
  `oid` and, for in-game writes, `onBehalfOf`.
- The ELO engine (`elo/service/EloService.java`) applies automatic punishments on threshold crossings
  (`elo.threshold_crossed`, `section.escalated`). `elo-scoring` requires that they apply without a
  moderator online (principle 4).
- Velocity has no exemption concept. Permissions are read through `Permissions.java`
  (`blackhole.kick`, `blackhole.kick.mass`, ...). LuckPerms is the permission backend on the proxy, so
  only online targets' permissions are visible to Velocity.
- Flyway: `V27__kick.sql` is the head on `main`. The exemption migration takes the next free version at
  implementation time (after the rollback migration if that lands first). No number is fixed here.

## Goals / Non-Goals

**Goals**: a protected player cannot be punished by mistake; the protection is decided by the backend,
which knows staff links and roles; the proxy contributes the one fact only it knows (the permission of
an online player); an admin can still act, with a recorded reason.

**Non-Goals**: protection from reports; protection from revocation; a new identity claim; per-section
ranks beyond the one `blackhole.exempt.<section>` form; live permission sync.

## Decisions

### 1. Where the rule lives: backend, with proxy input

The rule is enforced in the backend, in one guard (`ExemptionGuard`) called by every punishment
write before it writes anything. The proxy does two things: it reports the exemption flags of online
targets (login and each enforcement request), and it shows the refusal and the badge.

- **Backend only** is rejected: the backend cannot see a player's Minecraft permissions, so `blackhole.exempt`
  would never protect anyone.
- **Velocity only** is rejected: Velocity sees only online targets, does not know a linked staff member's
  role, and a proxy-side check can be bypassed by an offline target or a second proxy. Velocity is also a
  subscribe-only consumer of NATS and not the authority for punishments.
- **Both** (chosen): the backend decides from the projection; the proxy supplies the permission facts
  and gives the feedback.

### 2. Projection `player_exemption`, keyed by owner

`player_exemption` holds `owner` (the hashed owner, primary key part), `source` (`LINKED_STAFF` or
`PERMISSION`), `sections` (for `blackhole.exempt.<section>`; NULL means all sections for PERMISSION,
and is ignored for LINKED_STAFF which always covers all sections), `staff_oid` (for LINKED_STAFF),
`reported_at`. A player may have one row per source.

- A `LINKED_STAFF` row is written when `staff-link.created` is published and removed on
  `staff-link.removed` for that pair. The owner is computed from the linked Minecraft UUID with the same
  owner function the proxy uses, once, at link time.
- A `PERMISSION` row is written or replaced on each proxy report, and removed when a report arrives with
  no flag.
- Staleness: a `PERMISSION` row stays until the next report. Offline players keep their last state.
  Accepted in decision 6.

No raw UUID is stored in the projection; the owner hash is the key, as for every punishment table.

### 3. Rank model

Ranks: `VIEWER = 1`, `MODERATOR = 2`, `ADMIN = 3`. The guard refuses when `actorRank <= targetRank`.

- **Target rank**: a `LINKED_STAFF` target has the rank of `staff_actor.last_role` of its `oid`. The new
  `last_role` column is written from the token's highest role on every write (not from the request
  body). If the `oid` has no recorded role, the rank is MODERATOR (the conservative default).
  A `PERMISSION`-exempt target has rank MODERATOR. A player who is both takes the higher rank.
- **Actor rank**: a web or panel write uses the highest role of the current token. An in-game write with
  `onBehalfOf` uses the last recorded role of the linked `oid`; an unlinked in-game actor has no rank and
  is refused against exempt targets (with the refusal explaining that the account is not linked).
- **Consequence**: a MODERATOR cannot punish a MODERATOR-ranked or ADMIN-ranked exempt target; an ADMIN
  can punish a MODERATOR-ranked target but needs an override for an ADMIN-ranked target.

Rejected: a flat "exempt from everyone except ADMIN" rule (cannot express "equal or lower" for linked
moderators and admins); reading target roles from Entra (no link from a Minecraft UUID to the Entra
group membership is available to the backend, and the token of the target is never present).

### 4. ADMIN override

If the guard refuses and the actor is an ADMIN in the panel (token role), the request may carry
`exemptionOverride: { justification }`. The guard then allows the write, records the override on the
punishment (`metaData.exemptionOverride` with the justification, the target's rank and the source), and
publishes `punishment.exemption_overridden`. An override without a justification is refused with 400.

The override is never available to a proxy-delegated write. Rejected: an in-game override (no verified
identity of the in-game admin without a link; a link-only override would make the link the security
boundary).

### 5. Automatic ELO punishments become review items

When a threshold crossing would apply an automatic punishment (`elo` soft or perma-ban path) to an exempt
target, `EloService` still records the score change and still publishes `elo.threshold_crossed`
with `exempt: true`. It creates an `exemption_review` row (owner, section, track, the template that
would have applied, the score delta, the crossing time, state `OPEN`) and applies no punishment. An
ADMIN applies the template with a justification, which goes through the normal apply path with the
override recorded, or dismisses the review with a justification. A dismissed review writes nothing
else.

This is an explicit exception to principle 4 of `config.yaml` ("crossing configured thresholds must
punish without a moderator online"), for exempt targets only. The exception is required by the request
and is not decided here as a principle change; open question 1 asks the maintainer to choose between
amending principle 4 in this change or in a prerequisite change.

Rejected: punish anyway and show a flag (contradicts the request and undoes the protection); skip the
score change too (the ELO history would lose the signal the appeal workflow uses).

### 6. Freshness of the permission flags

The proxy reports on login and on every enforcement request for an online target, so the state used by a
punishment is the state at the moment a staff member acts on an online player. For offline targets, the
last report stands. A permission removed while the player is offline keeps protecting until the next
login. Rejected: a time-to-live on `PERMISSION` rows (a protected player who is offline for a week would
lose protection without anyone deciding so). The gap is written down in the risks.

### 7. Kicks and mass kicks

A kick is a punishment kind (`kick/`) and goes through the guard. A mass kick is an ADMIN maintenance
action (`blackhole.kick.mass`, see `add-kick`); it does not check exempt targets, because it is not a
punishment and must move everyone off a server for maintenance. Open question 3 asks whether a mass kick
should skip exempt players instead.

### 8. Velocity behaviour

- The staff command pre-check asks the backend's exemption read for the target before a command with a
  side effect is sent. The refusal message is the backend's answer, localised by the proxy as the other
  enforcement messages are, with the exemption note.
- The badge is shown in the staff messages that list a target (report and punish info).
- Velocity sends the exemption flags to the backend in the login report and in the enforcement request.
  The flags come from `Permissions` (`blackhole.exempt`, `blackhole.exempt.<section>`), read on the
  online player, never from the client.
- No enforcement decision is taken on the proxy. A protected player's existing punishment is not changed by
  the exemption: exemption stops new punishments only.

### 9. Panel

Badge on the player page, the case page and the review queue. Dialogs for apply, kick and punish show the
refusal and, for ADMIN, the override field with its justification. The review queue lists open review
items with apply and dismiss actions.

## Risks / Trade-offs

- **Stale permission protection.** A removed `blackhole.exempt` keeps protecting an offline player until
  the next login. Mitigation: the report on every enforcement request for an online target; a dashboard
  count of exempt players lets staff notice.
- **Wrong role rank.** A linked staff member whose role changed is ranked by the last write. Mitigation:
  `last_role` is refreshed on every write, and a role change is only effective after the person writes
  again; this is documented on the badge tooltip.
- **Review queue growth.** Automatic punishments of exempt targets accumulate as reviews. Mitigation: the
  queue shows counts; an admin dismiss is one action with a justification.
- **Principle 4 exception.** Exempt players are not punished automatically. Accepted for this request; the
  maintainer decides how it is recorded (open question 1).
- **Owner computation drift.** The owner computed by the backend at link time must equal the proxy's owner
  for the same UUID. Mitigation: a test that feeds the same UUID through the backend function and the
  proxy's function and compares the results, with the shared fixture.

## Migration plan

`V<next>__punishment_exemption.sql` (the number is assigned at implementation time: the next free number
on main; main is at V27 today) is additive only:

- `CREATE TABLE player_exemption (owner varchar(128), source varchar(16), sections varchar(512) NULL,
  staff_oid uuid NULL, reported_at bigint, PRIMARY KEY (owner, source))` with a CHECK on `source`.
- `CREATE TABLE exemption_review (id uuid PK, owner varchar(128), section_key varchar(64), track varchar(16),
  template_id varchar(64) NULL, elo_delta int, crossed_at bigint, state varchar(16), decided_by_oid uuid NULL,
  decided_at bigint NULL, justification varchar(500) NULL)` with a CHECK on `state` (`OPEN`, `APPLIED`,
  `DISMISSED`).
- `ALTER TABLE staff_actor ADD COLUMN last_role varchar(16) NULL`. Existing rows stay NULL, which ranks them
  MODERATOR (decision 3), so no backfill.
- Existing punishments and ELO history are not changed. No row is rewritten.
- `LINKED_STAFF` rows for links that exist at migration time are created by a one-time backfill job in the
  same change (idempotent, `INSERT ... ON CONFLICT DO NOTHING`), not by a Flyway data migration.

Rollback of the change: the guard can be switched off by config only as an emergency measure, documented in
`application.yml` (`blackhole.exemption.enabled`, default true); the tables can stay empty.

## Open questions

1. Principle 4 of `config.yaml`: amend it in this change (the change then edits `config.yaml`) or in a
   prerequisite change `amend-punishment-principle`. This proposal does not edit `config.yaml`.
2. Whether the ADMIN override should also be possible in game, given a link-based identity check.
3. Whether a mass kick should skip exempt players (decision 7).
4. Review queue ageing: whether open reviews expire or escalate after a set number of days, and whether
   they count on the dashboard.
5. The default rank of a linked staff member whose role was never recorded (MODERATOR proposed).
