## Context

The code this design builds on (checked on `origin/main`, 2026-10-28):

- Ticket lifecycle in the bot: `discord/.../ticket/TicketLifecycle.java` (create, freeze and thaw,
  close, archive, channel gone, delete). Its freeze comes from the leak path only: the bot freezes a
  ticket whose Ban ID leaked and mirrors it with `PATCH /discord/tickets/{id}` (`patchDiscordTicket`).
  No staff lock exists on main, and the panel has no lock or close control for tickets.
- Two-way sync: `discord/.../sync/SyncEngine.java`, `CaseEventHandler.java` (consumes `case.*` events).
- The only button is `appeal:open` (`discord/.../gateway/JdaGateway.java`). Staff commands are `/link`,
  `/unlink`, `/whoami` and `/appeal`.
- Backend routes the panel uses and this change reuses:
  - `POST /appeal/{identifier}/review` (`appeal/controller/AppealApi.java`), body `AppealReviewDTO`, answers
    400 for an invalid decision, 403 for self-review, 409 when not awaiting review, 422 for a UUID, IP or Ban ID
    in the player message.
  - `PATCH /case/{reference}` (`casetimeline/controller/CaseApi.java`), body `CaseDTO.UpdateRequest` with
    `expectedVersion`, which carries the assignee (422 for an unknown or non-human staff member).
  - `POST /discord/tickets/{id}/close` (`DiscordTicketApi.close`), the bot-side close with the completeness proof.
- Attribution: `security/Actor` (`oid`, `kind`, `proxy`, `admin`). `Actor.attribution(onBehalfOf)` accepts
  `onBehalfOf` only for a `PROXY` caller (`verified-actor`, "Delegated attribution by the proxy"); any other
  caller sending it gets 400. The `DISCORD` role is a machine role (client credentials, `ActorKind.SERVICE`).
- Staff identity: `staff_actor` (V9) holds `oid`, `kind`, `display_name`, first and last seen. It holds **no
  roles**. `discord_link` (V14) links a Discord user to a staff oid with `kind`. `StaffLinkService` handles the
  Minecraft link. `DiscordStaffLinksController` (`/discord/staff-links`) lists links; there is no lookup by
  Discord user that returns the oid.
- Roles come from the token only (`ActorResolver`, `Roles`: ADMIN, MODERATOR, VIEWER, PROXY, DISCORD, METRICS).
- Flyway: `V27__kick.sql` is the head on `main`. The migration number is assigned at implementation time
  (next free number on main; main is at V27 today).
- Client: the spec version is next after 0.0.44 (`client/build.gradle.kts`) at implementation time.
- Principles (`openspec/config.yaml`): the actor of every write is the token's `oid`, never a client field
  (principle 2). This change keeps that: the bot may name a staff oid only where the backend can check it
  (decision 2). Layered backend and append-only migrations apply.

## Goals / Non-Goals

**Goals**: a staff member can work an appeal from the channel with the same result as in the panel; the
recorded actor is the staff member; the backend, not the bot, decides whether the member may act; both
surfaces show the same state; a failed click changes nothing.

**Non-Goals**: a second authorization system in the bot; Discord identity as a login; new appeal rules;
moderating channel messages.

## Decisions

### 1. The bot may act for a linked staff member only through `onBehalfOf`

The `DISCORD` role may send `onBehalfOf` (the staff oid) on exactly four routes: appeal review, case update
(claim), ticket close and the staff freeze. On those routes the attributed actor is that oid and
`recorded_by_oid` is the bot's oid (the same shape as the proxy's delegation). On any other route `onBehalfOf`
from `DISCORD` gets 400, as it does for every role but `PROXY` today. A `DISCORD` call on the four routes
without `onBehalfOf` is refused with 403: the bot cannot act as itself on a decision.

The backend checks the oid before attributing: a live `discord_link` of kind staff exists for it (decision 3),
and its role snapshot is fresh and holds MODERATOR or ADMIN (decision 2). Refusals: 403 `STAFF_NOT_ELIGIBLE`
(no live link or no role) and 403 `STAFF_ROLE_STALE` (snapshot older than the window).

Rejected: **the bot as actor** (the record would say "Discord bot" and the appeal could not be traced to a
person, which the team needs). **The bot vouches on the guild role alone** (a compromised bot could then act as
any linked oid and nothing on the backend would object; the backend must keep its own check). **Discord OAuth
to Entra in the bot** (a user token in a bot, a new consent and a new trust surface, for four buttons).
**Entra group lookup per action through Microsoft Graph** (needs a new application permission and a secret, and
adds latency to each click; kept as the later option if the snapshot window is not enough).

### 2. Role snapshot on `staff_actor`

The `staff_actor` row already changes on every verified request (`last_seen_at`). The same write stores the
token's roles in `roles` (comma-separated, the `Roles` names only) and the time in `roles_seen_at`. The
snapshot is written only for `HUMAN` callers, and only the staff roles are stored.

The window `blackhole.discord.staff-role-max-age` (default `PT24H`, proposed) bounds how long a removed
MODERATOR keeps Discord access after the last panel or API use. A member who uses the panel daily is always
fresh. A member who has not used the panel for a day must sign in to the panel once before Discord actions
work again; the refusal says so.

Rejected: **no snapshot, trust the bot's guild role** (see decision 1). **A separate role table** (the snapshot
is one row per staff member, the same table that already has one row per oid).

### 3. Staff identity lookup

New route `GET /discord/staff-links/{discordUserId}` (`DISCORD` role) answers the linked staff oid, the
display name and the `roles_seen_at` freshness flag, or 404 when the Discord user has no live staff link. The
bot calls it once per press and never caches it beyond the press, so an unlink takes effect at the next press.

The response carries the oid, which the panel already shows for staff. It never carries a Discord id back
(the Discord id is in the request path only, as `DiscordLinkApi.whoami` does).

Rejected: **Discord id in the action body** (the bot would name any oid it likes; the lookup keeps the link as
the only source). **Cache the link in the bot** (an unlink would then not stop the buttons until restart).

### 4. Lock and unlock: a freeze with a source

Add `freeze_source` (`LEAK` or `STAFF`) to `discord_ticket`. The leak path keeps setting `frozen` with
`LEAK`. A staff lock sets `frozen = true` with `STAFF`. The bot's thaw (`TicketLifecycle.syncFreeze`) thaws only
a `LEAK` freeze; a staff lock survives a leak thaw, and only a staff unlock lifts it. Backfill: every row frozen
today is `LEAK`.

New route `PUT /case/{reference}/ticket/freeze` with body `{ "frozen": true | false, "onBehalfOf": uuid? }`:
`MODERATOR` (panel) or `DISCORD` with `onBehalfOf`. Repeating the same value is a no-op (answers 200 with the
current state), as `PATCH /discord/tickets/{id}` already does for its changes.

Rejected: **lock as a case status** (the case status is the case's own lifecycle and is shown to the player
side; a channel lock is a channel property). **Lock by moving the channel permissions in the bot without a
backend record** (the state would live only in Discord, and the panel could not see it).

### 5. Claim, accept, reject and close reuse the existing routes

- **Claim**: `PATCH /case/{reference}` with the staff oid as the assignee and `expectedVersion` from a read
  just before (`GET /case/{reference}`). With `onBehalfOf`, the assignee is the staff oid. A version conflict
  (409) is shown as "changed by someone else, press again".
- **Accept / Reject**: `POST /appeal/{identifier}/review` with the decision, the modal's player message (required
  for reject, optional for accept) and `onBehalfOf`. The rules of the appeal workflow apply unchanged: self-review
  is refused for the attributed oid (the staff member who issued the punishment cannot decide its appeal from
  Discord either), a second decision is 409 and the button shows the state already reached.
- **Close**: the existing bot close flow (`POST /discord/tickets/{id}/close`) with `onBehalfOf` added to the
  close request. It keeps its completeness proof and protocol gate; the button is shown only once the appeal is
  decided, and a close before the decision is refused by the backend (409 INVALID_STATE).

The player message of the modal is validated by the backend (UUID, IP, Ban ID: 422), the same as in the panel.
The bot shows the server's text in the modal's error, and does not validate separately.

### 6. The control message and the sync

The bot posts one **staff control message** in the ticket channel when the channel is created (and re-posts it
when a rebuild is needed). It shows the case reference, the state (open, locked, decided, closing, closed), the
claimed staff member's display name and the buttons. Button ids are `staff:<action>:<ticketId>`; the bot checks
the ticket id and the action against the ticket it has, and never reads a secret from an id.

Sync: on every `case.*` event for the ticket's case the bot re-reads the ticket and re-renders the control
message (state line and disabled buttons). A panel action therefore shows in the channel within the event
delivery time. Discord actions reach the panel through the existing live case stream. The bot does not keep
its own state: the message is a view of the backend.

Rejected: **the bot keeps a local state machine for the buttons** (two sources of truth; the bot is stateless by
design). **Edit the message on each click only** (a panel action would leave the message stale).

### 7. Failure behaviour

- Backend unreachable or a 5xx: an ephemeral message "not done, try again", nothing changed on either side, the
  control message is not edited.
- A refused action (403, 409, 422): an ephemeral message with the server's text, control message re-rendered
  from the current state.
- Discord unreachable during the re-render: the backend keeps the change; the next `case.*` event or the next
  start re-renders. Nothing is retried in a loop.

### 8. Audit

Every Discord staff action that succeeds writes one audit entry with surface `DISCORD`, actor = the staff oid,
`recorded_by_oid` = the bot's oid, through `StaffAuditRecorder` of `add-staff-audit-log`. A refused action writes
nothing.

### 9. Open in panel

A link button `<panelUrl>/cases/<reference>` (existing `BLACKHOLE_PANEL_URL`). It is not an action and has no
route. The reference is the case short id, never a Ban ID.

## Risks / Trade-offs

- **Stale role window**: a demoted MODERATOR keeps Discord action rights up to the window. Accepted at 24 hours
  (proposed); reduce the window or move to a Graph lookup if the team needs same-hour revocation.
- **The bot is a trusted caller** for four routes. A compromised bot can act as any linked staff member who has a
  fresh MODERATOR or ADMIN snapshot. Mitigations: the backend checks the link and the snapshot, the bot's secret
  is rotated like the proxy's, and every action is audited with the bot's oid as `recorded_by_oid`.
- **Two surfaces, one state**: a short delay between a panel action and the channel's control message (one event
  delivery). Accepted; the message says the state at its last render.
- **Claim by button** overwrites an assignee without asking. The same as the panel's assignee field; shown in the
  message so it is visible.

## Migration plan

1. `V<next>__staff_ticket_actions.sql`: add `staff_actor.roles varchar(128)` and `staff_actor.roles_seen_at bigint`;
   add `discord_ticket.freeze_source varchar(8)`; `UPDATE discord_ticket SET freeze_source = 'LEAK' WHERE frozen`.
   Additive only; existing `staff_actor` rows keep null roles and are not fresh until the next verified request.
2. Backend first: the snapshot write, the on-behalf rule for `DISCORD`, the staff lookup, the freeze route. Old bots
   keep working: they send no `onBehalfOf` and their existing calls are unchanged.
3. The bot's control message and buttons, behind a config flag `DISCORD_STAFF_ACTIONS` (default off) until the team
   has tested them in the dev environment.
4. The panel Lock / Unlock control.
5. Rollback: turn the flag off (buttons vanish on the next render); the backend routes stay, unused.

## Open questions

1. **Snapshot window**: 24 hours proposed. Does the team accept that a removed MODERATOR can act in Discord until the
   next day, or do they want same-hour revocation (Graph lookup, a new application permission)?
2. **Reject without a message**: the modal requires a player message on reject. Should a staff-only internal note be
   allowed too? Not in this change: the appeal contract has one player-facing text today.
3. **Who sees the buttons**: every staff member in the channel (the access reconciler admits only linked, roled staff)
   sees all buttons. Should Close be restricted to ADMIN? Proposed: no, the backend's close gate is the protection.
4. **Re-post of the control message** after a rebuild: does the channel keep the old message (disabled) or delete it?
   Proposed: edit in place, one message per ticket.
