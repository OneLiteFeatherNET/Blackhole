---
paths:
  - "backend/src/main/java/**/kick/**/*.java"
  - "velocity/src/main/java/**/enforcement/KickHandler.java"
---

# Kick

A kick removes a player from the network, a server or a section without banning them. Source: `openspec/changes/add-kick/design.md`
(decisions 1, 3 and 5).

## A kick is never active (decision 1)

- A kick is a `punishments` row with `kind = 'KICK'` (`kick_scope` NETWORK/SERVER/SECTION, `kick_target` = server name or section
  key, never a player name). It sits in the same history and case as every other punishment; `lifted = false`, not permanent, no expiry.
- A kick is never written to `PunishmentProfileEntity.active` (the kick apply path does not call `placeAndRecord`) and never reaches
  the proxy mirror (`PunishmentMirror`/`PunishmentRedisWriter`).
- Every active-state read filters `kind = 'PUNISHMENT'`: `JdbcActivePunishmentReader`, `ActivePunishmentExpirer`,
  `PunishmentExpirySweeper`, `hasActivePermanent`, the evidence-count join. A new active-state read must do the same.
- `applyForRule`, `revoke` and unban reject a kick identifier as an active punishment (409).
- Escalation does not filter on `kind`: a kick row counts as an earlier step of its escalation group. Active lists do not show it.
- Each of these has a test; a change that touches one keeps the test.

## Mass kick writes no punishment (decision 3)

- A mass kick (server or section) writes exactly one `kick_action` row and a `kick.mass` event. No `punishments` rows, no
  `case_event`, no ELO change, nothing in any player's history.
- The backend does not know who is online (only the proxies do), so it never loops over players. Outcome counts (players moved,
  proxies answered) go to `kick_delivery`, counts only.
- The action shows in the admin audit view, not in a player history.

## Events and acknowledgements (decision 5)

- Subjects: `blackhole.events.punishment.kick` and `blackhole.events.kick.mass`; `eventId` is the `Nats-Msg-Id`.
- `punishment.kick` payload: `{eventId, kickId, owner, scope, target?, occurredAt}`; `kick.mass`: `{eventId, actionId, scope, target,
  occurredAt}`. No reason text, no actor name, no raw UUID, no IP; `owner` is the hash the backend already publishes.
- Publish after the write, in a try/catch; a failed publish does not fail the request (counter `blackhole.kick.publish.failures`).
- The proxy is subscribe-only: it never publishes on NATS. It acknowledges over HTTP with `POST /kick/{id}/deliveries` using its
  machine token, after it has acted on the event or found nothing.
