# Proposal

**Ships as:** `feat(case)` (Markdown model, attachments on messages, evidence sharing), `feat(appeal)` (public upload and
download routes, live updates), `feat(portal)` (chat UI), `feat(web)` (panel chat UI, share dialog), `feat(discord)`
(attachment and Markdown relay) and one `docs` PR. One type per commit; the PR plan is in `tasks.md`.

**Builds on:** `add-appeal-portal` (public API, conversation, protocol), `add-discord-appeal-tickets` (ticket relay),
`add-punishment-case-timeline` and the evidence pipeline on `main`. It reverses two decisions of `add-appeal-portal`
(design decision 5: "no attachments in v1" and "plain text only"); the design says how.
**Depends on (soft):** `switch-event-bus-to-nats` for cross-replica live updates, see design decision 9.
**Related:** `add-appeal-conditions` (PR 304 and its stack) keeps its plain-text conditions; it may adopt the Markdown
renderer later, not in this change.

## Why

The appeal portal shows a thread and a one-line box. A player who wants to prove something (a screenshot of a false
detection, a PDF, a log) can only describe it in words; a moderator who wants to show the player what the ban was based
on cannot. Text is one flat string, so a longer explanation is hard to read, and every new message needs a page reload
or a 15 second poll. The appeal is the one place where a banned player and the team talk, so it should behave like a chat:
bubbles, formatting, files in both directions and updates that arrive on their own.

## What Changes

- **Chat UI** in the portal and in the panel's conversation section: bubbles (own side and other side), timestamps and day
  separators, a composer with attach button, drag and drop and paste, per-file progress, and a cheap "seen by the player"
  marker for staff.
- **Markdown, a safe subset**: bold, italic, strikethrough, inline code, code blocks, lists, quotes and `http(s)` links.
  No raw HTML, no images, no embedded media. The source text is stored; it is parsed on display by **one** Java parser
  (new module `markdown`) that backend and Discord bot share. The backend serves a sanitised block tree next to the
  source; portal and panel render the tree with Vue components, never HTML strings. External links open through an
  interstitial warning page.
- **Player uploads**: PNG, JPEG, WebP, PDF and TXT, 10 MB each, at most 5 per message, through the evidence pipeline
  (scan first, magic-byte sniffing, S3). A file is staged, scanned, and appears only once clean and sent. Images are
  re-encoded (PNG, JPEG) or stripped of EXIF and XMP (WebP). PDFs and TXT are downloads, never inline.
- **Team files and shared evidence**: staff can upload files in the chat (same rules) and can **share** an individual
  existing evidence item with the player. Nothing is visible to the player without a share; a share is an auditable
  case event and can be revoked. Chat-capture evidence is shared as a **server-rendered excerpt** with the other players
  relabelled "Player 2", "Player 3", and every share needs a staff confirmation that it is minimised.
- **Live updates by Server-Sent Events**: portal through the BFF (cookie session), panel through the backend (staff token).
  The event says only "changed, seq N"; the client fetches. Polling stays as the fallback.
- **Discord relay**: attachments and shares reach the ticket channel, Discord attachments from the bound player and
  verified staff come in under the same rules, and Markdown is mapped both ways.
- **Backend**: migration for evidence provenance, the share table and the read marker; public routes
  `POST /public/attachments`, `GET|DELETE /public/attachments/{id}`, `GET /public/conversation/events`; staff routes for
  shares and `GET /case/{reference}/events`; `attachmentIds` on both message-post routes; Discord routes for attachment
  bytes. Client spec `blackhole-api-0.0.N.yml` (next free number, once per PR that changes the HTTP API).
- Player-facing strings are English only.

BREAKING: none for existing callers. `POST /public/messages` and the staff message route gain an optional
`attachmentIds` field; conversation entries gain `blocks` and `attachments`; the portal BFF allow-list grows. Messages
written before this change are rendered as Markdown too (no data migration), so old text with `*`, `_` or backticks may
now show formatting.

## Out of scope

- Images by URL, embedded media, HTML, tables, headings, link previews, mentions, emoji pickers, reactions.
- A live Markdown preview in the composer (one parser lives on the server; preview would need a route), typing indicators,
  "seen by the team" for the player, push notifications and e-mail.
- Video uploads and animated images in the chat; staff evidence uploads keep their own type list.
- Blurring or editing screenshots before sharing; sharing anticheat, replay, link or transcript evidence.
- A later rescan of stored files when the virus signatures update.
- Translations of player-facing strings; app-level rate limiting (the edge owns it, the docs PR lists the rules).
- Per-actor identity beyond the existing verified `oid` of staff.

## Capabilities

### New Capabilities

- `chat-markdown`: the Markdown subset, the sanitiser contract, link rules, the shared parser and its mappings.
- `appeal-chat`: attachments on messages, the upload and download routes, quarantine, provenance, the chat UI.
- `evidence-sharing`: sharing and revoking evidence with the player, the excerpt and its minimisation.
- `chat-live-updates`: the SSE routes, the signal, fan-out across replicas and the polling fallback.
- `discord-chat-relay`: attachments and Markdown in the ticket channel, both directions.

### Modified Capabilities

- `punishment-evidence`: provenance, the chat upload profile, a separate player-supplied figure that is not part of the team evidence count, erasure of player files.
- `admin-web-ui`: the conversation section as a chat, the share dialog, the provenance label.

## Impact

- New Gradle module `markdown` (no dependencies), used by `backend` and `discord`.
- `backend/`: `casetimeline/`, `evidence/upload|service`, `publicapi/`, `discord/`, events, Flyway migrations (next free
  versions at implementation time), `application.yml` (`blackhole.chat.*`), `.claude/rules`.
- `portal/`: BFF allow-list (multipart upload, binary download, SSE stream), chat components, interstitial page.
- `web/`: conversation section, composer, share dialog, fetch-based SSE client.
- `discord/`: outbox relay, ingest, `DiscordMarkdown`, attachment fetcher.
- `client/`: regenerated from new spec files. Docs: operations note with the edge rules.
