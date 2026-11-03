import type { RouteRecordRaw } from 'vue-router'
import CaseMessageEditRoute from '../components/case/CaseMessageEditRoute.vue'
import CaseMessageRedactRoute from '../components/case/CaseMessageRedactRoute.vue'
import CaseNoteEditRoute from '../components/case/CaseNoteEditRoute.vue'
import CaseNoteNewRoute from '../components/case/CaseNoteNewRoute.vue'
import CaseNoteRedactRoute from '../components/case/CaseNoteRedactRoute.vue'
import EvidenceDeleteRoute from '../components/evidence/EvidenceDeleteRoute.vue'
import EvidenceItemView from '../views/EvidenceItemView.vue'
import { DIALOG_ROUTE } from './childRoute'

/*
 * The items of a case (`restructure-staff-panel`, decision 7, rows 2 to 5): child routes of `/cases/:reference`, which
 * render over the case page, and the evidence item page. Items inside the case page are addressed by `#event-<id>`
 * (`components/case/itemAnchor.ts`); a protocol has its own address. Nothing here sends a request on navigation: each
 * dialog sends its change only when it is confirmed. No route takes a Ban ID, a name or a secret (`routeTable.test.ts`).
 */

/** The child routes of the case page; the page renders them through its outlet while one is active. */
export const caseItemRoutes: RouteRecordRaw[] = [
  { path: 'notes/new', name: 'case-note-new', component: CaseNoteNewRoute, meta: { moderator: true } },
  { path: 'notes/:noteId/edit', name: 'case-note-edit', component: CaseNoteEditRoute, meta: { moderator: true } },
  { path: 'notes/:noteId/redact', name: 'case-note-redact', component: CaseNoteRedactRoute, meta: { admin: true } },
  { path: 'messages/:messageId/edit', name: 'case-message-edit', component: CaseMessageEditRoute, meta: { moderator: true } },
  { path: 'messages/:messageId/redact', name: 'case-message-redact', component: CaseMessageRedactRoute, meta: { admin: true } },
  // A protocol is read on its own address: the page opens that version and highlights it. Nothing is rendered here.
  { path: 'protocols/:evidenceId', name: 'case-protocol', component: DIALOG_ROUTE },
]

/** The evidence item page and its delete confirmation (ADMIN only, as the button that opens it). */
export const evidenceItemRoutes: RouteRecordRaw[] = [
  {
    path: '/evidence/:id',
    name: 'evidence-item',
    component: EvidenceItemView,
    props: true,
    meta: { titleKey: 'evidence.title', pageShell: true, workspace: 'moderation' },
    children: [{ path: 'delete', name: 'evidence-delete', component: EvidenceDeleteRoute, meta: { admin: true } }],
  },
]
