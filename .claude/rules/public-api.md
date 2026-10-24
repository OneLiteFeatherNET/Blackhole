---
paths:
  - "backend/src/main/java/**/publicapi/**/*.java"
  - "backend/src/main/java/**/security/PlayerSession*.java"
  - "backend/src/main/resources/db/**/V16__player_session.sql"
---

# Public API and player sessions (`/public`)

Detail: `openspec/changes/add-appeal-portal/` (design decisions 4 to 9, specs `appeal-portal`, `ban-id`,
`case-conversation`). Only the portal's backend-for-frontend calls it.

- **Session.** `POST /public/session {banId}` is the only anonymous route. A good Ban ID creates a `player_session`
  row (V16) and answers `{token, csrf, expiresAt, scope}`; the token (`bhps_` + 256 bits) is shown once, only its
  SHA-256 is stored, never the Ban ID. Every other route is `@Secured(PlayerSessionRule.ROLE)`; the role is granted only
  by `PlayerSessionTokenValidator` (it looks at tokens with the `bhps_` prefix, so staff JWTs never touch the table).
  Staff and proxy tokens are refused there, a session token is refused everywhere else (`RouteCoverageIntegrationTest`,
  `PublicAccessIntegrationTest`).
- **Expiry** is on the injected `Clock`: idle 30 minutes (sliding, the validating statement is an `UPDATE ... RETURNING`
  that also moves `last_seen_at`) and absolute 4 hours (`blackhole.public.session.*`); `PlayerSessionSweeper` deletes the rest.
  `scope` is `LIMITED` for a leaked Ban ID, read live from `punishment_case` on every request, so a mark takes effect at once
  even for a row the mark's delete missed. The leak mark and the reissue delete the punishment's sessions in their own CTE
  (`JdbcCaseWriter`).
- **Own punishment only.** Routes take the punishment from `PlayerSession`; there is no path, query or body identifier.
- **Uniform login failure.** Unknown, malformed, legacy hex, empty or unreadable body: one 401 `INVALID_BAN_ID`, one body
  (`PlayerSessionService.INVALID_BAN_ID`). `blackhole.public.login{result=success|limited|failure}` is the only metric and
  has no other tag. The Ban ID appears in no URL, log, event, meter or response (`PublicBanIdNoLeakIntegrationTest`).
- **Visibility is filtered in SQL** (`JdbcPublicConversationReader`): `visibility = 'PLAYER'` and `CaseEventPayload.PORTAL_VISIBLE_TYPES`,
  plus the fixed list `PORTAL_ANY_VISIBILITY_TYPES` (`appeal.filed`, `appeal.decided`): those two events stay `STAFF` visibility
  but the player sees their own filing and decision as bare markers (`APPEAL_FILED`, `APPEAL_DECIDED`) without reading the payload.
  `ban_id.leak_marked` becomes `FROZEN` (after filing) or `FORFEITED`; `ban_id.reissued` and every other type are not entries.
  An edit folds into the current text of all rows of that message id; deleted and redacted messages are `removed` without text.
  Author is `PLAYER`, `STAFF` (never a name) or `SYSTEM`.
- **Markdown.** A conversation entry carries `text` (the stored Markdown source, unchanged) and `blocks` (the sanitised tree
  `MessageContent` parses from it on every read with the `markdown` module; null for markers and removed messages). Nothing is
  stored derived, so a parser change changes no row. The tree has no HTML or image node and a `link` always has an http(s) `href`
  plus `external` (false only for `blackhole.chat.links.trusted-domains` and true subdomains); clients draw it with text nodes and
  elements. A player message is Markdown too: `MessageRules` (length, control characters, UUID/IP, Ban ID) look at the whole source,
  link targets and code included.
- **Filing** never records an ineligible attempt: `PublicAppealService` reads `AppealEligibilityService.availability` first and
  answers 409 `NOT_ELIGIBLE` (+ `availableFrom`), `FORFEITED` or `FROZEN`; `fileIfEligible` re-evaluates and saves. One open appeal
  is held by the unique partial index; a losing concurrent request is a 409, never a 500.
- **Messages** go through `CaseConversationService.postPlayerMessage` (open appeal required: 409 `NO_APPEAL`; leaked: 409 `FROZEN`).
  Text is trimmed, 1 to `blackhole.public.message.max-length` (400), no control character but newline (400), `PiiGuard` (422).
- **Leaked (`LIMITED`)**: facts only; filing 409 `FORFEITED`, posting 409 `FROZEN`/`NO_APPEAL`, conversation and appeal reads 403.
- **No rate limiting here** (the edge owns it); no `X-Forwarded-For` is read or stored; request bodies are never logged
  (`logback.xml` pins the HTTP loggers). Controllers are thin (`PublicController` implements `PublicApi`), DTOs sealed per resource,
  every answer `no-store`, errors are `PublicErrorBody {code, availableFrom?}`.
- **Player copy of the protocol** (`GET /public/appeal/protocol?format=text|json`, `PublicProtocolService`): rendered on demand from
  `PublicConversationReader` (the same SQL visibility filter and allow-list as the conversation), so a `STAFF` row cannot reach it. The player is
  "You", staff "Moderation team" (never a name), markers read "Appeal filed" / "Appeal decided". Message texts are the Markdown source as written;
  the JSON is `schemaVersion` 2 and a message item has `format: "markdown"`. `FULL` scope only (403 for `LIMITED`), and only
  once an appeal is decided (409 `NOT_DECIDED`, also when none exists); unknown format 400. The answer is `no-store`, `nosniff`, `CSP: sandbox` and an
  attachment. The stored staff protocol (`TRANSCRIPT` evidence, `casetimeline/protocol/`) is never read here (`PublicProtocolIntegrationTest`).
- **Appeal conditions** (`GET /public/appeal/conditions`, `appealconditions/`, V19): the current `{version, text}` for any session scope, `no-store`; no staff data.
  `POST /public/appeal` needs `conditionsVersion` equal to the current version, else 409 `CONDITIONS_CHANGED` (missing counts as changed). The check runs
  after the statement checks and the `FORFEITED`/`FROZEN`/`NOT_ELIGIBLE` refusals, so those answers win. The accepted number is stored only as
  `meta_data.conditionsVersion` of the appeal (`AppealEligibilityService.META_CONDITIONS_VERSION`), nothing else. `POST /discord/tickets` does not check it yet
  (the bot does not show the conditions; follow-up).
