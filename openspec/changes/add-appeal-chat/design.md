# Design

## Context

Read on `main` on 2026-10-24:

- Messages are `message.added|edited|deleted|redacted` case events; `CaseEventPayload.PORTAL_VISIBLE_TYPES` is the allow-list a
  player reader applies in SQL (`JdbcPublicConversationReader`). Text is plain, 1 to `MessageRules.MAX_MESSAGE` characters,
  checked by `PiiGuard` and the Ban ID check. The portal draws text nodes only; `portal/src/noMarkup.test.ts` fails on `v-html`.
- `MessageAttachment` is **metadata of Discord files** (no bytes). `add-appeal-portal` decision 5 says: no player attachments in
  v1 and plain text only. This change reverses both on purpose; the old decisions were scoped to "v1", and reuse of the
  staff-only pipeline for players was named there as its own change. This is that change.
- `UploadPipeline` (evidence) scans first (flagged 422, scanner down 503, fail closed), sniffs magic bytes (PNG, JPEG, MP4,
  WebM), caps size per type, caps pixels and re-encodes PNG/JPEG (strips metadata). `FILE` evidence is served by
  `EvidenceController.file` with `nosniff`, `CSP: sandbox`, `no-store`; only image and video are `inline`.
- Evidence can exist *pending* (owner + correlation id, no punishment) and is claimed later (`claimPendingByIds`); the
  housekeeping sweeper deletes unclaimed items after `pending-ttl` with reason `UNCLAIMED_EXPIRED`.
- The Discord outbox returns `PLAYER`-visible message events after `relayed_seq` (`ADD|EDIT|DELETE|REDACT`); ingest stores
  Discord files as metadata only; "Discord attachments are not evidence" (`evidence.md`).
- The portal is a Nitro v2 BFF with an allow-list of routes, JSON bodies capped at 16 KiB, a 15 s backend timeout, cookie
  `__Host-bh_portal` and CSRF (header + Origin) on state changes. The panel (`web/`) talks to the backend with the staff token
  through its own Nitro proxy. Both sit behind a Cloudflare Tunnel (ADR-ORG-0009: portal on `.net`, panel on `.dev`).
- Events are hints (`events.md`): published after the write in a try/catch, never content. RabbitMQ today; the parallel change
  `switch-event-bus-to-nats` replaces it.
- Config principles apply: layered backend (thin controllers, sealed DTOs, services answer with Error variants), no
  `@Transactional` (one data-modifying CTE per change), Flyway append-only, infra owns rate limiting, GDPR by design.

## Goals / Non-Goals

Goals: a chat that is safe by construction (no markup injection, no unscanned file reachable, no evidence visible without an
explicit, reversible, minimised share), one place where Markdown is defined, live updates that survive a Cloudflare Tunnel
and several replicas, and a Discord relay with the same rules.

Non-goals: see the proposal's out-of-scope list.

## Decisions

### 1. One parser, a block tree on the wire (renderer)

Options: (A) each client parses Markdown (markdown-it in the TS apps, a Java library in the bot); (B) one parser on the
server, clients render a sanitised tree.

**Recommended: B.** Three parsers drift, and every drift is a sanitiser bug. A new Gradle module `markdown` (plain Java, no
dependencies, linear hand-written scanner, no regular expressions on user text) holds `MarkdownParser`, the sealed node
types, `PlainTextRenderer` and `DiscordRenderer`. The backend parses on read and answers `text` (the stored source) and
`blocks` (the tree). The portal and the panel draw the tree with a small Vue component made of `h()` calls: text goes in as
text nodes, so `noMarkup.test.ts` keeps passing and gains a rule that the renderer never builds a string of markup. The bot
links the same module. The conformance corpus (`markdown/src/test/resources/corpus/*.json`: source, expected tree, expected
Discord text, expected plain text) is the one spec of behaviour; the TS components have their own tests on hostile *trees*.

Cost: no live preview in the composer (the client has no parser). Decided 2026-10-24: none in v1, only a formatting hint under
the composer; a `POST /public/markdown/preview` route can come later. Messages are re-parsed on each read; they are at most 2000 characters, parsing is linear, no cache.

**The subset** (everything else is literal text, never an error):

| Construct | Source | Node |
|---|---|---|
| paragraph, line break | a newline inside a paragraph is a break (chat style) | `paragraph`, `br` |
| bold, italic, strikethrough | `**x**`, `*x*` or `_x_`, `~~x~~` | `strong`, `em`, `del` |
| inline code | `` `x` ``, no nested markup inside | `code` |
| code block | fenced with three backticks, an info string is ignored, no nested markup | `code_block` |
| lists | `- `/`* ` and `1. ` items, nesting up to 3 levels | `list{ordered,start}`, `item` |
| quote | `> `, nesting up to 3 levels | `quote` |
| link | `[text](https://host/path)`, `<https://host>`, bare `https://host/path` | `link{href, external}` |

Not supported and shown literally: raw HTML (`<b>` stays the text `<b>`), `![alt](url)` (the `!` and the brackets stay text,
the URL is not a link inside it), headings, tables, horizontal rules, footnotes, reference-style links, `__x__` (not a
delimiter, because Discord uses it for underline), `javascript:`, `data:`, `vbscript:`, `file:`, `mailto:`, `//host`,
relative links. Limits: nesting depth 3, at most 500 nodes (more: the rest of the message is one literal paragraph),
source at most the existing message limit. The parser never throws and never loops on any input (fuzzed, see Tests).

**Links.** A link is accepted only when the target, after trimming, is at most 2048 characters, has no control character,
space, backslash or `<>"`, parses as `java.net.URI` with scheme exactly `http` or `https` (case-insensitive), has a non-empty
ASCII or punycode host, no user info (`user:pass@`), and a port in 1..65535. The tree carries the *normalised* absolute
URL. Otherwise the whole `[text](target)` stays literal text. Nothing is ever fetched or unfurled by the server (so there is no
SSRF surface; principle 5 stays satisfied). `external` is false only when the host equals `onelitefeather.net` or is a true subdomain of it (the host ends with
`.onelitefeather.net`, the dot included), decided 2026-10-24; `evilonelitefeather.net`, `onelitefeather.net.evil.example` and
`onelitefeather.net@evil.example` are external. The host is compared after normalisation (lower case, no trailing dot). The list
is `blackhole.chat.links.trusted-domains` (default `onelitefeather.net`, an empty list makes every link external). The clients put
every external link behind the interstitial.
Deceptive link text (the visible text looks like another URL): the clients always show the target host next to the link text.

**Interstitial.** Portal and panel each have a route `/leave`. The link is `/leave#<base64url of the URL>`: the URL travels in
the fragment, so it reaches no server log and no `Referer`. The page validates the URL again with the same rules in a small TS
function (`safeHttpUrl`, tested with the URL part of the hostile corpus, because anybody can hand a victim a `/leave#...`
link), shows the full target as text, explains that Blackhole does not control the site, and offers **Go back** and
**Continue** (`target=_blank`, `rel="noopener noreferrer nofollow ugc"`). It never redirects by itself, so it is not an open
redirect. An invalid target shows an error and no Continue.

**Transcript and protocol keep the raw source** (the decision the owner left open: raw or plain). Both the text and the JSON of
the stored protocol and the player copy, and the admin transcript, write the stored Markdown source verbatim, and the JSON
entry adds `"format": "markdown"`. Reasons: the record must show what was written; a plain rendering loses information (a
link target differs from its text) and would change when the renderer changes, so the same events would no longer give the same
file. The digest does not cover text, so it does not change. Attachments are listed as one marker line per file: type, size,
first 12 hex of the SHA-256 and the sanitised display name; a share as `[evidence shared: chat excerpt, 5 lines]` and a
revoke as `[evidence share withdrawn]`. Bytes and excerpt text are never in the protocol. `ProtocolRenderer` stays a pure
function; the JSON `schemaVersion` becomes 2 because entries gain fields.

### 2. Attachments: evidence items, staged then bound

A chat file is an `evidence_item` of kind `FILE`, so it is case evidence, follows the punishment's lifetime and uses the
existing store, audit and status handling. New column `provenance` (`STAFF` 0, `PLAYER` 1, ordinal, append-only enum; default
0 so every existing row is staff evidence).

Two steps, so a message with five files is one atomic post:

1. `POST /public/attachments` (multipart, one file) or the staff equivalent stores the file **staged**: a pending evidence item
   with `correlationId = chat:<appealId>`, the punishment's hashed owner, provenance and the actor. Pending items are in no
   listing, no count and no `GET /evidence/{id}` of the panel until bound. The answer is `{attachmentId, name, contentType,
   size, width?, height?}`; the id is the evidence item id (a random UUID).
2. `POST /public/messages` (and the staff route) take `attachmentIds` (at most 5, all staged, all with this appeal's
   correlation id, none used before). `appendMessage` is the existing single CTE extended to claim the items (set the
   punishment, which turns them into case evidence) and write the `message.added` payload. A repeat with the same
   `Idempotency-Key` changes nothing (`repeated: true`). A message may have no text when it has at least one attachment.

The pending lifetime is the existing `pending-ttl` (the sweeper deletes unbound items, reason `UNCLAIMED_EXPIRED`). The
composer deletes a removed chip at once (`DELETE /public/attachments/{id}`, only a staged item of the caller's appeal).

The payload entry is `{kind: "file", attachmentId, name, contentType, size, width?, height?, provenance}`; Discord metadata
entries (kind `file` without `attachmentId`, `sticker`) stay as they are. Nothing else is copied into a payload or an event:
**no bytes, no storage key, no hash in the event.** The file name is shown and is removed with the text by a redaction.

**Caps.** `blackhole.chat.attachments.*`: `max-bytes` 10 MiB, `max-per-message` 5, `allowed-types`
`image/png,image/jpeg,image/webp,application/pdf,text/plain`. A *quota* (a bound on stored state, not a rate):
`max-files-per-appeal` default 50 and at most 10 staged at once, answer 409 `QUOTA`. The edge owns rates (docs PR).

### 3. The upload path and quarantine

`UploadPipeline` gets a **profile**: `EVIDENCE` (today's behaviour, unchanged) and `CHAT` (the five types above, 10 MiB). The
order stays: spool to a bounded temp file; **scan first** (clean; flagged: 422 `FILE_FLAGGED`; scanner down: 503, fail closed);
sniff magic bytes; the type must be in the profile's list (client type and file name never used); size and pixel caps;
type-specific step; hash; store.

**Quarantine.** The spool directory is the quarantine: the bytes are in no store, no URL and no response until the scan said
clean, so a flagged file never exists anywhere a reader can reach. After the scan a file is *staged* (in S3, pending, reachable
by nobody but the delete route) and becomes visible to the other side only when a message binds it. The composer shows
`Uploading`, `Scanning`, `Ready` per chip and enables Send when every chip is ready. An asynchronous scan (accept first, scan
later) was rejected: it needs a second state machine and a visible "not scanned" window, and the scanner already answers
within one request. A later rescan after signature updates is out of scope and named as a risk.

**Type-specific step (recommendation on metadata).**

- **PNG, JPEG: re-encode** (today's `ImageSanitizer`): drops EXIF/XMP/ICC and any trailing or polyglot payload.
- **WebP: strip, do not re-encode.** The JDK cannot decode WebP, and linking a native or third-party decoder adds attack
  surface the project does not need (libwebp had a heap overflow, CVE-2023-4863). `WebpSanitizer` parses the RIFF container
  in pure Java: the `RIFF` size must match the file length exactly (no trailing bytes), chunks must be well formed, `EXIF` and
  `XMP ` chunks are removed, the `VP8X` flags are cleared for them, and the canvas size is read from the `VP8 `, `VP8L` or
  `VP8X` header and checked against `max-image-pixels`. An animated WebP (`ANIM` chunk or the animation flag) is refused (415).
  The image data is not decoded by our process; a file that parses but does not decode is shown as a broken image by the
  browser, which is harmless.
- **PDF: validate, refuse active content, serve as a download.** Header `%PDF-` within the first 1024 bytes. Best-effort
  refusal (422 `FILE_ACTIVE_CONTENT`) when the raw bytes contain the names `/JavaScript`, `/JS`, `/Launch`, `/OpenAction`,
  `/AA`, `/EmbeddedFile`, `/RichMedia`, `/XFA`, `/SubmitForm` or `/Encrypt` (an encrypted file cannot be scanned). This is
  defence in depth, not a guarantee (names can be escaped and hidden in object streams); the real controls are ClamAV and
  the delivery rules below. PDF metadata (author, producer) cannot be stripped and is documented as retained.
- **TXT: strictly UTF-8.** Decoded with a strict decoder (malformed input refused), no NUL, no control character except
  tab, newline and carriage return, a BOM tolerated, and a text that starts (after whitespace) with `<?xml`, `<svg`, `<html`,
  `<!doctype` or `<script` is refused, so it is never an active document in disguise. TXT is the fallback type: it is tried
  last, after every binary type failed to match.
- **File names:** `FileNames.sanitize`, plus: a name that contains a UUID, an IP or a Ban ID shape is replaced by
  `attachment.<ext>`. The name is the only text from the file's owner that other people see.

File *contents* are not checked for a Ban ID or personal data (a screenshot cannot be); the limit is stated in the player
text ("do not upload files that show other people's personal data") and the files are visible only to the player and staff.

### 4. Delivery: what a reader may fetch and how

- **Player:** `GET /public/attachments/{id}`. The id is either a message file's `attachmentId` or, for a shared image, the
  `shareId` (a player never sees the evidence id of a share). It is an opaque capability **checked against the session's
  punishment**. This amends "Own punishment only": the one identifier a public route accepts is this id, and another
  punishment's id and an unknown id are both 404. Visible when the file belongs to a `PLAYER`-visible, not deleted, not
  redacted message of the session's thread, or to an **active** share of the session's appeal. The leaked scope (`LIMITED`) is 403
  like the thread.
- **Staff:** the existing `GET /evidence/{id}/content` (`VIEWER`, audited `VIEWED`/`DOWNLOADED`), because the file is evidence.
- **Headers on every file**, from the one existing `file()` helper moved to a shared `SafeFileResponse`: the detected
  `Content-Type`, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: sandbox`, `Cache-Control: private, no-store`,
  `Content-Disposition`. **Only PNG, JPEG and WebP are `inline`** (the bubble shows them); **PDF and TXT are always
  `attachment`** with a sanitised file name, never inline, in the portal, the panel and the BFF. The BFF streams the body
  unchanged, forwards exactly these headers and sets no cookie on it.
- The portal CSP stays `default-src 'self'`; images come from the BFF's own origin, so `img-src 'self'` is enough, plus
  `blob:` for the composer's local thumbnails (`img-src 'self' blob:`).

### 5. Provenance, counting, labelling

Player-supplied files are evidence of the case and are labelled **player-supplied, unverified** wherever evidence is shown:
the DTO gains `provenance`, the panel shows a badge and lists them separately. **They do not count in the evidence number that
leaves the panel** (decided 2026-10-24): `countValid` and `GET /punishment/{ref}/evidence/count`, which the Velocity ban
screen reads, count `STAFF` provenance only, so a banned player cannot change the number on their own ban screen. The count
answers gain `playerSupplied` (a separate figure), and the panel shows the breakdown, "3 team, 2 player-supplied". Punishment
DTOs keep `evidenceCount` as the team figure. A staff chat upload is `STAFF` provenance and, after the message is posted, is
visible to the player; the panel warns before send. A revoke or a full-lift appeal marks these items like any other evidence.

**Erasure.** A redaction of a message also deletes the message's evidence items and blobs (reason `ERASURE`); the player-side
erase (`erasePlayerMessages`) deletes every `PLAYER`-provenance chat file of the case; the cascade with the punishment is
unchanged.

### 6. Sharing evidence with the player

**Share, not visibility.** Evidence has no player visibility today and gets none: a player sees a staff item only through a
*share*, one per item and selection. `evidence_share` holds `share_id`, `punishment_identifier`, `appeal_id`, `evidence_id`,
`mode` (`FILE` or `EXCERPT`), `selection` (`int[]` line indexes, empty for a file), `appellant_speaker` (the speaker label
shown as "You"), `confirmed_minimised` (always true), `shared_by_oid`, `shared_at`, `revoked_at`, `revoked_by_oid`,
`revoke_reason`. A unique partial index (`appeal_id, evidence_id, selection hash` where not revoked) makes a repeat the same
share. The state is mutable; the history is the two case events:

- `evidence.shared` (`PLAYER` visibility, in `PORTAL_VISIBLE_TYPES`), dedupe `evidence.shared:<shareId>`, payload
  `{shareId, evidenceKind, mode, lineCount?}`;
- `evidence.share_revoked` (`PLAYER` visibility, in `PORTAL_VISIBLE_TYPES`), dedupe `evidence.share_revoked:<shareId>`, payload
  `{shareId, reason}` (a code: `MISTAKE`, `PRIVACY`, `OTHER`).

Share and revoke are one data-modifying CTE each (row + event), `MODERATOR`. Neither payload holds content, a file name or a
speaker. Sharing needs an appeal on the case and a non-leaked Ban ID (409 `NO_APPEAL`, `FROZEN`); an evidence item that is
not `VALID`, not of a shareable kind, or not of this punishment is 404/409.

**Shareable kinds:** `FILE` that is an image (the screenshot) and `CHAT_LOG` (the excerpt). `ANTICHEAT` (reveals detection
internals), `REPLAY` and `LINK` (external, unverifiable), `TRANSCRIPT` (it has its own player copy) and video are not shareable
in v1; the share route answers 422 `NOT_SHAREABLE`. A player-supplied item is not shared back (the player has it already).

**Shown on read, never copied.** An `EXCERPT` share stores line indexes only. The reader renders the lines from the evidence
payload when the player asks, and returns `UNAVAILABLE` (no lines) when the evidence was deleted or the share is revoked. So
there is no second copy of other players' words: a profile erasure or an admin delete of the evidence ends the share without
another job, and a revoke makes the next read an empty card.

**GDPR minimisation (recommendation: automatic pseudonymisation *and* a required confirmation).**

- Chat capture already pseudonymises other speakers per evidence item. The share goes further: the staff member chooses which
  speaker is the appealing player (`appellantSpeaker`, a required choice among the speakers of the selected lines), that
  speaker is shown as **You**, and every other speaker is relabelled **Player 2, Player 3, ...** in order of first appearance
  in the selection, whatever label the capture holds (so a legacy item with real names is covered too). Timestamps are shown as
  offsets from the first selected line, not clock times. Lines are shown as plain text, never as Markdown or links.
- At most `blackhole.chat.share.max-lines` (20) lines, chosen by the staff member (no "share all" button); each line passes
  `PiiGuard` (UUID, IP) again at share time (422 `PII_REJECTED` names the line index only).
- A line's *text* can still contain a name ("Bob, stop") that no program can find. Therefore the share request must carry
  `confirmedMinimised: true`, the dialog shows the exact preview the player will see (`POST .../evidence-shares/preview`,
  `VIEWER`, no state change) and a checkbox "I checked that this shows no other person's personal data beyond what is needed
  for this appeal"; the backend rejects a share without it (400). The confirming `oid` is the share's `shared_by_oid`.
- **Screenshots** cannot be pseudonymised. They need the same checkbox with the stronger text "this image may show other
  players' names; I accept that the player sees it", and the dialog shows the image. Blurring is out of scope.
- Revoking removes access at once (the download route and the card), but cannot take back what a player already saw or saved;
  the revoke dialog says so.

### 7. The chat UI

Shared behaviour for portal and panel (two implementations with the same tests, because the apps are separate by decision 6 of
the portal change):

- Bubbles: the viewer's own messages on one side, the others on the other; consecutive messages of one author are grouped;
  `<time datetime>` per message and a separator per day; system markers (appeal filed, decided, shares, ticket) centred; removed
  messages as a muted "message removed" bubble. Portal: own = the player, others = "Moderation team" (never a name). Panel: own
  side = the staff, other = the player; Discord, bot and external participants as today; staff names where verified.
- Assistive technology: an `ol` with `role="log"` and `aria-live="polite"` for appended items; no focus steal; reduced motion
  respected; every control keyboard operable; contrast from the MD3 tokens.
- Composer: auto-growing textarea; Enter sends and Shift+Enter inserts a newline on a hardware keyboard (a visible Send button
  always); an attach button (`<input type=file multiple accept=...>`), drag and drop onto the thread (a visible drop target),
  paste of image or file clipboard items; chips per file with name, size, state and remove; a counter `n/5`. Client prechecks
  (count, size, extension) are conveniences; the server decides and its code is shown ("This file type is not allowed", "The
  file is larger than 10 MB", "The file could not be accepted", "Uploads are unavailable right now, try again"). Drafts and
  chips live in memory only (no browser storage; the portal test forbids it).
- Attachment rendering: images as a bounded thumbnail in the bubble that opens the full image in a lightbox on the same origin;
  PDF and TXT as a file card (icon, name, size, "Download"). A share card shows the excerpt lines or the image, a "shared by the
  moderation team" label, and "No longer shared" after a revoke or when unavailable.
- **Read state (the cheap version).** `GET /public/conversation` moves `punishment_case.player_seen_seq` to the highest returned
  seq with `GREATEST` in one statement (the read already slides the session, so there is a precedent). The portal fetches only
  while the tab is visible, so "seen" means "fetched while visible". Staff get `playerSeenSeq` in the case read and in the live
  update and show "Seen" under the last staff message. No timestamp and no per-message row is stored. There is no staff read state
  for the player.
- Strings: player-facing keys are English only (`en.json`; the portal's parity test ignores the `chat.*` keys, or the whole file
  if `add-appeal-conditions` already made the portal English-only); the panel keeps German and English.

### 8. Live updates (SSE): the signal

**Event contract.** A stream sends: `retry: 5000` once; `event: ready` with `data: {"seq":N}` on connect (the case's current seq
as this reader may see it); `event: changed` with `id: N` and `data: {"seq":N}` after every change the reader can see (a new or
changed message, a share, a revoke, a decision, a status change, for staff also `playerSeenSeq`); and a comment line `: ka` as
keep-alive. **No text, name, file name or id other than seq is ever in an event.** After an event the client calls the normal
read with its cursor. `Last-Event-ID` is honoured: on reconnect the server sends `changed` when the current seq is greater. A
player's signal is raised only for events the player reader would return (same allow-list), not for staff notes; the existing
`cursor` already exposes seq gaps, so this adds nothing.

**Where it is raised.** A `CaseChangeNotifier` is called by the places that append a case event (the `CaseChanges` statements
and `CaseEventRepository.append`), after the write, in a try/catch, counted in `blackhole.live.signal.failures`. A test walks the
writers (like `CaseEventRepositorySurfaceTest`) so a new write path cannot skip it. A lost signal costs only latency, because
polling remains.

### 9. Fan-out across replicas and the event bus

A `LiveHub` per replica keeps the open streams per case and is fed by a `ChangeSignalTransport` port:
`publish(ChangeSignal{caseKey, seq, audience})` and a callback for signals from any replica. `caseKey` is the punishment
identifier (already in events); the signal carries nothing else.

Adapters: `InProcessTransport` (single replica, tests) and a bus adapter on a **transient** subscription of the event bus: one
non-durable subscription per replica that receives all `case.live` signals (RabbitMQ: an exclusive auto-delete queue on a
fanout exchange; NATS: a core subscription, which is at-most-once and cheap). Durable queues would be wrong: a signal older
than the stream is useless.

**Dependency on `switch-event-bus-to-nats`.** The domain code depends only on the port. At the start of the `feat(appeal)` PR
ship `InProcessTransport` (exact on one replica) and say in the PR that multi-replica live updates need the bus adapter.
Decided 2026-10-24: the adapter follows afterwards as its own small PR. `switch-event-bus-to-nats` (#314) is decided: the backend
uses `micronaut-nats` 5.0.0 through the BOM, and the adapter is a core NATS subscription (not JetStream) on a subject such as
`blackhole.live.case`, using whichever publisher abstraction that change introduces. Until it lands a signal raised on another
replica is picked up by the client's safety poll, at most 30 s later. No RabbitMQ-specific adapter is written.

**Resource bounds (state caps, not rate limits).** One stream per session (a new one closes the old with `event: replaced`),
`blackhole.chat.live.max-streams` per replica (default 2000; beyond it 503 and the client polls), keep-alive every 15 s, and a
stream lives at most `max-lifetime` (default PT30M) before the client reconnects. Session rules for the portal: validity is
checked without sliding when the stream opens and at every keep-alive; a leak mark, a logout or an expiry ends the stream
(`event: expired`, then close), so a stream can never outlive its session. A keep-alive does not slide the idle timer; a
delivered `changed` makes the client fetch, which does.

### 10. Live updates through the portal and the tunnel

- **Portal BFF.** `GET /api/events` is a new allow-list entry with `stream: true`: cookie session (no CSRF, it is a GET that
  changes nothing), 401 with no body when the session is invalid (a native `EventSource` treats a non-200 answer as a failure
  and does not reconnect, which is what an expired session needs). The handler opens the backend stream with the session token as
  `Authorization: Bearer`, passes the backend's event lines through unchanged, without the 15 s timeout, and aborts the
  upstream request when the browser disconnects. It sets `Content-Type: text/event-stream; charset=utf-8`, `Cache-Control:
  no-cache, no-transform`, `X-Accel-Buffering: no` and no `Content-Length`. The client uses `EventSource` and falls back to
  polling when it is closed.
- **Panel.** The panel's token is a Bearer header, which `EventSource` cannot send, so the web app reads
  `GET /case/{reference}/events` with `fetch` and a stream reader (a small parser of the SSE lines, `AbortController`), takes a
  fresh token on every reconnect and backs off exponentially (1 s to 30 s). `VIEWER` and above; `PROXY` and `DISCORD` denied.
  The panel's Nitro proxy must stream the response (spike).
- **Micronaut.** A route that returns a `Publisher<Event<String>>` with `@Produces(MediaType.TEXT_EVENT_STREAM)`, merged with a
  keep-alive ticker on the injected scheduler, never blocking the event loop. Tests drive it with virtual time (no sleeps).
- **Nitro v2.** Nitro 2.13 runs on h3 1.15. h3 documents `createEventStream(event)` (`push`, `onClosed`, `send`) for 1.15.x, but
  its guide calls the SSE support experimental. The BFF therefore proxies the backend response body (a `ReadableStream` with
  `sendStream`) and does not depend on that helper. The first BFF task is a spike that confirms: no compression of
  `text/event-stream`, a flush per event, upstream abort on client close.
- **Cloudflare Tunnel.** What the web sources say (the Cloudflare documentation search was unavailable while this was written,
  so the spike re-checks it on the real tunnel): Cloudflare passes `text/event-stream` through without caching; its proxy read
  timeout is about silence between bytes (reports say 100 s on Free to Business, one source 125 s), so a 15 s keep-alive is far
  below either; the origin must not buffer or compress the stream (the BFF sets `no-transform` and `X-Accel-Buffering: no`,
  Nitro adds no compression to dynamic routes, no buffering reverse proxy sits between the tunnel and Nitro); a WAF managed
  challenge or a "cache everything" rule on `/api/events` would break it, so the docs PR lists a bypass for that path. Long-lived
  HTTP streams are normal through `cloudflared`. Over HTTP/2 the browser multiplexes, so a second tab does not exhaust
  connections. On the team domain the stream is an ordinary authenticated request behind the existing SSO.
- **Fallback.** While a stream is open, the client polls once every 30 s as a safety net (covers lost signals and a missing bus
  adapter); with no stream (error, closed, unsupported) it polls every 15 s while the tab is visible, as today. The page shows a
  quiet "reconnecting" state only after the second failed attempt.

### 11. Discord

**Mapping (one module, both directions).** `markdown` has `DiscordRenderer` (tree to Discord text) and the bot has
`DiscordMarkdown.toSource` (Discord text to our source).

| Our node | To Discord | From Discord |
|---|---|---|
| `strong`, `em`, `del`, `code`, `code_block`, `quote`, lists | `**`, `*`, `~~`, `` ` ``, fences, `> `, `- `/`1. ` | the same marks are kept as they are |
| `link` | `text <https://...>` (angle brackets stop the embed; masked links are not relied on) and `<https://...>` for bare links | links kept as written; `<url>` becomes the bare URL |
| literal text | an `@` that starts `everyone`, `here`, a user or a role is written `@` + zero-width space; `<...>` forms are escaped; allowed mentions are empty on every post | |
| `__x__` (underline) | - | markers removed, text kept |
| `\|\|x\|\|` (spoiler) | - | markers removed, text kept |
| `# heading`, `-# small` | - | the line marker is removed, text kept |
| `<@id>`, `<@&id>`, `<#id>` | - | `@user`, `@role`, `#channel` (no Discord id enters the case) |
| `<:name:id>` custom emoji | - | `:name:` |

A rendered message over Discord's 2000 characters is split at block boundaries. The mapping is covered by the same corpus
(expected Discord text) plus a round trip test: source to Discord to source gives the same tree for the supported subset.

**Outbound files.** An outbox `ADD` item gains `attachments[]` (`attachmentId`, name, type, size); `EVIDENCE_SHARED` and
`EVIDENCE_REVOKED` are new item kinds. The bot fetches the bytes with `GET /discord/tickets/{ticketId}/attachments/{attachmentId}`
(`DISCORD` role only, the same visibility rule as the player route, scoped to the ticket's appeal) and uploads them to the
channel; PDF and TXT go up as files. A share of an image is a file post with a label; an excerpt is posted as a code block of the
server-rendered lines ("Player 2: ..."). A revoke deletes that Discord message and posts "Evidence share withdrawn" (the outbox
item is a `DELETE` of the linked message plus a system line). A redaction deletes the Discord message as today.

**Inbound files.** For a message from the bound `PLAYER` or a verified `STAFF` participant (decided at ingest as today), the
bot, for each Discord attachment: checks the declared size against 10 MiB and the count against 5 (extra files are skipped and
recorded as rejected), downloads it from an exact host allow-list (`cdn.discordapp.com`, `media.discordapp.net`) over HTTPS,
without following redirects, with a streamed cap of 10 MiB and a timeout (the SSRF hardening that principle 5 asks for; the
URL comes from the gateway and is still validated), and posts the bytes to `POST /discord/tickets/{ticketId}/attachments` with
`discordAttachmentId` (idempotency: the evidence is keyed `discord:<id>`). The backend runs the `CHAT` profile, so type, size,
scan and sanitising are identical; the answer is the `attachmentId` or a reject code. The bot then posts the `message.added`
ingest with the entries. A rejected file stays metadata-only with `rejected: <code>` (the thread shows "file not accepted") and
the bot adds a warning reaction. The CDN link is never stored. A transient failure (scanner down, backend down) holds the ingest
of that message and is retried from the cursor like any outage. Files from other participants (`EXTERNAL`, unverified staff,
bots) stay metadata-only as today. This amends "Discord attachments are not evidence": accepted files of the player and of
verified staff are evidence of provenance `PLAYER`/`STAFF`; everything else is unchanged. The ticket's protocol lists them as
marker lines.

### 12. Layering and API shape

- `markdown/` module: `MarkdownParser`, `Node` (sealed), `PlainTextRenderer`, `DiscordRenderer`, `LinkRules`.
- Backend: `casetimeline/` (`MessageContent` builds `text` + `blocks`, `CaseChanges.appendMessage` claims attachments),
  `evidence/upload/` (`UploadProfile`, `WebpSanitizer`, `PdfGuard`, `TextGuard`), `evidence/share/` (`EvidenceShareService`,
  `JdbcEvidenceShareStore`, sealed `EvidenceShareDTO`, `EvidenceShareApi`, thin controller), `publicapi/`
  (`PublicAttachmentService`, `PublicLiveController`), `live/` (`LiveHub`, `ChangeSignalTransport`, `CaseChangeNotifier`).
  Controllers hold one service call; OpenAPI annotations are on the `*Api` interfaces; services answer with `Error` variants
  (`FILE_FLAGGED`, `FILE_ACTIVE_CONTENT`, `QUOTA`, `NOT_SHAREABLE`, `PII_REJECTED`, `UNSUPPORTED_TYPE`, `TOO_LARGE`,
  `UNAVAILABLE`); the BFF maps them to a fixed code set.
- Routes and roles: staff `POST /case/{ref}/evidence-shares` and `.../{shareId}/revoke` `MODERATOR`; `GET .../evidence-shares`
  and `POST .../evidence-shares/preview` `VIEWER`; `POST /case/{ref}/attachments` (staff chat upload) `MODERATOR`;
  `GET /case/{ref}/events` `VIEWER`; `PROXY` denied everywhere. Public routes need the player session and `FULL` scope.
  `/discord/tickets/{id}/attachments` (GET, POST) `DISCORD` only. `RouteCoverageIntegrationTest` and
  `PermissionMatrixIntegrationTest` get every new route.
- Request caps: the BFF accepts up to 10 MiB + 64 KiB on the upload route (multipart, one `file` part, `Content-Length`
  required, streamed to the backend, not buffered); the JSON routes keep 16 KiB.

## Flyway migrations

Numbered at implementation time as the next free version on `main` (other open changes take numbers; none is fixed here).
Each is append-only; no applied migration is edited.

1. `V<next>__chat_attachments_and_shares.sql` (PR `feat(case)`): `evidence_item.provenance smallint not null default 0`
   (existing rows become `STAFF`, which is true: no player upload existed), the table `evidence_share` with its unique partial
   index and `ON DELETE CASCADE` on the punishment, and an index on `evidence_item (correlation_id)` for pending chat items if
   the existing one does not cover it.
2. `V<next>__player_seen.sql` (PR `feat(appeal)`): `punishment_case.player_seen_seq bigint not null default 0`.

Events need no migration (`type` is text). The new case event types are added to `CaseEventPayload` and
`PORTAL_VISIBLE_TYPES`; a payload of an unknown version still reads as `Unknown`.

## Tests (F.I.R.S.T.)

- **Markdown (`markdown` module):** the conformance corpus (supported subset, expected tree, expected Discord and plain
  output) and a **hostile corpus**: `[x](javascript:alert(1))`, `JaVaScRiPt:`, `java\tscript:`, `&#106;avascript:`, percent- and
  entity-encoded schemes, `data:text/html;base64,...`, `vbscript:`, `file:///`, `//evil.example`, `<script>`, `<img onerror>`,
  `<b>` and `<a href>` HTML, `![x](https://x/y.png)`, `[a](<https://x> "t")`, nested `[[a](b)](c)`, link text that looks like
  another URL, `https://user:pass@host`, `https://127.0.0.1`, very long URLs, unbalanced `**`, `*` and backticks, fences inside
  quotes inside lists, depth 50, 100000 characters of `[`, `*`, `_` and backticks (linear time asserted by node and step
  counters, not by a clock), control characters, RTL override, zero-width characters, `@everyone` and `<@123>` in the Discord
  renderer. Every case asserts: no `link` node with a scheme other than http/https, no raw HTML in any renderer output, no throw,
  bounded size. A property test with a fixed seed feeds random strings and checks the same invariants.
- **Portal and panel:** the tree renderer is tested with hostile *trees* (a `link` with `javascript:`, a `text` node containing
  `<script>`, an unknown node type, depth 50): none produces an element with an `href` other than http(s), a `script`, an event
  handler or `innerHTML`; `noMarkup.test.ts` still forbids `v-html`; the interstitial validator uses the URL part of the corpus.
- **Uploads:** the pipeline with a fake scanner and `InMemoryBlobStore`: each type accepted; a renamed file (PNG as `.pdf`)
  refused by sniffing; a polyglot; a WebP with trailing bytes; an animated WebP; an EXIF-carrying JPEG and WebP come out without
  EXIF/XMP; an encrypted PDF and a PDF with `/JavaScript`; TXT with NUL, bad UTF-8 or an `<svg` start; flagged 422; scanner down
  503 with nothing stored; 10 MiB + 1 byte; the sixth file; a name with a UUID or Ban ID; staged files never in a listing, a
  count or a public read; unbound items swept with the injected clock.
- **Delivery:** a headers table test (inline for the three images only; PDF and TXT `attachment`, `nosniff`, `sandbox`,
  `no-store`); another punishment's id is 404; a removed message's file is 404; a revoked share is 404; `LIMITED` scope is 403.
- **Sharing:** only `FILE` images and `CHAT_LOG` shareable; no share without `confirmedMinimised`; excerpt relabelling ("You",
  "Player 2"), offsets instead of times, the 20 line cap, UUID/IP in a line rejected; revoke; evidence deleted gives
  `UNAVAILABLE`; the player route returns nothing for an unshared item; the event payloads contain no content (a reflection test
  as `CaseConversationNoLeakTest`).
- **Live:** `LiveHub` with an injected scheduler and virtual time (ready, changed, keep-alive, replaced, expired, Last-Event-ID,
  max streams), the writer-surface test for the notifier, a two-hub test over a fake transport for the cross-replica path, the
  BFF handler with a fake `fetch` stream (pass-through, abort on client close, 401 closes without retry). No `Thread.sleep`.
- **Discord:** `DiscordRenderer` and `toSource` against the corpus and the round trip; the attachment fetcher with a fake
  gateway: host allow-list, redirect refused, size cap, count cap, rejected file recorded; the outbound fake gateway posts files
  and deletes on revoke; mention escaping with empty allowed mentions.
- **Integration (real containers, as today):** Postgres with a fake scanner and the blob store: stage, send, read, download,
  redact, erase; a route-coverage and permission-matrix run with the new routes.

## Risks / Trade-offs

- **Active content in a PDF or text file** is the main residual risk: mitigated by delivery (attachment, sandbox CSP, nosniff, a
  separate origin per app), ClamAV and the best-effort token refusal; not by parsing PDFs. Stated in the docs.
- **Stale virus signatures:** a file clean today may be flagged tomorrow. A rescan job is not in this change.
- **Audit noise** (a thumbnail view writes a `VIEWED` row): accepted; a dedupe window for chat thumbnails is a follow-up if the
  audit table grows.
- **Old messages are re-rendered as Markdown.** Rare; no migration.
- **No live preview** (decided 2026-10-24) because there is one parser. Mitigation: a formatting hint under the composer; a
  preview route later.
- **Cross-replica latency until the bus adapter exists:** up to 30 s through the safety poll; a single replica is exact.
- **A share the player already saw** cannot be recalled; the dialog says so.
- **SSE and the Cloudflare timeout** values come from third-party reports; the first BFF task is a spike on the real tunnel.
- Reversing "plain text only" widens the XSS surface of a public page; the controls are the single parser, the tree renderer
  without HTML strings, the CSP (`default-src 'self'`, no inline script) and the hostile corpus in CI.

## Migration Plan

Merge in PR order. PRs 2 and 3 are backwards compatible (new fields, optional request fields); the old portal keeps working
with `text` only and ignores `blocks`. PR 4 adds routes and SSE; the BFF of PR 5 uses them. The Discord PR is last and can be
deployed after the backend: until then ticket attachments stay metadata-only. Rollback: the new columns and table are unused
by older code. Existing evidence rows keep `STAFF` provenance.

## Decisions (settled 2026-10-24, by the owner)

1. Chat UI in portal and panel with bubbles, timestamps, composer with attach, drag and drop and paste; read state if cheap.
2. Markdown safe subset, http(s) links behind an interstitial, no HTML, no images by URL, one renderer for portal, panel and
   Discord, source stored and rendered on display, hostile corpus tests; raw or plain for the protocol: decision 1 above.
3. Player uploads PNG, JPEG, WebP, PDF, TXT, 10 MB, 5 per message, evidence pipeline, quarantine, PDFs as downloads, counted
   as player-supplied evidence, no app-level rate limits; EXIF policy: decision 3 above.
4. The team shares individual evidence, auditable and revocable, nothing visible without a share; GDPR minimisation: decision 6.
5. SSE for portal (BFF, cookie) and panel (backend, staff token), polling fallback, events carry only seq, fan-out from the
   event bus behind an abstract port (decisions 8 to 10).
6. Discord relays attachments and Markdown both ways (decision 11).
7. Player-facing strings are English only.

Settled later on 2026-10-24 (the four open questions of the proposal PR):

8. **Evidence count:** player-supplied files do not count in the evidence number on the Velocity ban screen or the proxy count
   route; only team evidence counts. The panel shows the breakdown with player-supplied files separate (decision 5).
9. **Markdown preview:** none in v1; only a formatting hint under the composer. The preview route can come later (decision 1).
10. **Trusted link hosts:** only `onelitefeather.net` and its true subdomains skip the interstitial, never a suffix match such as
    `evilonelitefeather.net`; this is tested (decision 1).
11. **NATS adapter:** the in-process transport ships in PR 4 and the NATS adapter follows in its own PR. `switch-event-bus-to-nats`
    (#314) is decided; the backend uses `micronaut-nats` 5.0.0 through the BOM (decision 9).
