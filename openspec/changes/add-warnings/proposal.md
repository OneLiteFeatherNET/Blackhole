## Why

Staff have two answers to a rule breaker today: a ban or mute, or nothing. A short spam mute is
too much for a first slip, and a kick, which the network now offers, is a moment and not a record of
the warning itself. Moderators want to say "this is a warning, you have been told" and have the player
actually see it, confirm they read it, and find the warning in their history. When the same player
repeats the rule, the template should climb a ladder: a warning first, a kick next, a one-day ban
after that. Without a warning kind the ladder either starts at a kick, which is not a warning, or at a
ban, which is too harsh.

## What Changes

- **New punishment kind `WARN`.** A warning is stored like a kick: a row in `punishments` with its
  reason, staff actor, time, case event and no duration. It is never active: it is not placed in the
  profile's active slots, it is never enforced on login, it is not in the active lists and it is not in
  the proxy mirror. It counts towards escalation of its template group, like a kick does.
- **Acknowledgement.** A warning is shown in game at once when the player is online, as a chat message
  with a clickable "Acknowledge" line. A player who is offline sees it on the next login. The warning
  stays pending until the player acknowledges it, and it is shown again on each login while pending.
  A warning never blocks play. Acknowledging is recorded on the case.
- **Template step kind `WARN`.** A template step may be a warning (`kind: WARN`, no duration), so a
  ladder such as step 1 warn, step 2 kick, step 3 one-day ban is one template. A warning step may carry
  an ELO cost like any step; the default is zero.
- **Warning expiry for escalation (per template).** A template may set `warnWindow`, an ISO-8601
  duration. Warnings older than it stop counting towards escalation. Without it, a warning counts for the
  template's escalation window like any other step. Warnings never expire from the history: they stay
  visible, they only stop climbing the ladder.
- **Entry points:**
  - In game: `/warn <player> <template>` (permission `blackhole.warn`), with template tab completion.
    It applies the template exactly like `/punish` does: the escalation step decides what happens.
  - Panel: a "Warn" action on the player page and the case page; a warning badge in the history.
  - Templates: `kind: WARN` on a step.
- **Appeals.** A warning is a manual punishment and stays contestable, as principle 4 requires. A
  warning is appealed through the appeal workflow with the minimum wait of a manual punishment. A
  granted full lift marks the warning lifted (it stops counting for escalation) and refunds its ELO
  cost. No slot is involved. A duration reduction is not offered for a warning.
- **BREAKING (generated client and Velocity plugin):** the punishment `kind` field and the template step
  `kind` field gain the value `WARN`. A generated Java client enum that does not know `WARN` fails to
  parse a player's history, and an older Velocity build fails on a warning row in the history. The
  client spec version is bumped, the client regenerates, and the Velocity PR ships with it. The same
  mitigation as `add-kick`, `design.md` decision 9, applies.

## Capabilities

### New Capabilities

- `player-warning`: the warning kind and its never-active rules, the acknowledgement by the player, the
  pending-on-login read, the warning window for escalation and the warning routes.

### Modified Capabilities

- `punishment-management`: `WARN` as a punishment kind that is never active; template steps of kind
  `WARN`; escalation counts warnings within `warnWindow`; the `punishment.warned` and
  `punishment.acknowledged` domain events.
- `proxy-enforcement`: the `/warn` command and its permission; the warning delivery on the NATS subject
  `blackhole.events.punishment.warned`; the acknowledgement; the pending warnings shown on login. Warnings
  never reach the punishment mirror or the cache.
- `elo-scoring`: a `WARN` template step applies its `eloCost` like any template step; a warning appeal
  refunds the ELO cost of the warning.
- `admin-web-ui`: the "Warn" action on the player page and the case page; the warning badge in the
  history; warnings are not counted as active.
- `appeal-workflow`: warnings are appealable with the manual minimum wait; a full lift of a warning
  lifts it without a slot and refunds its cost; a duration reduction is refused for a warning.

## Out of scope

- Localized warning text. Warning messages are English only, like kick and ban screens. This is a
  deliberate exception to the localized screens of `proxy-enforcement`.
- Editing a warning. A warning is corrected by a new warning or an appeal, not by amendment. Amendments
  apply to punishments only (`punishment-amendment` in the sibling change `add-punishment-amendment`).
- Warnings from Discord, scheduled warnings and warnings for players who never joined the network.
- Blocking play until the warning is acknowledged. Warnings never block.
- Rate limiting of warnings. Infra owns rate limiting (`config.yaml` principle 5).
- Any change to identity or auth. The actor of a warning is the token's `oid`, as for every write.

## Stacked pull requests and commit types

Ships as: feat(punishment): add player warnings with acknowledgement

The code ships as one feature type. The split is in `tasks.md`:

1. `feat(punishment): add the warning kind and warning template step` (migration, model, escalation,
   active-read filters)
2. `feat(punishment): add the warning routes, acknowledgement and NATS events`
3. `feat(template): apply warning steps with warning window and ELO cost`
4. `feat(velocity): add the warn command, warning delivery and acknowledgement`
5. `feat(web): add the warn action and the warning badge`
6. `feat(appeal): allow appeals of warnings`
7. `docs(openspec): propose warnings` (this change; the later archive commit reads
   `docs(openspec): archive add-warnings`)

## Impact

- **Backend**: `punishment/` (kind, step kind, filters), `appeal/` (warning appeal path), `elo/` (no
  change; the template path applies `eloCost`), a new `warning/` feature package (controller, service,
  DTOs, reader and writer), `events/` (two event types), `casetimeline/` (`punishment.warned`,
  `punishment.acknowledged` case events), `application.yml` (none; `warnWindow` is per template).
- **Database**: one new Flyway migration `V<next>__warnings.sql`, the next free version at
  implementation (main is at V27 today). Additive, plus a CHECK constraint replacement on `kind` and
  `step kind` (see `design.md` decision 10).
- **API**: new routes under `/warn`; the template routes accept `kind: WARN`. The `client/specs` file gets
  the next free version.
- **Velocity**: the `/warn` command, the warning message with the acknowledgement, the login read of
  pending warnings and the NATS subscription for `punishment.warned`.
- **Web**: a warn dialog (reuses the apply path), a history badge.
- **Docs**: `.claude/rules/warning.md` (path-scoped) and one line in `CLAUDE.md`.

## Prerequisite

The code of this change extends `PunishmentKind` and `StepKind` as `add-kick` introduced them. `add-kick` is
implemented on `main` (PRs #382 to #387) but its change is not archived. The specs here only ADD requirements,
so they validate without the archive. The code PRs open after `add-kick` is archived
(`docs(openspec): archive add-kick`), so the code is written against the archived model.
