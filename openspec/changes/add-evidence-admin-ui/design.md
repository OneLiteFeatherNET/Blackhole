# Design

## Context

The UI (`add-admin-web-ui`, PR #173 branch `feat/admin-web-ui-v2`) is a Vue 3 + TypeScript SPA
with `web/src/components/md/` MD3 primitives (`MdButton`, `MdDialog`, `MdDataTable`,
`MdChip`, `MdProgress`, `MdSnackbar`...), a typed `openapi-fetch` wrapper in
`web/src/api/blackhole.ts`, vue-i18n (`locales/de.json`, `en.json`, with a key-parity test),
player detail components (`ActivePunishments.vue`, `PunishmentHistory.vue`) and
`AppealDecisionDialog.vue`. It is served same-origin behind nginx with `/api/` proxied to the
backend and a runtime `/config.json`.

## Goals / Non-Goals

**Goals:** reviewers see all evidence of a punishment where they decide; staff can add and
remove evidence without the API; previews come from the backend only.

**Non-Goals:** backend work; unfurling external links; editing items; mobile-specific layouts
beyond the existing responsive shell.

## Decisions

1. **One `EvidencePanel.vue`** (props: punishment identifier, `readonly`) used by player detail
   and `AppealDecisionDialog`. State via the existing `useResource` composable; paging by
   the API's page parameters. Subcomponents: `EvidenceList`, `EvidenceItem` (kind icon from
   Material Symbols already used by the rail), `AnticheatDetail`, `ChatLogDetail`,
   `FilePreview`, `EvidenceUpload`, `AddLinkDialog`, `DeleteEvidenceDialog`.
2. **Kind icons and labels** are a pure `evidenceKind.ts` map (kind -> icon, i18n key) with a
   unit test that every backend kind is mapped (types come from the generated `schema.d.ts`,
   so a new kind breaks `vue-tsc`).
3. **Links**: rendered as `<a href target="_blank" rel="noopener noreferrer">`; the UI
   re-validates `http(s)` before rendering an anchor and shows non-matching values as plain
   text (defence in depth against stored `javascript:` values). No `fetch` to the URL, no
   favicon/preview. A small external-link icon and the host name make the target visible.
4. **Preview via backend**: `<img src="/api/evidence/{id}/content">` and `<video controls
   preload="metadata">`. The browser sends no `X-API-VERSION` header for media; the backend's
   `default-version: 1` covers it. The element is shown only for image/video content types
   from the metadata. Backend headers (`nosniff`, `sandbox` CSP) already contain the response.
5. **Upload with progress**: `fetch` cannot report upload progress, so `uploadEvidence.ts`
   wraps `XMLHttpRequest` (`upload.onprogress`, `abort()` for cancel) and returns a typed
   result or the backend `Error` DTO. Several files upload sequentially to keep the backend's
   spool/temp usage bounded. The zone supports drag-and-drop and a keyboard-reachable file
   button. Pre-checks use limits and allowed types from `/config.json`
   (`BLACKHOLE_UI_EVIDENCE_*`, defaulting to the backend defaults) and are advisory only; the
   server is authoritative and its status codes map to messages: 413 too large, 415 type not
   allowed, 422 rejected (scan or personal data), 503 scanner unavailable.
6. **Chat transcript**: speakers arrive as owner hashes; the component resolves them through
   the existing player lookup store for display only, caching in memory for the open dialog.
   Unknown speakers render as "Player N". Nothing is written to local storage.
7. **Privacy in the UI**: the actor sent as `source` is the existing configured operator UUID;
   a banner on the panel states that opening content is audited (`VIEWED`/`DOWNLOADED`).
8. **Delete**: `MdDialog` confirm naming the item; on success the list refetches and a
   snackbar confirms. In appeal review, delete is allowed (same API) but visually secondary.
9. **i18n and a11y**: all strings in both locale files (existing parity test); drop zone has
   `role="button"`, visible focus ring, `aria-live` region for progress; video has captions
   disabled-note; image `alt` is the display name.
10. **No Flyway migrations**: this change adds none (UI only).

## Risks / Trade-offs

- **Inline media from an unauthenticated API**: same exposure as the rest of the UI, behind the
  network boundary.
- **XHR instead of the typed client for uploads**: one hand-written call; covered by a test
  that checks form fields and the target path against the generated schema keys.
- **Large videos in the browser**: `preload="metadata"` avoids prefetching the file.

## Migration Plan

Frontend-only; ship after `add-punishment-evidence`. Rollback: revert the UI commit.

## Open Questions

Whether to show the player-facing evidence count in the player detail header too (cheap: the
count endpoint exists).
