# Tasks

Prerequisite: `add-punishment-evidence` and `add-admin-web-ui` (PR #173) are merged. UI tests use
Vitest + Vue Test Utils with the existing `fakeFetch` helper: no network, no timers, a fresh
mount per test.

## 1. API layer

- [ ] 1.1 Regenerate `src/api/schema.d.ts` from the new client spec and add typed wrappers (list, count, get, delete, add JSON evidence); verify `vue-tsc --noEmit` and wrapper tests for paths, `X-API-VERSION` and `source`
- [ ] 1.2 Add `uploadEvidence.ts` (XHR with progress and abort); verify unit tests with a fake `XMLHttpRequest` for progress events, cancel and mapping of 413/415/422/503 to typed errors
- [ ] 1.3 Add evidence limits to `/config.json` handling with backend-default fallbacks; verify a unit test for defaults and overrides

## 2. Components

- [ ] 2.1 `evidenceKind.ts` (icon + i18n key per kind) and `EvidenceList`/`EvidenceItem`; verify component tests for each kind's icon, empty state and paging
- [ ] 2.2 `AnticheatDetail` and `ChatLogDetail` (speaker resolution via the lookup store, no persistence); verify tests incl. unknown speaker fallback
- [ ] 2.3 Safe link rendering (new tab, `noopener noreferrer`, non-http plain text); verify tests for `https:`, `javascript:` and `data:` values
- [ ] 2.4 `FilePreview` (img/video from the content endpoint, `preload="metadata"`); verify tests that only image/video content types render media
- [ ] 2.5 `EvidenceUpload` (drag-and-drop, picker, progress, cancel, advisory checks, per-file server errors); verify component tests with a drop event and a fake uploader
- [ ] 2.6 `AddLinkDialog` and `DeleteEvidenceDialog`; verify tests for scheme validation and that cancel sends no request

## 3. Integration into screens

- [ ] 3.1 Embed `EvidencePanel` in `ActivePunishments`, `PunishmentHistory` and `AppealDecisionDialog` (read-only variant in appeals except delete); verify view tests for each screen
- [ ] 3.2 Add German and English strings; verify the existing locale-parity test passes and a test that no evidence string is hard-coded
- [ ] 3.3 Accessibility pass (focus order, drop zone keyboard use, `aria-live` progress, contrast in light and dark); verify with component tests and notes in the PR

## 4. Verification and docs

- [ ] 4.1 Manual check against a local backend: upload a PNG and an MP4, preview both, open a link, delete with confirm, review in an appeal; record in the PR description
- [ ] 4.2 README UI section: evidence panel, `/config.json` limits; verify documented keys match the code

## 5. Pull request

- [ ] 5.1 Open the pull request titled `feat(web): show and manage punishment evidence in the admin ui` against `main`; verify PR lint, `npm test` and `openspec validate --all --strict` pass
