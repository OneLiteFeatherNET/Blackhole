# Proposal

**Ships as:** `docs(openspec)` for this change (proposal, design, specs, tasks). The implementation ships as the
small stacked PRs listed in `tasks.md`, one Conventional Commit type per PR: `refactor(web)`, `feat(web)`,
`feat(portal)`, `feat(staff)`, `feat(case)`, `feat(api)`, `feat(discord)`, `fix(web)`, `docs(appeal)`.
Pull request titles are the squash-merge commits and follow the same rules.

Based on `openspec/changes/redesign-ux/audit.md` (2026-10-25, commit e86fea6). Every finding referenced here
uses the audit's section numbers.

## Why

The audit found that the staff panel (`web/`), the player portal (`portal/`) and the Discord ticket relay
(`discord/`) do not share one navigation, one set of components, one filter idiom, one error vocabulary or one
word list. The most serious gap is in the appeal chat: a banned player sees every staff member as "Moderation
team", and Discord relays staff the same way (audit 4, 6.1, 6.3). The team decided on 2026-10-25 that an
appeal is a **chat between the banned player and individual team members**, so players must see who they are
talking to. That needs a staff identity that players can see, which does not exist today.

## What Changes

### Staff identity and the people-to-people appeal chat

- **Staff display name.** Every staff account gets a display name that the staff member edits in the panel
  (`My profile`). It is **required** before the account can write to a player (appeal chat message, decision
  text, Discord reply from a linked account). Pseudonyms are allowed. The default is empty; the Entra `name`
  claim is never shown to players (the `staff_actor.display_name` stays internal and audited).
- **Staff avatar.** A Minecraft head rendered from the staff member's linked Minecraft UUID, produced and cached by
  the backend and served through the portal's BFF and a hashed key. If the account is not linked, or the staff
  member chooses initials, the avatar is an initials badge rendered locally. The player's browser never calls a
  third party and never receives a Minecraft UUID.
- **Portal, panel and Discord show the name and avatar** of the staff member on each staff message. The player
  sees "Staff" only for legacy messages written before this change (audit 4, decision of 2026-10-25).
- **Protocols.** New protocol versions (`schemaVersion` 2) show display names; existing stored versions are not
  rewritten.
- **GDPR.** Showing staff identity to banned players is new processing of staff personal data. The change adds a
  staff notice (acknowledged in the profile dialog) and updates the player-facing protocol text and the retention
  rules. Legal points are marked **pending legal confirmation**.

### Navigation, design system and UI consistency (all three surfaces)

- New home **Inbox / Posteingang** that lists what needs attention: unanswered appeal chats, open appeals, new
  reports, unassigned cases (audit 1.6). Backed by a new read endpoint.
- Navigation regrouped and renamed to remove the terminology clashes (audit 1.2, 1.4, 2 Wording); the account-link
  panels move out of the Sections page (audit 1.3, 6.6). Breadcrumbs on every detail page (audit 1.7).
- A design-system contract for the panel: one spacing scale, one type ramp, button/chip/filter variants, one
  filter idiom (toolbar in one row with active-filter chips), one row-action pattern, one table component, one
  page template, empty/loading/error states, a dense field variant (audit section 2).
- One token source shared by `web/` and `portal/`, with a parity test (audit section 3).
- Portal guided flow: login, conditions, appeal, chat as one flow on mobile and desktop.
- Discord embeds use the staff display name and avatar, in a consistent tone.

### Lists and errors

- Every list is paginated and filtered on the server. The UI gets a shared pagination component and removes the
  client-side search over one page (audit 7). The API side is the separate change
  `standardize-api-pagination-and-problems` (cursor pagination `{items, nextCursor}` and RFC 9457 Problem Details),
  which this change **depends on**.
- One error display: Problem Details `title`/`detail`, translated when a `code` is known; no raw backend text;
  silent failures become visible (audit 8, 6.12, 6.15).

### Accessibility

- Heading levels, focus, labels and live regions as listed in audit section 5.

## Capabilities

### New Capabilities

- `staff-profile`: staff display name, avatar policy, the required-name rule for player-facing writes, and the staff
  notice. Durable behaviour: how staff appear to players.

### Modified Capabilities

- `admin-web-ui`: navigation, the Inbox replaces the dashboard as the home page, the design-system contract, the
  queues use server-side filters and pagination, error display, the profile setting. Delta at
  `specs/admin-web-ui/spec.md`.
- `appeal-portal`: guided flow, staff names and avatars in the chat, the player copy of the protocol shows names.
  Delta at `specs/appeal-portal/spec.md`. **Depends on `add-appeal-portal`**, whose requirements it modifies.
- `appeal-chat`: the chat interface shows the staff author's name and avatar. Delta at `specs/appeal-chat/spec.md`.
  **Depends on `add-appeal-chat`.**
- `appeal-protocol`: protocol version 2 uses display names and avatars; version 1 is unchanged. Delta at
  `specs/appeal-protocol/spec.md`. **Depends on `add-appeal-portal`.**
- `case-conversation`: a `message.added` event for staff carries a snapshot of the display name at write time
  (payload version 2). Delta at `specs/case-conversation/spec.md`. **Depends on `add-appeal-portal`.**
- `discord-appeal-tickets`: staff messages in the ticket channel use the display name and the avatar. Delta at
  `specs/discord-appeal-tickets/spec.md`. **Depends on `add-discord-appeal-tickets`.**
- `verified-actor`: anonymisation also clears the staff display name and avatar key. Delta at
  `specs/verified-actor/spec.md`.

The three `add-*` changes are in flight (their code is partly on `main`, their specs are not yet archived). The
MODIFIED requirements in this change name requirements those changes add. This change is applied after they are
archived, or the deltas are rebased onto their final text. See open question 1.

## Out of scope

- The API pagination and Problem Details change itself (`standardize-api-pagination-and-problems`).
- Entra SSO and the per-actor identity redesign (`add-team-sso`).
- Player accounts, a player-side profile, or any player-chosen name.
- Hashing the stored `staff_link.minecraft_uuid` column. It is raw personal data today (see the risk in
  `design.md`); it is a follow-up change with its own migration.
- Final legal wording, the retention period and the lawful basis. They are marked pending legal confirmation.
- A dark-mode or colour redesign. The palette is the existing MD3 seed; only the contrast check is in scope.
- Screenshots. The audit did not capture them (see `audit.md` header); the design is checked after implementation
  in the real app, per the project's runtime-verification rule.
- Any change to the Velocity plugin or the proxy enforcement path.

## Impact

- **Backend:** new table `staff_profile`; `GET`/`PUT /staff-profile/me`; `GET /inbox`; `GET /public/staff-avatar/{key}`
  (served through the BFF); `message.added` payload version 2; `PublicConversationDTO` and
  `PublicProtocolService`/`ProtocolModel` renderers; `DiscordTicketDTO` and the outbox payload; anonymisation.
- **Client spec:** `client/specs/blackhole-api-<next>.yml`, where `<next>` is the next free version at implementation
  time (main is at 0.0.40 today). Regenerated in every PR that changes the HTTP API.
- **Panel (`web/`):** shell, tokens, components, every view listed in `design.md`.
- **Portal (`portal/`):** flow, chat names and avatars, pagination of older messages, errors.
- **Discord (`discord/`):** `OutboxRelay`, `JdaGateway`, embeds.
- **BREAKING:** none for generated clients or the Velocity plugin. The staff write routes start to answer
  `409 STAFF_PROFILE_REQUIRED` for an account without a display name; this is an additive error code and a
  behaviour change for staff, announced in the release notes.
- **Migrations:** one new Flyway migration, `V<next>__staff_profile.sql` (see `design.md`).
