## Context

The code this design builds on (checked on `main`, 2026-10-28):

- `punishments` carries the attribution triple written by every write (see `Attribution.java`):
  `source` (the attributed actor: the caller's `oid`, or the in-game Minecraft UUID for a proxy
  write with `onBehalfOf`), `recorded_by_oid` (V9, always the verified token `oid`) and `actor_oid`
  (V10, the `oid` a linked in-game account is bound to; NULL for web writes and unlinked in-game
  accounts). Also `owner` (SHA-512 hash), `section_key`, `kind` (V27), `created_at`, `lifted`.
- `PunishmentApplicationService.revoke` (around lines 505-560) revokes the single active punishment
  of an owner in one section: it clears the slot with `clearIf`, marks the row lifted, marks its
  evidence revoked, records the case event, resets the score to pardon for permanent punishments
  only, and publishes `punishment.revoked`. A staff revoke of a temporary punishment does not refund
  ELO (documented on the method).
- `PunishmentSyncHandler` clears the proxy mirror on `punishment.revoked`; the proxies unban from
  that event over NATS (`velocity/.../nats/Subjects.java`).
- `StaffLinkService` owns the link between a Minecraft UUID and an `oid` (`staff_link`, V10) and
  publishes `staff-link.removed` with a reason.
- Roles are `ADMIN > MODERATOR > VIEWER`, plus the machine roles `PROXY`, `DISCORD`, `METRICS`
  (`security/Roles.java`). The proxy is an app-only token and has no staff role.
- The ELO system writes automatic punishments with its own source (`elo-scoring`), so they have no
  staff actor at all.
- Flyway: `V27__kick.sql` is the head on `main`. The rollback migration takes the next free version
  at implementation time (V28 if nothing else lands first). No number is fixed in this change.
- The admin panel's Team area is the staff accounts tab (`web/src/views/AdminView.vue`,
  `StaffAccountsView.vue`, label `nav.staffAccounts`).

## Goals / Non-Goals

**Goals**: undo a compromised or abusive account's punishments in one recorded, previewed action;
never leave a half-done rollback without a way to finish it; keep every effect identical to a manual
revoke, so the proxies, the case history and the ELO records need no special case.

**Non-Goals**: a new punishment semantics; an undo of the rollback; rolling back the automatic ELO
system; disabling the Entra account; a rollback that happens without an admin in the loop.

## Decisions

### 1. Which punishments belong to a staff actor

A row belongs to the selected staff actor `S` when `COALESCE(actor_oid, recorded_by_oid) = S` and
`created_at` lies in the window. For a linked in-game action `actor_oid` is the staff member's `oid`;
for a web write `actor_oid` is NULL and `recorded_by_oid` is the person. For a proxy write by an
unlinked account, `recorded_by_oid` is the proxy's `oid`, so the row matches no human actor. Such
rows are selected explicitly by the Minecraft UUID of the account (`source`), which the admin picks
from the preview's "unlinked in-game actor" list.

The selector must be a `HUMAN` `staff_actor` (or a Minecraft UUID); a `SERVICE` `oid` such as the
proxy's is refused with 400 `ROLLBACK_SELECTOR_INVALID`, so a rollback can never mean "everything the
proxy did".

Rejected: `recorded_by_oid` only (misses every linked in-game action, which the proxy records with the
proxy's `oid`); `source` only (it holds the Minecraft UUID for in-game writes, which is not known to
the admin for every staff member; kept only as the explicit selector for unlinked accounts).

### 2. Preview freezes the set in a job

`POST /admin/rollback/preview` creates a `staff_rollback_job` in state `PREVIEW` and copies the
matching rows into `staff_rollback_item`. Only then is the set fixed: the execution runs over the
frozen items, so a punishment applied after the preview is not revoked by surprise and the confirmed
count is the count that runs. A preview expires after `blackhole.rollback.preview-ttl` (default
PT1H, configured in `application.yml` with a comment); an expired preview cannot be started.

A preview is refused with 422 `ROLLBACK_TOO_LARGE` above `blackhole.rollback.max-items` (default 5000).
The admin then narrows the window. This keeps one job bounded and the NATS burst manageable.

Only items that can be revoked are frozen: active punishments in a section (the owner's slot still
holds them). Historical rows (already lifted or expired) are not revocable and are listed in the
preview only as "already inactive" counts, so the admin sees what was ignored.

### 3. Execution is a resumable job with a lease

`staff_rollback_job.state`: `PREVIEW` -> `RUNNING` -> `COMPLETED`, or `PREVIEW` -> `CANCELLED`, or
`RUNNING` -> `CANCELLED` (cancel stops before the next item; revoked items stay revoked).
`staff_rollback_item.state`: `PENDING` -> `REVOKED` | `SKIPPED` | `FAILED`.

- **Start** (`POST /admin/rollback/{id}/execute`) requires an `Idempotency-Key` header (missing: 400)
  and the justification. The key is stored with the job; a second start with the same key returns
  the same job, and a start on a job that is not in `PREVIEW` returns 409.
- **Runner**: a scheduled tick (not a thread that sleeps) takes a lease on one `RUNNING` job
  (`locked_by`, `locked_until` from the injected `Clock`), processes a batch of `PENDING` or `FAILED`
  items, and renews the lease. A replica that dies lets the lease expire; another replica resumes
  from the first `PENDING` item. Already `REVOKED` items are never processed twice.
- **Each item** calls the revoke path (decision 4). The revoke clears only the slot that still holds
  that punishment (`clearIf`). If the slot now holds something else or nothing, the item becomes
  `SKIPPED` with a reason code (`ALREADY_INACTIVE`); this is not an error and does not fail the job.
- **Failure** of one item (database or publish error) marks it `FAILED` with the error code and no
  message text, and continues with the next item. A retry is a second execute call that moves the
  job back to `RUNNING`, which is refused unless the job has `FAILED` items.
- **Progress** is read from `GET /admin/rollback/{id}`: counts per state, and the job's state.

Transactions: `@Transactional` is unusable (see `database` rules and `config.yaml`). Each item is
written with the same explicit, non-annotated repository calls the existing revoke uses, and the
item state is written after the revoke, so a crash between the two re-runs the item, which the
`clearIf` check turns into `SKIPPED`. This is the idempotency argument: the effect is at most once.

Rejected: a single long transaction over all items (not available; and it would hold locks on every
profile); a thread per job with a sleep loop (breaks the Fast and Repeatable rules).

### 4. Reuse the manual revoke, do not duplicate it

`PunishmentApplicationService.revoke(RevokeRequest, Actor)` is split into a public method that
resolves the actor and an internal method that takes the owner, section, identifier, attribution,
and an optional `rollbackJobId` plus `reason`. The rollback calls the internal method with the
admin's attribution. The case event payload carries `rollbackJobId` and the reason, and its text is
"rolled back by <admin display name>, reason: <reason>". The display name is the staff notice already
written into case protocol headers (#398).

Rejected: a direct SQL update of the punishment rows (skips the case event, the evidence marking, the
score reset and the NATS event, and would leave the proxies' mirror stale).

### 5. ELO follows the manual revoke

A rolled-back temporary punishment does not refund its ELO cost. A rolled-back permanent punishment
resets the section score to the pardon score, as a manual revoke does, with the reason code and
`rollbackJobId` in the ELO event metadata. Compromised accounts therefore leave score damage behind
only where a manual revoke would; the admin can adjust the score through the existing ELO adjustment
with its own justification.

Open question (for the maintainer): whether the rollback should offer an explicit "refund ELO"
checkbox for compromised accounts. It is not in this change, because it is a new ELO rule.

### 6. Automatic ELO punishments are excluded

The selection requires a staff actor, so the ELO system's automatic punishments never match. They are
not offered as an option in this change. A later change may add `includeAutomatic`, which would also
need a decision on whether a compromised staff account could have triggered thresholds; that is not
decided here.

Rejected: including them behind a flag now (the flag and its preview would need their own
justification and tests, and nothing in this change needs them).

### 7. Staff link suspension and history marks

- **Suspend link** (optional, default off): the rollback calls `StaffLinkService` to remove the link
  of the selected staff actor before the items run, with removal reason `ROLLBACK`. This publishes
  `staff-link.removed`. `actor_oid` values on existing rows are not changed (the staff-account-link
  rule). A rollback with suspension does not block the person from relinking later.
- **History marks** (optional, default off): kicks (V27) in the window are marked
  `metaData.rolledBackBy` and `rollbackJobId`. They are not active, so there is no enforcement
  effect, no ELO and no proxy event. Warnings are marked the same way once their change has landed;
  until then the marks cover kicks only. A marked kick still counts for escalation (unchanged rule).

### 8. Authorisation and attribution

`POST` and `GET` under `/admin/rollback` are `@Secured(ADMIN)`. The actor of each revoke is the admin's
token `oid` (verified-actor rule); the selector is the staff actor the admin picked, not the caller.
The reason is checked with the existing PII guard (no UUID, IP or Ban ID in free text).

### 9. Events

- `punishment.revoked`: one per revoked item, unchanged payload; the admin's `oid` is `revokedBy`.
- `staff.rollback.started` and `staff.rollback.completed`: job id, the selector's `oid` or Minecraft
  UUID, counts per state and kind, the admin's `oid`. No owner hash, no player name, no reason text.

Subjects follow the existing `blackhole.events.<type>` rule. Publish failure after a write never
fails the write (the same rule as `punishment-management`).

### 10. Panel

The rollback page sits on the staff accounts tab. Step 1: pick the staff actor (or an unlinked
in-game account), the window and the options; preview shows counts per kind and per section and the
unlinked list. Step 2: justification, the suspend-link and marks options, confirm. Progress polls the
job until `COMPLETED` or `CANCELLED`. No personal data beyond what the player pages already show.

## Risks / Trade-offs

- **Revoke burst.** A large rollback publishes many `punishment.revoked` events and proxies unban in
  bulk. Mitigation: the item cap, batches per tick, and the proxies' existing idempotent unban.
- **Wrong actor.** An admin may pick an actor whose punishments were correct. Mitigation: the preview
  shows the affected players before the confirm step, and the reason is mandatory and recorded.
- **Stale attribution.** `COALESCE(actor_oid, recorded_by_oid)` relies on the link being correct at
  write time. A link corrected later does not move history (the staff-account-link rule), so an
  account linked to the wrong person can leave rows on the wrong actor. Mitigation: the preview shows
  the `source` Minecraft UUIDs so the admin can cross-check.
- **Concurrent manual revoke.** A moderator may revoke an item during the rollback. The `clearIf` check
  makes that item `SKIPPED`, not an error.
- **Crash during a batch.** Covered by decision 3 (at most once effect through the `clearIf` check).

## Migration plan

`V<next>__staff_rollback.sql` (the number is assigned at implementation time: the next free number on
main; main is at V27 today) is additive only:

- `CREATE TABLE staff_rollback_job (id uuid PK, state varchar(16), selector_oid uuid NULL,
  selector_minecraft_uuid uuid NULL, window_from bigint, window_to bigint, include_marks boolean,
  suspend_link boolean, reason varchar(500), requested_by_oid uuid, idempotency_key varchar(64) UNIQUE
  NULL, preview_expires_at bigint, created_at bigint, started_at bigint NULL, finished_at bigint NULL,
  locked_by varchar(64) NULL, locked_until bigint NULL)`, with a CHECK on `state` and on exactly one
  selector being set.
- `CREATE TABLE staff_rollback_item (job_id uuid, punishment_identifier varchar(64), owner varchar(128),
  section_key varchar(64), kind varchar(16), state varchar(16), reason_code varchar(32) NULL,
  processed_at bigint NULL, PRIMARY KEY (job_id, punishment_identifier))`, FK to the job only.
- No backfill, no change to existing `punishments` rows. Anonymizing a staff actor does not touch the
  job: the job keeps the `oid` as history, like every other record (`verified-actor`).
- Retention: jobs and items are kept with the punishment records they reference; the existing
  sweepers cover them once the punishment is pruned (to be wired in the tasks).

Rollback of the change: the routes are removed and the tables can stay empty; no existing data depends
on them.

## Open questions

1. ELO refund option for compromised accounts (decision 5).
2. Whether `includeAutomatic` is wanted at all, and who may use it (decision 6).
3. Default and maximum values: preview TTL (PT1H proposed), maximum items (5000 proposed), default
   window size.
4. Does a rollback with `suspendLink` also need to block the link from being re-created for a set
   period, or is the existing confirm flow enough?
5. Warnings: the marks depend on the warnings change landing first; if it lands later the marks
   simply cover kicks until then.
