# Tasks

Every task group follows the same order: write the failing Vitest tests first (fake API from
`web/src/test/auth` and a fake `BlackholeApi`, no network, no wall-clock waits, injected clock where the
time matters), then the code, then the strings. Each group ends with the code pull request for its phase,
opened from `origin/main` after the proposal PR `docs(openspec): propose restructure-staff-panel` is merged.
No code pull request contains a file under `openspec/`. Checkboxes are ticked in a follow-up PR, not in the
code PRs.

## 1. Inbox as a work queue (`feat(web): turn the inbox into a work queue`)

- [ ] 1.1 Test the section order, the counts and the ten-row limit with a fake inbox read
- [ ] 1.2 Test the Assigned to me section from `GET /auth/me` and `GET /case?assignee=`, including the no-identity message
- [ ] 1.3 Test the Unassigned and urgent section: URGENT and HIGH merged, closed cases left out
- [ ] 1.4 Test the role matrix (VIEWER no actions, MODERATOR Assign and Close, ADMIN as MODERATOR) with a fake caller
- [ ] 1.5 Test that every row is a link to its entity URL and that "Show all" keeps the filter in the query
- [ ] 1.6 Implement the sections, the row actions with a ten-second undo for Assign, and the empty states
- [ ] 1.7 Add German and English strings, run `i18n.keys.test.ts`, `npm test` and the type check in `web/`
- [ ] 1.8 Open the code pull request titled `feat(web): turn the inbox into a work queue`

## 2. Navigation by job (`feat(web): group the navigation by job and add badges and shortcuts`)

- [ ] 2.1 Test the Moderation groups (Today, Work, Players, Protection) and that every destination has a unique icon
- [ ] 2.2 Test the badge text rules (zero hidden, 99+, last value kept on a failed read) in `navCounts`
- [ ] 2.3 Test the navigation bar on compact (five items with More), the collapsed rail on medium and the expanded rail on wide, as component tests with a fixed width
- [ ] 2.4 Test the `g` chords and `?` with a keyboard event fake, including the text-field exclusion
- [ ] 2.5 Test that the search clears on navigation and classifies `#A1B2C3D4` as a case
- [ ] 2.6 Replace the drawer with the rail components, add the bar, the icons and the chords; keep the switcher for ADMIN
- [ ] 2.7 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 2.8 Open the code pull request titled `feat(web): group the navigation by job and add badges and shortcuts`

## 3. Entity header (`feat(web): add the entity header with state, owner and breadcrumbs`)

- [ ] 3.1 Test the state mapping of case, appeal, report and player to text pills with a table test
- [ ] 3.2 Test the disabled-action reasons: the text is shown and `aria-describedby` points to it
- [ ] 3.3 Test the breadcrumb (route match, parts are links) for case, report and player
- [ ] 3.4 Test the cross-links of a case, an appeal, a report and a player with fake entities
- [ ] 3.5 Implement `EntityHeader` and use it on the case page (replacing the case bar of `add-case-workspace-tabs`) and the report and player pages
- [ ] 3.6 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 3.7 Open the code pull request titled `feat(web): add the entity header with state, owner and breadcrumbs`

## 4. List and detail panes (`feat(web): open cases and reports in list and detail panes`)

- [ ] 4.1 Test the URL change on select and Back returning to the list query, with the router in memory
- [ ] 4.2 Test that changing the open item does not request the list again (fake API call counter)
- [ ] 4.3 Test the `j`, `k`, `Enter` and `Esc` movement and focus return, with no wall-clock waits
- [ ] 4.4 Test the three widths: side by side, detail replacing the list, and separate pages
- [ ] 4.5 Implement the list-detail layout for `/cases` and `/reports`, with `aria-current` on the open row
- [ ] 4.6 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 4.7 Open the code pull request titled `feat(web): open cases and reports in list and detail panes`

## 5. URLs for view state (`feat(web): add shareable urls for view state and a name-free player lookup`)

- [ ] 5.1 Test the query parsing and the fallback for unknown `tab`, `status`, `priority` and `view` values, with a replace and no new history entry
- [ ] 5.2 Test the redirects `/appeals?show=all` to `?status=all` and `/cases/:reference/timeline` to `?tab=notes-audit`, keeping the query
- [ ] 5.3 Test that the player lookup calls the resolve endpoint and replaces the address with `/players/:owner`, and that a NOT_FOUND keeps the address
- [ ] 5.4 Test that no list filter is written to local storage or session storage (a fake storage that fails the test on write)
- [ ] 5.5 Implement the query parameters on the lists and the redirects in `router/index.ts`, keep every old path
- [ ] 5.6 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 5.7 Open the code pull request titled `feat(web): add shareable urls for view state and a name-free player lookup`

## 6. Appeal detail page (`feat(web): add the appeal detail page at /appeals/:id`)

Blocked until `add-staff-panel-read-endpoints` is merged (`GET /appeal/{identifier}`). Do not start the
code before that.

- [ ] 6.1 Test the appeal page content with a fake appeal read: appeal, punishment, case link, conversation and decision panel
- [ ] 6.2 Test that the decision panel is hidden for a viewer and that a decided appeal shows the decision text, not the form
- [ ] 6.3 Test the unknown id: a NOT_FOUND shows the not-found text and no appeal data
- [ ] 6.4 Implement the route `/appeals/:id` with the entity header, and link the appeals list rows to it; keep the existing decision dialog
- [ ] 6.5 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 6.6 Open the code pull request titled `feat(web): add the appeal detail page at /appeals/:id`

## 7. Resolve and undo (`feat(web): resolve reports on their page and undo case closing`)

- [ ] 7.1 Test that the report page offers Resolve to a MODERATOR, with the template list from `GET /templates`, and submits `POST /report/:id/resolve`
- [ ] 7.2 Test the case undo: close, then Undo within ten seconds sends the update with the read version; a 409 shows the reload text
- [ ] 7.3 Test Reopen restores the status found in the case timeline, and falls back to OPEN when none is found
- [ ] 7.4 Implement the report resolve action and the snackbar undo for close and reopen, keeping the appeal dialogs as they are
- [ ] 7.5 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 7.6 Open the code pull request titled `feat(web): resolve reports on their page and undo case closing`

## 8. Follow-up: changed by others (`feat(web): show cases and appeals changed by others in the inbox`)

Starts after `add-staff-panel-read-endpoints` is merged. Not one of the seven phases above.

- [ ] 8.1 Test the section with fake items that carry `lastActor`: rows of other staff only, the caller's own changes left out
- [ ] 8.2 Implement the section and its empty state
- [ ] 8.3 Strings, `i18n.keys.test.ts`, `npm test`, type check
- [ ] 8.4 Open the code pull request titled `feat(web): show cases and appeals changed by others in the inbox`
