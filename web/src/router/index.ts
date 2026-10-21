import { createRouter, createWebHistory, type RouteRecordRaw, type RouterHistory } from 'vue-router'
import DashboardView from '../views/DashboardView.vue'
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
]

export function createAppRouter(history: RouterHistory = createWebHistory()) {
  return createRouter({ history, routes })
}

export default createAppRouter()
