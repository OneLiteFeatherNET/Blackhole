# Tasks

Every task group follows the same order: write the failing Vitest tests first (fake API from
`web/src/test/auth` and a fake `BlackholeApi`, no network, no wall-clock waits, injected clock where the
time matters), then the code, then the strings. Each group ends with the code pull request for its phase,
opened from `origin/main` after the proposal PR `docs(openspec): propose restructure-staff-panel` is merged.
No code pull request contains a file under `openspec/`, and no code PR carries an endpoint of
`add-staff-panel-read-endpoints`. Checkboxes are ticked in a follow-up PR, not in the code PRs.

Each area's URLs ship with its screens: a route is added only in the phase that builds its page.
Rows refer to `design.md` decision 7.1.

## 1. Foundation: dialogs from the route and copy link (`feat(web): open dialogs from the route and add copy link`)

- [ ] 1.1 Test that a child route renders its dialog over the parent and that Back closes it, with the router in memory
- [ ] 1.2 Test the not-found view for an unknown id in a child route (no empty dialog)
- [ ] 1.3 Test the copy-link action: the absolute URL with query and hash, the text confirmation, no fake clipboard wait
- [ ] 1.4 Implement a route-driven dialog helper and the `CopyLink` button, and use the helper in no existing page yet
- [ ] 1.5 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 1.6 Test the redirect allow-list: `https://` and `//` targets, `/\` and control characters, and an unknown route name are ignored; an allow-listed route is followed (CWE-601, design decision 8)
- [ ] 1.7 Authorization test for each new route: a VIEWER, a MODERATOR without administrator rights and an anonymous caller get the no-access view or sign-in and no staff data; the companion endpoints of the phase answer 401 and 403 in their own tests (OWASP API1 and API5)
- [ ] 1.8 ASVS Level 2 review of this phase, items V2, V3, V4, V5, V7 and V14 as touched, recorded in the PR description (design decision 8)
- [ ] 1.9 Open the code pull request titled `feat(web): open dialogs from the route and add copy link`

## 2. Inbox as a work queue (`feat(web): turn the inbox into a work queue`)

- [ ] 2.1 Test the section order, the counts and the ten-row limit with a fake inbox read
- [ ] 2.2 Test the Assigned to me section from `GET /auth/me` and `GET /case?assignee=`, including the no-identity message
- [ ] 2.3 Test the Unassigned and urgent section: URGENT and HIGH merged, closed cases left out
- [ ] 2.4 Test the role matrix (VIEWER no actions, MODERATOR Assign and Close, ADMIN as MODERATOR) with a fake caller
- [ ] 2.5 Test that every row is a link to its entity URL and that "Show all" keeps the filter in the query
- [ ] 2.6 Implement the sections, the row actions with a ten-second undo for Assign, and the empty states
- [ ] 2.7 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 2.8 Authorization test for each new route: a VIEWER, a MODERATOR without administrator rights and an anonymous caller get the no-access view or sign-in and no staff data; the companion endpoints of the phase answer 401 and 403 in their own tests (OWASP API1 and API5)
- [ ] 2.9 ASVS Level 2 review of this phase, items V2, V3, V4, V5, V7 and V14 as touched, recorded in the PR description (design decision 8)
- [ ] 2.10 Open the code pull request titled `feat(web): turn the inbox into a work queue`

## 3. Navigation by job (`feat(web): group the navigation by job and add badges and shortcuts`)

- [ ] 3.1 Test the Moderation groups (Today, Work, Players, Protection) and that every destination has a unique icon
- [ ] 3.2 Test the badge text rules (zero hidden, 99+, last value kept on a failed read) in `navCounts`
- [ ] 3.3 Test the bar on compact (five items with More), the collapsed rail on medium and the expanded rail on wide, with a fixed width
- [ ] 3.4 Test the `g` chords and `?` with a keyboard event fake, including the text-field exclusion
- [ ] 3.5 Test that the search clears on navigation and classifies `#A1B2C3D4` as a case
- [ ] 3.6 Replace the drawer with the rail components, add the bar, the icons and the chords; keep the switcher for ADMIN
- [ ] 3.7 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 3.8 Authorization test for each new route: a VIEWER, a MODERATOR without administrator rights and an anonymous caller get the no-access view or sign-in and no staff data; the companion endpoints of the phase answer 401 and 403 in their own tests (OWASP API1 and API5)
- [ ] 3.9 ASVS Level 2 review of this phase, items V2, V3, V4, V5, V7 and V14 as touched, recorded in the PR description (design decision 8)
- [ ] 3.10 Open the code pull request titled `feat(web): group the navigation by job and add badges and shortcuts`

## 4. Entity header (`feat(web): add the entity header with state, owner and breadcrumbs`)

Needs `add-case-workspace-tabs` merged (the case bar it replaces).

- [ ] 4.1 Test the state mapping of case, appeal, report and player to text pills with a table test
- [ ] 4.2 Test the disabled-action reasons: the text is shown and `aria-describedby` points to it
- [ ] 4.3 Test the breadcrumb (route match, parts are links) and the copy-link action in the header
- [ ] 4.4 Test the cross-links of a case, an appeal, a report and a player with fake entities
- [ ] 4.5 Implement `EntityHeader` on the case, report and player pages, replacing the case bar
- [ ] 4.6 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 4.7 Authorization test for each new route: a VIEWER, a MODERATOR without administrator rights and an anonymous caller get the no-access view or sign-in and no staff data; the companion endpoints of the phase answer 401 and 403 in their own tests (OWASP API1 and API5)
- [ ] 4.8 ASVS Level 2 review of this phase, items V2, V3, V4, V5, V7 and V14 as touched, recorded in the PR description (design decision 8)
- [ ] 4.9 Open the code pull request titled `feat(web): add the entity header with state, owner and breadcrumbs`

## 5. List and detail panes (`feat(web): open cases and reports in list and detail panes`)

- [ ] 5.1 Test the URL change on select and Back returning to the list query, with the router in memory
- [ ] 5.2 Test that changing the open item does not request the list again (fake API call counter)
- [ ] 5.3 Test `j`, `k`, `Enter` and `Esc` with focus return, with no wall-clock waits
- [ ] 5.4 Test the three widths: side by side, detail replacing the list, and separate pages
- [ ] 5.5 Implement the list-detail layout for `/cases` and `/reports`, with `aria-current` on the open row
- [ ] 5.6 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 5.7 Authorization test for each new route: a VIEWER, a MODERATOR without administrator rights and an anonymous caller get the no-access view or sign-in and no staff data; the companion endpoints of the phase answer 401 and 403 in their own tests (OWASP API1 and API5)
- [ ] 5.8 ASVS Level 2 review of this phase, items V2, V3, V4, V5, V7 and V14 as touched, recorded in the PR description (design decision 8)
- [ ] 5.9 Open the code pull request titled `feat(web): open cases and reports in list and detail panes`

## 6. Query state and redirects (`feat(web): keep list filters and sort in the url and redirect old paths`)

- [ ] 6.1 Test the query parsing of every list in 7.1 (rows 1, 6, 8, 12, 16, 18, 24, 27, 29, 31, 35, 37, 41, 46, 48), with unknown values falling back and removed by replace
- [ ] 6.2 Test the redirect table of 7.2 for every old path, keeping the query string (table-driven test over the router)
- [ ] 6.3 Test that the name lookup replaces the address with `/players/:owner` and that NOT_FOUND keeps the address
- [ ] 6.4 Test that no list state is written to local storage or session storage (a fake storage that fails the test on write)
- [ ] 6.5 Implement the query parameters and the redirects in `router/index.ts`, keeping every old path
- [ ] 6.6 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 6.7 Authorization test for each new route: a VIEWER, a MODERATOR without administrator rights and an anonymous caller get the no-access view or sign-in and no staff data; the companion endpoints of the phase answer 401 and 403 in their own tests (OWASP API1 and API5)
- [ ] 6.8 ASVS Level 2 review of this phase, items V2, V3, V4, V5, V7 and V14 as touched, recorded in the PR description (design decision 8)
- [ ] 6.9 Open the code pull request titled `feat(web): keep list filters and sort in the url and redirect old paths`

## 7. Case notes, messages, protocols and evidence (`feat(web): address case notes, messages, protocols and evidence by url`)

- [ ] 7.1 Test the hash highlight: `#event-<id>` scrolls to and highlights the item, a missing id opens the top with no highlight
- [ ] 7.2 Test the child routes `/cases/:ref/notes/new`, `/notes/:noteId/edit`, `/notes/:noteId/redact`, `/messages/:messageId/edit`, `/messages/:messageId/redact`
- [ ] 7.3 Test `/cases/:ref/protocols/:evidenceId` and `/evidence/:id` (item page with its case and punishment links) with the fake API
- [ ] 7.4 Test that the hash is not part of any request (fake fetch records the URL)
- [ ] 7.5 Implement the routes and the highlight, using the dialog helper of phase 1
- [ ] 7.6 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 7.7 Authorization test for each new route: a VIEWER, a MODERATOR without administrator rights and an anonymous caller get the no-access view or sign-in and no staff data; the companion endpoints of the phase answer 401 and 403 in their own tests (OWASP API1 and API5)
- [ ] 7.8 ASVS Level 2 review of this phase, items V2, V3, V4, V5, V7 and V14 as touched, recorded in the PR description (design decision 8)
- [ ] 7.9 Open the code pull request titled `feat(web): address case notes, messages, protocols and evidence by url`

## 8. Appeal detail page (`feat(web): add the appeal detail page at /appeals/:id`)

Blocked until `add-staff-panel-read-endpoints` ships `GET /appeal/{identifier}`. Do not start before that.

- [ ] 8.1 Test the appeal page with a fake read: appeal, punishment, case link, conversation and decision panel
- [ ] 8.2 Test the tabs `?tab=conversation|decision|case` and the decide child route `/appeals/:id/decide` (hidden for a viewer, hidden once decided)
- [ ] 8.3 Test the unknown id shows not-found with no appeal data
- [ ] 8.4 Implement `/appeals/:id` with the entity header, link the list rows to it, keep the decision dialog as the decide route
- [ ] 8.5 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 8.6 Authorization test for each new route: a VIEWER, a MODERATOR without administrator rights and an anonymous caller get the no-access view or sign-in and no staff data; the companion endpoints of the phase answer 401 and 403 in their own tests (OWASP API1 and API5)
- [ ] 8.7 ASVS Level 2 review of this phase, items V2, V3, V4, V5, V7 and V14 as touched, recorded in the PR description (design decision 8)
- [ ] 8.8 Open the code pull request titled `feat(web): add the appeal detail page at /appeals/:id`

## 9. Reports (`feat(web): resolve and dismiss reports on their own routes`)

- [ ] 9.1 Test `/reports/:id/resolve` offers the template list from `GET /templates` to a MODERATOR and submits `POST /report/:id/resolve`
- [ ] 9.2 Test `/reports/:id/dismiss` and that Back closes each dialog without sending anything
- [ ] 9.3 Test `/reports?status=` and `?player=` as queries on the list
- [ ] 9.4 Implement the two child routes on the report page and the report list query
- [ ] 9.5 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 9.6 Authorization test for each new route: a VIEWER, a MODERATOR without administrator rights and an anonymous caller get the no-access view or sign-in and no staff data; the companion endpoints of the phase answer 401 and 403 in their own tests (OWASP API1 and API5)
- [ ] 9.7 ASVS Level 2 review of this phase, items V2, V3, V4, V5, V7 and V14 as touched, recorded in the PR description (design decision 8)
- [ ] 9.8 Open the code pull request titled `feat(web): resolve and dismiss reports on their own routes`

## 10. Players, punishments and warnings (`feat(web): address players, punishments and warnings by url`)

- [ ] 10.1 Test `/players/:owner?tab=` for every tab in 7.1 row 11 and the child routes punish, warn, elo/adjust, kick and exemption
- [ ] 10.2 Test `/punished/:shortId` with `?tab=details|evidence|history` and the revoke child route `/punished/:shortId/revoke`
- [ ] 10.3 Test `/warnings/:id` with the fake warning read, and that the Ban ID never appears in any of these addresses
- [ ] 10.4 Implement the routes; the Ban ID reveal stays an action on the case
- [ ] 10.5 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 10.6 Authorization test for each new route: a VIEWER, a MODERATOR without administrator rights and an anonymous caller get the no-access view or sign-in and no staff data; the companion endpoints of the phase answer 401 and 403 in their own tests (OWASP API1 and API5)
- [ ] 10.7 ASVS Level 2 review of this phase, items V2, V3, V4, V5, V7 and V14 as touched, recorded in the PR description (design decision 8)
- [ ] 10.8 Open the code pull request titled `feat(web): address players, punishments and warnings by url`

## 11. Staff accounts and links (`feat(web): address staff accounts and links by staff id`)

Blocked until `add-staff-panel-read-endpoints` ships `GET /admin/staff-actor/{oid}`.

- [ ] 11.1 Test `/admin/staff/:oid?tab=profile|links|activity` and `/admin/staff/:oid/anonymize` (confirm route, ADMIN only)
- [ ] 11.2 Test that the links tab shows Minecraft and Discord status with no Minecraft UUID and no Discord id in the address
- [ ] 11.3 Test `/profile`, `/profile/link/minecraft` and `/profile/link/discord` as child routes of the account page
- [ ] 11.4 Implement the routes and the tabs; keep the staff list query `?q=`
- [ ] 11.5 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 11.6 Authorization test for each new route: a VIEWER, a MODERATOR without administrator rights and an anonymous caller get the no-access view or sign-in and no staff data; the companion endpoints of the phase answer 401 and 403 in their own tests (OWASP API1 and API5)
- [ ] 11.7 ASVS Level 2 review of this phase, items V2, V3, V4, V5, V7 and V14 as touched, recorded in the PR description (design decision 8)
- [ ] 11.8 Open the code pull request titled `feat(web): address staff accounts and links by staff id`

## 12. Rollback, kick, audit and imports (`feat(web): address rollback, kick, audit and import entries by url`)

Blocked for the audit entry until `add-staff-panel-read-endpoints` ships `GET /admin/audit/{auditId}`. The
rollback, kick and import parts do not wait.

- [ ] 12.1 Test `/admin/rollback/:id`, `/admin/rollback/new` (preview) and `/admin/rollback/:id/cancel`
- [ ] 12.2 Test `/admin/kick/:id`, `/admin/kick/new` and `/admin/kick/:id/ack`, and `/admin/imports?tab=` and `?group=`
- [ ] 12.3 Test `/admin/audit?actor=&action=&surface=&cursor=` and `/admin/audit/:auditId` (not-found for an unknown id)
- [ ] 12.4 Test `/admin/cloudnet/:kind/:name` for the kinds the catalogue names, and that unknown names show not-found
- [ ] 12.5 Implement the routes and the query state of each list
- [ ] 12.6 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 12.7 Authorization test for each new route: a VIEWER, a MODERATOR without administrator rights and an anonymous caller get the no-access view or sign-in and no staff data; the companion endpoints of the phase answer 401 and 403 in their own tests (OWASP API1 and API5)
- [ ] 12.8 ASVS Level 2 review of this phase, items V2, V3, V4, V5, V7 and V14 as touched, recorded in the PR description (design decision 8)
- [ ] 12.9 Open the code pull request titled `feat(web): address rollback, kick, audit and import entries by url`

## 13. Configuration: templates, sections, client rules, appeal terms (`feat(web): address templates, sections, client rules and appeal terms by url`)

- [ ] 13.1 Test `/admin/templates/new`, `/admin/templates/:id`, `/admin/templates/:id/edit` (delete on the edit route only)
- [ ] 13.2 Test `/admin/sections/new`, `/admin/sections/:key`, `/admin/sections/:key/edit` and `/admin/sections/:key/disable`
- [ ] 13.3 Test `/admin/client-rules/:key?tab=details|hits`, `/new` and `/:key/edit`
- [ ] 13.4 Test `/admin/appeal-terms`, `/admin/appeal-terms/versions/:version` and `/admin/appeal-terms/new` (saving creates a version)
- [ ] 13.5 Implement the routes; the version detail reads the versions list when the companion read is not yet merged
- [ ] 13.6 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 13.7 Authorization test for each new route: a VIEWER, a MODERATOR without administrator rights and an anonymous caller get the no-access view or sign-in and no staff data; the companion endpoints of the phase answer 401 and 403 in their own tests (OWASP API1 and API5)
- [ ] 13.8 ASVS Level 2 review of this phase, items V2, V3, V4, V5, V7 and V14 as touched, recorded in the PR description (design decision 8)
- [ ] 13.9 Open the code pull request titled `feat(web): address templates, sections, client rules and appeal terms by url`

## 14. Exemption reviews (`feat(web): address exemption reviews by url`)

Blocked until `add-staff-panel-read-endpoints` ships `GET /exemption/reviews/{id}`.

- [ ] 14.1 Test `/admin/exemption-reviews/:id` and the decide child route `/admin/exemption-reviews/:id/decide`
- [ ] 14.2 Test `/admin/exemption-reviews?status=` and that the decision dialog closes on Back without a decision
- [ ] 14.3 Implement the routes, keeping the decision dialog and its strings
- [ ] 14.4 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 14.5 Authorization test for each new route: a VIEWER, a MODERATOR without administrator rights and an anonymous caller get the no-access view or sign-in and no staff data; the companion endpoints of the phase answer 401 and 403 in their own tests (OWASP API1 and API5)
- [ ] 14.6 ASVS Level 2 review of this phase, items V2, V3, V4, V5, V7 and V14 as touched, recorded in the PR description (design decision 8)
- [ ] 14.7 Open the code pull request titled `feat(web): address exemption reviews by url`

## 15. Moderation lists: IP bans, area bans, guard, VPN policy, allow list (`feat(web): address ip bans, area bans, guard, vpn policy and allow list by url`)

Blocked for the area-ban detail and the allow-list entry until `add-staff-panel-read-endpoints` ships
`GET /area-bans/{id}` and `GET /intel/allow-list/{owner}`. The IP bans, guard and VPN policy parts do not wait.

- [ ] 15.1 Test `/ip-bans/:id` and `/ip-bans/new`, `/ip-bans/:id/lift`, and `?status=` on the list
- [ ] 15.2 Test `/area-bans/:id`, `/area-bans/new`, `/area-bans/:id/lift` and `?status=`
- [ ] 15.3 Test `/guard/settings` and `/guard/settings/edit`, and the level action on `/guard`
- [ ] 15.4 Test `/intel/policy/:classification` and `/intel/policy/:classification/edit`
- [ ] 15.5 Test `/intel/allow-list/:owner`, `/intel/allow-list/new`, `/intel/allow-list/:owner/remove` and `?page=`
- [ ] 15.6 Implement the routes and keep the moderation lists' existing behaviour
- [ ] 15.7 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 15.8 Authorization test for each new route: a VIEWER, a MODERATOR without administrator rights and an anonymous caller get the no-access view or sign-in and no staff data; the companion endpoints of the phase answer 401 and 403 in their own tests (OWASP API1 and API5)
- [ ] 15.9 ASVS Level 2 review of this phase, items V2, V3, V4, V5, V7 and V14 as touched, recorded in the PR description (design decision 8)
- [ ] 15.10 Open the code pull request titled `feat(web): address ip bans, area bans, guard, vpn policy and allow list by url`

## 16. Follow-up: changed by others (`feat(web): show cases and appeals changed by others in the inbox`)

Starts after `add-staff-panel-read-endpoints` ships the inbox fields. Not one of the fifteen phases.

- [ ] 16.1 Test the section with fake items that carry `lastActor`: rows of other staff only, the caller's own changes left out
- [ ] 16.2 Implement the section and its empty state
- [ ] 16.3 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 16.4 Open the code pull request titled `feat(web): show cases and appeals changed by others in the inbox`

## Companion: `add-staff-panel-read-endpoints` (`feat(api)`, separate proposal)

Not part of this change and not in any web PR. Its endpoints are listed in `design.md` 7.3: appeal by
identifier, exemption review by id, area ban by id, allow-list entry by owner, audit entry by id, staff actor by oid,
and optionally one terms version; plus the inbox fields. Each endpoint is merged before the phase that names it.
