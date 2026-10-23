# Proposal

**Ships as:** `feat(auth): issue and confirm staff account link codes`, additive: new endpoints,
a new table pair and nullable columns, no removed or renamed API. Delivered as a stack of four
small pull requests (see "PR split" and `tasks.md`). The change spans types (`feat` backend,
`feat` Velocity, `feat` web, `docs`), so each pull request carries exactly one.

## Why

`add-team-sso` makes the actor of every write the token's `oid`. A web action is therefore
attributed to a verified person. An in-game action is not: the Velocity plugin is one machine
principal (`PROXY`), and the staff member it vouches for arrives as `onBehalfOf`, a raw Minecraft
UUID that nothing ties to an Entra identity. Consequences today:

- a staff member who bans in game can decide the appeal for that ban in the panel, because the
  self-review check compares `oid` values and the in-game UUID is not one (the documented known
  limit of `add-team-sso`, decision 5);
- the punishment timeline shows a bare UUID for in-game bans instead of the person.

Decision (2026-10-23): staff link their Minecraft account to their Entra account themselves,
with a short code, proven from both sides. LuckPerms meta was rejected as the source of truth
because whoever can edit LP meta could attribute actions to someone else.

## What Changes

The flow:

1. A signed-in staff member (any staff role) clicks "Minecraft-Konto verknüpfen" in the admin
   panel. The backend issues a short-lived, single-use link code bound to the caller's `oid`.
2. In game they run `/blackhole link <code>`. The proxy (`PROXY` role) calls the backend with the
   code and the executing player's UUID as `onBehalfOf`. The backend binds that UUID to the
   `oid`; the panel session proved the Entra side, the in-game command proves the Minecraft side.
3. From then on, actions from in-game commands are attributed to the linked `oid`, the
   self-review check also covers in-game bans, and the timeline shows the real staff member.

Pieces:

- **Backend:** `staff_link_code` and `staff_link` tables, `StaffLinkService` in the `security`
  package, endpoints to issue, confirm, inspect and remove links, nullable `actor_oid` next to
  `recorded_by_oid`, extended self-review, domain events `staff-link.created` and
  `staff-link.removed`, anonymize also removes the link.
- **Velocity:** `/blackhole link <code>` (permission `blackhole.link`, players only),
  `/blackhole whoami`, messages in the `bh:` style in English and German.
- **Web:** an account dialog with the code, a countdown and a copy button, the link status with an
  unlink button, a small admin list of links, de/en strings, accessible.
- **Client spec** `client/specs/blackhole-api-0.0.16.yml` (next after 0.0.15; confirm at apply).

## PR split (stacked, each based on the previous branch)

1. `feat(auth): issue and confirm staff account link codes` (backend, migration, spec, client spec)
2. `feat(velocity): link a staff minecraft account with a code`
3. `feat(web): link the minecraft account from the admin ui`
4. `docs(auth): document staff account linking` (README, operator guide, permission nodes)

## Out of scope

- Using LuckPerms meta, Mojang session auth or any other proof of the Minecraft side.
- Backfilling `actor_oid` on punishments issued before a link existed (design decision 6).
- Per-actor identity for system rows (ELO automation, vanilla import) and for players.
- New app-level rate limiting (infra owns it); the per-code failed-attempt cap is code state.
- Linking more than one Minecraft account per person, or one account to several people.
- Linking service principals (`PROXY`, `METRICS`); only `HUMAN` tokens may link.
- The appeal portal and any player-facing linking.

## Impact

Not BREAKING: no existing field, route or behaviour is removed, and old clients keep working.
New nullable columns stay NULL for existing rows. Two new `PROXY` call shapes
(`POST /staff-link/confirm`, `GET /staff-link/by-minecraft/{uuid}`) are used only by the new plugin
version; an old plugin simply never calls them. Requires `add-team-sso` to be archived or merged
first (it supplies `ActorResolver`, `staff_actor`, `Roles`, `onBehalfOf` and the self-review check).
