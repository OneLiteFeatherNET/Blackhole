import { createRouter, createWebHistory, type RouteRecordRaw, type RouterHistory } from 'vue-router'
import AdminView from '../views/AdminView.vue'
import AuthCallbackView from '../views/AuthCallbackView.vue'
import AuthErrorView from '../views/AuthErrorView.vue'
import CasesView from '../views/CasesView.vue'
import CaseView from '../views/CaseView.vue'
import DashboardView from '../views/DashboardView.vue'
import NoAccessView from '../views/NoAccessView.vue'
import PlayerDetailView from '../views/PlayerDetailView.vue'
import PlayersView from '../views/PlayersView.vue'
import AppealsView from '../views/AppealsView.vue'
import ReportsView from '../views/ReportsView.vue'
import SectionsView from '../views/SectionsView.vue'
import TemplatesView from '../views/TemplatesView.vue'

export const routes: RouteRecordRaw[] = [
  { path: '/', name: 'dashboard', component: DashboardView, meta: { titleKey: 'nav.dashboard' } },
  { path: '/players', name: 'players', component: PlayersView, meta: { titleKey: 'nav.players' } },
  { path: '/players/:owner', name: 'player', component: PlayerDetailView, props: true, meta: { titleKey: 'nav.players' } },
  { path: '/templates', name: 'templates', component: TemplatesView, meta: { titleKey: 'nav.templates' } },
  { path: '/sections', name: 'sections', component: SectionsView, meta: { titleKey: 'nav.sections' } },
  { path: '/reports', name: 'reports', component: ReportsView, meta: { titleKey: 'nav.reports' } },
  { path: '/appeals', name: 'appeals', component: AppealsView, meta: { titleKey: 'nav.appeals' } },
  { path: '/admin', name: 'admin', component: AdminView, meta: { titleKey: 'nav.admin', admin: true } },
  { path: '/cases', name: 'cases', component: CasesView, meta: { titleKey: 'nav.cases' } },
  { path: '/cases/:reference', name: 'case', component: CaseView, props: true, meta: { titleKey: 'case.title' } },
  { path: '/no-access', name: 'no-access', component: NoAccessView, meta: { titleKey: 'auth.noAccess.title' } },
  { path: '/auth/callback', name: 'auth-callback', component: AuthCallbackView, meta: { public: true, titleKey: 'auth.signingIn' } },
  { path: '/auth/error', name: 'auth-error', component: AuthErrorView, meta: { public: true, titleKey: 'auth.error.heading' } },
]

export function createAppRouter(history: RouterHistory = createWebHistory()) {
  return createRouter({ history, routes })
}

export default createAppRouter()
