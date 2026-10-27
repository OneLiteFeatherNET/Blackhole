# Design

## Context

Observed on `main` (2026-10-27), `web/` only:

- 28 `MdDialog` instances in 27 `.vue` files (`grep -rn '<MdDialog' web/src`). Full list in the
  migration table below. `web/src/components/md/MdDialog.vue` is the only dialog primitive: a
  centred `role="dialog"`, `aria-modal`, focus moved in on open and returned on close, Tab trapped,
  Escape closes, `wide` for a larger width. It has no concept of a parent.
- `ClientRuleEditor.vue` (424 lines) is one dialog with about 18 controls: key, name, kind, patterns
  with suggestions, negate, hide-command, scope, server sections, target kind, servers or CloudNet
  picker, action, deny message, template, acknowledgement, enabled. Each `supporting-text` is one
  sentence from `en.json` `clientRules.*Hint`. Terms `brand`, `mod`, `channel`, `protocol`, `command`,
  `negate`, `scope`, `section`, `target kind` have no explanation beyond the hint.
- The client-rule model is in `openspec/changes/add-client-fingerprint-rules/design.md` (decisions 1
  to 7). Its "spoofable" rule (decision 5) is written only as a note in the editor
  (`clientRules.editor.spoofNote`, `punishWarning`, `acknowledge`).
- `ClientRulesView.vue:182` deletes with a dialog; the hit list of one rule is an inline section below
  the table (`hits-section`), which the redesign's "when to use what" table already says should be a
  side panel above ten rows.
- `ReportDetailView.vue:20` uses `ApplyTemplateDialog` for "Apply template" (the spec requirement
  "Apply a template to a report" names "the apply dialog"). `PlayerDetail.vue:14-15` uses
  `AdjustDialog` and `ApplyTemplateDialog` (punish). `ReportsView.vue:17` uses `ReportResolveDialog`,
  `AppealsView.vue:7` `AppealDecisionDialog`.
- Routes are flat with a `workspace` meta (`web/src/router/index.ts:41-68`); `keepQuery` already
  preserves query strings on redirects. Filters use URL state (`redesign-ux`, "Filter idiom and URL
  state").
- `web/src/motion/` has `MdTransition.vue` and `useReducedMotion.ts`; they are reused.
- In-flight changes add more dialogs: `add-kick` (kick dialog, mass kick), `add-appeal-chat` (share
  dialog), `add-appeal-portal` (leak and reissue dialogs), `structure-case-evidence` (canned reasons
  edit dialog). Their requirements are not yet in `openspec/specs/admin-web-ui/spec.md`.
- Translation: `add-i18n-platform` puts player-facing and content text (template names, reasons,
  section names, deny messages, kick reasons) into a backend catalog. Staff UI copy is in
  `web/src/locales/en.json` and `de.json`, and `i18n.keys.test.ts` checks key parity.

## Research (sources and what they support)

Verified by fetching or searching, 2026-10-27. Where a source was not reachable it is marked.

**Material Design 3 side sheets.** The m3.material.io pages `components/side-sheets/guidelines` and
`components/dialogs/guidelines` returned only their titles to our fetch (client-rendered), so their
body text could not be read here. The text used below comes from the Material Components pages:

- Side sheets are standard (on screen beside the primary content, for tablet and desktop) or modal
  (on mobile; dismissed before the content underneath can be used). Source: <https://material.io/components/sheets-side>
  (marked beta; the guidance may change) and the Android docs <https://developer.android.com/develop/ui/views/layout/canonical-layouts>.
- Window size classes used by MD: compact (below 600 dp), medium (600 dp or more), expanded (840 dp or
  more). Same source. A standard side sheet can be a third pane on extra-large windows.
- The same Android page says a supporting pane on medium and compact widths can be shown "in a bottom
  or side sheet accessible by means of a control such as a menu or button", and that a list-detail
  layout shows one pane at a time below expanded width.

**Dialogs versus sheets versus pages.** Material 2 dialog guidance (<https://m2.material.io/components/dialogs>,
via search) reserves dialogs for blocking, critical information that needs a decision or
acknowledgement. Primer (<https://primer.style/product/components/dialog/guidelines>, via search)
says dialogs with one or more input fields should use the full-screen variant on narrow screens, and
bottom sheets keep the user in the page context for secondary actions. Visa's design system
(<https://design.visa.com/components/dialog/usage/>, via search) advises against full-screen dialogs
for larger content and recommends embedding it in the workflow. The three sources disagree on
full-screen dialogs; they agree that a dialog is for a decision, not for a long form. This design
follows that common point and uses sheets for forms.

**Canonical layouts.** The Android canonical layouts page (above) defines list-detail, supporting
pane and feed. Supporting pane: primary about two thirds, secondary the rest, on expanded widths.
Secondary content "is meaningful only in relation to the primary content". SAP Fiori for Android
mirrors these layouts (<https://www.sap.com/design-system/fiori-design-android/v26-4/foundations/adaptive-layout/canonical-layouts>).

**Multi-step tasks.** No Material 3 stepper page was reachable. The material community uses Material 2
stepper guidance. Material UI notes that its stepper is no longer in the Material guidelines
(<https://v7.mui.com/material-ui/react-stepper.md>, a Material 2 reference). Angular Material's
stepper (<https://v15.material.angular.io/components/stepper>) is a common implementation: linear
or non-linear, a step indicator and Next and Back. A benchmark spec
(<https://cdn.jsdelivr.net/npm/@hegemonart/get-design-done@1.60.4/reference/components/stepper.md>,
secondary source) describes the purpose: how many steps exist, which are complete, current or
upcoming. Our choice: linear, because a rule's action depends on its kind, and a review step at the
end.

**Rich tooltips and help.** Nothing official about MD3 rich tooltips or progressive disclosure was
found by search. This design uses an info control with a popover and a native disclosure, which
follow WAI-ARIA disclosure and tooltip patterns, not an MD3 citation. This is a gap to check against
m3.material.io before task 2 is merged.

**Azure portal blades.**

- Blades open horizontally; from July 2015 a breadcrumb trail shows the blades a user has visited and
  clicking one brings it back into focus. Source: Microsoft's navigation update
  (<https://azure.microsoft.com/nl-nl/updates/navigation-improvements-to-the-azure-preview-portal/>,
  also in <https://mc.merill.net/message/AZ-navigation-improvements-to-the-azure-preview-portal>).
- Pros we take: the path stays visible, a parent is one click away, context of the parent is kept.
- Cons we avoid: horizontal sprawl ("blades can go well off of screen", Petri,
  <https://petri.com/azure-portal-life-hacks/>); lost context with horizontal scroll and a refresh that
  drops the user into a deep view (Atomic Object design review, <https://spin.atomicobject.com/?p=163085>);
  users close blades to find things and restart their task. The sources are from 2015 and are
  anecdotal; the current portal was not checked (the current Azure dashboard docs,
  <https://learn.microsoft.com/en-us/azure/azure-portal/azure-portal-dashboards>, do not describe blades).
- A SaaS breadcrumb guide (<https://www.saasui.design/blog/saas-breadcrumb-navigation-ux-patterns>)
  notes that a trail based on the path taken can differ from the resource hierarchy. We show the
  stack as a trail of the sheets open now, not of history, and limit the depth to two so the trail
  stays short.

**Real products.** Verified only in part:

- Google Admin console (2015 Material redesign with a guide to its navigation and filters,
  <https://workspaceupdates.googleblog.com/2015/01/a-new-look-for-google-apps-admin-console.html>).
  The side-panel detail was not verifiable.
- Android Studio, Google Cloud console and Firebase console were searched. We found no official
  description of their stacked detail panels, so they are not cited as evidence. Their current
  behaviour must be checked by hand before a claim is made about them.
- Android's own implementation (SlidingPaneLayout for list-detail) is the verified reference for
  "one pane at a time below expanded width".

Conclusion from the research: MD3 supports side sheets for create, edit and detail work, and dialogs
for decisions. The stacked blade model works only with a shallow depth, a visible trail and a
keyboard-usable back action. Those are the design choices below.

## Goals / Non-Goals

Goals:
- A moderator keeps the page they work from visible while editing, punishing, kicking, sharing evidence
  or deciding an appeal.
- Every multi-step task shows its steps, explains each one, validates each one and ends with a review.
- Client rule terms are explained where they are used, in plain language, in both languages.
- Dialogs are used only for confirmations.
- The change is shippable in small PRs, each leaving the panel working.

Non-Goals:
- Backend, API, client spec, Velocity or Discord changes.
- A new UI library (no Vuetify, no headless library). Components are built from `web/src/components/md/`
  and the `redesign-ux` tokens.
- Changing the client-rule model or the punishment model.
- Making help texts translatable through the backend catalog (decision 3).

## Decisions

### 1. Panel stack is the default for create, edit and detail

A create, edit or detail task opens a `SidePanel`. The page stays rendered behind it.

Confirmations (destructive or single-question) keep `MdDialog`. The rule for the migration table:
a dialog only when the whole task is "yes or no" or a single required note.

### 2. Depth: two levels (one child), recommended default

A side sheet may open one child sheet. A child cannot open a further child. Reasons: the trail stays
readable in 320 px to 480 px; the Azure failure mode (blades off screen) starts at about the third
blade. If a third level is needed, it is a page, not a sheet. Open question 2 asks whether to allow
three.

### 3. URL state

The state is one query parameter, `panel`, a comma-separated list of tokens from parent to child:

```
?panel=client-rule:new
?panel=client-rule:brand-block
?panel=evidence:CASE-12,link:new
```

Token grammar: `<kind>:<target>`, where `kind` is a registered sheet kind (`[a-z-]{1,32}`) and
`target` is `new`, a key or an id (`[A-Za-z0-9._-]{1,64}`). At most 2 tokens; a third is dropped
with the URL rewritten. Unknown kinds, unknown ids and kinds the role may not use are removed from the
URL without a request. Opening a sheet pushes a history entry (Back closes it); closing replaces the
entry. Other query parameters, including `FilterToolbar` filters, are kept. The parse and serialise
functions are pure (`panelState.ts`) so they are unit-tested without a router.

Why a query parameter and not nested routes: nested routes would split each page into many
components and would change the existing `/admin/...` routes that `redesign-ux` relies on. A query
parameter adds no route, keeps `keepQuery` redirects working and keeps the page component mounted.

### 4. Widths and size classes

| Size class (Material, window width) | Sheet | Behaviour |
|---|---|---|
| compact, below 600 px | full viewport, modal | `aria-modal`, focus trap, page inert, close control top right |
| medium, 600 px to 839 px | standard side sheet, 400 px | page visible and interactive; no focus trap, no `aria-modal` |
| expanded, 840 px and wider | standard side sheet, 480 px | page interactive; a covered parent shows as a 48 px strip |

The compact breakpoint is the Material one (600 px). The repository's Tailwind breakpoints
(`sm` 640 px, `md` 768 px, `lg` 1024 px) are not used for sheets; `useWindowClass()` reads
`matchMedia('(min-width: 600px)')` and `matchMedia('(min-width: 840px)')` and is injected in tests.
`redesign-ux` uses 1024 px as "desktop" for the aside; sheets use the MD3 values on purpose and the
aside keeps its value. Task 1 records this in a comment.

### 5. Focus, keyboard and dismissal

- On open: focus moves to the first focusable control of the sheet, or to its heading if none.
- On close: focus returns to the element that opened the sheet, or to the parent's first control when
  that element was removed.
- Escape closes the top sheet only. A dialog above a sheet handles its own Escape first (the existing
  `MdDialog` already stops propagation).
- Dirty sheets: closing (Escape, close, back, strip) with changed input asks a confirmation dialog
  "Discard changes?" with the destructive button "Discard" and the default "Keep editing".
- Tab order across the stack: the top sheet is first in the DOM order after the page header; the
  covered parent is inert on compact and keeps its order on expanded (the strip is focusable with a
  label "Back to <parent title>").
- `aria-modal="true"` only on compact. On medium and expanded the sheet has `role="complementary"`
  with `aria-label` = title, and the page stays operable. This follows the MD sheet split (standard
  vs modal) and the requirement in `admin-panel-stack`.

### 6. Reduced motion

The slide-in is 300 ms (enter, emphasised decelerate) and 200 ms on exit (MD3 motion values from
`web/src/motion/tokens`). With `useReducedMotion()` true the sheet appears with no transform and a
100 ms opacity change. No test waits on a timer: the transition is replaced by a stub in tests
(`MdTransition` is already stubbed in `motion/wiring.test.ts`).

### 7. Guided flow (`StepFlow`) inside a sheet

- Linear, because later steps depend on earlier ones (rule kind decides the action options;
  template steps depend on the chosen template). Non-linear jumps go through the review.
- Each step: a title, a one-sentence purpose, its fields, field help (`HelpTip`), and a per-step
  validation function run only on "Next".
- Progress: a row of step labels with the current step as `aria-current="step"`, completed steps with a
  check icon and the text "done", and the text "Step 2 of 5" for screen readers.
- Live region: `aria-live="polite"` announces "Step n of m: <title>" once when the step changes; focus
  moves to the step heading.
- Review: a plain-language summary sentence per choice, the consequence sentence of the final action,
  one edit link per line that jumps to the step, and the single primary button named by its verb
  ("Create rule", "Save template", "Apply punishment", "Send kick", "Share with player", "Decide appeal").
- Server errors: shown in the review; a field error sends the flow back to that step.

### 8. Concept help

Three pieces, all keyboard-usable:

- `HelpTip`: an icon button labelled "More about <term>" with `aria-describedby` pointing at a
  popover. Opens on focus, click and hover (with a 300 ms delay on hover), closes on Escape, blur and
  pointer leave (not while the pointer is on the popover).
- `ConceptHelp`: a native `<details>` element "What is this?" with the definition, one example and a
  link "Read in the staff guide" to `docs/panel-staff-guide.md#<anchor>` (task 7).
- Supporting text stays under each field; `HelpTip` adds the longer explanation, not a repetition.

The spoofability warning is not only in the review. It appears (a) next to the kind choice for
brand, mod and channel, (b) in the action step when TEMPLATE is chosen, (c) in the review with the
acknowledgement, as in `admin-concept-help`.

### 9. Help texts: app bundles, not the translation catalog (decision 3)

Decision: the help texts live in `web/src/locales/en.json` and `de.json`, keyed
`help.<concept>.definition|example|link`.

Justification:
- The catalog (`add-i18n-platform`) is for text the proxy and the Discord bot show to players, and for
  content staff create (template names, deny messages). Its values are loaded by the proxy from the
  backend, versioned, and validated with MiniMessage. Staff UI copy is none of these. Putting it there
  would make every panel string depend on a live backend fetch and an editing screen.
- The bundles ship with the code, so a help text changes with the feature it explains (a new kind
  needs a new definition in the same PR), and `i18n.keys.test.ts` already enforces key parity for
  `en` and `de`.
- The panel already loads `en` and `de` at build time; no new runtime fetch, no new failure mode.
- Cost accepted: a text fix needs a deploy. For a staff panel with a small audience that is acceptable.

Caveat: the proxy's in-game deny messages are a different text and stay in the catalog
(`clientrule.deny.<id>`) as `add-i18n-platform` defines.

### 10. Components

All under `web/src/components/`, built on `md/` components and the `redesign-ux` tokens (`text-title-*`,
`bg-surface-container-*`, `rounded-md-*`, spacing scale on 4 px).

**`panel/PanelStack.vue`**

- Props: `registry: Record<string, Component>` (sheet kind to component), `viewportClass?` (for tests).
- Reads the `panel` query through `usePanelStack()`; renders one `SidePanel` per token, the component
  from `registry`, with props `target` and `depth`.
- Unknown kinds or forbidden kinds: dropped by `panelState.ts` before render.

**`panel/usePanelStack.ts`** (composable)

- `stack: ComputedRef<{kind: string; target: string}[]>`
- `open(kind, target, { child?: boolean })`: pushes; with `child` and a full stack, returns `false`.
- `close()`: removes the top token (replace). `back()`: `router.back()` when the entry came from the
  stack, else `close()`. `clear()`.
- Pure parse and serialise in `panel/panelState.ts`.

**`panel/SidePanel.vue`**

- Props: `title: string`, `breadcrumb: string[]`, `hasParent: boolean`, `covered?: boolean`,
  `dirty?: boolean`, `labelledBy?: string`.
- Emits: `back`, `close`, `request-close` (when dirty; PanelStack shows the discard dialog).
- Slots: default (body), `actions` (footer, sticky, like `MdDialog`'s actions slot).
- Header: back icon button (when `hasParent`), title (`h2`), close icon button, breadcrumb below the
  title as an ordered list with the current item last.

**`guided/StepFlow.vue`**

- Props: `steps: { id: string; title: string; purpose: string; validate?: () => boolean | Promise<boolean>; }[]`,
  `modelValue: number` (current index, `v-model`), `finalLabel: string`, `busy?: boolean`,
  `errors?: string[]`.
- Emits: `update:modelValue`, `submit`, `cancel`.
- Slots: `step-<id>` for each step's fields; `review` (receives `goTo(index)` for edit links).

**`help/HelpTip.vue`**

- Props: `text: string` (may be empty when `term` is given), `term: string` (accessible name "More
  about <term>"), `placement?: 'top' | 'bottom'`.
- Renders an icon button, `aria-describedby` to a `role="tooltip"` element.

**`help/ConceptHelp.vue`**

- Props: `concept: string` (a key of `help/glossary.ts`).
- Renders `<details>` "What is this?" with definition, example, link.

**`help/glossary.ts`**

- `export const CLIENT_RULE_CONCEPTS = [...] as const` with every concept in `admin-concept-help`.
  The test compares it with the editor's option lists, so a new option without help fails.

### 11. Client rule flow (the main pain point)

Task 3 replaces `ClientRuleEditor.vue` (424 lines) with `clientrules/ClientRuleWizard.vue` in a
`client-rule` sheet. The old editor's logic (`chooseKind`, `chooseAction`, the acknowledgement reset,
`clientRuleFieldErrors`, the catalogue suggestions, the CloudNet picker) moves into the wizard's steps
without behaviour change, and the existing test ids (`new-client-rule`, `edit-<key>`, `spoof-note`,
`ack`, `section-<key>`, `suggest-<name>`) are kept so `ClientRulesView.test.ts` changes only where a
step must be opened first.

Steps:

1. **What it matches.** Key and name (key fixed after saving), kind select with `ConceptHelp` for
   each kind, the spoofability statement for brand, mod and channel, pattern input with suggestions,
   negate (brand and protocol only), protocol range for protocol.
2. **Where it applies.** Whole network or limited to servers; for limited: server sections (checkboxes),
   target kind, servers or CloudNet names.
3. **What happens.** Action select (NOTIFY, DENY, TEMPLATE) with a plain sentence for each; deny message
   for DENY; template for TEMPLATE; the acknowledgement for TEMPLATE on a spoofable kind, which blocks
   "Next" until ticked; hide-command for COMMAND.
4. **Switch on.** Enabled (edit only). Hint: "New rules start with Notify, so the hit count shows what
   it would do before you deny or punish."
5. **Review.** Example summary sentence, e.g. "When a client whose brand matches `fabric*` connects to
   a server in section `lobby`, the team is notified. Nothing else happens." and the consequence
   sentence for the action (DENY: "The player is refused and sees: <message>."; TEMPLATE: "The
   punishment template <key> is applied. The player can appeal it."). Final button "Create rule".

Default action preselected: NOTIFY (as now).

**Help texts (English drafts; German to be written by the author of task 2, same meaning).**

| Key | Definition | Example |
|---|---|---|
| `help.rule` | A rule checks each player's client against one condition. When it matches, the action you choose runs. | "Brand fabric matches, so the team is notified." |
| `help.key` | A permanent short name for the rule, used in logs and links. It cannot be changed after saving. | `fabric-brand` |
| `help.kind.brand` | The name the game client sends about itself, for example `vanilla` or `fabric`. Any client can send any name, so a brand can be faked. | `fabric*` |
| `help.kind.mod` | The id of a mod in the Forge mod list. Only Forge up to version 1.12 sends that list, so most clients have none. It can be faked. | `jei@*` |
| `help.kind.channel` | A plugin channel is a named data channel a mod registers with the game. Its name hints at the installed mods. It can be faked. | `meteor-client:*` |
| `help.kind.protocol` | The number of the network protocol the client speaks. It identifies the game version, not a version name. The proxy observes it, so the client cannot fake it. Use the same number twice for one version. | 767 to 767 |
| `help.kind.command` | The first word of a command the player types, without the slash. Commands forwarded to a server count too. The proxy observes it. | `op` |
| `help.pattern` | A name to match. `*` stands for any text, `?` for one character. Capitals do not matter. | `fabric*` matches `fabric-1.21` |
| `help.negate` | Turns the match around: the rule applies to every value except the listed ones. A player whose client sends no brand is not affected, so such a rule can be avoided by sending no brand. | "Everything except vanilla" |
| `help.scope` | Whole network applies on every server. Limited to servers applies only to the sections or servers you pick. | |
| `help.section` | A server section defined on the Sections page. Changing the section changes the rule too. | `lobby` |
| `help.targetKind` | If CloudNet runs your servers, pick the CloudNet task, group or service the rule means. Pattern means server names. | |
| `help.action.notify` | Records the hit and tells online staff. The player is not affected. Start here: the hit count shows what the rule would do. | |
| `help.action.deny` | Also refuses the connection or command, with the message you write. | |
| `help.action.template` | Also applies a punishment template automatically. The player can appeal it, like every automatic punishment. | |
| `help.denyMessage` | The text the player sees when refused. English, plain, up to 200 characters. | |
| `help.template` | The punishment template applied on a hit. It is applied only once while the player has an active punishment from this rule. | |
| `help.spoofable` | Brand, mod and channel values come from the client. A hit is evidence that the client sent the value, not proof of cheating. Confirm that you accept a punishment on a false hit before you save. | |
| `help.hideCommand` | Hides the command from the suggestions the player sees. The action still applies. Cosmetic only. | |
| `help.enabled` | A disabled rule is not applied and records no hits. | |
| `help.hit` | One occurrence of a rule matching a player, recorded for the team. | |
| `help.enforced` | Enforced: the proxy refused the connection or command. Not enforced: the rule matched, the proxy could not refuse it (for example a signed command with SignedVelocity installed), and only the notice was sent. | |
| `help.bypassed` | A staff member with the bypass permission matched the rule. The hit is recorded; no action is applied to them. | |

The glossary text is the source for `admin-concept-help`'s "Glossary is complete" requirement.

### 12. Migration table

Columns: file and line (current), purpose, controls (approximate count of form controls in the file,
from grep), multi-step, target. Target: **Guided** = guided sheet (`StepFlow`), **Sheet** = plain side
sheet, **Child** = child of a sheet, **Dialog** = kept as a confirmation.

| # | File:line | Purpose | Controls (approx.) | Multi-step | Target | Task |
|---|---|---|---|---|---|---|
| 1 | `components/player/RevokeDialog.vue:52` | Revoke a punishment | 0 (confirm) | no | Dialog | 6 (kept) |
| 2 | `components/appeals/AppealDecisionDialog.vue:94` | Decide an appeal | 4 | yes: evidence, decision, consequence, confirm | Guided | 5 |
| 3 | `components/player/AdjustDialog.vue:86` | Adjust a punishment or ELO | 2 | no | Sheet (with explanation of the effect) | 6 |
| 4 | `components/player/ApplyTemplateDialog.vue:117` | Punish: pick a template, preview, apply (also used by the report page) | 2 + preview | yes | Guided | 5 |
| 5 | `components/sections/SectionEditor.vue:178` | Create or edit a server section | 14 | no | Sheet (guided later, follow-up) | 6 |
| 6 | `components/kick/KickDialog.vue:125` | Kick: scope, target, reason, outcome | 2 | yes | Guided | 5 |
| 7 | `components/clientrules/ClientRuleEditor.vue:252` | Create or edit a client rule | 18 | yes | Guided (`ClientRuleWizard`) | 3 |
| 8 | `components/evidence/AddLinkDialog.vue:60` | Add a link as evidence | 3 | no | Child of the evidence sheet | 6 |
| 9 | `components/evidence/DeleteEvidenceDialog.vue:53` | Delete evidence | 0 | no | Dialog | 6 (kept) |
| 10 | `components/evidence/EvidenceDialog.vue:16` | Evidence picker for a case file | picker | yes: choose, order, review | Guided (case file) | 6 |
| 11 | `components/evidence/EvidenceShareDialog.vue:174` | Share evidence with the player (preview of the exact text, confirmation) | 3 + preview | yes | Guided | 6 |
| 12 | `components/reports/ReportDismissDialog.vue:48` | Dismiss a report with one note | 1 | no | Dialog | 6 (kept) |
| 13 | `components/reports/ReportResolveDialog.vue:75` | Resolve a report (context visible) | 3 | no | Sheet | 6 |
| 14 | `components/account/AccountLinkDialog.vue:130` | Link a Minecraft account with a code | 0 | yes: code, confirm | Sheet | 6 |
| 15 | `components/account/AccountLinkDialog.vue:180` | Confirm unlink (nested) | 0 | no | Dialog | 6 (kept) |
| 16 | `components/account/DiscordLinkSection.vue:145` | Confirm Discord unlink | 0 | no | Dialog | 6 (kept) |
| 17 | `components/account/DiscordLinksPanel.vue:83` | Confirm Discord unlink (list) | 0 | no | Dialog | 6 (kept) |
| 18 | `components/account/StaffLinksPanel.vue:77` | Confirm staff unlink | 0 | no | Dialog | 6 (kept) |
| 19 | `components/admin/ImportedPunishmentsPanel.vue:102` | Confirm applying imported punishments | 1 | no | Dialog | 6 (kept) |
| 20 | `components/admin/StaffActorsPanel.vue:82` | Anonymise a staff actor (erasure) | 0 | no | Dialog (destructive) | 6 (kept) |
| 21 | `components/admin/VanillaImportPanel.vue:222` | Confirm a vanilla ban import (template, preview) | 6 | yes | Guided | 6 |
| 22 | `components/case/ParticipantErasure.vue:70` | Erase a participant (destructive) | 0 | no | Dialog | 6 (kept) |
| 23 | `components/case/ReasonDialog.vue:58` | One reason for a case action | 1 | no | Dialog | 6 (kept) |
| 24 | `components/case/RedactNoteDialog.vue:49` | Redact a note (destructive) | 1 | no | Dialog | 6 (kept) |
| 25 | `components/templates/TemplateEditor.vue:172` | Create or edit a template with escalation steps | 7 + step rows | yes | Guided | 4 |
| 26 | `views/ClientRulesView.vue:182` | Delete a rule (confirm) | 0 | no | Dialog | 3 (kept) |
| 27 | `views/SectionsView.vue:130` | Disable a section (confirm) | 0 | no | Dialog | 6 (kept) |
| 28 | `views/TemplatesView.vue:129` | Delete a template (confirm) | 0 | no | Dialog | 4 (kept) |

Totals: 28 instances. Guided 8 (rows 2, 4, 6, 7, 10, 11, 21, 25). Sheet or child 5 (rows 3, 5, 8, 13,
14). Dialog kept 15 (rows 1, 9, 12, 15-20, 22-24, 26-28). The control counts come from a grep of
form-control tags and are approximate; they only order the work.

Not a dialog today but moved: the client rule hit list (`ClientRulesView.vue`, `hits-section`) becomes
a sheet `client-rule-hits:<key>` (task 6), because the redesign's rule for more than ten rows is a side
panel.

### 13. Dependencies and ordering

- Before task 1: `redesign-ux` tokens and `MdDialog` exist on `main` (they do).
- Before task 3: `add-client-fingerprint-rules` is merged (the editor it replaces is on that branch,
  not on `main`). If it is not merged, task 3 is blocked and task 4 may go first.
- Before task 5 (kick): `add-kick` is merged. Its kick dialog is the file in row 6.
- Before task 6 (share): `add-appeal-chat` and `structure-case-evidence` are merged, or row 11 is
  rebased onto them.
- Tasks 1 and 2 have no dependency on in-flight changes.

The in-flight changes that add dialogs after this change is merged must follow this pattern. Task 7
adds a line to each of their `tasks.md` (docs only).

### 14. Accessibility checklist (per component)

- Every sheet has an `h2` (title) and a labelled region.
- Focus order: header controls, body, footer; the strip of a covered parent is in the order of the
  stack, before the top sheet.
- Errors: `role="alert"` for field errors summary; field-level `aria-invalid` and `aria-describedby`.
- Colour is never the only signal (step state, acknowledgement, action tone uses text too).
- Touch targets 48 px on compact (`MdIconButton` already uses this).
- Motion respects reduced-motion (decision 6).

## Tests (F.I.R.S.T., Vitest)

Every test is written before the code it covers (red, then green). No `setTimeout`, no `sleep`, no
`Date.now()` (the transition stub and an injected viewport class replace them). No shared module state:
each test creates its own router (`createMemoryHistory`) and its own `panelRegistry`. Test files:

- `web/src/components/panel/panelState.test.ts`: parse and serialise; depth cap of 2; unknown kind
  dropped; the URL keeps other parameters. Table test, one behaviour per case.
- `web/src/components/panel/usePanelStack.test.ts`: open pushes a history entry; close replaces it;
  Back closes the top only; forbidden kind removed without a request.
- `web/src/components/panel/SidePanel.test.ts`: header, breadcrumb, back and close; `aria-modal` only
  on compact (viewport class injected); focus returns to the opener; dirty sheet asks before closing.
- `web/src/components/panel/PanelStack.test.ts`: two tokens render two sheets; Escape closes the top
  only; a third token is dropped.
- `web/src/components/guided/StepFlow.test.ts`: Next blocked by an invalid step; Back keeps values;
  step change is announced once; review shows the consequence sentence; a server error returns to the
  field's step.
- `web/src/components/help/HelpTip.test.ts`: opens on focus and Enter; `aria-describedby` set; Escape
  closes; stays open while hovered.
- `web/src/components/help/ConceptHelp.test.ts`: collapsed by default; expands; shows definition,
  example and link.
- `web/src/components/help/glossary.test.ts`: every option of `kindOptions`, `ACTIONS`, `scopeOptions`
  and `targetKindOptions` has a glossary entry; a missing entry fails with its name.
- `web/src/i18n.keys.test.ts`: extended with the `help.*` keys (existing parity test, no new file).
- `web/src/components/clientrules/ClientRuleWizard.test.ts`: step order; spoofable statement next to the
  kind choice; TEMPLATE on BRAND blocks "Next" until acknowledged; review names the consequence.
- Migration tests (one per migrated view, in its existing test file): the dialog count of the page is
  reduced and the flow opens a sheet with its token (for example `ReportDetailView.test.ts`:
  "Apply template opens the sheet with panel=apply:<id>").

## Risks / Trade-offs

- A query-parameter stack is less discoverable in the URL than routes. Accepted; the parser is small and
  tested.
- Sheets on expanded widths make the page narrower, so the wide tables on the client rules and players
  pages need the `FilterToolbar` wrap already in `redesign-ux`. Checked in task 1 with a fixture page.
- Guided flows are longer for simple tasks. The rule "a task with one or two fields stays a plain
  sheet" limits this.
- The help texts are a maintenance cost. The glossary test makes missing entries a test failure, not a
  silent gap.
- MD3 guidance could not be read in full; the design relies on the Material Components pages and
  sources marked as secondary. Task 2 requires a check against m3.material.io before merge.

## Migration Plan

Each task is one PR in the order of `tasks.md`. A PR must leave the panel working; a migrated dialog
is removed from its page in the same PR that adds its sheet. No feature flag. A rollback is a revert
of one PR.

## Open questions

1. **One change or two.** The Conventional Commits rule in `CLAUDE.md` says a change that mixes types
   is two changes. This change spans `feat`, `refactor` and `docs`. Recommended default: keep one change
   (one design) and ship each task as its own PR with its own type, as requested. If the user prefers the
   rule strictly, split into `adopt-panel-stack` (tasks 1, 2) and `adopt-guided-flows` (tasks 3 to 7).
2. **Depth 2 or 3.** Recommended default: 2. Three levels are a page, not a sheet. Revisit only if a real
   flow needs three.
3. **Help text location.** Recommended default: app bundles (decision 9). Revisit if staff ask to edit
   help texts without a deploy; then the catalog gets a `help.*` namespace.
4. **Ordering with in-flight changes.** Recommended default: task 3 waits for `add-client-fingerprint-rules`
   to merge; tasks 1, 2 and 4 do not wait.
