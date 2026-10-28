## Why

A staff account can be compromised, or a moderator can abuse the power to punish. Today the only
tool is revoking one punishment at a time, per section. An admin who must clean up two hundred
bans issued by one account has to find each one, revoke it in each section and write the reason
again every time. Players stay punished in the meantime, and the case history of each player says
nothing about why the punishment disappeared. Admins need one recorded action: see exactly what
would be undone, confirm it with a reason, and let the system finish the job, with progress that
survives a backend restart in the middle.

## What Changes

- **New admin action: roll back by staff actor.** An ADMIN picks one staff actor and a time window
  and undoes every punishment that actor issued in that window that is still in force.
- **Preview before anything changes.** The preview lists the counts per punishment kind and per
  section, and the affected players as a list. It writes nothing except a preview job that freezes
  the set. Changes after the preview are not picked up; a new preview is needed.
- **Confirm with a justification.** Starting the rollback requires a reason (1 to 500 characters).
- **One audited job.** The rollback runs as a job. Each punishment is revoked through the normal
  revoke path, so each one gets the same case event, evidence marking, ELO handling and
  `punishment.revoked` event as a manual revoke. The case event text reads "rolled back by
  <admin>, reason: <reason>". The job is idempotent and resumable, and the panel shows progress.
- **NATS.** Every revoke publishes `punishment.revoked`, so proxies unban exactly as for a manual
  revoke. The job publishes `staff.rollback.started` and `staff.rollback.completed` with counts only.
- **Optional: suspend the staff link.** The rollback can remove the Minecraft-to-identity link of
  the staff actor (`staff-link` `ROLLBACK` removal), so that later in-game actions of that account
  are no longer attributed to the person. It does not disable the Entra account; that stays with
  the identity provider.
- **Optional: mark history.** Kicks, and warnings once their change has landed, can be marked as
  rolled back. A mark changes no enforcement, costs no ELO and emits no proxy event; it only sets a
  metadata flag and adds a case event.
- **ELO** follows a manual staff revoke exactly: a temporary punishment is not refunded, and a
  permanent punishment resets the section score to the pardon score. No new ELO rule is introduced.
- **Panel.** A rollback page under Administration, on the existing staff accounts tab (labelled
  "Team" in the navigation). Two steps: preview, then confirm with the reason.
- **Automatic ELO punishments are not included.** They have no staff actor; see `design.md`,
  decision 6.

Not BREAKING. The change adds routes and a client spec version; no existing field changes.

## Capabilities

### New Capabilities

- `staff-rollback`: the selection of one staff actor's punishments, the frozen preview, the audited
  resumable job, the revoke reuse, the optional link suspension and history marks, and the events.

### Modified Capabilities

- `admin-web-ui`: a rollback page under the team administration.
- `staff-account-link`: a link removed by a rollback (`ROLLBACK` reason) and its effect on
  attribution.

## Out of scope

- Rolling back automatic ELO punishments (the system actor). A later change may add an explicit
  `includeAutomatic` flag; this change does not.
- ELO refunds. The rollback follows the revoke rule; a refund option for compromised accounts is an
  open question for the maintainer.
- Disabling or suspending the Entra account. The rollback removes the Minecraft link only.
- Undoing a rollback. A rolled-back punishment is re-applied by hand if it was wrong.
- Rolling back appeal decisions, reports, or evidence uploads by a staff member. Evidence follows
  its punishment through the existing revoke marking.
- Scheduled or recurring rollbacks, and rollbacks from Discord.
- Rate limiting. Infra owns rate limiting (`config.yaml` principle 5).
- Localized job messages beyond the panel's own strings.

## Impact

- **Backend**: a new feature package `rollback/` (controller, service, job runner, JDBC reader and
  writer, DTOs). `PunishmentApplicationService.revoke` is split so that the public revoke and the
  rollback share one internal method; the public behaviour does not change. `staff-account-link`
  gets the `ROLLBACK` removal reason. `events/` gets two event types.
- **Database**: one new Flyway migration `V<next>__staff_rollback.sql`, additive only: two tables
  `staff_rollback_job` and `staff_rollback_item`.
- **API**: new routes under `/admin/rollback`, ADMIN only. `client/specs/blackhole-api-*.yml` gets
  the next free version.
- **Web**: the rollback page and its components, EN and DE strings.
- **Prerequisite**: the job stores the staff member's Minecraft UUID as a selector when the staff
  action came from an unlinked in-game account. `punishments.source` already holds it in clear text,
  so the job reuses the existing column semantics. Under the amended data protection principle
  (change `amend-data-protection-principle`) this is allowed for a feature that needs it, with the
  retention of the job enforced by the sweeper.

Ships as: feat(punishment): add a rollback of one staff actor's punishments

## Stacked pull requests

Proposal PR first, merged before the code. The code is split as in `tasks.md`; the Ships-as title
is the backend PR that lands the feature.
