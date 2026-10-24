# Tasks

Small stacked PRs, squash-merged, one Conventional Commit type each. Every group starts with failing tests (red, green,
refactor) that follow F.I.R.S.T.: no sleeps (virtual time and injected `Clock`), no real network, fresh fixtures per test.
Migration versions and the client spec number are the next free ones on `main` right before they are added.

## 1. Proposal (PR 1, `docs(openspec)`)

- [x] 1.1 Proposal, design, specs and tasks; `openspec validate --all --strict`

## 2. Markdown (PR 2, `feat(case)`)

- [x] 2.1 Failing tests first: the conformance corpus, the hostile corpus (every case: no non-http(s) link, no raw HTML, no throw,
  bounded output, step-counted linear time), the seeded random-input test, `DiscordRenderer` and `PlainTextRenderer` expectations,
  the Discord round trip, `LinkRules` table, trusted-domain matching (`onelitefeather.net` and `appeal.onelitefeather.net` trusted; `evilonelitefeather.net`, `onelitefeather.net.evil.example`, `onelitefeather.net@evil.example` not), conversation entry carries `text` and `blocks`, `MessageRules` apply to link targets
- [x] 2.2 New Gradle module `markdown` (no dependencies): `MarkdownParser`, sealed `Node`, `LinkRules`, renderers; add to
  `settings.gradle.kts`, `backend` and `discord`
- [x] 2.3 Backend: `MessageContent` builds `blocks`; `blackhole.chat.links.trusted-domains`; public and staff conversation DTOs gain
  `blocks`; `ProtocolRenderer`/`TranscriptRenderer` keep the source, JSON `schemaVersion` 2 with `format: "markdown"` (adapt
  `ProtocolModel` tests and the digest test)
- [x] 2.4 Update `.claude/rules` (case-timeline, public-api), regenerate the client spec and bump `inputSpec`; verify
  `./gradlew :markdown:test :backend:test :client:compileJava`

## 3. Attachments and sharing (PR 3, `feat(case)`)

- [x] 3.1 Failing tests first: pipeline profiles (every type, sniffing, polyglot, EXIF/XMP removal for JPEG and WebP, WebP trailing
  bytes and animation, PDF active content and `/Encrypt`, TXT UTF-8 and `<svg`, flagged 422, scanner down 503, size, name
  sanitising), staged items invisible, claim in `appendMessage` (idempotency, foreign id, six files, text-less message), quota,
  provenance migration (existing rows `STAFF`), count breakdown (team count and the proxy count route exclude player files; `playerSupplied` separate), redaction and player erase delete files, share rules (kinds,
  confirmation, relabelling, offsets, 20 lines, UUID/IP line, repeat, revoke twice, evidence deleted gives `UNAVAILABLE`), event
  payloads carry no content, route roles in `RouteCoverageIntegrationTest` and `PermissionMatrixIntegrationTest`, protocol markers
- [x] 3.2 Migration `V<next>__chat_attachments_and_shares.sql`: `evidence_item.provenance`, table `evidence_share` with the unique
  partial index and cascade
- [x] 3.3 `evidence/upload`: `UploadProfile`, `WebpSanitizer`, `PdfGuard`, `TextGuard`, name rule; shared `SafeFileResponse`;
  `blackhole.chat.attachments.*` in `application.yml` with comments
- [x] 3.4 `evidence/share/`: store, `EvidenceShareService`, sealed `EvidenceShareDTO`, `EvidenceShareApi`, thin controller; routes
  `POST|GET /case/{ref}/evidence-shares`, `.../preview`, `.../{shareId}/revoke`, `POST /case/{ref}/attachments`;
  `evidence.shared` and `evidence.share_revoked` in `CaseEventPayload` and `PORTAL_VISIBLE_TYPES`; `attachmentIds` on the staff
  message route; `provenance` and `playerSupplied` in the evidence DTOs and counts
- [x] 3.5 Update `.claude/rules` (evidence, case-timeline, events, security), regenerate the client spec; verify
  `./gradlew :backend:test :client:compileJava`

## 4. Public routes, live updates (PR 4, `feat(appeal)`)

- [ ] 4.1 Failing tests first: public upload, delete, download (other punishment 404, removed message 404, revoked share 404,
  `LIMITED` 403, `NO_APPEAL` 409, headers table), `attachmentIds` on `POST /public/messages`, `player_seen_seq` (monotonic),
  `LiveHub` on virtual time (ready, changed, keep-alive, replaced, expired, `Last-Event-ID`, max streams, lifetime), notifier writer
  surface test, two-hub test over a fake transport, no content in any event, route coverage
- [ ] 4.2 Public attachment service and routes in `PublicApi`; `PlayerSessionService` checks for streams; migration
  `V<next>__player_seen.sql`
- [ ] 4.3 `live/`: `LiveHub`, `ChangeSignalTransport`, `InProcessTransport`, `CaseChangeNotifier` hooked into the case writers;
  `GET /public/conversation/events` and `GET /case/{reference}/events`
- [ ] 4.4 Ship the in-process transport only and note in the PR that multi-replica updates wait for the NATS adapter (task 4.6);
  `blackhole.chat.live.*` config
- [ ] 4.5 Update `.claude/rules` (public-api amended "own punishment only", events, security), regenerate the client spec; verify
  `./gradlew :backend:test :client:compileJava`

- [ ] 4.6 Follow-up PR (`feat(appeal)`), after `switch-event-bus-to-nats` (#314) has merged: failing two-replica test with a real
  NATS container, then `NatsChangeSignalTransport` on a core subscription with `micronaut-nats` 5.0.0 from the BOM; update
  `.claude/rules/events.md`

## 5. Portal (PR 5, `feat(portal)`)

- [ ] 5.1 Spike first: SSE through the real tunnel and Nitro v2 (no compression, flush per event, abort on client close, 100 s idle
  not reached with a 15 s keep-alive); record the result in the PR; adjust the design if it differs
- [ ] 5.2 Failing tests first: BFF allow-list (multipart upload with size cap and CSRF, binary download streaming and headers,
  SSE pass-through and 401), error-code mapping, tree renderer with hostile trees, `safeHttpUrl` with the corpus, interstitial,
  bubbles and grouping, formatting hint under the composer (no preview), composer (attach, drop, paste, chip states, counter, Send disabled), image lightbox, PDF/TXT cards, share
  cards, polling fallback timings with fake timers, `noMarkup.test.ts` still green, no browser storage
- [ ] 5.3 Contract and BFF: routes, streaming helpers, CSP `img-src 'self' blob:`, English strings; chat components; `/leave`
- [ ] 5.4 Verify `npm test` and the type check in `portal/`

## 6. Panel (PR 6, `feat(web)`)

- [ ] 6.1 Failing tests first: API client methods, fetch-based SSE client (parsing, backoff, token per connection, abort), tree
  renderer with hostile trees, interstitial, bubbles, composer and staff-upload warning, provenance label, share dialog
  (selection, appellant speaker, preview, confirmation, 20 line cap, role gating), revoke dialog, "Seen" marker, de/en parity
- [ ] 6.2 Types, client methods, `ConversationSection` as a chat, `MessageComposer`, `EvidenceShareDialog`, evidence list labels,
  live updates; verify the panel's Nitro proxy streams the events response
- [ ] 6.3 Verify `npm test` and the type check in `web/`

## 7. Discord (PR 7, `feat(discord)`)

- [ ] 7.1 Failing tests first: `DiscordMarkdown.toSource` and round trip against the corpus, mention escaping and empty allowed
  mentions, message splitting, attachment fetcher (host allow-list, no redirect, size and count caps, rejected file recorded),
  outbound file post, share post and revoke delete, hold-and-retry on a 503, backend ticket attachment routes (roles, visibility,
  idempotency per Discord attachment id, chat profile applied)
- [ ] 7.2 Backend: `GET|POST /discord/tickets/{id}/attachments`, outbox items for attachments and shares, ingest of `attachmentIds`
  and `rejected`; amend `evidence.md`; regenerate the client spec
- [ ] 7.3 Bot: `DiscordMarkdown`, attachment fetcher and uploader, relay of files and shares, warning reaction; verify
  `./gradlew :discord:test :backend:test`

## 8. Documentation (PR 8, `docs`)

- [ ] 8.1 Operations note: the edge rules for the portal host (rate limits for `POST /api/attachments`, `POST /api/messages`,
  concurrent `GET /api/events`; a body cap of 10 MiB on the upload path; no cache, no challenge, no compression on `/api/events`
  and on the panel's events path; keep-alive 15 s vs the proxy idle timeout), the file types and limits, ClamAV expectations,
  PDF metadata retention, privacy text for players about files and chat excerpts, the Markdown subset for staff
- [ ] 8.2 Update `docs/` appeal portal page and `README` pointers; note the dependency on `switch-event-bus-to-nats`

## 9. Wrap-up

- [ ] 9.1 Archive with `docs(openspec): archive add-appeal-chat` after the PRs merge
- [ ] 9.2 Open the pull requests as small stacked PRs under these titles: `docs(openspec): propose the appeal chat`,
  `feat(case): add markdown rendering to case messages`, `feat(case): attach files to messages and share evidence`,
  `feat(appeal): add public chat files and live updates`, `feat(portal): turn the appeal conversation into a chat`,
  `feat(web): add the chat view and evidence sharing to the case page`, `feat(discord): relay chat files and markdown`, after the bus change `feat(appeal): relay live signals over nats`,
  `docs(appeal): document the appeal chat operations`
