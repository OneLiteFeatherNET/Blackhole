# Proposal

**Ships as:** `feat(web): show and manage punishment evidence in the admin ui` (additive).

## Why

`add-punishment-evidence` makes evidence available through the API, but staff and appeal
reviewers work in the admin UI. Without a panel they cannot see an anticheat record, open a
replay, read the chat excerpt or view a screenshot while deciding, and cannot upload a
screenshot or remove wrong evidence without calling the API by hand.

## What Changes

- An **evidence panel** on the punishment (player detail: active punishments and history) and in
  the appeal decision dialog: list with a kind icon per item (anticheat, replay, chat log, link,
  file), source system, captured time and short summary; empty state; paging.
- Item detail: anticheat check and values table; chat excerpt as a read-only transcript
  (speakers shown as resolved names via the existing lookup, never persisted by the UI); links
  open in a **new tab with `rel="noopener noreferrer"`**; the UI never fetches link targets.
- **Inline preview** of uploaded images (`<img>`) and videos (`<video controls>`) from
  `GET /evidence/{id}/content` through the backend; range requests make scrubbing work.
- **Upload**: drag-and-drop zone and file picker with client-side type/size pre-checks (limits
  read from `/config.json`), per-file progress (XHR, since `fetch` has no upload progress),
  cancel, and server error messages (413/415/422/503) shown per file.
- **Add link / replay** form (kind, URL, description) with scheme validation.
- **Delete** with a confirm dialog that **requires a reason** (erasure request, wrong evidence, other, plus optional note); the backend audits it.
- **Lifted evidence** (punishment lifted by appeal, or revoked) is kept by the backend and **hidden by default**; an explicit toggle "aufgehobene Beweise anzeigen" / "Show lifted evidence" reveals it, each item labelled "Strafe per Appeal aufgehoben" (or "Strafe widerrufen"). Using the toggle is audited server-side; there is no auth layer, so this is a UI default, not an access control.
- Appeal review shows the valid evidence panel read-only plus the same toggle and delete.
- German and English strings; Material Design 3 components from `web/src/components/md/`.
- Light, dark and keyboard accessibility as in the rest of the UI.

Out of scope: any backend change (this change consumes the `evidence_*` API), proxy capture,
auth, link unfurling/preview (no server- or client-side fetching of external URLs), editing
evidence items, bulk operations.

## Capabilities

### New Capabilities

- `evidence-admin-ui`: the evidence panel, previews, upload and deletion behaviours in the UI.

### Modified Capabilities

None.

## Impact

- `web/` only: new `components/evidence/*`, API wrapper methods and an XHR upload helper,
  locale keys, tests, `/config.json` gains the upload limits.
- Depends on `add-punishment-evidence` (API, generated types) and on `add-admin-web-ui`
  (PR #173, branch `feat/admin-web-ui-v2`) being merged first.
