# Proposal

**Ships as:** `feat(import)` (PR 1), `feat(punishment)` (PR 2), `feat(web)` (PR 3). One type per
commit, see the PR plan in `tasks.md`.

## Why

Bans imported from a vanilla `banned-players.json` have no punishment template. The player sees the
free-text reason of the file verbatim and untranslated (English text on a German client), the case
has no template, and staff cannot tell an imported ban from a hand-written one. The team requires that
imported bans are tied to templates, and that the ones already imported are tied afterwards.

## What Changes

- **Import with a template.** `POST /admin/import/vanilla` takes a required `templateKey` and an
  optional `reasonTemplates` mapping (original reason text to template key) for mixed files. Each
  imported ban is linked to its template: the section comes from the template and the reason shown to
  the player is the template's. The original vanilla reason is kept in the metadata as
  `importedReason` for staff. The dry run lists, per original reason, the template it would get.
- **No retroactive ELO.** An import is a record of an earlier ban, not a fresh offence: no cost is
  deducted, no escalation step or group is recorded, and the case timeline gets a `punishment.applied`
  event without a step.
- **Mute templates are refused.** A template in a `MUTE` section is rejected (HTTP 400) before anything
  is written; vanilla bans are bans.
- **Re-template existing imports.** An `ADMIN` bulk action links already imported punishments (source
  is the import identity, no template) to a chosen template, filtered by original reason text or by an
  explicit selection. It is idempotent, changes no score, refreshes the proxy mirror and records
  `punishment.retemplated` on each case with the actor of the token.
- **Admin UI.** The import panel gets a required template picker, an optional reason-to-template
  table and a preview that shows each entry's template. The admin page gets an "Importierte Strafen"
  section that lists imported punishments without a template grouped by reason, lets the admin pick a
  template per group and applies after a confirmation.

## Out of scope

- IP bans (`banned-ips.json`) stay skipped.
- No per-actor identity beyond the token's `oid`; no change to ELO, thresholds or escalation.
- Imports are not made into escalation inputs: they do not count towards a template's group.
- Velocity needs no change: the sync payload already carries `templateIdentifier` and the template's
  reason (`PunishmentEvents.reasonOf`), and the profile path reads the template reason first.

## Impact

- Backend: `imports/` (service, controller, DTO), a new bulk service and controller (PR 2), a new
  case payload `punishment.retemplated`.
- **BREAKING (internal):** `POST /admin/import/vanilla` now requires `templateKey`. The endpoint is
  hidden from the published API and only the admin page calls it, so the generated client is unaffected.
- No schema change: the template link and the metadata already exist on `punishments`.
