# Design

## Decisions

1. **Tabs are hidden, not unmounted.** `v-show` keeps conversation stream, polling and timeline
   alive, and `CaseView` keeps reloading them through its refs. Alternative: lazy tabs or child routes.
   Rejected, because a moderator on Overview would stop getting live messages and the reload wiring
   would have to know which tab exists.
2. **The tab is a query parameter set with `router.replace`.** A child route would change the
   route tests and the breadcrumbs for no gain; replace keeps Back returning to the list. An unknown
   value falls back to Overview.
3. **Overview is the default tab.** It shows what the case is about before anyone replies.
4. **Close is a separate action, the status list stays.** The bar does what `CaseHeader` does:
   `updateCase` with the read version and `status`. WAITING and IN_REVIEW remain in the list.
   Reopen sets `OPEN`. No confirmation, because Reopen undoes it at once.
5. **Enter sends only in the reply composer.** Editing an existing message keeps Enter as a new line;
   Ctrl/Cmd+Enter keeps working in both. Enter during IME composition does not send.
6. **Stick to the bottom only when already there.** On a new message or on opening the tab the thread
   scrolls down if the reader was within a small distance of the end. A reader who scrolled up keeps
   their place. A hidden thread has no size, so opening the tab scrolls down on the next tick.
7. **Thread height follows the viewport** (`dvh`), with a minimum, so the composer stays on screen on
   a phone as well.

## Risks

- **Enter sends by accident.** A staff message can be corrected or deleted, the sanitising server
  checks are unchanged, and a failed send keeps the text.
- **Conflict on Close.** Two moderators on one case: the second gets the same 409 notice and reload.
