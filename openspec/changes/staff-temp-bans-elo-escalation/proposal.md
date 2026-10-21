# Proposal

## Why

Staff should not decide who gets banned forever - the network wants permanent bans to be the
system's consequence of repeated, recorded misbehaviour. Today staff can apply any template,
including permanent ones; ELO effects are an optional per-template `eloDelta`; there are
exactly two hard-coded tracks; and permanent bans need an operator to configure a perma-ban
template UUID per track (otherwise escalation is silently skipped). Applying a mute also
clears an unrelated active ban (both slots rotate on every apply). This change makes the model
explicit: staff issue temporary punishments from templates, each costs ELO in its section,
and the system bans permanently from that section once its score is too low.

## What Changes

- **Punishment sections** become a first-class, data-driven concept. A section (seeded: `chat`
  enforced as mute, `network` enforced as proxy ban) owns its own ELO score per player, its
  thresholds, its permanent-ban reason and its pardon score. New sections with an existing
  enforcement kind need no code change.
- **BREAKING** Templates are always temporary and belong to a section: required `key` (command
  slug), `section`, `duration` (ISO-8601, > 0) and `eloCost` (>= 0) replace `type`,
  `eloDelta` and the metadata-duration convention. Permanent templates no longer exist.
- **BREAKING** Applying a template by any non-system source creates a temporary punishment in
  the template's section and deducts `eloCost` from that section's score; the apply endpoint
  returns the punishment, score before/after and any escalation.
- Escalation: when a deduction leaves a section score below its permanent threshold and the
  player has no active permanent punishment there, the system replaces the section's active
  punishment with a permanent one. The optional soft threshold per section keeps auto temp
  punishments. Per-track perma-ban template UUIDs in config are removed.
- One active punishment per player **per section** (fixes the mute-clears-ban bug).
- **BREAKING** ELO API: scores are reported per section; new manual adjustment endpoint
  (audited, may escalate). Report categories and the chat toxicity scorer map to sections.
- Lifting a permanent section punishment (revoke or granted appeal) resets that section score
  to the section's pardon score; a granted full-lift appeal of a staff temp punishment refunds
  its ELO cost.
- **BREAKING** (proxy sync contract): Redis mirror keys become per section
  (`blackhole:punish:<section>:<owner>`); proxies enforce by the section's enforcement kind.
- Proxy commands: `/ban` and `/mute` select templates of ban/mute sections by key, `/punish`
  takes any template, `/elo <player>` shows section scores; staff feedback shows duration, ELO
  change and escalation. `--server` flag and SERVER-scoped bans are removed from commands.

Out of scope: per-server (SERVER) bans, duration escalation ladders, staff identity/auth,
admin UI (change `add-admin-web-ui`), migrating existing MariaDB data.

## Capabilities

### New Capabilities

- `punishment-sections`: the section registry (enforcement kind, thresholds, pardon score,
  permanent reason) and the automatic permanent escalation per section.

### Modified Capabilities

- `elo-scoring`: tracks become per-section scores; threshold behaviour, report/toxicity mapping,
  read endpoints and a manual adjustment endpoint change.
- `punishment-management`: templates (temporary-only, sectioned, ELO cost), apply semantics and
  result, per-section active slots, revocation, Redis mirroring keys.
- `proxy-enforcement`: ban/mute/punish/elo commands, template argument by key, login/chat
  enforcement and sync keyed by section.
- `appeal-workflow`: ELO consequences of granted appeals.

## Impact

- Backend packages `elo/`, `punishment/`, `profile/`, `appeal/`, `report/`, new `section/`;
  Liquibase changesets; `application.yml` (`blackhole.elo.*` restructured).
- OpenAPI spec bump in `client/specs` and `client/build.gradle.kts`; regenerated client.
- Velocity plugin commands, listeners, Redis sync, i18n files.
- Depends on `switch-to-postgresql` (schema is written for PostgreSQL) and should land after
  `adopt-dragonfly-cache` (section list is cached).
