# Design

## Context

- **Case page on `main`** (`web/src/views/CaseView.vue`): `CaseHeader`, `BanIdPanel`, `ConversationSection`,
  `ProtocolSection`, `TranscriptExport` (admin), notes, `RelatedCases`, `CaseTimeline`, in that order. Evidence is
  `EvidenceInline`, shared per item through `EvidenceShareDialog`. No allegation, no decision block, no bundle split.
- **Evidence** (`punishment-evidence`, `evidence/`): kinds `ANTICHEAT`, `REPLAY`, `LINK`, `FILE`, `CHAT_LOG`,
  `TRANSCRIPT`; provenance `STAFF` or `PLAYER` (`evidence_items.provenance`, `V<n>` of add-appeal-chat). Share rules and
  the `Player N` excerpt renderer are in `evidence/share/` (`EvidenceShareService`, `ExcerptRenderer`), with a 20-line cap
  and `confirmedMinimised`.
- **Leak today** (`casetimeline/JdbcCaseWriter.MARK_LEAKED`): one CTE sets `punishment_case.ban_id_leaked_at|reason`,
  deletes the punishment's `player_session` rows and appends `ban_id.leak_marked`. The case status is not touched and no
  appeal row changes. The portal derives `FROZEN` or `FORFEITED` from the leak state on read (`add-appeal-portal`,
  decision 8). `AppealStatus` has no forfeited value (`SUBMITTED`, `ELIGIBLE_PENDING_REVIEW`, `IN_REVIEW`,
  `GRANTED_FULL_LIFT`, `GRANTED_DURATION_REDUCTION`, `DENIED`, `INELIGIBLE`).
- **Reopen rule** (`JdbcCaseWriter`): a filed appeal reopens a `CLOSED` case to `OPEN`. A leak close must not be undone
  by that path.
- **Staff identity** (`redesign-ux` section 4): display name from `staff_profile` (`staffprofile/`), required before any
  write that reaches a player (`409 STAFF_PROFILE_REQUIRED`), never the Entra name, `oid` or Discord id on a player surface.
- **Audit** (`redesign-ux/audit.md`): case page has no `h2`, heading levels skip, back navigation is an inline link;
  empty states are bare paragraphs. The new layout uses `PageShell`, `FilterToolbar`, `DataTable` and the state components
  of `redesign-ux`.

## Goals / Non-Goals

- Goals: one clear case model with a fixed section order; both sides' evidence attributed and labelled; a picker that makes
  the shareability of each data type explicit before staff act; counter-evidence that is threaded per item; one predefined
  closing comment for a leak; canned texts that are managed, translatable and auditable.
- Non-goals: anything listed as out of scope in `proposal.md`.

---

## Decision 1. Case model and page layout

The case is still one `punishment_case` row with its `case_event` log. This change adds no case table. It adds three
things: an allegation event, a case file (presentations to the player) and a read model for evidence items. The page
renders eight sections in a fixed order. Desktop uses the `PageShell` aside for the summary column; mobile stacks.

| # | Section | Content | Who sees it | Source |
|---|---|---|---|---|
| 1 | Summary | Punishment kind, template and step, section, status, assignee, priority, tags, Ban ID state (`ACTIVE` or `LEAKED`, generation number; never the value), linked cases. Team notes in the aside. | VIEWER reads, MODERATOR edits | `punishment_case`, `CaseHeader`, `BanIdPanel`, `RelatedCases`, notes |
| 2 | Allegation | The team's internal statement of what the player is accused of, with the rule (template or section) it refers to, and its version history. | VIEWER reads, MODERATOR writes | `case_event` `allegation.set` (new) |
| 3 | Evidence | Two bundles, team and player (decision 2). Each item: brought by, provenance, verification, status, shareability badge. Opens the picker (decision 4). | VIEWER reads; player items never visible to the player except as presented | `evidence_items`, player message attachments, case-file responses |
| 4 | Conversation | Messages, unchanged (`ConversationSection`), plus the reply canned texts in the composer. | as today | `case_event` `message.*` |
| 5 | Case file | The presentations of this appeal, one card per item, each with its thread (decision 6). | VIEWER reads, MODERATOR presents and reviews | `case_file`, `case_file_item` (new) |
| 6 | Decision | Appeal state, decision reason (canned code and text), decision note (internal), player message (checked by `MessageRules`). Decide and review actions stay in the appeal dialog. | VIEWER reads | `appeals`, `case_event` `appeal.decided` |
| 7 | Timeline | Unchanged `CaseTimeline` with filters. | VIEWER | `case_event` |
| 8 | Protocol | Stored appeal protocol and staff export (`ProtocolSection`, `TranscriptExport`). | VIEWER, ADMIN for export | `appeal_protocol` |

Rules:

- The section order is the order on the page; a section with no data still renders its heading and an empty state with
  the next step (for example "No evidence yet. Add evidence or present a case file once an appeal is open.").
- Headings: the `PageShell` `h1` is the case title; sections are `h2`; items inside a section are `h3`. The duplicate title
  of `ReportDetailView` pattern is not repeated.
- Back navigation uses `Breadcrumbs` (Moderation › Cases › `CASE-REF`) as in `redesign-ux`.
- The page never renders the Ban ID, a Minecraft UUID, an IP, a Discord id or an `oid`.

### Ban ID state and status lock

- A leaked Ban ID puts the case in `CLOSED` with the closing reason `BAN_ID_LEAKED` (decision 8). While that reason is set,
  `PATCH` of the status answers `409 CASE_LEAKED`; assignee, tags and priority stay editable. Only a reissue changes the
  status again.

---

## Decision 2. Who brought an item, provenance and verification

Every item in the evidence section is a read model built by `casetimeline/CaseItemReader` from existing rows. Nothing new
is stored.

**Bundle** (the section it belongs to):

- `TEAM`: evidence items whose `provenance` is `STAFF`, staff chat captures and staff uploads.
- `PLAYER`: evidence items whose `provenance` is `PLAYER`, player message attachments, and player responses to case file
  items (decision 6).
- A proxy-attached record (anticheat, replay, client-rule hits, evasion hints) is `TEAM` only when a moderator attached it;
  otherwise it is `SYSTEM` and listed in the team bundle.

**Provenance** (`TEAM`, `PLAYER`, `SYSTEM`) answers "where did it come from". It is derived: `PLAYER` when the item is a
player upload or a player response, `SYSTEM` when a process wrote it (proxy, template, reconciler, a scan), `TEAM` when a
staff member did.

**Verification** (`VERIFIED`, `UNVERIFIED`, `PLAYER`, `SYSTEM`) answers "can we say who acted". It reuses `CaseActor.ofRow`:

| Verification | Meaning | Label in the panel |
|---|---|---|
| `VERIFIED` | Staff actor with a verified `oid` (Entra) | Verified staff |
| `UNVERIFIED` | Legacy row, Discord-only staff, or an unknown actor | Unverified staff |
| `PLAYER` | Player-supplied; unverified by nature | Player-supplied, unverified |
| `SYSTEM` | Automatic (ELO, template step, reconciler, scanner, proxy) | System |

**Brought by** (the display label):

- A `VERIFIED` team item: the staff display name from `staff_profile` (live, as in `redesign-ux` 4.6). When the name is
  empty (legacy row), "Unnamed staff member". An anonymised profile gives "Staff member (name removed)".
- The viewing staff member's own item: "You" in the panel. The player never sees "You" for a staff item.
- `UNVERIFIED` team item: "Unverified staff".
- `PLAYER` item in the panel: "Player". To the player, their own items are "You".
- `SYSTEM` item: "System".
- Player-facing labels: a staff item shows the staff display name, or "Moderation team" when none is set. Never the
  `oid`, never a trust level.

The read model is a sealed `CaseItemDTO.Response`; `CaseItemDTO.Error` for expected failures.

---

## Decision 3. Shareability per data type

This table is the rule set of the picker. A type is **shareable** only when the table says so. Shareable excerpts and
files reuse `evidence/share/` (live share, one row per appeal, item and selection). Summaries are **snapshotted** at
presentation time from a whitelist of fields (below), because they are not evidence rows and must not change under the
player. Excerpts and files stay live, as today: revoking a share withdraws it, and a deleted source reads `UNAVAILABLE`.

| Data type | Source | Shareable | What the player sees | Why not more |
|---|---|---|---|---|
| Chat excerpt (`CHAT_LOG`, incl. proxy captures and appeal conversation lines) | `evidence_items`, `case_event` `message.*` | **Yes** | The selected lines, at most 20, others as "Player N", the appellant as "You", times as offsets; preview and `confirmedMinimised` required | Existing rule; minimisation is enforced by `ExcerptRenderer` |
| Image file (`FILE`, PNG, JPEG, WebP) | `evidence_items` | **Yes** | The image, inline | Existing rule |
| Video, PDF, text, other team file | `evidence_items` | **No** | Nothing | `422 NOT_SHAREABLE` (existing); listed locked in the picker |
| Player's own upload | `evidence_items` `PLAYER` | **Not presented** | Their file in their bundle only | The player already has it |
| Anticheat record (`ANTICHEAT`) | proxy and anticheat sources | **No, never raw** | Nothing; the allegation may describe the behaviour in plain words | Reveals the detection method and its thresholds |
| Replay (`REPLAY`) | `evidence_items` | **No** | Nothing | Contains other players' actions and detection data |
| Link (`LINK`) | `evidence_items` | **No in v1** | Nothing | External content can carry sources and tracking; a later change may allow trusted domains |
| Previous punishments | `punishment` history of the owner | **Yes, summary** | Kind, applied date, outcome (`ACTIVE`, `LIFTED`, `EXPIRED`, `REVOKED`) | No staff names, no notes, no template internals |
| Reports against the player | `report` of the reported owner | **Yes, summary** | Category, date, outcome (`OPEN`, `RESOLVED`, `DISMISSED`) | Never the reporter, the reporter hash, the description or the resolution note: those are third parties' words |
| Client-rule hits | `clientrule` hits | **No in v1** | Nothing | Rule keys and brand or channel names reveal how the client is detected. A later change may add an admin-set public label per rule |
| Evasion hints | `evasion` sightings | **Never** | Nothing, not even a count, a date or another owner's hash | Reveals detection and that another account exists. The picker lists it as "Team only", without content |
| Kick record (per player, `KICK`) | `punishment` kind `KICK` | **Yes, summary** | Applied date and scope (network, server or section); the canned reason text when one was used | Free-text kick reasons are staff words and are not shown |
| Mass kick (per server or section) | kick audit | **No** | Nothing | Not a per-player record; it names no punished player |
| Team notes, allegation, decision note | `case_event` note and allegation | **Never** | Nothing (the decision message is separate and checked) | Internal |
| Other players' chat lines outside a selected excerpt | `message.*` | **Never** | Only as "Player N" inside a shared excerpt | Minimisation |

Enforcement:

- `casefile/` has a `ShareableKind` enum whose values are exactly the shareable rows above. A presentation request with any
  other kind answers `422 NOT_SHAREABLE`, and a unit test lists every value of the enum to make the table and the code
  agree.
- The summary snapshot is a whitelist record per kind (for example `PreviousPunishmentSummary(kind, appliedAt, outcome)`).
  No code path copies a whole source row.
- The picker's candidate list carries `shareability` and `lockReason` as data, so the UI cannot decide them.

---

## Decision 4. The picker: flow

Entry point: the Evidence section header has "Present a case file" for `MODERATOR`. It is disabled with its reason when
the case has no open appeal (`NO_APPEAL`), when the case is closed (`CASE_CLOSED`) or when the staff profile has no
display name (`STAFF_PROFILE_REQUIRED`, with a link to "My profile").

1. **Open.** A side panel on desktop (the `PageShell` aside slot, 320 px min, 560 px for the picker), a full-screen dialog
   below 1024 px. The panel is a route-less state `?picker=open` so a reload keeps it; focus goes to the first heading.
2. **List.** Two columns. Left: candidates grouped by data type, each group a `FilterToolbar` chip (Chat, Images, Previous
   punishments, Reports, Kicks, Client-rule hits, Evasion hints, Other files). Each row: data-type icon, neutral label
   ("Chat, 12 Jun 14:02, 9 lines", "Image, 3 Jun", "Punishment, ban, 2 Feb, expired"), date, a shareability badge:
   `Shareable` (success), `Summary` (secondary), `Locked` (outlined, with the lock reason as text, for example "Not shareable:
   reveals detection"). Locked rows are shown so staff know the evidence exists, but have no checkbox. Evasion hints read
   "Evasion hint, team only" and have no count, no date and no preview. Search is server side (`q`, cursor pages of 50).
   Empty and error states use the `redesign-ux` components.
3. **Select.** A checkbox adds a shareable candidate to the draft on the right. For a chat candidate, "Choose lines" opens the
   line selector: each line with a checkbox, a live `n / 20` counter, a disabled state past 20 lines with the message "At
   most 20 lines per excerpt", and the rendered label for each speaker ("You", "Player 2"). Selections are line indexes,
   sorted.
4. **Write.** Per draft item an optional short text the player reads above it (`REPLY` canned texts may be inserted, or free
   text up to 500 characters, `PiiGuard` applied on save). The case file has one opener: an `ALLEGATION` canned text or free
   text up to 1000 characters (required).
5. **Preview as player.** "Preview as player" calls `POST /case/{ref}/case-file/preview`. The server renders the same blocks
   the player will see (excerpt renderer, summary whitelist) and the panel shows them in a player-styled frame. This is the
   only place a moderator sees the minimised result. The preview writes nothing.
6. **Confirm and present.** A checkbox "I checked the preview: other people are minimised and nothing internal is included"
   is required when the draft contains an excerpt (`confirmedMinimised`). "Present to player" sends `POST /case/{ref}/case-files`
   with an `Idempotency-Key`. Success: the card appears in the Case file section and `case_file.presented` plus one
   `evidence.shared` per file or excerpt are appended (existing events, no content).
7. **Afterwards.** Each presented item shows its status: `PRESENTED`, `ANSWERED` (a player response exists), `REVIEWED` (a
   moderator marked it) or `WITHDRAWN`. "Withdraw" uses the existing share revoke for files and excerpts and sets
   `withdrawn_at` for summaries. A withdrawn item stays visible to staff and is marked for the player.

Nothing in the picker is written before step 6. Closing the panel discards the draft (the draft is kept in memory, never
in browser storage).

---

## Decision 5. The case file

Data model (migration in Flyway section):

- `case_file`: `id uuid`, `punishment_identifier uuid`, `appeal_id` (the open appeal at presentation), `allegation_code`
  (nullable, canned `ALLEGATION` code), `allegation_text varchar(1000)` (snapshot), `presented_by_oid uuid`,
  `presented_at timestamptz`, `withdrawn_at timestamptz null`.
- `case_file_item`: `id uuid`, `case_file_id uuid` (cascade), `position smallint`, `data_kind smallint` (the `ShareableKind`
  ordinal), `source_key varchar(96)` (opaque, for example `evidence:<uuid>`, `punishment:<uuid>`; never an owner hash),
  `selection jsonb` (line indexes for excerpts), `share_id uuid null` (the `evidence_share` row for files and excerpts),
  `summary jsonb null` (whitelisted snapshot), `staff_text varchar(500) null`, `canned_code varchar(32) null`,
  `withdrawn_at`, `reviewed_by_oid uuid null`, `reviewed_at timestamptz null`, `dedupe_key varchar(160) unique`
  (`presented:<appealId>:<dataKind>:<sourceKey>:<selectionHash>`, so a repeated request inserts nothing).

Rules:

- A case file is per appeal. A later appeal on the same punishment gets its own file; the team re-presents explicitly.
- Items are immutable after presentation except for `withdrawn_at`, `reviewed_*`. Staff text is never edited in place; a
  correction is a new item.
- The player's view is rendered on read by `CaseFileRenderer` from the stored selection and the live share or the
  snapshot. A deleted file or excerpt reads `UNAVAILABLE` (existing semantics).
- No Ban ID, no owner hash, no Discord id and no file name appear in any case file field or event. Events carry ids and
  counts only.

## Decision 6. Counter-evidence: linking, threads, staff view

**Player response.** `POST /public/case-files/{id}/items/{itemId}/responses` (session `FULL`, `Idempotency-Key`) with
`stance` (`DISPUTE`, `ACCEPT`, `NO_COMMENT`), an optional `text` (1 to `blackhole.public.message.max-length`, the same
rules as a player message) and `attachmentIds` (at most five, the existing staged chat files of this appeal). The response is
a `message.added` event with author `PLAYER` and visibility `PLAYER`, plus two optional payload fields: `caseFileItemId` and
`stance`. Attachments bind through the existing `appendMessage` claim (one CTE, all or nothing), so the quota and the
`evidence.attached` records are unchanged.

**The link is the message payload, not a new table.** A response is a conversation message; the thread is a query over
`case_event` where `type = 'message.added'` and `payload->>'caseFileItemId' = ?`. A partial expression index serves it
(Flyway section). The player's uploads count in the player bundle with provenance `PLAYER` and carry the same item id.

**Staff reply in the thread.** A moderator replies with the existing message route plus `caseFileItemId`; the reply is a
`STAFF` author message with visibility `PLAYER`, so the player sees it under the item.

**Staff view: pairs.** The case file section lists items as cards. Each card:

- Left: the team side (the presented item preview, the presenter's display name, "Presented 26 Oct 10:14", the staff text).
- Right: the player side, in order: each response with its stance, text and attachments, then staff replies.
- A card reads as "team evidence ↔ player response". With no response the right side says "No response yet" and the
  card is not marked `ANSWERED`.

**Limits.** One stance per response; many responses per item allowed (the player may add more files later). Responses are
never edited by the player; redaction stays the only change, as for every player message. A withdrawn item takes no
new responses (`409 ITEM_WITHDRAWN`). A leaked case takes no responses (`409 CASE_CLOSED`).

---

## Decision 7. Canned reasons

**Kinds** (ordinal `smallint`, append-only): `CLOSING` 0, `DECISION` 1, `REPLY` 2, `ALLEGATION` 3.

- `CLOSING`: why a case is closed (the leak close is the first; the team may add more later).
- `DECISION`: the reason written with an appeal decision (the decision message is composed from it).
- `REPLY`: a canned chat reply, inserted into the composer. It is sent only when a person sends it, and the message is a
  normal staff message with `cannedReasonCode` in the payload (additive).
- `ALLEGATION`: the opener of a case file (decision 5).

**Table** `canned_reason`: `id uuid`, `kind smallint`, `code varchar(64)` (`^[A-Z][A-Z0-9_]{2,63}$`, immutable after
creation), `body varchar(4000)` (the source text in English, Markdown like a message), `system boolean` (true for codes the
code depends on), `active boolean`, `sort_order int`, `updated_at timestamptz`, `updated_by_oid uuid null`, unique
`(kind, code)`.

**Rules:**

- The body passes `MessageRules` and `PiiGuard` on save (no UUID, IP, Ban ID or link target outside http(s); `422` otherwise).
  No placeholders or variables in v1: a canned text is the same for every player.
- Only `ADMIN` writes. A system reason cannot be deactivated (`409 SYSTEM_REASON`). There is no hard delete: a reason is
  deactivated, which hides it from pickers while every use stays readable.
- Every use stores the text it had at use time (message body, decision message, closing message). A later edit changes new
  uses only, so the record of a case never changes.
- Moderators read `GET /canned-reasons?kind=` (active only). Administrators may add `includeInactive=true` on the admin
  page. The list is small and bounded (at most 200), so it is not paginated.

**Translation.** `add-i18n-platform` owns translation storage. This change defines one port, `CannedReasonText.resolve(reason,
locale)`, which asks the i18n platform for a translation keyed by (`canned_reason`, `id`, locale) and falls back to `body`.
Until that change lands, the port returns `body` for every locale, and the English text is the only text. The port does not
store anything. Translations are edited on the Canned reasons page with the i18n platform's editor, not in this change.

**Admin UI.** A page `/admin/canned-reasons` in the Administration workspace, Configuration group, between Templates and Sections
(`redesign-ux`, two-workspace decision). Tabs by kind are filters (`FilterToolbar`). Each row: code (mono), kind, active state,
updated at. Editing opens the `MdDialog` with the body, a live Markdown preview and the character count. The system marker
explains why deactivation is blocked.

---

## Decision 8. Leaked Ban ID closes the case

**Current state.** A leak marks the Ban ID, deletes sessions and appends `ban_id.leak_marked`. The case stays as it was and
an open appeal stays open (the portal shows it as `FROZEN`). This change replaces that for the appeal and the case.

**The close is one statement.** `JdbcCaseWriter.MARK_LEAKED` becomes one data-modifying CTE (the rule for case-own changes):

1. `UPDATE punishment_case SET ban_id_leaked_at, ban_id_leak_reason, status = 'CLOSED', closing_reason_code =
   'BAN_ID_LEAKED', updated_at WHERE punishment_identifier = ? AND ban_id_leaked_at IS NULL` (returns the row when it
   changed; a repeat mark changes nothing and writes nothing).
2. If (1) changed a row and the case has an appeal not yet decided (`SUBMITTED`, `ELIGIBLE_PENDING_REVIEW`, `IN_REVIEW`),
   `UPDATE appeals SET status = 'FORFEITED_BAN_ID_LEAKED'` (new terminal status, appended ordinal). A forfeited appeal has
   no merits decision, no repeat-appeal cooldown and does not change the punishment.
3. `DELETE FROM player_session` for the punishment (unchanged).
4. Appends `ban_id.leak_marked` (unchanged), `case.status` (from the previous status to `CLOSED`, reason
   `BAN_ID_LEAKED`, no text), and the closing comment `message.added` with author `SYSTEM`, visibility `PLAYER`, the
   closing text as it is at close (decision 7), and the locale used. Dedupe keys: `leak-close:<punishmentId>:<generation>`.

The guard in (1) makes the whole statement idempotent. The closing comment text is `CannedReasonText.resolve` of the
`BAN_ID_LEAKED` closing reason, in English until the i18n platform provides another locale.

**Predefined closing text (proposed, English):**

> This appeal is closed. The Ban ID of this punishment has been shared, so it can no longer be used to appeal. Keep your
> Ban ID private; it is the only key to your appeal.

It names no Ban ID and no staff member; it passes `MessageRules`.

**Effects, by session:**

- Before any appeal exists: the right is forfeited (as decided). `POST /public/appeal` answers `409 FORFEITED` as today.
- After filing: the appeal becomes `FORFEITED_BAN_ID_LEAKED`, which the portal shows as `FORFEITED` (not `FROZEN`).
  This replaces the `FROZEN` path of `add-appeal-portal`, decision 8. See open question 1.
- The `LIMITED` session (a leaked Ban ID still logs in): `GET /public/punishment` gets a `closing` field `{code, text, closedAt}`
  from the stored closing comment. No staff data, no conversation, no decision text. The `LIMITED` rule for conversation and
  appeal reads (403) is unchanged.
- The `FULL` session is deleted by the leak, so the closing comment reaches a `FULL` player only through the protocol
  (`ProtocolModel` lists `message.added` of `SYSTEM`) and the admin transcript.

**Reopen and reissue.**

- A leaked case cannot be reopened by a filed appeal (the appeal is refused as `FORFEITED` before the reopen rule runs), and
  a status change answers `409 CASE_LEAKED`.
- `ADMIN` reissue (existing `reissueBanId` and `reissueBanIdAtGeneration` CTEs) clears the leak and, when
  `closing_reason_code = 'BAN_ID_LEAKED'`, sets `status = 'OPEN'`, clears the closing reason and appends `case.status`.
  The forfeited appeal stays forfeited. Whether a new appeal right comes back is open question 3.

**Discord.** `LeakCheckService` calls the same `CaseChanges.markBanIdLeaked`, so a leak from the bot closes the case in the
same way. The open ticket moves to `CLOSING` (conditional PR, `feat(discord)`), and the outbox relays the closing comment.

---

## Decision 9. Layering, routes and contracts

Packages (new): `casefile/` and `cannedreason/`, each with `controller`, `service`, `dto` and a JDBC store. `casetimeline/`
gains `CaseItemReader` and the allegation write. `appeal/` gains the status. `publicapi/` gains the closing field and the
case file routes. `evidence/share/` is called through `EvidenceShareService` and is not changed.

Rules followed (micronaut-controller-layer, micronaut-dto-contract, micronaut-service-layer, micronaut-openapi-contract):

- Controllers implement a `*Api` interface, delegate to one service call and map the result to `HttpResponse`.
- Each resource has one sealed marker interface (`CaseFileDTO`, `CannedReasonDTO`, `CaseItemDTO`, `AllegationDTO`) with
  `CreateRequest`, `UpdateRequest`, `Response` and `Error`. The service answers expected failures with `Error`.
- Controllers do not inject repositories. Services own the store.

Staff routes (`/case/{reference}` unless noted; `reference` is the punishment's public reference, never a Ban ID):

| Method and path | Role | Purpose |
|---|---|---|
| `GET /case/{ref}/items?bundle=TEAM\|PLAYER&cursor&limit` | VIEWER | Evidence bundle read model (decision 2) |
| `GET /case/{ref}/allegation` | VIEWER | Current allegation with version |
| `POST /case/{ref}/allegation` | MODERATOR (`Idempotency-Key`) | New allegation version as `allegation.set` |
| `GET /case/{ref}/case-file/candidates?kind=&q=&cursor` | MODERATOR | Picker list with `shareability` and `lockReason` |
| `POST /case/{ref}/case-file/preview` | MODERATOR | Player rendering of a draft; writes nothing |
| `POST /case/{ref}/case-files` | MODERATOR (`Idempotency-Key`) | Present a case file |
| `GET /case/{ref}/case-files?cursor` | VIEWER | List of files for the case |
| `GET /case/{ref}/case-files/{id}` | VIEWER | One file with its threads |
| `POST /case/{ref}/case-files/{id}/items/{itemId}/withdraw` | MODERATOR (`Idempotency-Key`) | Withdraw one item |
| `POST /case/{ref}/case-files/{id}/items/{itemId}/review` | MODERATOR | Mark reviewed |
| `POST /case/{ref}/messages` (existing) | as today | Adds optional `caseFileItemId` and `cannedReasonCode` |
| `GET /canned-reasons?kind=&includeInactive=` | MODERATOR (inactive: ADMIN) | Picker list |
| `POST /canned-reasons` | ADMIN (`Idempotency-Key`) | Create |
| `PATCH /canned-reasons/{id}` | ADMIN | Edit body or active flag, with `version` |

Public routes (session `FULL` unless noted):

| Method and path | Purpose |
|---|---|
| `GET /public/case-files?cursor` | Presented items, rendered for the player |
| `POST /public/case-files/{id}/items/{itemId}/responses` (`Idempotency-Key`) | Counter-evidence |
| `GET /public/punishment` (existing, `LIMITED` and `FULL`) | Adds `closing` for a leak close (additive) |

Contracts:

- Cursor pages with the `standardize-api-pagination-and-problems` cursor (opaque, signed); the page sizes are 50 default, 200 max.
- Errors follow that change's Problem Details shape. Where the existing public routes still answer `PublicErrorBody`, the new
  routes use Problem Details once the standardization is on `main`; until then they use the same `code` values.
- Every `POST` that writes accepts `Idempotency-Key` (1 to 80 of `[A-Za-z0-9._:-]`). A repeat with the same key answers the
  first result with `repeated: true`.
- Errors used: `NO_APPEAL` 409, `CASE_CLOSED` 409, `CASE_LEAKED` 409, `STAFF_PROFILE_REQUIRED` 409, `NOT_CONFIRMED` 400,
  `SELECTION_TOO_LONG` 400, `NOT_SHAREABLE` 422, `PII_REJECTED` 422, `ITEM_WITHDRAWN` 409, `SYSTEM_REASON` 409, `CODE_TAKEN`
  409, `VERSION_CONFLICT` 409, `NOT_FOUND` 404 (uniform for unknown and foreign ids).
- The generated client (`client/specs/blackhole-api-*.yml`) is regenerated in every API PR, with the spec number taken at
  implementation time.
- Domain events: `case.allegation_set`, `case.file_presented`, `case.file_item_withdrawn`, `case.closed` (ids, enums and
  counts only). None carries text, a name, a Ban ID or an owner hash. `case.status` already exists in some form and gains the
  reason code.

---

## Decision 10. Privacy and the Ban ID

- No raw UUID or IP: owner hashes only. Staff reads of owners use the existing hashed owner.
- The Ban ID never appears in a URL, a log line, an event, a metric label or a response other than `GET /punishment/{ref}/ban-id`
  and `POST /punishment/lookup`. The closing text, the canned texts, the allegation and the staff texts are all checked by
  `PiiGuard` and `MessageRules` on write, and `BanIdNoLeakTest` gets the new event types added.
- The player never sees a staff `oid`, a Discord id, a trust level or a staff note.
- Excerpts follow the existing minimisation. Summaries use whitelisted fields only (decision 3).
- Evasion hints: the picker, the API and the events carry at most "a hint exists" in staff views and nothing in player views.
  A unit test asserts that `CaseFileRenderer` has no branch for `EVASION_HINT`.

---

## Decision 11. Discord

Only one transition changes. When a leak closes a case whose appeal ticket is open, the ticket moves to `CLOSING`, and the
bot closes the channel after the outbox relay has posted the closing comment (`ticket` state machine, `add-appeal-chat`
decision 11). The bot needs no other change. Presentations and responses are not relayed to Discord in v1; the Discord
channel shows them only through the portal. This keeps `feat(discord)` to one PR and no new relay label.

---

## Decision 12. Tests (F.I.R.S.T.)

Every group starts with failing tests (red, green, refactor). No sleeps, injected `Clock`, fixed `TestKeys`, in-memory
stores for unit tests, one Postgres integration test per SQL change, fresh fixtures per test, no shared static state.

- Unit: `ShareableKind` covers every row of decision 3; `CaseFileRenderer` renders only shareable kinds; summary whitelists;
  `CaseItemReader` provenance and verification table; `CannedReasonService` rules (code format, system reason, deactivate,
  PiiGuard, MessageRules, `CODE_TAKEN`); `CannedReasonText` fallback; picker selection rules (20-line cap, sorted indexes).
- Integration (Postgres): the leak CTE (idempotent repeat, appeal forfeited, sessions deleted, closing message and events
  present, no Ban ID in any row or event); reissue reopens only a leak-closed case; a leaked case refuses the filing with
  `FORFEITED` before the reopen rule; case-file dedupe; thread query uses the index; `LIMITED` answer carries `closing`
  and nothing else.
- Route coverage: `RouteCoverageIntegrationTest` and `PermissionMatrixIntegrationTest` get every new route.
- Web: Vitest component tests for `CaseLayout` (section order, heading levels), `CaseItemCard` (brought-by labels for all
  four verification states, "You"), `EvidencePicker` (locked rows have no checkbox, evasion hint has no count, 20-line cap,
  preview required before present, draft discarded on close), `CaseFileThread` (pairs), `CannedReasonsPage` (system reason
  cannot be deactivated).
- Portal: Vitest for the case file card, the stance control, the response composer (attachment limit five, text optional
  with files) and the `LIMITED` closing notice.
- Contrast and axe checks for the new states: the existing `web/tests/contrast.test.ts` covers the new tokens.

## Flyway migrations

Two migrations, numbers taken from the next free versions on `main` at implementation time (main is at V26 today; the kick
change takes V27 when it lands):

1. `V<n>__canned_reasons.sql`: creates `canned_reason` with `unique (kind, code)`; seeds `CLOSING / BAN_ID_LEAKED` (system,
   active, body as in decision 8); adds `punishment_case.closing_reason_code varchar(32) null`. Existing cases get `NULL`.
   No backfill. `AppealStatus` gets a new ordinal (smallint); if a check constraint on `appeals.status` exists, the same
   migration widens it, otherwise the range is enforced by the application (database rule).
2. `V<n+1>__case_file.sql`: creates `case_file` and `case_file_item` (both with `punishment_identifier` and `appeal_id`
   indexes, `dedupe_key` unique, cascade from file to item), and a partial expression index on `case_event`
   `((payload->>'caseFileItemId'))` where `type = 'message.added'`. Existing rows: no files, no responses; nothing to migrate.

Provenance and verification are not stored, so no backfill exists for them. Existing evidence rows keep `provenance` as it is.

## Migration plan

1. Land `docs(openspec)` (this proposal) and archive the in-flight changes it depends on (or sync their specs) first.
2. PR order is in `tasks.md`. Each API PR regenerates the client spec and merges with the Velocity plugin untouched.
3. The leak close changes an existing behaviour. It ships behind no flag, because the rule was already decided. The panel's
   leak dialog states the close and the forfeit before the moderator confirms (PR 7, `feat(web)`), and the release notes name
   the change.
4. Rollback: the migrations are additive; code can be reverted without schema rollback. A reverted leak close leaves
   `CLOSED` cases in place, which staff can reopen manually.

## Risks / Trade-offs

- **A leak now ends an appeal the team could have read.** This follows the decided rule that a leaked Ban ID forfeits the
  right, and it removes the frozen-but-open state. Mitigation: open question 1 asks for confirmation before PR 3 merges.
- **Minimisation can fail in a preview-only path.** Mitigation: the picker preview calls the same renderer as the player
  path; the confirmation is required server side.
- **Staff see locked rows** (anticheat, evasion). That is intended: staff must know the evidence exists. Player-facing
  output has no branch for them.
- **Canned text is written by admins and sent to players.** Mitigation: `PiiGuard`, `MessageRules`, the system marker and
  the audit fields.
- **Translations are not ready.** The English fallback ships; the translation port is a seam.
- **Summaries are snapshots**, so a corrected report outcome does not reach the player until a new presentation. This is
  deliberate (open question 4 covers a refresh action).

## Open questions

Each question has a recommended default that the tasks assume. The team answers them before PR 3 and PR 4.

1. **Leak after filing.** Default: close the case and forfeit the open appeal (`FORFEITED_BAN_ID_LEAKED`) with the closing
   text, as the brief asks. This replaces the `FROZEN` path of `add-appeal-portal`, decision 8, which said the team keeps
   deciding. Alternative: keep `FROZEN` and only close the case; the team then decides without player messages.
2. **Translation dependency missing.** `add-i18n-platform` is not on `main` or any branch on 2026-10-26. Default: this change
   ships English-only canned texts behind the `CannedReasonText` port; the port returns the English body until the i18n
   platform lands, and the closing comment stays English.
3. **Right after a reissue.** Default: a reissue reopens a leak-closed case to `OPEN`, and a forfeited appeal stays
   forfeited, so a new appeal right does not come back for that punishment. Alternative: a reissue restores an appeal right
   that was never filed.
4. **Summary refresh.** Default: presented summaries are snapshots and are not refreshed. Alternative: a "refresh" action
   that creates a new item with the current snapshot.
5. **Presentation before an appeal, and a Discord relay.** Default: no presentations without an open appeal (`NO_APPEAL`),
   and no relay of presentations or responses to Discord in v1. Alternative: allow a presentation on an open case with a
   "pre-appeal" flag, and relay responses to the ticket channel.
