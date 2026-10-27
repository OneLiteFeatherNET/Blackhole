# Proposal

## Why

The staff panel opens a modal `MdDialog` for every task: punish, kick, share evidence, decide an
appeal, edit a template with escalation steps, edit a client rule. There are 28 dialog instances in
27 files (see `design.md`, migration table). A modal dialog blocks the page, hides the player, report
or case the moderator is working on, cannot be linked to, and loses the form when it is dismissed by
mistake. The largest form, the client rule editor, has about 18 controls and a branching structure
(kind, action, scope, acknowledgement) with no explanation of its terms.

Staff do not know what "brand", "mod", "channel", "protocol" or "command" mean in this context, what
`NOTIFY`, `DENY` and `TEMPLATE` do to a player, why a brand rule can be faked, or what "Negate" or
"Hide from tab completion" change. The current hints are one short line under each field. In the worst
case a moderator picks `TEMPLATE` on a brand rule without knowing that the punishment lands on a value
the client chose. The fix is layout (a stacked side sheet that keeps the context visible), guidance
(a step flow with a review step for the multi-part tasks), and explanation (concept help written for
non-technical staff).

## What Changes

- **Panel stack.** Create, edit and detail tasks open in a side sheet that slides in from the right
  over the current page. The page stays visible. A sheet can open one child sheet (maximum depth 2).
  Each sheet has a header with title, back or close and breadcrumb. Its state lives in the URL, so
  Back works and links can be shared. Full screen on compact widths, a standard side sheet on medium
  and expanded widths, modal (with focus trap and `aria-modal`) only where the sheet covers the page.
- **Dialogs are reserved** for short confirmations and destructive confirmations (delete, revoke,
  disable, unlink, leak, anonymise, redact). This follows Material Design 3's guidance that dialogs
  interrupt for a decision or an acknowledgement.
- **Guided flows.** Multi-step tasks use a step flow inside a sheet: a progress indicator, one
  explanation per step, validation per step and a review step before the final action. Used for:
  creating a client rule, creating or editing a template with escalation steps, punishing a player
  (template choice, preview, confirm), kicking, sharing evidence, presenting a case file and deciding
  an appeal.
- **Concept help.** A `HelpTip` (info icon with a rich tooltip or popover, keyboard reachable) and a
  `ConceptHelp` disclosure ("What is this?") with a plain-language definition, an example and a link to
  the staff guide. A glossary of client-rule concepts is written for non-technical staff. The texts
  are staff-facing UI copy in the `en` and `de` bundles (`web/src/locales/`), not in the translation
  catalog of `add-i18n-platform` (decision 3 in `design.md`).
- **Client rules** get a guided creation flow as the first slice (task 3 of `tasks.md`): the form is
  split into what to match, where, what happens, and review.
- **Migration** of all 27 dialog files to the target pattern (sheet, guided sheet or kept dialog), in
  the order of `tasks.md`.
- **Capabilities added** to the spec for the panel's navigation, guided flows and concept help.
- No backend, API, client-spec or Velocity change. No new UI library.

## Capabilities

### New Capabilities
- `admin-panel-stack`: stacked side sheets over the page, their depth limit, URL state, widths per
  window size class, focus handling, and the rule that only confirmations use a dialog.
- `admin-guided-flows`: the step flow used by multi-step tasks, its progress, per-step validation,
  review step, and the list of tasks that must use it.
- `admin-concept-help`: help tips, concept disclosures and the staff glossary for the panel's domain
  terms, and where their texts come from.

### Modified Capabilities
- `admin-web-ui`: "Apply a template to a report" moves from the apply dialog to the panel stack
  (the apply step opens as a sheet on the report page). Its requirement text changes; the resolve call
  does not.

Requirements that these changes supersede in changes that are not yet archived:
`Client rule editor` (`add-client-fingerprint-rules`), `Kick dialog on the player page and the case
page` (`add-kick`), `Share dialog` (`add-appeal-chat`) and `Case page sections and evidence picker`
(`structure-case-evidence`). This change is applied after those changes are merged, and each affected
task says which requirement it replaces.

## Impact

- `web/src/components/` (new `panel/`, `guided/`, `help/` directories; migrated dialog components),
  `web/src/views/` (`ClientRulesView.vue`, `TemplatesView.vue`, `SectionsView.vue`,
  `ReportDetailView.vue`, `PlayerDetailView.vue`, `CaseView.vue`), `web/src/router/index.ts` (query
  handling only), `web/src/locales/en.json` and `de.json`.
- `web/src/components/md/MdDialog.vue` stays as the confirmation primitive. No change to its API.
- Tests: Vitest, F.I.R.S.T., one test file per new component and per migrated flow.
- Other changes: `add-kick`, `add-appeal-chat`, `add-appeal-portal` and `structure-case-evidence`
  add dialogs that must follow this pattern once this change is merged (noted in their tasks by the
  follow-up of task 7).
- Users: staff only. No player-facing text changes. Players, the proxy and the API are unaffected.

## Out of scope

- Backend, API, generated client, Velocity plugin and database changes.
- Replacing the design tokens or the `redesign-ux` components (`PageShell`, `FilterToolbar`,
  `DataTable`, `RowMenu`). This change builds on them.
- Converting the section editor to a guided flow (it becomes a side sheet only; a guided flow for
  sections is a follow-up).
- Player-facing text and the Discord bot.
- Translation of the help texts through the catalog (see `design.md`, decision 3).
- Rule semantics. The client-rule model (kinds, actions, acknowledgement) stays as in
  `add-client-fingerprint-rules`; only how it is presented changes.
