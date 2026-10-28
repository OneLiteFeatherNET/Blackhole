## Context

The code this design builds on (checked on `main`, 2026-10-28):

- `add-kick` is implemented on `main` (PRs #382 to #387). It adds `PunishmentKind {PUNISHMENT, KICK}`
  (`backend/.../punishment/PunishmentKind.java`), `StepKind {PUNISH, KICK}` (`StepKind.java`), the
  `kick/` package and a `punishment.kicked` case event. Its change folder is not archived yet.
- `PunishmentApplicationService.plan` counts earlier punishments of the escalation group with
  `countByOwnerAndEscalationGroupAndLiftedFalseAndCreatedAtGreaterThanEquals`, which does not look at the
  kind. A kick is therefore already an earlier step. A warning inherits this.
- The active-state reads filter `kind = 'PUNISHMENT'` (add-kick task 1.6): `JdbcActivePunishmentReader`,
  `ActivePunishmentExpirer`, `PunishmentExpirySweeper`, `hasActivePermanent`. A warning is excluded from
  them by the same filter; no new filter is needed, but a test per reader is.
- `PunishmentSyncHandler` routes `punishment.created` and `punishment.updated` to the mirror. A warning
  never publishes those two (decision 4), so the mirror never sees it.
- `AppealDecisionService.applyDecision` rejects a decision for a punishment that is not the owner's active
  slot (`PUNISHMENT_NOT_ACTIVE`, HTTP 409, spec `appeal-workflow` "Inactive punishment conflict"). A warning
  has no slot, so warning appeals need their own branch (decision 8).
- `AppealDecisionService.restoreElo` refunds `eloCost` of a lifted staff temporary punishment through
  `EloReasonCode.APPEAL_REFUND`. A warning appeal reuses that path.
- The Velocity plugin reads kick text over HTTP and acknowledges over HTTP (add-kick decision 5). The
  proxy is subscribe-only on NATS, so it cannot publish an acknowledgement.
- Flyway: `V24__player_seen.sql` is the head on `main` as far as this change needs; `V25` (CSRF hash) and
  `V26` (staff profile) are taken. `add-kick` takes the next free version. This change takes the next free
  version after that, at implementation time. No number is fixed here.
- `openspec/config.yaml` principles: principle 4 (every automatic or manual punishment stays contestable),
  principle 6 (migrations append-only).

## Goals / Non-Goals

**Goals**: a warning a player actually sees and confirms; a warning ladder in one template; a warning that
does not lock the player out; warnings contestable like any manual punishment.

**Non-Goals**: blocking play until acknowledged; editing a warning; localized warning text; warnings in the
public ban list; warnings from systems other than staff templates.

## Decisions

### 1. Storage: `WARN` is a punishment kind

`PunishmentKind` gains `WARN`. The `punishments.kind` CHECK gains `WARN` (decision 10). A warning is a row
with `kind = 'WARN'`, `permanent = false`, `lifted = false` and no expiry, like a kick. Its identifier is a
Ban ID, because a warning is quoted in appeals like any punishment; the Ban ID is issued through the existing
`BanIdService`. The reason, actor and case are those of any punishment. `acknowledged_at` (nullable,
epoch milliseconds) is set once by the acknowledgement. No second table.

"Never active" is enforced the same way as for kicks, each covered by a test:

- warnings are never written to `PunishmentProfileEntity.active` (the warn path does not call
  `placeAndRecord`, like the kick path);
- every active-state read keeps filtering `kind = 'PUNISHMENT'`, so a warning is never active;
- `applyForRule`, `revoke`, unban and the appeal full lift do not accept a warning as an active punishment
  (409), except the warning appeal path of decision 8.

### 2. Template step kind `WARN`

`StepKind` gains `WARN`. A `WARN` step has no duration (like `KICK`); `TemplateValidator` refuses a duration
on a `WARN` step with 400. The template gains an optional `warnWindow` (ISO-8601 duration, null by default).
Compatibility: existing steps and templates are untouched; `warnWindow` null means "no separate window".

### 3. Escalation with the warning window

The escalation count of a template group becomes:

- every earlier punishment of the group that is not lifted and falls within the template's escalation window
  (today's rule, kicks and warnings included), **except** warnings;
- plus earlier warnings of the group that fall within `warnWindow`, when the template sets one, otherwise
  within the escalation window.

Warnings older than `warnWindow` stop climbing the ladder, but they stay in the history. The rule is per
template: staff who want warnings to count for a year set `warnWindow` to `P365D` on that template only.
`StepSelector.select` is unchanged: it still takes the count.

The count is one query per kind, summed in `PunishmentApplicationService.plan`. The repository method
`countByOwnerAndEscalationGroupAndLiftedFalseAndCreatedAtGreaterThanEquals` keeps its meaning for the
non-warning kinds; a new `countWarningsByOwnerAndEscalationGroupAndLiftedFalseAndCreatedAtGreaterThanEquals`
covers warnings.

### 4. Delivery: NATS event, acknowledgement over HTTP

Subject `blackhole.events.punishment.warned` (decision D1 of `switch-event-bus-to-nats`), published after the
write in a try/catch like the other domain events. Payload: `{eventId, warningId, owner, occurredAt}`. No
reason text, no name, no raw UUID, no IP (the same rule as `punishment.kick`). The proxy reads the text with
`GET /warn/{id}`.

Subject `blackhole.events.punishment.acknowledged` is **not** published: the proxy is subscribe-only, and the
acknowledgement is an HTTP write (decision 6), so the case event is the record.

A failed publish does not fail the write. The warning still stands, and a proxy that never heard about it
shows it on the next login (decision 5). This is the difference from a kick: a kick that nobody heard about
is recorded as `NOT_ONLINE`; a warning that nobody heard about stays pending. Nothing is lost.

### 5. Login: pending warnings

On login the proxy calls `GET /warn/pending?owner=<hash>` (role PROXY, cursor-paginated, `limit` default 10,
the standard envelope of `standardize-api-pagination-and-problems`). It returns the owner's warnings with
`acknowledged_at` null, oldest first, with their reasons. The proxy shows each one once per login as a chat
block with a clickable Acknowledge line. The read is `GET`, so nothing changes on login.

If the call fails, login proceeds and nothing is shown; the warning stays pending for the next login. A
login is never refused because of a warning.

### 6. Acknowledgement

`POST /warn/{id}/acknowledge` (role PROXY only; body `{owner}`). The backend checks that the warning belongs to
the owner (a mismatch is 404, not 403, so the id does not reveal an owner). A repeat is idempotent: the
existing `acknowledged_at` is kept and the answer is 204 again. The first acknowledgement records
`punishment.acknowledged` on the case (dedupe key `acknowledged:<id>`). The acknowledgement is written by the
proxy because the proxy is the one that knows the player is in game and has clicked; the proxy's machine token
is the only credential, as for every write from the proxy. Staff cannot acknowledge for a player.

The Acknowledge line is a chat click event that runs `/blackhole warning ack <id>`, which the proxy handles
for the player who clicked (the sender must be the owner of the warning; the proxy checks the owner hash before
it calls the backend). No button, no web page.

### 7. Routes

| Method and route | Who | Purpose |
|---|---|---|
| `POST /punishment/apply` (existing) | MODERATOR and up | `/warn` and the panel's "Warn" use this route with a template. The step kind decides. A `WARN` step creates a warning, a `PUNISH` or `KICK` step creates what it creates today. |
| `GET /warn/{id}` | VIEWER and up, PROXY | Reason, actor, createdAt, owner. The proxy reads the text. |
| `GET /warn/pending` | PROXY | Pending warnings of an owner (`owner` query, hashed). Cursor page. |
| `POST /warn/{id}/acknowledge` | PROXY | Body `{owner}`. 204, idempotent. |

No `POST /warn` route. A warning is an apply of a template whose step is `WARN`, so there is one write path
and one escalation rule. The brief asked for `/warn <player> <template>`; that is the command name, and it
applies the template. See the open questions.

Errors: 404 `NOT_FOUND` (unknown warning, or an owner mismatch on acknowledge), 400 `VALIDATION`, 409 for a
disabled section (as apply today). A `WARN` step on a disabled section is refused like any apply.

### 8. Appeal of a warning

`appeal-workflow` gains a warning branch, not a change of the punishment appeal rules:

- eligibility: a warning is appealable; the minimum wait is `blackhole.appeal.min-days-manual` (14 by default),
  since a warning is never automatic; the repeat-appeal cooldown applies as for any punishment;
- the checklist severity is `STANDARD` (a warning is not a permanent network ban);
- a `DENIED` decision changes nothing;
- a `GRANTED_FULL_LIFT` sets `lifted = true`, moves nothing (there is no slot), refunds the recorded `eloCost`
  through `EloReasonCode.APPEAL_REFUND` (the existing `restoreElo` path for a temporary punishment), and records
  the lift on the case (`punishment.lifted`) and the evidence as `LIFTED`;
- a `GRANTED_DURATION_REDUCTION` is refused with 400: a warning has no duration.

Because a warning lifted by appeal no longer counts, a lift changes escalation for the player's later
offences. That is intended.

### 9. ELO

A `WARN` step applies its `eloCost` through the template ELO path, with the same dedupe and history event as a
ban step. The default `eloCost` for a warning step is 0; the template editor shows the cost field for every
step. A warning without a template does not exist: warnings always come from a template.

### 10. Migration (BREAKING for the generated client)

`V<next>__warnings.sql`, the next free version at implementation (after `add-kick`'s migration):

- drop the CHECK constraint on `punishments.kind` that `add-kick` created, and add it again with
  `('PUNISHMENT','KICK','WARN')`. The constraint name is read from the `add-kick` migration at implementation;
  Flyway migrations are not edited;
- `ALTER TABLE punishments ADD COLUMN acknowledged_at BIGINT NULL`;
- index `punishments (owner, kind, acknowledged_at)` for the pending read;
- the template step table: drop and re-add the CHECK on `kind` with `('PUNISH','KICK','WARN')`;
- `ALTER TABLE <template table> ADD COLUMN warn_window VARCHAR(32) NULL`.

Existing rows are not rewritten. Old values stay valid.

The client spec adds `WARN` to both enums in the next free `client/specs/blackhole-api-*.yml` version. The
kind enum is the BREAKING part: a Velocity build without `WARN` cannot read a player's history once a warning
exists, and the Velocity PR depends on the new client. Mitigation as in `add-kick` decision 9: PR order and the
Velocity release note, no compatibility switch.

### 11. Case timeline

Two case events, both on the warning's case:

- `punishment.warned` (payload `Warned(templateKey, eloCost)`), recorded after the warning is written, dedupe key
  `warned:<id>`. The reason is on the warning itself, as for any punishment.
- `punishment.acknowledged`, recorded at the first acknowledgement, dedupe key `acknowledged:<id>`, with no
  payload beyond the time. The verified actor is the proxy's `onBehalfOf` player owner; no name.

A recording failure never fails the write or the acknowledgement (as for `punishment.retemplated`).

### 12. Tests (F.I.R.S.T.)

- Backend unit (no DB, injected `Clock`): `StepSelector` with a warning counted; the warning-window count rule
  (warnings inside `warnWindow` count, warnings outside it do not, non-warnings still use the escalation window);
  `TemplateValidator` (`WARN` without duration valid, with duration refused); acknowledgement state (`pending`
  until set, unchanged on repeat).
- Backend integration (Postgres via the existing test support, fixed `Clock`): a warning is not in `active`, is
  not on the mirror, does not block login; a warning counts in escalation; a warning outside `warnWindow` does not;
  a repeat acknowledgement writes one case event; an acknowledgement for another owner is 404;
  `WarningEventsNoLeakTest` scans the published payloads for UUIDs, IPs and reason text (as `KickEventsNoLeakTest`).
- Appeal: a warning full lift refunds the cost and lifts without a slot; a duration reduction is 400.
- Velocity unit (no server): the login read shows each pending warning once; a failed read shows nothing and does
  not refuse the login; the `/warn` argument parsing; the acknowledgement command accepts only the owner.
- Web component tests (Vitest, no network): the warn action sends the template apply; the badge shows for warning
  rows; warnings are not counted as active.
- Every backend and Velocity task group lands its own JUnit tests; every web group its own component tests
  (`config.yaml` rules).

## Risks / Trade-offs

- **A warning the player never sees** stays pending and is shown on the next login. A player who never logs in
  again keeps it pending forever. Accepted: the record is the point, and escalation counts it regardless.
- **Warnings climb the ladder** unless `warnWindow` is set. The default is the escalation window, which is the
  same rule as kicks. Operators who want warnings to expire faster set `warnWindow` on the template.
- **Appeals lift escalation.** A lifted warning stops counting, so an appeal can make a later ban one step
  lower. Intended, and visible on the case.
- **Warnings are English only.** Consistent with kick and ban screens; players who read another language see
  English text.
- **`/warn` applies the escalation.** A staff member who uses `/warn` on a ladder whose current step is a ban gets
  a ban. The command name promises a warning; the response says what was applied. Open question 1.
- **Client breaking change**, as for kicks: documented, mitigated by PR order and the release note.

## Open questions

1. `/warn` on a template whose current step is a ban or kick: apply the step (this design), refuse with 422
   `WARN_STEP_REQUIRED` (safer), or require `blackhole.punish` in addition to `blackhole.warn`?
   Recommendation: refuse with 422 unless the step is `WARN`, and have the panel and `/punish` do the escalation.
   The design above applies the step; the maintainer decides before the code PR.
2. Should the proxy show a warning to an online player as a chat line only, or also as a title (screen) message?
   Recommendation: chat line only; a title is intrusive for a warning that never blocks.
3. Do warnings appear in the public player-facing history (portal)? Recommendation: yes, as a warning row, without
   the template key, because the player is the subject of the warning.
4. `warnWindow` default. Recommendation: none (the escalation window applies), so no existing template changes
   behaviour.
