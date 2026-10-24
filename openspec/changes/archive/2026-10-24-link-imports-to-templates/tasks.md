# Tasks

Three small stacked PRs, each squash-merged; the PR title is the Conventional Commit. Tests follow
F.I.R.S.T. (injected `Clock`, self-signed JWTs per role, Testcontainers for SQL, Vitest with a fake
fetch and no sleeps).

## 1. Import with a template (PR 1, `feat(import)`)

- [x] 1.1 Failing tests first: link to template, original reason kept, no score change, case event without step, mapping, dry run groups, MUTE rejection, unknown template, malformed mapping, idempotent re-import, role matrix
- [x] 1.2 `VanillaImportDTO` (Result, ReasonGroup, Error), `VanillaImportService` with up-front template validation, controller with `templateKey` and `reasonTemplates`
- [x] 1.3 Proposal, design and spec deltas of this change
- [x] 1.4 Open the pull request titled `feat(import): link imported bans to templates`

## 2. Re-template imported punishments (PR 2, `feat(punishment)`)

- [x] 2.1 Failing tests first: listing groups, retemplate by reason and by identifiers, idempotency, no score change, MUTE rejection, not-an-import and unknown ids skipped, never overwriting another template, case event with the token's actor, role matrix
- [x] 2.2 `punishment.retemplated` payload, draft and `CaseTimeline.retemplated`
- [x] 2.3 `ImportedPunishmentService`, controller and `*Api`, matrix row in `RouteCoverageIntegrationTest`
- [x] 2.4 Regenerate the client spec (next free number, bump `inputSpec`, web `generate:api`, docs test, `BlackholeApplication`)
- [x] 2.5 Open the pull request titled `feat(punishment): re-template imported punishments`

## 3. Admin UI (PR 3, `feat(web)`)

- [x] 3.1 Failing tests first: picker required, mapping table, preview shows templates, imported punishments section groups, confirmation, German and English
- [x] 3.2 API client methods and types, import panel changes, "Importierte Strafen" section
- [x] 3.3 Archive this change (`docs(openspec): archive link-imports-to-templates`), shipped with PR 3
- [x] 3.4 Open the pull request titled `feat(web): pick templates when importing and re-template imported bans`
