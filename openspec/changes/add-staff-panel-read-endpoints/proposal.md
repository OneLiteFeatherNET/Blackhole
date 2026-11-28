## Why

The staff panel is being restructured (`restructure-staff-panel`) so that every entity has its own URL. A deep link
must open one item without loading the list it belongs to: an appeal, an exemption review, an area ban, an allow-list
entry, an audit entry, a staff member or a terms version. Six of these have no read-by-id endpoint today, so the panel
would have to download a whole list to show one row, or could not open the address at all. The inbox also needs three
facts it does not get today: what another staff member changed last, who did it, and when.

## What changes

- Seven `GET` endpoints that return one item, each with the role of the list endpoint of the same entity, a 404 for an
  unknown id and no Ban ID, raw UUID, IP or name in the response. Six are required by the restructure design; the
  terms version (`GET /appeal-conditions/versions/{version}`) is optional there and is included because it reuses an
  existing DTO. The table is in `design.md`.
- `GET /inbox` gets one additional section, `changedByOthers`: the cases and appeals whose last change in the last
  24 hours came from another verified staff member, with what changed, the staff display name and the time.
- The OpenAPI client spec gets a new file `client/specs/blackhole-api-0.0.68.yml` (newest + 1 at implementation time)
  and the `inputSpec` pin moves to it. Published spec files are not edited.
- No migration, no write endpoint, no change to an existing route or to an existing response field.

Out of scope:

- Version columns and ETags for the appeal, exemption review, area ban, allow-list entry and staff actor. Those
  entities have no version today; listed as known gaps in `design.md`, to be added by a later change with an
  append-only migration.
- Roles and link status on the staff actor read. Listed as a known gap.
- List filters the restructure's query state needs (appeal `status`, report `status`, audit `surface`). A separate
  change; these are list parameters, not read-by-id endpoints.
- Converting `AppealDTO` into a sealed marker. It would change the list and review wire shapes.
- Any change to the Ban ID, the player-facing portal, or the Velocity plugin.

## Impact

- Conventional type and scope: `feat(api)`. One type: the read endpoints and the inbox section ship together because
  the inbox section reads the same case timeline the endpoints do.
- Not breaking. The new routes are additive. `GET /inbox` gains one field in its response; the generated Java client
  gains that field as an optional property. No existing route changes its path, role or status codes.
- Affected code: `backend/` packages `appeal`, `exemption`, `areaban`, `intel`, `audit`, `security` (staff actor),
  `appealconditions`, `inbox`; tests `PermissionMatrixIntegrationTest` and `RouteCoverageIntegrationTest`; `client/`.
- Consumers: the restructured panel, phases 8, 11, 12, 14 and 15 of `restructure-staff-panel` (blocked on this
  change), and its follow-up "changed by others" section (phase 16).
