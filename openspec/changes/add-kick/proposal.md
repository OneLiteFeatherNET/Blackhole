## Why

Staff can ban, mute and restrict a player to servers, but cannot just remove a player from the
network or from a server for a while. Today a disconnect only happens as a side effect of a ban,
a server section or a client rule. Moderators need a short, recorded kick: remove the player now,
say why, let them reconnect at once, and keep the fact in the player's history so that repeated
kicks can escalate through a template. Admins also need to move every player off a server or
section in one step for maintenance, without that counting as misconduct.

## What Changes

- **New punishment type `KICK`.** A kick is stored with its reason, staff actor, time and a case
  event, and has no duration. It appears in the player history and can cost ELO through a template
  step. It is never active: it is not placed in the profile's active slots, it is not enforced on a
  later login, and it does not count in active-punishment lists or evidence counts. It does count
  towards escalation of its template group.
- **Three kinds of kick:**
  - **Network kick**: disconnect from the proxy with a kick screen showing the reason. The player may
    reconnect immediately.
  - **Server or section kick**: move the player off the current server, or off a section (server
    section or CloudNet task/group/service), to the fallback server. If there is no fallback, disconnect.
  - **Mass kick** (ADMIN only): every player on a server or section. It is one audited admin action
    with no per-player punishment rows.
- **Three entry points:**
  - In game: `/kick <player> [server|section] <reason>` (permission `blackhole.kick`) and
    `/kickall <server|section> <reason>` (permission `blackhole.kick.mass`), both with tab completion.
    The command goes through the backend, so the player may be on any proxy.
  - Panel: a kick action on the player page and on the case page; a mass kick in the admin area.
  - Templates: a template step may be a kick (`kind: KICK`), e.g. step 1 kick, step 2 one-day ban.
- **Online feedback.** The backend publishes `punishment.kick` (or `kick.mass`) on NATS. Proxies
  act on it and report back over HTTP. The panel and the command show `DELIVERED`, `NOT_ON_TARGET`
  or `NOT_ONLINE` after a bounded wait.
- **Player-facing kick messages are English only**, like ban messages. This is a deliberate
  exception to the localized screens of `proxy-enforcement`.
- **BREAKING (generated client and Velocity plugin):** the punishment `kind` field and the template
  step `kind` field gain the value `KICK`. A generated Java client enum that does not know `KICK`
  fails to parse a player's history. The client spec version is bumped, the client regenerates, and
  the Velocity PR ships with it. A proxy running an older build must not be served kick rows.
  See `design.md`, decision 9.

## Capabilities

### New Capabilities

- `player-kick`: the kick behaviour as a durable system capability. It owns the kick types and
  scopes, the not-online and not-on-target outcomes, the kick routes and the delivery acknowledgement,
  the mass kick and its audit record.

### Modified Capabilities

- `punishment-management`: `KICK` as a punishment kind that is never active; template steps with a
  kind (`PUNISH` or `KICK`); escalation counts kicks; the kick and mass kick domain events.
- `proxy-enforcement`: the `/kick` and `/kickall` commands and their permissions; the kick handler
  that reuses the existing enforcement building blocks; the fallback rule; the NATS kick subscription
  and the delivery acknowledgement.
- `elo-scoring`: a `KICK` template step applies its `eloCost` like any other step; a kick without a
  template costs no ELO.
- `admin-web-ui`: the kick dialog on the player page and on the case page; the mass kick in the
  admin area.

## Out of scope

- Per-actor identity. The actor is what the caller supplies until the identity change lands; this
  change does not add an identity claim (see `CLAUDE.md`, "Known gap", and `config.yaml` principle 2).
- Localized kick screens. Kick messages are English only.
- Kick history for mass kicks per player. The mass action records no owner hashes.
- Scheduled or recurring kicks, kicks from Discord, kicks for offline players (a kick for a player
  not online is recorded, but there is nothing to disconnect).
- Rate limiting of kicks. Infra owns rate limiting (`config.yaml` principle 5).
- Removing the backend Redis punishment mirror. That is the separate follow-up of
  `switch-event-bus-to-nats`.
- Changing `redesign-ux` or `standardize-api-pagination-and-problems`. This change follows their
  conventions and does not reopen them.

## Stacked pull requests and commit types

Every PR title is a Conventional Commit and is the squash-merge commit on `main`. The split is in
`tasks.md`:

1. `feat(punishment): add the kick punishment kind and template step kind` (migration, model, filters)
2. `feat(punishment): add the kick routes, delivery acknowledgement and NATS events` (API, client spec)
3. `feat(template): apply kick steps with ELO cost and escalation` (template apply path)
4. `feat(velocity): add the kick and kickall commands and the kick handler`
5. `feat(web): add the kick dialog and the mass kick in the admin area`
6. `docs(openspec): propose kicking players from the network and from servers` (this change; the
   archive commit later reads `docs(openspec): archive add-kick`)

Each type is one type per commit: `feat` for behaviour, `docs` for the rule file and the archive.

## Impact

- **Backend**: `punishment/`, `casetimeline/` (new `kicked` event type), `section/` (no change in
  semantics), `events/` (two new event types), `template` validator, `elo/` (no change; the template
  path already applies `eloCost`), a new `kick/` feature package (controller, service, DTOs, JDBC
  reader and writer), `application.yml` (`blackhole.kick.*`).
- **Database**: one new Flyway migration `V26__kick.sql` (next free after `V25`, which two in-flight
  changes reserve). Additive only.
- **API**: new routes under `/kick`. `client/specs/blackhole-api-*.yml` gets the next free version.
- **Velocity**: two commands, a kick handler, the kick screen, the NATS kick subscription and an
  HTTP client for the acknowledgement. Velocity config gets `kick.ackTimeout` only if the wait is
  not taken from the backend's answer.
- **Web**: a kick dialog, a mass kick dialog in the admin area, a status chip for the delivery outcome.
- **Docs**: `.claude/rules/kick.md` (path-scoped) and one line in `CLAUDE.md` pointing to it.
