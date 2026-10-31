# Design

## Decisions

1. **Files on the form are uploaded after filing.** `/public/attachments` answers `NO_APPEAL` without
   an open appeal. The form keeps the picked `File` objects in the session drafts, so no backend change
   and no orphaned file. Alternative: stage first with a new temporary scope. Rejected, it needs a
   backend store and a sweeper for appeals that are never filed.
2. **The files are sent automatically as one message.** A player who attached proof and pressed Submit
   considers it delivered; a second Send would be missed and staff would never see the proof.
3. **A failed file stays visible.** The filing itself succeeded and is not undone. The conversation
   opens with the failed file as a chip with its reason in the composer; the ready files are sent.
4. **The filing is never held back by a file.** The precheck runs on picking. A refused file is shown
   and is not sent, the statement goes through.
5. **Enter on touch stays a new line** (`pointer: coarse`). Otherwise a phone player sends half a
   sentence with no way to edit it: players cannot change a sent message.
6. **Same follow rule as the staff panel**: follow only near the end, always after the own send.
   Height follows the viewport with `dvh` and a minimum.

## Risks

- **The tab closes between filing and upload.** The files were never stored and the appeal is filed
  without them. The player can attach them in the chat afterwards.
- **Enter sends by accident on desktop.** Players cannot edit a message. The text stays until the
  server accepted it, and a player can write a follow-up.
