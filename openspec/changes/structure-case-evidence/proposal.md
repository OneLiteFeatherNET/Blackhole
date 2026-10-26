# Proposal

**Ships as:** `feat(case)` (canned reasons, leak closes the case, case model and case file API, counter-evidence
API), `feat(web)` (case layout, evidence picker, canned reasons page), `feat(portal)` (case file and counter-evidence),
`feat(discord)` (only the ticket state on a leak close), and `docs(case)` (rules). This proposal itself ships as
`docs(openspec)`. One type per commit; the PR plan is in `tasks.md`.

**Builds on, not yet archived:** `add-appeal-chat` (evidence sharing, `Player N` excerpts, staged chat files),
`add-appeal-portal` (Ban ID session, `LIMITED` scope, leak rule, conversation), `add-punishment-case-timeline`
(case and `case_event`), `redesign-ux` (PageShell, FilterToolbar, DataTable, two workspaces, staff identity),
`standardize-api-pagination-and-problems` (cursor pages, Problem Details, Idempotency-Key). The code for all of
them is on `main`; their change folders still exist. This change is implemented only after they are archived or
their specs are synced, so that the deltas here apply to real capability specs.

**Depends on, not defined here:** `add-i18n-platform` (translation storage). That change does not exist on `main`
or on any remote branch as of 2026-10-26 (see open question 2). This change only names it as the translation
store for canned reason text.

## Why

A case today is a list of panels (header, Ban ID, conversation, protocol, transcript, notes, related cases,
timeline) with no stated order and no place for the thing a moderator actually does: state the allegation, put the
evidence on the table, let the player answer each piece, and decide. The audit (`redesign-ux/audit.md`, sections 1,
2 "Page headers" and "Empty, loading and error states") found the case page has no `h2`, a heading level skipped
under the app `h1`, and inline text links for its back navigation. Evidence is one inline list. The player sees only
the conversation and the shares that a moderator made one at a time; nothing lets the player answer a specific
piece of evidence, and nothing says who brought which item.

Closing a case has the same gap. A leaked Ban ID already forfeits the right to appeal (decision of `add-appeal-portal`),
but the case itself stays open and the player receives no explanation. Reasons for closing, deciding and replying are
typed fresh every time, so their wording drifts and cannot be translated.

This change gives the case a clear model and layout, lets both sides bring evidence, adds an evidence picker that
presents existing data to the player as a case file with per-item counter-evidence, closes a leaked case with a
predefined comment, and introduces canned reasons that admins maintain.

## What Changes

- **Case model and page** (`case-structure`): one case page with eight sections in this order: Summary, Allegation,
  Evidence (team bundle and player bundle), Conversation, Case file, Decision, Timeline, Protocol. Team notes move to
  the summary column. Every evidence item shows **who brought it** (staff display name, "You", "Player", "System"),
  its **provenance** (`TEAM`, `PLAYER`, `SYSTEM`) and its **verification** (`VERIFIED`, `UNVERIFIED`, `PLAYER`,
  `SYSTEM`). Provenance and verification are derived on read from existing rows; no column is added for them.
- **Allegation**: a staff-written internal text per case, stored as an append-only `allegation.set` event with a
  version number. It is never shown to the player directly; the player sees the presented allegation of a case file.
- **Evidence picker and case file** (`case-file`): staff select data already attached to or derivable from the case
  (chat captures with line selection, screenshots and files, previous punishments, reports against the player,
  client-rule hits, evasion hints, kick records) and **present** it to the player as a case file for the open appeal.
  The picker shows per data type whether it is shareable at all (table in `design.md`, decision 3). Shareable
  excerpts and files reuse the existing share rules, minimisation, 20-line cap and preview with confirmation.
- **Counter-evidence** (`case-file`): the player answers each presented item with a stance (`DISPUTE`, `ACCEPT`,
  `NO_COMMENT`), a text and up to five files, all linked to that item. Staff see a threaded pair per item:
  team presentation on one side, player responses and staff replies on the other.
- **Canned reasons** (`canned-reasons`): predefined texts of four kinds, `CLOSING`, `DECISION`, `REPLY` (chat
  reply, inserted into the composer and sent by the person) and `ALLEGATION` (the opener of a case file). Admins
  maintain them in the Administration workspace; moderators read active ones in every picker. Texts are translatable
  through `add-i18n-platform`, with the stored English text as the fallback.
- **Leaked Ban ID closes the case** (`appeal-workflow`, `case-structure`): marking a Ban ID leaked sets the case to
  `CLOSED` with the closing reason `BAN_ID_LEAKED` in the same statement that marks it, forfeits an open appeal as
  the new terminal status `FORFEITED_BAN_ID_LEAKED`, and appends one player-visible closing comment with the
  predefined text. The player's `LIMITED` session shows the same comment. The closing comment never contains the
  Ban ID.
- **Discord** (`feat(discord)`, conditional): a ticket that is open when a leak closes the case moves to `CLOSING`,
  so the bot posts the closing comment and closes the channel. No other relay changes.

Not breaking. All routes and fields are additive. The generated client gains operations and fields; the Velocity
plugin is unchanged. A client that ignores unknown fields keeps working. The new `closing` field on the `LIMITED`
public punishment answer is additive.

## Out of scope

- Presenting evidence before an appeal exists (`NO_APPEAL` stays a refusal), and a player-initiated case file.
- New evidence kinds, a share of video, PDF, text or replay files, and any share of an anticheat, client-rule or
  evasion record in raw form (see the shareability table in `design.md`).
- Retention or expiry of case files. A case file lives as long as its punishment, like evidence.
- Multi-language canned reasons beyond what `add-i18n-platform` provides; no placeholders or variables in canned
  texts in this change.
- Reversing the leak rule itself (the Ban ID cannot be "unleaked"; only a reissue restores a Ban ID).
- Per-moderator canned reasons (all canned reasons are network-wide).
- Changing the ELO engine, templates, the punishment flow, the Velocity plugin or the Discord ticket state machine
  beyond the one leak transition.
- The portal layout and the design system; both come from `redesign-ux` and `add-appeal-portal`.

## Capabilities

### New Capabilities

- `case-structure`: the section model of a case, the item view (provenance, verification, brought-by), the allegation
  event and the leak close of the case.
- `case-file`: the evidence picker, presentation of a case file to a player, shareability per data type, and the
  counter-evidence threads.
- `canned-reasons`: the four kinds of predefined texts, their management, their use in pickers, and the translation
  dependency.

### Modified Capabilities

- `punishment-evidence`: adds the shareability rule per data type, the brought-by and verification read model, and
  the rule that the evidence count still counts team items only.
- `appeal-workflow`: adds the terminal status `FORFEITED_BAN_ID_LEAKED`, the leak close of the case, and the
  guarantee that a leaked case cannot be reopened except by a reissue.
- `admin-web-ui`: adds the case page layout reference, the evidence picker entry point and the Canned reasons page in
  the Administration workspace.

## Impact

- Backend: new packages `cannedreason/` and `casefile/`; changes in `casetimeline/` (`JdbcCaseWriter` leak statement,
  allegation event, closing reason), `appeal/` (`AppealStatus`), `publicapi/` (`closing` field, case file routes),
  `evidence/share/` (called, not changed), `discord/ticket` (conditional).
- Database: two Flyway migrations, numbers taken at implementation (see `design.md`, Flyway migrations). Append-only
  ordinal for `AppealStatus` (smallint).
- Client spec: bumped once per API PR, numbers taken at implementation.
- Web and portal: new components as listed in `tasks.md`, reusing `redesign-ux` components.
- Ops: no new configuration except `blackhole.casefile.*` limits, documented in `application.yml` when added.
