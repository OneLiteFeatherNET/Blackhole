# Proposal

**Ships as:** `feat(portal)`. One code PR, titled `feat(portal): show the appeal as a messenger with file upload`.
The proposal PR is `docs(openspec): propose add-portal-messenger`. No other type is in this change.

## Why

The staff panel now shows a case conversation as a messenger. The player side of the same
conversation does not feel like one, and evidence comes too late:

- **The chat grows the page.** Thread and composer sit in one long column. With a longer exchange the
  player scrolls past everything to reach the answer box, and new replies arrive out of view.
- **The composer is a form.** It is a large card with a big text field and the attach button far from
  the text, so sending a screenshot does not look like the natural thing to do.
- **The appeal form takes no files.** A player who files an appeal has the proof at hand, but the form
  offers text only. The backend accepts a file only once the appeal is open, so the player has to
  know that the conversation afterwards is the place for it.

## What Changes

- **Messenger layout of the conversation**: the thread scrolls in its own area, the composer stays
  below it, and the view follows new messages unless the player scrolled up. Sending always lands at
  the end. Day dividers and bubbles stay.
- **Compact composer**: a small text field with the attach button next to it and the send button in
  the same row. Limits and format hints stay readable. Enter sends and Shift+Enter adds a line; on a
  touch device Enter stays a new line, because a phone keyboard has no Shift+Enter habit.
- **Files on the appeal form**: the player picks files while writing the statement, with the same
  checks and chips as the chat. They stay in the browser until the appeal is filed, then they are
  uploaded and sent as the first chat message, automatically. A file that fails stays in the
  composer with its reason, so nothing is lost silently.

Not breaking: portal only. No backend route, field, migration or client spec changes.

## Out of scope

- **New file types, sizes or limits.** The existing chat checks and the backend scan apply unchanged.
- **Staging a file before the appeal exists.** That would need a backend change and a store for files
  of appeals that are never filed.
- **Downloads and the staff panel.**
- **Storing a draft file beyond the page.** Files stay in memory, like the text draft.

## Capabilities

### New Capabilities

- `portal-messenger`: the messenger layout, the compact composer and the files on the appeal form.

### Modified Capabilities

None. It builds on `add-appeal-chat` and `add-appeal-portal`, which are not yet archived, and does
not edit their specs.

## Impact

- **Portal**: `ConversationView.vue`, `ChatComposer.vue`, `AppealFormView.vue`, `src/chat/`, strings.
- **Backend, API, migrations, client, Velocity, web**: none.
- **Depends on**: the merged appeal chat and its upload route.
