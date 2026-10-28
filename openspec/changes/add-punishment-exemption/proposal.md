## Why

Moderators regularly see a fellow staff member, a streamer or a long-standing supporter get punished
by mistake: a report is answered by someone who does not know the person, or an automatic ELO
threshold fires on an account that is known to be fine. Today nothing in the system says "this
person is staff" or "this person is protected". A moderator can ban an admin's linked account the
same way as any other player, and an in-game ban of a protected player is only undone by hand. The
result is a loss of trust in the moderation team and a lot of appeals that should never have been
needed.

Rank protection makes the rule explicit: a player who is linked staff, or who holds the Velocity
exemption permission, cannot be punished by staff of an equal or lower role. An admin can still
act, but has to say why in the panel, and the decision is on the record.

## What Changes

- **Exempt player.** A player is exempt when either
  - their Minecraft account is linked to a staff member (`staff_link`, V10), or
  - the proxy reports the Velocity permission `blackhole.exempt`, or `blackhole.exempt.<section>`
    for one section, on the player's online session.
- **Rank rule.** An actor whose role is equal to or lower than the target's protection rank cannot
  apply a punishment or a kick to an exempt player. The request is refused with 409
  `TARGET_EXEMPT`, nothing is written, and the proxy or panel shows the badge and the refusal.
  - The rank of a linked staff target is the last role recorded for that `oid` (`staff_actor.last_role`,
    new). An unknown role counts as MODERATOR.
  - A permission-exempt target has rank MODERATOR.
  - The actor's rank is the highest role in the actor's current token, or for an in-game action the
    last recorded role of the linked `oid`. An unlinked in-game actor has no rank and is refused
    against exempt targets.
- **ADMIN override.** An ADMIN may apply the action anyway with a justification (1 to 500
  characters). The override is recorded on the punishment (`exemptionOverride`) and published as
  `punishment.exemption_overridden`.
- **Automatic ELO punishments become review items.** When a threshold crossing would apply an
  automatic punishment to an exempt player, the score still moves and `elo.threshold_crossed` still
  fires, but no punishment is applied. An open review item is created for an admin, who applies the
  punishment with a justification or dismisses it. This is a deliberate exception to principle 4 of
  `config.yaml` for exempt players only; see `design.md`, decision 5.
- **Proxy reports exemption.** Velocity reports the exemption flags at login, and again with each
  enforcement request for an online target, to the backend. The flags are a fact about the target,
  not an actor claim, so they are accepted from the `PROXY` role only and are stored on the player's
  projection, so offline players keep their last known state.
- **In-game feedback.** Velocity shows an exemption note in the staff command pre-check and in the
  refusal message. Velocity does not decide anything itself; the backend answer is authoritative.
- **Panel.** An exemption badge (linked staff, or permission exempt) on the player page, the case page
  and the review queue. Apply, kick and punish dialogs show the refusal and the override field for
  ADMIN.

BREAKING for the generated client and the Velocity plugin: the apply, punish and kick request bodies
gain an optional `exemptionOverride` object, and the error `TARGET_EXEMPT` is added to the error
enumeration. A generated client that does not know the new error code parses it as an unknown error,
so the client spec version is bumped and the Velocity PR ships with the regenerated client.

## Capabilities

### New Capabilities

- `punishment-exemption`: the exemption projection, the rank rule, the ADMIN override, the review item
  for automatic punishments against exempt players, and the proxy's exemption report.

### Modified Capabilities

- `elo-scoring`: threshold crossings against exempt players create a review item instead of an
  automatic punishment.
- `proxy-enforcement`: the exemption report at login and per enforcement request, the pre-check and
  refusal messages.
- `admin-web-ui`: the exemption badge, the override field and the review queue entry.

## Out of scope

- Exempting players from reports (`blackhole.report.bypass` already exists for reporters). Reports
  against exempt players are still recorded.
- Exempting players from revocation, unban and appeals. Exemption only blocks new punishments.
- Mass kicks. A mass kick is an ADMIN maintenance action, not a punishment; it does not skip exempt
  players. Open question 3.
- Changes to identity or auth. The actor is the token's `oid`. Role data comes from the existing role
  mapping; this change adds no claim.
- Live synchronisation of LuckPerms group changes. The permission flags refresh at login and on each
  enforcement request; a removed permission keeps protecting until the next report.
- An in-game ADMIN override. The override is panel-only in this change.
- Rate limiting of refusals. Infra owns rate limiting (`config.yaml` principle 5).

## Impact

- **Backend**: a new feature package `exemption/` (projection, rank rule, guard, review items, DTOs,
  controller). `PunishmentApplicationService.applyForRule`, the kick path and the template apply path
  call the guard before writing. `elo/` threshold path creates a review item for exempt targets.
  `staff_actor` gets `last_role`, written from the token on each write.
- **Database**: one new Flyway migration `V<next>__punishment_exemption.sql`, additive only: a
  `player_exemption` projection, an `exemption_review` table, and `staff_actor.last_role`.
- **API**: the optional `exemptionOverride` field on apply, punish and kick requests, the review routes
  under `/exemption`, the `TARGET_EXEMPT` error. `client/specs/blackhole-api-*.yml` gets the next free
  version.
- **Velocity**: the permission reader for `blackhole.exempt` flags, the login and enforcement report,
  the pre-check and refusal messages, one new config value `exemption.refresh` only if the report is
  not taken from the existing login call.
- **Web**: the badge component, the override field in the dialogs, the review queue entry.
- **Docs**: `config.yaml` principle 4 needs the exemption exception written as an amendment (see open
  question 1). This proposal does not edit `config.yaml`.

Ships as: feat(punishment): add rank protection and punishment exemption

## Stacked pull requests

Proposal PR first, merged before the code. The code is split as in `tasks.md`; the Ships-as title is
the backend PR that lands the guard.
