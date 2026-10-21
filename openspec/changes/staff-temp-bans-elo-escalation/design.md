# Design

## Context

- `EloService.applyDelta(owner, EloTrack, delta, ...)` is the single write path for scores;
  `elo_profiles` stores `chat_elo`/`gameplay_elo` columns.
- `PunishmentApplicationService.apply` is the single chokepoint for creating punishments;
  `punishment_profiles` has two FK slots (`active_ban_identifier`, `active_chat_ban_identifier`)
  plus a history join table. Every apply rotates both slots (bug).
- Proxy sync: backend consumes domain events from RabbitMQ and writes Redis keys
  `blackhole:punish:{ban|chatban}:<owner>` + channel `blackhole.punishment.sync`.
- Velocity commands parse templates by exact `reason` (spaces, NPE on miss) and the
  `@PunishTypeScope` filter never fires (no runtime retention).

## Goals / Non-Goals

**Goals:** sections as data; one ELO write path per section; permanent bans only by the
system; escalation correct under repeated offences and after pardons; proxy and API agree on
one per-section contract.

**Non-Goals:** concurrency hardening of apply/escalation beyond today's accepted race window
(`@Transactional` still unusable); per-section decay curves beyond a linear recovery rate;
escalating the *ELO-system* soft punishment (only staff templates have steps).

## Decisions

1. **Section entity** `punishment_sections`: `key` (PK, kebab-case), `display_name`,
   `enforcement` (`MUTE`|`BAN`|`SERVER`), `servers` (list of glob patterns, required and
   only allowed for SERVER), `base_score`, `soft_threshold` (nullable),
   `soft_duration` (nullable), `permanent_threshold`, `pardon_score`, `permanent_reason`,
   `decay_per_day`, `enabled`. CRUD under `/section` (v1). Seeded by SQL `INSERT` in a Flyway migration:
   `chat` (MUTE, 1000/700/PT24H/300/700) and `network` (BAN, 1000/700/P3D/300/700).
   *Alternative:* keep the `EloTrack` enum and add a third value when needed - rejected,
   every new section would touch schema, services, proxy and UI.
   Enforcement kinds stay an enum because the proxy must implement each kind. SERVER sections
   replace the old SERVER `PunishType`: one section per server group gives server bans their
   own score and permanent escalation instead of sharing the network score.
2. **Scores table** `elo_scores(owner, section_key, score, updated_at)` PK (owner,
   section_key), replacing the two columns. A missing row means `base_score`. `elo_events`
   gains `section_key`; `track` stays nullable for history written before this change.
3. **Escalation rule** (replaces "genuine downward crossing" for the permanent tier): after
   any negative delta, if `score < permanent_threshold` and the section has no active
   permanent punishment for the owner, apply a permanent punishment (source = ELO system,
   reason = `permanent_reason`). This closes the gap where a pardoned player below the
   threshold could never be escalated again. The soft tier keeps the crossing rule (otherwise
   every offence below 700 would add an extra auto temp punishment).
   Escalation is implemented behind an `EscalationPolicy` interface (default:
   threshold policy) so a network can swap in e.g. a strike-count policy as a bean.
4. **Permanent punishments without templates.** System-created permanent punishments have no
   template (`template_identifier` null) and carry `section_key`, reason and
   `permanent: true` in their own columns/metadata. Templates are therefore always temporary;
   validation rejects missing/zero/negative durations and negative `eloCost`.
5. **Template shape**: new columns `template_key` (unique), `section_key` (FK),
   `escalation_group` (default = key), `escalation_window` (ISO-8601, nullable -> config
   `blackhole.punishment.escalation.default-window`, default P365D); steps in table
   `punishment_template_steps(template_identifier, step_index, duration, elo_cost)` ordered
   by `step_index`, at least one step.
5a. **Step selection**: `n` = count of the owner's punishments with the same
   `escalation_group` created within the window that were not revoked or lifted by a granted
   appeal (expired ones count); step = `min(n, steps-1)`. The punishment stores
   `escalation_group`, `step_index`, `step_count` and the deducted cost so history stays
   explainable after template edits. *Alternatives:* per template (variants of one offence
   wouldn't add up) or per section (unrelated offences would escalate each other) - rejected
   per user decision. `type` and `elo_delta` columns become
   unused (kept nullable; dropped in a later migration once no code reads them). Punishments
   get `section_key` and `permanent` columns.
6. **Active slots per section**: table `active_punishments(owner, section_key,
   punishment_identifier)` PK (owner, section_key). Apply rotates only the target section's
   slot. Profile DTO exposes `active: Map<sectionKey, Punishment>` and `history`.
   The two old FK columns are dropped by an appended migration after code stops using them.
7. **Apply API**: `POST /punishment/apply` body `{owner, templateKey|templateId, source}`
   returns `ApplyResult{punishment, section, scoreBefore, scoreAfter, escalation?}` (sealed
   DTO with an `Error` variant: unknown template, disabled section, invalid owner). The old
   path-param endpoint is removed. Revoke becomes `POST /punishment/revoke`
   `{owner, section, source}`.
8. **Pardon semantics**: revoking or appeal-lifting a *permanent* section punishment sets the
   section score to `pardon_score` (event reason `PARDON_RESET`); a granted full lift of a
   *staff temporary* punishment adds back its recorded `eloCost` (`APPEAL_REFUND`). Recorded
   cost lives in the punishment's metadata at apply time so later template edits don't change
   refunds.
9. **Signal mapping**: toxicity flags -> section from `blackhole.elo.chat.section` (default
   `chat`); report categories -> `blackhole.elo.report.category-sections`
   (default CHAT_ABUSE=chat, CHEATING=network, GRIEFING=network, OTHER none).
10. **Proxy contract**: key `blackhole:punish:<section>:<owner>`, message adds `section` and
    `enforcement`, replaces `slot`/`type`. Proxy loads the section list (shared cache, API
    fallback) at startup and on a `blackhole.section.changed` pub/sub message; login denies
    on any active BAN-section punishment, chat denies on any active MUTE-section punishment,
    `ServerPreConnectEvent` denies a target server matching an active SERVER-section
    punishment (message shown, player stays where they are). A SERVER ban landing while the
    player is on a matching server moves them to the proxy's fallback/lobby server, or
    disconnects them if none is available.
11. **Commands** (Cloud annotations): `/ban <player> <templateKey>` (BAN and SERVER sections),
    `/mute <player> <templateKey>` (MUTE sections), `/punish <player> <templateKey>` (any),
    `/elo <player>` (`blackhole.elo.view`), `/unban <player> [section]` (BAN or SERVER) and
    `/unmute <player> [section]` gain resolver-based
    offline targets; lifting a permanent punishment needs `blackhole.pardon.permanent`.
    Parser errors use i18n keys instead of returning null.

## Flyway migrations (appended, V2 onward)

Each is a `V<n>__<name>.sql` file, e.g. `create-punishment-sections` + `seed-punishment-sections`, `create-elo-scores`,
`add-section-to-elo-events`, `add-section-columns-to-templates`,
`add-section-columns-to-punishments`, `create-template-steps`, `add-servers-to-sections`
(`punishment_section_servers(section_key, pattern)`), `create-active-punishments`, and (last, after code
switch) `drop-legacy-elo-columns`, `drop-legacy-profile-slots`. No row migration: deployments
start on a fresh PostgreSQL (see `switch-to-postgresql`).

## Risks / Trade-offs

- [Concurrent deductions both see "no active permanent" and create two permanent
  punishments] -> PK on `active_punishments(owner, section_key)` rejects the second slot
  write; the losing write is logged. Same accepted window as today.
- [Breaking API for the Velocity plugin] -> plugin and backend ship from the same repo and
  release; the client spec version is bumped.
- [Escalation counting is a read over history on every apply] -> index
  `punishments(owner, escalation_group, created_at)`; histories per player are small.
- [Overlapping server patterns across SERVER sections] -> allowed; a connect is denied if
  any matching section has an active punishment. Pattern validation rejects empty/`*`-only
  patterns (use a BAN section for network-wide).
- [Operators relied on staff permanent templates] -> documented: permanent bans now require
  lowering the section score (manual adjustment endpoint) which is audited.

## Migration Plan

Deploy backend + Velocity plugin together on the fresh PostgreSQL. Recreate templates with
keys/sections/durations/costs (UI from `add-admin-web-ui` or API). Rollback = previous release
against its own database.
