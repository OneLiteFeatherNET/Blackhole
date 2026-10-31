# Tasks

## 1. Tabs

- [ ] 1.1 Add an accessible tab component (roles, arrow keys, roving tabindex) with component tests
- [ ] 1.2 Move the case page content into the three tabs with `v-show`, keep the tab in `?tab=`, with view tests

## 2. Case bar

- [ ] 2.1 Add the case bar with status, punishment state and Close/Reopen, including the conflict case, with component tests

## 3. Messenger

- [ ] 3.1 Rebuild the conversation layout: scroll area, composer below, bubbles, day dividers, slim ticket strip, collapsed erasure tools; keep the existing tests green
- [ ] 3.2 Stick-to-bottom behaviour with tests through an injected or stubbed scroll element, no wall-clock waits
- [ ] 3.3 Enter sends in the reply composer, with composer tests for Enter, Shift+Enter, IME and the edit composer
- [ ] 3.4 Count unseen messages on the Conversation tab, with view tests

## 4. Finish

- [ ] 4.1 German and English strings, `i18n.keys.test.ts` parity, `npm test` and the type check in `web/`
- [ ] 4.2 Look at the page in a browser in light and dark mode, on desktop and phone width

## 5. Code pull request

- [ ] 5.1 Open the code pull request titled `feat(web): split the case page into tabs with a chat` from `origin/main`, with the body from `.github/pull_request_template.md`, no file under `openspec/`, after the proposal PR `docs(openspec): propose add-case-workspace-tabs` is merged
