# Tasks

## 1. Proposal (PR 1, `docs(openspec)`)

- [x] 1.1 Proposal, design, specs and tasks; `openspec validate --all --strict`

## 2. Backend (PR 2, `feat(appeal)`)

- [x] 2.1 Failing tests first: store (versions append, next number, concurrent save), service validation, filing 409 `CONDITIONS_CHANGED` for missing, older and newer version, precedence of `FORFEITED`/`FROZEN`/`NOT_ELIGIBLE`, stored `conditionsVersion` in the appeal metadata, route roles (matrix and route coverage), public route needs a session, migration test of the default row and the nullable column
- [x] 2.2 Migration `V19__appeal_conditions.sql` (next free version on `main` right before adding it; V18 is the Discord ticket migration): table and the default text as version 1
- [x] 2.3 `appealconditions/` package: store seam and JDBC implementation, `AppealConditionsService`, sealed DTO, `AppealConditionsApi` and thin controller; `GET /public/appeal/conditions` in `PublicApi`; `conditionsVersion` on `PublicAppealDTO.FileRequest`
- [x] 2.4 Update `.claude/rules` (public-api, security), regenerate the client spec (`client/specs/blackhole-api-0.0.N.yml`, next free number, bump `inputSpec`); verify `./gradlew :backend:test :client:compileJava`

## 3. Staff panel (PR 3, `feat(web)`)

- [x] 3.1 Failing tests first: API client methods, role gating, save creates a version, 409 reload, i18n parity
- [x] 3.2 Types, client methods, `AppealConditionsView`, route, navigation rail entry, de/en strings; verify `npm test` and the type check

## 4. Portal (PR 4, `feat(portal)`)

- [ ] 4.1 Failing tests first: file button disabled until confirmed, request carries the version, `CONDITIONS_CHANGED` reload, markup rendered as text, collapsible block on the conversation page, BFF allow-list
- [ ] 4.2 Contract, BFF allow-list, text renderer (paragraphs and bullets), `AppealFormView` and `ConversationView`, English strings; verify `npm test` and the type check

## 5. Wrap-up

- [ ] 5.1 Follow-up (separate change): when the Discord bot shows the conditions, require `conditionsVersion` on `POST /discord/tickets`
- [ ] 5.2 Archive with `docs(openspec): archive add-appeal-conditions` after the PRs merge
- [ ] 5.3 Open the pull requests as small stacked PRs (squash-merged, the title is the Conventional Commit): `docs(openspec): propose appeal conditions`, `feat(appeal): add versioned appeal conditions`, `feat(web): edit appeal conditions in the admin area`, `feat(portal): show and confirm appeal conditions`
