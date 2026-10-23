# Tasks

Tests follow F.I.R.S.T.: fakes for the backend gateway and players, a fixed `Clock`, no sleeps, no
network, no running proxy; backend tests use self-signed JWTs and an injected `Clock`; web tests use
Vitest with `fakeFetch`. Each numbered group is one pull request in a stack (each based on the
previous one). Run `./gradlew clean build` (web: also `npm ci`, `npm run build`, `npm test -- --run`,
the type-check) and `openspec validate --all --strict` before each PR.

## 1. `/report` (PR 1: `feat(velocity): report players with /report`)

- [x] 1.1 `ReportCategories` (words, aliases, suggestions, mapping to the backend categories); verify unit tests that every backend category has a word
- [x] 1.2 `ReportEvidence` attaches the reported player's redacted chat snapshot as `CHAT_LOG` evidence under the reported owner (no `onBehalfOf`, so no reporter UUID is stored) and returns the evidence id; `ReportService` refuses self-reports and bypass-protected players, requires text for `other`, passes the backend's HTTP 429 on; verify unit tests with a stub gateway, including "no cooldown in the plugin" and "a failed upload does not stop the report"
- [x] 1.3 `ReportCommand` (`/report <user> <reason> [details]`, no `@Permission`, explicit denial of `blackhole.report` refuses) and `ReportMessages` with en/de texts and the Blackhole prefix, no clicks for players; verify command and render tests and the bundle parity test
- [x] 1.4 OpenSpec change `complete-player-reports` (this change) with the `proxy-reports` delta; verify `openspec validate --all --strict`

## 2. Staff notification (PR 2: `feat(velocity): notify staff of new reports in game`)

- [ ] 2.1 `ReportNotice` wire message and codec, Redis channel `blackhole:report:created`; verify codec round trip and tolerance of unknown fields
- [ ] 2.2 `ReportNotifier` delivers a notice once to online players holding `blackhole.report.notify`, publishes via the channel and delivers locally if Redis is down; verify tests with a fake channel and fake audiences for "one delivery per proxy", "no permission, no message" and "Redis down"
- [ ] 2.3 Notice message with reporter, reported player, reason, short excerpt, "open" (suggests `/blackhole <player> info`) and "panel" (opens `panelUrl` + `/reports/<id>`, only when configured); config key `panelUrl` (http(s) only); verify render tests in en/de
- [ ] 2.4 `proxy-reports` delta extended; verify `openspec validate --all --strict`

## 3. Outcome for the reporter (PR 3: `feat(report): tell the reporter about the outcome`)

- [ ] 3.1 Flyway migration for `report_feedback`, entity, repository and `ReportFeedbackService` (create on resolve, list pending per owner, acknowledge, expire); verify service tests with a fixed `Clock`
- [ ] 3.2 `PROXY` routes `GET /report/feedback/{owner}` and `POST /report/feedback/{identifier}/ack`, sealed DTO, `*Api` interface, matrix row; verify controller tests with self-signed JWTs for allowed and refused roles
- [ ] 3.3 `report.feedback` event relayed to Redis `blackhole:report:feedback`; verify the payload carries hashes and ids only
- [ ] 3.4 Velocity delivers pending feedback on login and on the event, acknowledging each delivered row; verify tests with a fake gateway and players
- [ ] 3.5 Next free client spec version, `inputSpec`, web `generate:api`, docs-test filename and `BlackholeApplication` version bumped together; verify the build
- [ ] 3.6 Specs: `player-reports`, `proxy-reports`, `staff-authentication` (matrix) deltas

## 4. Report detail page (PR 4: `feat(web): add a report detail page`)

- [ ] 4.1 Route `/reports/:id`, linked from the list and the global search; verify router and search tests
- [ ] 4.2 Detail sections (report, evidence, other reports, active punishments and case links); verify Vitest tests with `fakeFetch`
- [ ] 4.3 "Apply template" (existing apply dialog, resolves as actioned) and "Dismiss" with a note through the existing resolve route; verify tests for both and for the 409 case
- [ ] 4.4 English and German texts, keyboard and screen reader behaviour; verify the i18n parity test
- [ ] 4.5 `admin-web-ui` delta; verify `openspec validate --all --strict`

## 5. Pull requests

- [ ] 5.1 Open the four pull requests, each stacked on the previous one, under the titles above and watch their checks
