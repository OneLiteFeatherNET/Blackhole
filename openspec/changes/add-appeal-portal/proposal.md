# Proposal

**Ships as:** `feat(appeal)` (Ban ID, public API), `feat(case)` (conversation model), `feat(portal)`
(player app), `feat(web)` (panel), `feat(velocity)` (ban screen), `docs(appeal)`. One type per
commit, see the PR plan in `tasks.md`.

## Why

A banned player can appeal today only through a staff member or a tool that calls `POST /appeal`.
There is no player-facing place to file an appeal, to talk to the team or to read the decision, and
the case timeline (`add-punishment-case-timeline`) reserves `message.added` and `ban_id.leak_marked`
for exactly this. The team wants players to appeal **with nothing but their Ban ID**, without a
Discord or Microsoft account, and the full conversation to be kept as part of the case.

## What Changes

- **Ban ID.** Every punishment gets a unique 8 character Crockford base32 Ban ID (about 40 bits,
  `0-9 A-Z` without `I L O U`). It is the only credential of the portal, so it is stored as a
  peppered hash for lookups and encrypted for staff display, never as plain text, never logged,
  never in a URL or a domain event. Existing hex short ids stay valid **for staff lookup only**;
  existing punishments are issued a Ban ID by a backfill job.
- **Public API** under `/public/**` for an anonymous *player session*: log in with the Ban ID,
  read the own punishment's public facts, file the one appeal, post and read messages, read the
  decision. The session is a server-issued opaque token, revocable.
- **Appeal portal.** A separate public Nitro app `portal/` on the player domain (`.net`,
  ADR-ORG-0009), a backend-for-frontend that keeps the session in an HttpOnly cookie and exposes
  only an allow-list of routes. German and English, accessible, no attachments in v1.
- **Case conversation.** `message.added` (plus `message.edited` / `message.deleted`) on the case
  timeline, visibility `PLAYER` or `STAFF`, written by the portal, the panel and (next change)
  Discord. The backend is the record: nothing lives only in a chat. Messages are kept as long as
  the case, redactable for GDPR, and exportable by an admin.
- **Leak rule.** The team marks a Ban ID as leaked with a reason code (`ban_id.leak_marked`): the
  appeal right of that Ban ID is forfeited; a leak after filing freezes the player side of the
  ticket while the team can still decide. An admin can reissue the Ban ID for a leak the player
  is not to blame for (`ban_id.reissued`).
- **Panel.** The case page shows the conversation with a composer, leak and reissue actions and a
  transcript export; the appeals queue links to it and shows unanswered player messages.
- **Velocity** shows the Ban ID on the ban screen.
- **Appeal protocol.** When an appeal's conversation closes, the backend turns it into a complete,
  human-readable protocol plus a machine-readable JSON, generated from the stored case events only, and
  stores it with the case as an evidence item of the new kind `TRANSCRIPT` (idempotent, versioned, kept in
  step with redactions). Staff open and download it on the case page; the player can download the
  player-visible view in the portal.
- **Settled with the team (2026-10-23):** portal host `appeal.onelitefeather.net`; session 30 minutes idle and
  4 hours absolute; key rotation in v1 is "reissue all" (key ids later).
- Client spec `blackhole-api-0.0.N.yml` (next free number at implementation time).

**BREAKING (contract, not behaviour):** the player-visible identifier changes from the 8 hex short
id to the Ban ID. The generated client and the Velocity plugin must move to the new
`GET /punishment/{ref}/ban-id`; the short id routes and fields keep working unchanged for staff.
`POST /appeal` (staff-driven) is unchanged.

## Out of scope

- The Discord bot and ticket channels (`add-discord-appeal-tickets`, which builds on this change).
- General support tickets or any appeal type other than "unban appeal" for an existing punishment.
- Discord or Microsoft sign-in for players, in-game codes, e-mail.
- App-level rate limiting of login or messages (infra owns it, config principle 5); a failure
  *counter metric* for alerting is not a limiter and is in scope.
- Attachments from players (v1 is text only), message edit or delete by players in the portal.
- Per-actor identity for moderators beyond the verified actor already in place.
- Retention sweeps of conversation text (the lifetime rule is the case's).

## Capabilities

### New Capabilities

- `ban-id`: format, generation, storage, lookup, legacy handling, reissue.
- `appeal-portal`: public API, player session, the portal app, abuse and safety rules.
- `case-conversation`: messages on the timeline, visibility, leak and freeze, retention, erasure, export.
- `appeal-protocol`: the stored protocol of an appeal: content, creation, versions, access, redaction.

### Modified Capabilities

- `staff-authentication`: the `/public/**` prefix gets routes and a permission-matrix entry; new
  staff rows for messages, leak, reissue, ban-id and transcript.
- `appeal-workflow`: one open appeal per Ban ID, portal filing, player-visible decision message.
- `admin-web-ui`: conversation, leak, reissue and export on the case page; queue badge.
- `proxy-enforcement`: the ban screen shows the Ban ID.
- `punishment-evidence`: the system-generated kind `TRANSCRIPT` (storage, count, status, access).

## Impact

- Backend: `punishment/` (Ban ID generator, store, backfill, lookup), new `publicapi/` package
  (session, `PublicApi` controllers, services), `casetimeline/` (message types, leak, reissue,
  export, `protocol/`), `appeal/` (filing path for the portal), `evidence/` (kind `TRANSCRIPT`,
  `GeneratedEvidenceGateway`), `security/` (public rules in the matrix test).
- Flyway: `V13__ban_id.sql`, `V14__player_session.sql`, `V15__appeal_protocol.sql` (next free versions at implementation time).
- New module: `portal/` (Nitro + Vue, own Dockerfile and CI image build). Also Velocity, `web/`
  and the client spec.
- Config: `blackhole.banid.*`, `blackhole.public.*`, `blackhole.protocol.*` (`settle-delay`,
  `reconcile-interval`, `max-bytes`); env `BLACKHOLE_BANID_KEY`.
- Depends on `add-punishment-case-timeline` (case, `case_event`, `visibility`, redaction). The
  message model extends it with new event types only; no `case_event` column changes.
