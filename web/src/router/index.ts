import { createRouter, createWebHistory, type RouteRecordRaw, type RouterHistory } from 'vue-router'
import AdminView from '../views/AdminView.vue'
import AuthCallbackView from '../views/AuthCallbackView.vue'
import AuthErrorView from '../views/AuthErrorView.vue'
import CasesView from '../views/CasesView.vue'
import ClientRulesView from '../views/ClientRulesView.vue'
import CaseView from '../views/CaseView.vue'
import InboxView from '../views/InboxView.vue'
import NotFoundView from '../views/NotFoundView.vue'
import NoAccessView from '../views/NoAccessView.vue'
import PlayerDetailView from '../views/PlayerDetailView.vue'
import PlayersView from '../views/PlayersView.vue'
import PunishedPlayersView from '../views/PunishedPlayersView.vue'
import AppealConditionsView from '../views/AppealConditionsView.vue'
import AppealsView from '../views/AppealsView.vue'
import ReportDetailView from '../views/ReportDetailView.vue'
import ReportsView from '../views/ReportsView.vue'
import SectionsView from '../views/SectionsView.vue'
import TemplatesView from '../views/TemplatesView.vue'
import LeaveView from '../views/LeaveView.vue'

export const routes: RouteRecordRaw[] = [
  // The inbox is the front page. `pageShell` means the page renders its own single heading (PageShell).
  { path: '/', name: 'inbox', component: InboxView, meta: { titleKey: 'inbox.title', pageShell: true } },
  // `nav` names the navigation destination a page belongs to, so the detail pages keep their group marked.
  { path: '/punished', name: 'punished', component: PunishedPlayersView, meta: { titleKey: 'nav.activePunishments', pageShell: true } },
  { path: '/players', name: 'players', component: PlayersView, meta: { titleKey: 'nav.playerLookup', pageShell: true } },
  { path: '/players/:owner', name: 'player', component: PlayerDetailView, props: true, meta: { titleKey: 'nav.playerLookup', nav: '/players', pageShell: true } },
  { path: '/templates', name: 'templates', component: TemplatesView, meta: { titleKey: 'nav.templates', pageShell: true } },
  { path: '/client-rules', name: 'client-rules', component: ClientRulesView, meta: { titleKey: 'nav.clientRules', pageShell: true } },
  { path: '/sections', name: 'sections', component: SectionsView, meta: { titleKey: 'nav.sections', pageShell: true } },
  { path: '/appeal-conditions', name: 'appeal-conditions', component: AppealConditionsView, meta: { titleKey: 'nav.appealTerms', pageShell: true } },
  { path: '/reports', name: 'reports', component: ReportsView, meta: { titleKey: 'nav.reports', pageShell: true } },
  { path: '/reports/:id', name: 'report', component: ReportDetailView, props: true, meta: { titleKey: 'reports.detail.title', nav: '/reports', pageShell: true } },
  { path: '/appeals', name: 'appeals', component: AppealsView, meta: { titleKey: 'nav.appeals', pageShell: true } },
  { path: '/admin', name: 'admin', component: AdminView, meta: { titleKey: 'nav.administration', admin: true } },
  // The staff accounts tab of the administration area; the same page until the tabs of the area land.
  { path: '/admin/staff', name: 'admin-staff', component: AdminView, meta: { titleKey: 'nav.administration', admin: true, nav: '/admin' } },
  { path: '/cases', name: 'cases', component: CasesView, meta: { titleKey: 'nav.cases', pageShell: true } },
  { path: '/cases/:reference', name: 'case', component: CaseView, props: true, meta: { titleKey: 'case.title', nav: '/cases', pageShell: true } },
  { path: '/no-access', name: 'no-access', component: NoAccessView, meta: { titleKey: 'auth.noAccess.title' } },
  { path: '/auth/callback', name: 'auth-callback', component: AuthCallbackView, meta: { public: true, titleKey: 'auth.signingIn' } },
  { path: '/auth/error', name: 'auth-error', component: AuthErrorView, meta: { public: true, titleKey: 'auth.error.heading' } },
  // The interstitial for external links in chats; the target is in the fragment.
  { path: '/leave', name: 'leave', component: LeaveView, meta: { titleKey: 'leave.title' } },
  // Last: any other path. Not public, so it is shown after sign-in like every page.
  { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundView, meta: { titleKey: 'notFound.heading' } },
]

export function createAppRouter(history: RouterHistory = createWebHistory()) {
  return createRouter({ history, routes })
}

export default createAppRouter()
