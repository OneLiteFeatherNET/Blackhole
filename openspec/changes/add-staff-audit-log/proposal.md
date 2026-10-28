## Why

Staff act on players from many places: the panel, the Discord bot, in-game commands on the
proxy, and direct API calls. Each place writes its own record, but nobody on the team sees the
whole picture as it happens. When a ban is revoked at 23:40 or an appeal is rejected from a
ticket channel, the rest of the team learns about it only by chance. The maintainer asked
(2026-10-28) for every staff button to be logged to a webhook channel that the team reads, with a
direct link into the team panel at the end of each entry, so that anyone can jump to the case
or the player in one click.

## What Changes

- **A staff audit entry for every staff action**, from any surface: who (staff display name and
  Entra object id), through which surface (`PANEL`, `DISCORD`, `IN_GAME`, `API`), what (a fixed
  action name such as `punishment.revoke`), the target (case reference or player name), and when.
- **Source of truth is an append-only backend table** `staff_audit`, written by the services after
  a successful action. The same entry is published once as the NATS event
  `blackhole.events.staff.action`.
- **Delivery to a Discord webhook channel** is done by the `discord` bot, which already consumes
  NATS. The bot posts each entry in order, honours Discord's rate limits (`retry_after`) and
  retries server errors. A delivery failure never blocks or rolls back the staff action.
- **Every entry ends with a direct link** to the team panel page of the case (`/cases/<reference>`)
  or of the player (`/players/<owner>`), built from the configured panel base URL.
- **The panel gets a read-only Audit view** for ADMIN users, cursor-paginated over `staff_audit`.
- **Retention**: audit rows are kept for one year by a sweeper (proposed, see open questions).
- **Configuration**: the webhook URL is an operator secret in the bot's Kubernetes Secret
  (`DISCORD_AUDIT_WEBHOOK_URL`). It is validated at bot startup and never logged.
- **Ban IDs never appear** in the entry, the link, the event, the logs or the table.
- **BREAKING**: none for existing clients. The client spec gains the audit route for the panel
  (additive). The Velocity plugin is not changed beyond passing the surface it already sends.

Ships as: `feat(audit): log staff actions to a discord channel`

## Capabilities

### New Capabilities

- `staff-audit-log`: the audit entry, its append-only table, the `blackhole.events.staff.action`
  event, the webhook delivery by the bot, the panel link, retention and the audit read route.

### Modified Capabilities

- `admin-web-ui`: a read-only Audit view for ADMIN users (new requirement in the admin area).

## Impact

- **Backend**: a `staff audit` feature package (`audit/`: recorder, entity, repository, reader,
  controller `AuditApi`/`AuditController`, DTOs), calls from the existing staff write services
  (punishment, case, appeal, report, evidence, ELO, client rules, sections, templates, imports,
  staff links, kick), a retention sweeper, `application.yml` (`blackhole.audit.*`, panel base URL).
- **Database**: one new Flyway migration `V<next>__staff_audit.sql`. Additive only.
- **Discord bot**: a durable JetStream consumer for `blackhole.events.staff.action`, a webhook
  poster with rate-limit handling, config `DISCORD_AUDIT_WEBHOOK_URL`.
- **Velocity**: no new code. In-game commands already reach the backend with `onBehalfOf`; the
  backend derives the `IN_GAME` surface from the proxy role and `onBehalfOf`.
- **Web**: an Audit tab under the admin area (`/admin/audit`).
- **Client**: `client/specs/blackhole-api-*.yml` gets the next version (0.0.44 today, the next
  free number at implementation time) for the audit read route.
- **Helm**: `deploy/charts/blackhole` documents the new key in the discord Secret, and the
  chart's secret keys list in `README.md`.

## Out of scope

- Logging views of the audit log itself (a read is not an action; see open questions).
- Auditing player actions or automatic system actions (ELO sweeps, expiry sweepers). Only staff
  actions are audited.
- Replacing the domain records (`case_event`, `evidence_audit`, `elo_events`). They stay as they
  are; the audit log is a cross-cutting record of who did what, not a replacement for them.
- A Discord slash command that searches the audit log.
- Verifying the surface `PANEL` or `API`: the panel declares it, the backend cannot prove it.
- Rate limiting of audit writes (infra owns rate limiting, `config.yaml` principle 5).
- Any Ban ID, IP address or raw UUID in the entry (not in scope for any amendment).

## Pull requests and commit types

One code pull request titled `feat(audit): log staff actions to a discord channel`, with one commit per
group of `tasks.md`, all of type `feat` and scope `audit`. This change lands before
`add-discord-staff-ticket-actions`, whose ticket actions write audit entries through the recorder of
this change.
