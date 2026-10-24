# Proposal

**Ships as:** `feat(appeal)` (backend, migration, routes, client spec), `feat(web)` (staff panel editor),
`feat(portal)` (portal display and confirmation). One type per commit, see the PR plan in `tasks.md`.

**Builds on:** `add-appeal-portal` (public API, player session, portal, appeal filing). Branched from `main`,
which already carries it.

## Why

A player who appeals accepts rules about how the appeal works (one appeal per Ban ID, the waiting period after
a rejection, a leaked Ban ID forfeits the appeal, no guaranteed response time). Today these live only in the
team's heads. The portal should show them before the player files, the player should confirm them, and the
team should be able to change the wording without a deployment.

## What Changes

- **Appeal conditions** ("Rahmenbedingungen") are a plain text maintained in the staff panel by `ADMIN`; every
  team role can read it. It is **versioned and append-only**: each save creates a new version, old versions stay.
  A default English text is seeded as version 1 by the migration.
- **Backend:** a new table `appeal_conditions`, the accepted version in the appeal's own metadata (`conditionsVersion`), staff routes
  (`GET /appeal-conditions`, `GET /appeal-conditions/versions`, `POST /appeal-conditions`) and the public route
  `GET /public/appeal/conditions`. `POST /public/appeal` requires `conditionsVersion` to equal the current version,
  otherwise HTTP 409 `CONDITIONS_CHANGED`. The filed appeal stores that version number and nothing else.
- **Panel:** an "Appeal conditions" page in the admin area (editor for `ADMIN`, read-only for the rest, version
  history).
- **Portal:** the appeal form shows the current conditions and a checkbox the player must tick before filing; the
  conversation page keeps them viewable in a collapsible "Appeal conditions" block at the top. The text is rendered
  as text nodes only, never as HTML.
- Player-facing text is English only for now.
- Client spec `blackhole-api-0.0.N.yml` (next free number at implementation time).

BREAKING for API clients that file public appeals: `POST /public/appeal` now needs `conditionsVersion`. The only
caller is the portal, which ships in the same change set. The generated client gains operations; nothing is removed.

## Out of scope

- Discord ticket open (`POST /discord/tickets`): the bot does not show the conditions yet (PRs 299 and 300 are
  merged, the bot has no such screen). Applying the same rule there is a follow-up task once the bot shows them.
- Translations of the conditions; Markdown or any HTML; per-section conditions; approval workflows for edits.
- Storing who accepted what beyond the version number on the appeal. No timestamps, addresses or identifiers are added.
- A per-actor audit trail beyond the existing `recorded_by_oid` of the staff member who saved a version.
- App-level rate limiting (the edge owns it).
