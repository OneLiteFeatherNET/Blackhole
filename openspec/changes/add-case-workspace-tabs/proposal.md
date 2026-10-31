# Proposal

**Ships as:** `feat(web)`. One code PR, titled `feat(web): split the case page into tabs with a chat`.
The proposal PR is `docs(openspec): propose add-case-workspace-tabs`. No other type is in this change.

## Why

The case page is one long column: facts, Ban ID, conversation, protocols, export, notes, related
cases and the full timeline. A moderator who works a case has three problems with it:

- **The reply is buried.** The conversation sits between the Ban ID panel and the protocols, grows
  the page with every message, and the composer lies at its end, below the whole thread.
- **The audit trail is mixed into the work.** Timeline and protocols are needed to look something
  up, not to work the case, but they share the page with everything else.
- **Closing takes a detour.** Closing means scrolling to the header, opening the status list and
  choosing a value. It is the most frequent last step of a case and has no action of its own.

## What Changes

- **Tabs at the top of the case page**: Overview (facts, status, assignee, priority, tags, Ban ID,
  related cases), Conversation, and Notes & audit (notes, timeline, protocols, transcript export).
  The active tab is in the URL (`?tab=`), so a link and a reload keep it. It holds no personal data.
- **A case bar above the tabs**, on every tab: the case status and the punishment state, and for a
  moderator one **Close** button (**Reopen** on a closed case). It uses the existing case update with
  its version check, so a conflict is reported as in the header and nothing is overwritten.
- **Conversation as a messenger**: a thread of a fixed height that scrolls on its own, the composer
  fixed below it, bubbles with the player on the left and staff on the right, one divider per day,
  and the view stays on the newest message unless the reader scrolled up. Enter sends and
  Shift+Enter adds a line in the reply composer. Ticket state and lock sit in a slim strip above the
  thread, the admin erasure tools move into a collapsed section below it.
- **New messages stay visible**: the Conversation tab shows a count of the messages that arrived
  while another tab was open. All tabs stay mounted, so the live stream keeps running.

Not breaking: web only. No route, field, event or migration changes. The client spec and the Velocity
plugin are unchanged.

## Out of scope

- **The player portal.** Its case page keeps its layout.
- **Closing the Discord ticket or deciding the appeal.** Close sets the case status, exactly as the
  status list does today. It does not touch the ticket, the appeal or the punishment.
- **A reason on close, and reply-and-close.** Reopen is the undo.
- **Closing from the case list.** That belongs to `add-case-bulk-actions`.
- **Backend changes**, including new events or read tracking per moderator: the tab count is local to
  the open page and starts at zero on every load.

## Capabilities

### New Capabilities

- `case-workspace`: the tabs, the case bar and the messenger view of the case page.

### Modified Capabilities

None. It builds on `add-punishment-case-timeline`, which is not yet archived, and does not edit its spec.

## Impact

- **Web**: `web/src/views/CaseView.vue`, `web/src/components/case/ConversationSection.vue`,
  `MessageComposer.vue`, a new tab component and a new case bar component, German and English strings.
- **Backend, API, migrations, client, Velocity**: none.
- **Depends on**: the merged case page. No dependency on the open `add-case-bulk-actions`.
