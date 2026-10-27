# Tasks

Each numbered group is one PR, stacked in order (PR 1 on `main`, each later PR on the branch of the
previous one) and each with one Conventional Commits type. Haiku agents implement the work; the
commit and PR steps are run by a Sonnet agent per the session's subagent rule. Every test is written
and run red before the code it covers. Test commands: `cd web && npm test -- <file>` (Vitest) and
`cd web && npm run build` before a PR is opened. No test may wait on a timer or read the system clock.

## 1. PR: `feat(web): add the panel stack and side panel components`

- [ ] 1.1 Red: `web/src/components/panel/panelState.test.ts` - parse and serialise `panel` tokens;
  `client-rule:new` and `evidence:CASE-12,link:new` round-trip; a third token is dropped; an unknown
  kind is dropped; other query parameters are untouched. Table test, one behaviour per case.
- [ ] 1.2 Green: `web/src/components/panel/panelState.ts` (pure functions, no router import).
- [ ] 1.3 Red: `web/src/components/panel/usePanelStack.test.ts` - router from `createMemoryHistory`
  (fresh per test); open pushes a history entry; close replaces it; back closes only the top;
  forbidden kind is removed without a request (a fake API spy with zero calls).
- [ ] 1.4 Green: `web/src/components/panel/usePanelStack.ts`.
- [ ] 1.5 Red: `web/src/components/panel/SidePanel.test.ts` - header title, breadcrumb (current item
  last), back shown only with a parent, close emits `close`; `aria-modal="true"` only when the viewport
  class is `compact` (injected, not read from `window`); focus returns to the opener element; a dirty
  sheet emits `request-close` instead of `close`.
- [ ] 1.6 Green: `web/src/components/panel/SidePanel.vue` (uses `web/src/components/md/MdIconButton.vue`,
  `web/src/motion/MdTransition.vue`, `web/src/motion/useReducedMotion.ts`; 300 ms enter, 200 ms exit,
  reduced motion without transform).
- [ ] 1.7 Create `web/src/components/panel/useWindowClass.ts`: returns a ref of `compact | medium |
  expanded` from `matchMedia` at 600 px and 840 px, injectable for tests; test it with a stubbed
  `matchMedia` in `web/src/components/panel/useWindowClass.test.ts`.
- [ ] 1.8 Red: `web/src/components/panel/PanelStack.test.ts` - two tokens render two sheets in order;
  Escape closes the top sheet only; a registry without the kind renders nothing and does not throw.
- [ ] 1.9 Green: `web/src/components/panel/PanelStack.vue` (prop `registry: Record<string, Component>`).
- [ ] 1.10 Check the layout at 480 px, 720 px and 1280 px in the existing `ClientRulesView` with a
  test fixture (no new route). Record the result in the PR description.
- [ ] 1.11 Run `cd web && npm test` and `npm run build`. Green. Commit with
  `feat(web): add the panel stack and side panel components`.

## 2. PR: `feat(web): add the step flow and the help components`

- [ ] 2.1 Red: `web/src/components/guided/StepFlow.test.ts` - Next is blocked by a failing `validate`
  of the current step; errors list links to the field; Back keeps values; the step title is announced
  once through the live region (`aria-live="polite"`); the review slot receives `goTo`; a server error
  passed as `errors` returns the flow to the step named by the caller (`errorStep` prop).
- [ ] 2.2 Green: `web/src/components/guided/StepFlow.vue` (props and events as in `design.md` decision 10).
- [ ] 2.3 Red: `web/src/components/help/HelpTip.test.ts` - opens on focus and on Enter; the popover is
  the target of `aria-describedby`; Escape closes it; it stays open while the pointer is on the popover
  (use `pointerenter` and `pointerleave` events, no timers: the 300 ms hover delay is a prop `delay`
  set to `0` in tests).
- [ ] 2.4 Green: `web/src/components/help/HelpTip.vue`.
- [ ] 2.5 Red: `web/src/components/help/ConceptHelp.test.ts` - collapsed by default (native `details`
  without `open`); expanding shows definition, example and the link to `docs/panel-staff-guide.md#<anchor>`.
- [ ] 2.6 Green: `web/src/components/help/ConceptHelp.vue`.
- [ ] 2.7 Red: `web/src/components/help/glossary.test.ts` - every key in `CLIENT_RULE_CONCEPTS` has
  `definition` in `en` and `de`; the list covers the concepts of `admin-concept-help` (test lists them).
- [ ] 2.8 Green: `web/src/components/help/glossary.ts` (`CLIENT_RULE_CONCEPTS`, const list).
- [ ] 2.9 Add the `help.*` keys from `design.md` decision 11 to `web/src/locales/en.json` and
  `web/src/locales/de.json` (German written by the author; same meaning). `web/src/i18n.keys.test.ts`
  must be green (parity) with no change to that file.
- [ ] 2.10 Run `cd web && npm test` and `npm run build`. Green. Commit with
  `feat(web): add the step flow and the help components`.

## 3. PR: `refactor(web): create client rules in a guided sheet with concept help`

Precondition: `add-client-fingerprint-rules` is merged on `main`. Replaces the "Client rule editor"
requirement of that change (this change's `admin-guided-flows` and `admin-concept-help` requirements apply).

- [ ] 3.1 Red: `web/src/components/clientrules/ClientRuleWizard.test.ts` - five steps in the order of
  `design.md` decision 11; kind step shows `ConceptHelp` for every kind and the spoofability note for
  brand, mod and channel; TEMPLATE on BRAND blocks "Next" on step 3 until the acknowledgement is ticked;
  the review sentence names the consequence for each action; the final button reads "Create rule"; no
  field is editable on the review.
- [ ] 3.2 Green: `web/src/components/clientrules/ClientRuleWizard.vue` (new). Port the logic from
  `web/src/components/clientrules/ClientRuleEditor.vue` (`chooseKind`, `chooseAction`, acknowledgement
  reset, `clientRuleFieldErrors` from `web/src/components/templates/fieldErrors.ts`, suggestions, the
  CloudNet picker from `web/src/components/sections/CloudNetTargetPicker.vue`). Keep the test ids
  listed in `design.md` decision 11.
- [ ] 3.3 Red: `web/src/views/ClientRulesView.test.ts` - "New client rule" opens the sheet with
  `?panel=client-rule:new` and the rule is created through the review; "Edit" opens
  `?panel=client-rule:<key>`; the delete confirmation stays a dialog (`data-testid="confirm"`).
- [ ] 3.4 Green: `web/src/views/ClientRulesView.vue` registers `client-rule` in the `PanelStack`
  registry and removes its use of `ClientRuleEditor`. Delete `web/src/components/clientrules/ClientRuleEditor.vue`
  only after 3.2 and 3.3 are green.
- [ ] 3.5 Red-then-green: the `help.*` text appears next to each field of the wizard; a field with no
  help key fails `glossary.test.ts` (from task 2).
- [ ] 3.6 Run `cd web && npm test` and `npm run build`; check the flow at 480 px and 1280 px (record in
  the PR description). Commit with `refactor(web): create client rules in a guided sheet with concept help`.

## 4. PR: `refactor(web): edit templates in a guided sheet`

- [ ] 4.1 Red: `web/src/components/templates/TemplateEditor.test.ts` (new) - steps: name and section,
  escalation steps (duration, ELO cost, reason; each step a row, add and remove keep at least one row),
  review listing each step in order with its consequence; the save button is "Save template".
- [ ] 4.2 Green: `web/src/components/templates/TemplateEditor.vue` converted to `StepFlow` inside a
  `SidePanel` (`template` kind, token `template:new` or `template:<key>`). Keep the existing step-row
  logic (`newRow`, move up and down).
- [ ] 4.3 Red: `web/src/views/TemplatesView.test.ts` - "New template" opens `?panel=template:new`;
  delete still uses the confirmation dialog.
- [ ] 4.4 Green: `web/src/views/TemplatesView.vue` uses the panel stack; the delete dialog stays.
- [ ] 4.5 If `add-kick` is merged: the step kind `KICK` is a choice in the step row with its help
  (`help.kickStep`, a new key added to both bundles). If it is not merged, do not add the choice and
  say so in the PR description.
- [ ] 4.6 Run `cd web && npm test` and `npm run build`. Commit with
  `refactor(web): edit templates in a guided sheet`.

## 5. PR: `refactor(web): punish, kick and decide appeals in guided flows`

- [ ] 5.1 Red: `web/src/components/player/ApplyTemplateDialog.test.ts` (new or extended) - the punish
  flow: choose template (section kind filter first, "show all" switch), preview of the step, review,
  one call; `web/src/views/ReportDetailView.test.ts`: "Apply template" opens `?panel=apply:<reportId>`
  and makes one resolve call with `ACTIONED` and the template (the `admin-web-ui` MODIFIED requirement).
- [ ] 5.2 Green: `web/src/components/player/ApplyTemplateDialog.vue` becomes `StepFlow` inside a sheet
  (file name kept to limit churn); used from `web/src/views/ReportDetailView.vue` and
  `web/src/components/player/PlayerDetail.vue`.
- [ ] 5.3 Red: `web/src/components/kick/KickDialog.test.ts` - steps scope, target and reason, review
  with the outcome text; the chip shows `Delivered`, `Not on that server`, `Not online` or `No proxy
  answered` after submit (behaviour of `add-kick`, unchanged).
- [ ] 5.4 Green: `web/src/components/kick/KickDialog.vue` becomes a guided sheet (file name kept). Requires
  `add-kick` merged.
- [ ] 5.5 Red: `web/src/components/appeals/AppealDecisionDialog.test.ts` - steps: evidence, decision,
  consequence (the ELO and punishment effect as text), review; the final button names the decision.
- [ ] 5.6 Green: `web/src/components/appeals/AppealDecisionDialog.vue` becomes a guided sheet (file name
  kept). Used from `web/src/views/AppealsView.vue`.
- [ ] 5.7 Run `cd web && npm test` and `npm run build`. Commit with
  `refactor(web): punish, kick and decide appeals in guided flows`.

## 6. PR: `refactor(web): move the remaining dialogs to sheets or keep them as confirmations`

- [ ] 6.1 Red-then-green for each sheet, one test file each, next to the component:
  `web/src/components/player/AdjustDialog.vue` (sheet, explains the effect on ELO),
  `web/src/components/sections/SectionEditor.vue` (sheet),
  `web/src/components/evidence/AddLinkDialog.vue` (child of the evidence sheet, depth 2),
  `web/src/components/reports/ReportResolveDialog.vue` (sheet, report context visible),
  `web/src/components/account/AccountLinkDialog.vue` (sheet with the code steps; its nested unlink
  confirmation stays a dialog).
- [ ] 6.2 Guided flows for evidence and imports, with red tests first:
  `web/src/components/evidence/EvidenceDialog.vue` (case file: choose, order, review),
  `web/src/components/evidence/EvidenceShareDialog.vue` (choose lines, exact preview, confirmation; the
  requirement "Share dialog" of `add-appeal-chat` is replaced by this task's requirement; requires that
  change merged),
  `web/src/components/admin/VanillaImportPanel.vue` (template, preview, confirm; import requires a
  template per the `admin-web-ui` requirement "Import panel requires a template").
- [ ] 6.3 `web/src/views/ClientRulesView.vue`: the hit list becomes the sheet `client-rule-hits:<key>`
  (token), replacing the inline `hits-section`. Red test first in `web/src/views/ClientRulesView.test.ts`.
- [ ] 6.4 Keep as confirmation dialogs, and check each still uses `MdDialog` with an error-variant confirm
  for destructive actions: `web/src/components/player/RevokeDialog.vue`,
  `web/src/components/evidence/DeleteEvidenceDialog.vue`,
  `web/src/components/reports/ReportDismissDialog.vue` (one note field),
  `web/src/components/account/DiscordLinkSection.vue`, `web/src/components/account/DiscordLinksPanel.vue`,
  `web/src/components/account/StaffLinksPanel.vue`, `web/src/components/admin/ImportedPunishmentsPanel.vue`,
  `web/src/components/admin/StaffActorsPanel.vue`, `web/src/components/case/ParticipantErasure.vue`,
  `web/src/components/case/ReasonDialog.vue`, `web/src/components/case/RedactNoteDialog.vue`,
  `web/src/views/SectionsView.vue`. Add one test per file that the dialog opens and that Escape closes it
  (only where no test exists yet).
- [ ] 6.5 Run `cd web && npm test` and `npm run build`. Confirm the count of `<MdDialog` usages is 15 in
  the file list of `design.md` decision 12 (grep). Commit with
  `refactor(web): move the remaining dialogs to sheets or keep them as confirmations`.

## 7. PR: `docs`

- [ ] 7.1 Write `docs/panel-staff-guide.md`: one section per glossary concept (anchor names match
  `ConceptHelp` links), one section per guided flow (what each step asks, what the review means), and
  the dialog rule (when a confirmation is used). Plain language, no code. Follows the existing docs
  style in `docs/appeal-portal-staff.md`.
- [ ] 7.2 Add one line to the `tasks.md` of each in-flight change that adds a dialog (`add-kick`,
  `add-appeal-chat`, `add-appeal-portal`, `structure-case-evidence`): "New flows use the panel stack and
  guided flows of `adopt-sheets-and-guided-flows`." Docs only.
- [ ] 7.3 Run `openspec validate --all --strict` and `cd web && npm run build` (docs only; build must stay green).
- [ ] 7.4 Commit with `docs(web): add the staff guide for the panel concepts and flows`
  (the commit type is `docs`; the 7.2 lines go in a separate commit with `docs(openspec): link in-flight
  panel changes to the sheet pattern`).
- [ ] 7.5 Open the PRs for tasks 1 to 7, in order, stacked: each PR title is its commit title and each body
  ends with the session line. Task 1 targets `main`; each later PR targets the branch of the PR before it.
  Do not merge. Report the PR URLs. Run `gh pr checks --watch` on each.
