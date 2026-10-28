## Why

An appeal ticket lives in a private Discord channel, and the player and the staff member who
decides it are both usually in that channel. Today the staff member can read and talk there, but
to claim the case, accept or reject the appeal, lock the channel or close it, they must leave for
the web panel, find the case again and come back. Staff have asked for the Discord bot to handle
the ticket too, so that the whole appeal can be worked where it is answered. The panel stays the
other way in, and both must show the same thing.

## What Changes

- **Buttons in the ticket channel** under one staff control message: **Claim**, **Accept**,
  **Reject** (a modal asks for the player-facing message, required on reject), **Lock** / **Unlock**,
  **Close**, and **Open in panel** (a link button to `<panelUrl>/cases/<reference>`).
- **Staff identity**: a button press is attributed to the staff member's Entra object id, found
  through their live Discord staff link (`discord_link`, kind staff). An unlinked member is refused
  with a hint to run `/link`. The bot is never the recorded actor.
- **Roles are enforced by the backend**, not by the bot. The backend keeps a role snapshot of each
  staff member, refreshed on every verified request, and refuses a Discord action unless the snapshot
  holds MODERATOR or ADMIN and is fresh.
- **Same result as the panel**: each action calls the backend route the panel already uses (appeal
  review, case update for the claim, the close flow of the ticket), and the same case events, the
  same player notification and the same validation follow. The new routes are only the ones that do
  not exist yet (lock and unlock, the staff lookup).
- **Both surfaces stay in sync**: an action in the panel updates the Discord control message (state
  shown, buttons disabled), and an action in Discord appears in the panel through the existing live
  stream.
- **Panel lock control**: the panel gets a Lock / Unlock action on the case page (there is none on
  main), so that the two surfaces offer the same actions.
- **Audited**: every Discord staff action writes an audit entry with surface `DISCORD` through the
  recorder of `add-staff-audit-log`.
- **BREAKING**: none. All routes and fields are additive. The generated client gains the new
  operations and fields.

Ships as: `feat(discord): add staff ticket actions`

## Capabilities

### New Capabilities

- `discord-staff-ticket-actions`: the staff control message and its buttons, the staff identity
  lookup, the failure behaviour, the panel and Discord synchronisation and the open-in-panel link.

### Modified Capabilities

- `verified-actor`: a new requirement that the `DISCORD` machine role may send `onBehalfOf` for a
  linked staff member on four routes, plus the staff role snapshot that makes the check possible.
- `admin-web-ui`: a Lock / Unlock action on the case page, and the Discord state shown on the case page.

This change extends the ticket channel of `add-discord-appeal-tickets` (capability
`discord-appeal-tickets`, in flight). It does not restate that capability.

## Impact

- **Backend**: `appeal/` (review accepts `onBehalfOf` from the bot), `casetimeline/` (claim through
  the existing case update), `discord/ticket/` (freeze source, staff lock route, close with
  `onBehalfOf`), `discord/controller/` (staff lookup route), `security/` (role snapshot on `staff_actor`,
  the `DISCORD` on-behalf rule), `audit/` (calls to the recorder, from `add-staff-audit-log`).
- **Database**: one new Flyway migration `V<next>__staff_ticket_actions.sql`: `staff_actor` gets
  `roles` and `roles_seen_at`; `discord_ticket` gets `freeze_source`, backfilled to `LEAK` where a
  ticket is frozen today. Additive only.
- **Discord bot**: a staff control message, button and modal handlers, a state renderer, the
  synchronisation from `case.*` events, and a staff-lookup call per press.
- **Web**: a Lock / Unlock action and the Discord state on the case page.
- **API and client**: new route `PUT /case/{reference}/ticket/freeze` (lock and unlock), new route
  `GET /discord/staff-links/{discordUserId}`, and `onBehalfOf` on the appeal review, case update,
  close and freeze request bodies. Client spec: next version after 0.0.44.
- **Config**: `blackhole.discord.staff-role-max-age` (default `PT24H`, proposed) on the backend.

## Out of scope

- Non-appeal tickets, and any ticket type other than the appeal ticket.
- Deciding an appeal from a player's Discord message, or any slash command that changes a punishment.
- Looking up Entra group membership from the backend (the role snapshot is used instead; see design).
- A Discord OAuth login for staff or players; staff are identified by the existing link code.
- Per-user cooldowns or rate limits in the bot (infra owns rate limiting).
- Moderating the channel's messages (delete, pin, mute) from the control message.
- Changing the leak scanning or the protocol rules of `add-discord-appeal-tickets`.

## Dependencies

- `add-discord-appeal-tickets`: the ticket channel, the staff access reconciler and the Discord
  message sync that the control message attaches to. It is in flight; this change is built on it.
- `add-staff-audit-log`: the audit recorder and the `staff.action` event. Land it first, or ship the
  audit calls of this change in the same PR behind the recorder interface.
