# Proposal

**Ships as:** `feat(case)` (backend, PRs 1-2), `feat(web)` (PR 3), `docs(case)` (PR 4). One type per
commit, see the PR plan in `tasks.md`.

## Why

A punishment today is spread over several tables: the punishment row, its appeals, its evidence
(with `evidence_audit`), the ELO events and a few metadata keys. Nothing says in one place what
happened to a ban, in which order, and who did it. Staff cannot leave a note, assign a ban to
someone, mark it waiting for a reply or find "all ban cases I am looking at". The team wants to
work a ban as a **case**: one page per punishment with a clean timeline and meta information.

The case is also where the planned appeal portal (players sign in with their Ban ID, Discord
tickets synced with the panel, a leaked Ban ID forfeits the appeal right) will later write its
events. This change builds the case and an event model that those can extend; it does not build
the portal or the bot.

## What Changes

- **One case per punishment**, keyed by the punishment identifier. A case row holds the staff
  metadata; the timeline is an **append-only `case_event` log**.
- Going forward every lifecycle fact is **written into `case_event`** by the flow that causes it:
  punishment applied (step, ELO cost, escalation), evidence attached / lifted / revoked, appeal
  filed / decided, revoked / lifted / expired. Existing punishments are **backfilled** by a
  migration; backfilled events are marked and unverified actors are shown as unverified.
- **Case metadata**: status (`OPEN`, `IN_REVIEW`, `WAITING`, `CLOSED`), assignee (a staff `oid`),
  tags, priority and **internal notes** (staff only). Every change is itself a timeline event
  with the old and new value; notes are events too, an edit is a new event and never an in-place
  update.
- **Linked cases**: the same player's other cases are listed on the case.
- **API** (`/case/...`): read a case with a paged, filterable timeline; add and edit notes;
  change status, assignee, tags and priority; list cases; ADMIN redacts a note for GDPR erasure.
  Permissions follow the matrix (VIEWER reads, MODERATOR writes, ADMIN erases).
- **Domain events** `case.*` for case-own changes (notes, status, assignee, tags, priority). They
  carry ids and no note text, so the later Discord sync can consume them.
- **Admin UI**: a case page per punishment (header, vertical timeline with filters and
  verified/unverified actors, notes editor, evidence inline, links to the player's other cases),
  German and English, keyboard and screen-reader accessible, MD3.
- Client spec `blackhole-api-0.0.N.yml` (next free number when implemented; `0.0.17` and `0.0.18`
  are claimed by `integrate-cloudnet-sections`).

Not breaking: all routes and fields are additive. Existing endpoints and the Velocity plugin are
unchanged.

## Out of scope

- The appeal **portal** (Ban ID sign-in), the **Discord bot** and ticket sync, the leak rule
  (forfeit, freeze after filing) and any player-facing view. The event model only **reserves**
  the types `message.added` and `ban_id.leak_marked` and a `visibility` column that is always
  `STAFF` in this change.
- Per-case access control (every moderator sees every case), SLA timers, notifications, e-mail.
- Cases that span several punishments, or cases without a punishment (reports keep their own flow).
- Changing how punishments, appeals or evidence themselves behave; they only gain a recording step.
- Retention sweeps for cases: a case lives exactly as long as its punishment.

## Capabilities

### New Capabilities

- `punishment-case`: the case, its append-only timeline, metadata, notes, linked cases, API,
  events and erasure.

### Modified Capabilities

- `punishment-management`: applying, revoking and expiring record timeline events.
- `appeal-workflow`: filing and deciding an appeal record timeline events.
- `punishment-evidence`: attach, lift and revoke record timeline events.
- `verified-actor`: timeline actors carry a trust level; anonymization covers them.
- `staff-authentication`: permission matrix rows for `/case`.
- `admin-web-ui`: the case page.

## Impact

- Backend: new `case/` package (entities, repositories, `CaseTimeline` recorder, service,
  controller + `CaseApi`, reconciler, events); small calls added in `punishment/`, `appeal/`,
  `evidence/` (through a narrow interface, like `PunishmentEvidenceGateway`).
- Flyway: one migration (`case_event`, `punishment_case`, backfill). Next free version when
  implemented (V11 is claimed by `integrate-cloudnet-sections`).
- OpenAPI client spec bump in `client/specs` and `client/build.gradle.kts`.
- Web: route, view, components, API client, en/de strings.
- Depends on the verified actor (done); no dependency on the portal. Later changes (portal,
  Discord) depend on this one.
