import { createRouter, createWebHistory, type RouteRecordRaw, type RouterHistory } from 'vue-router'
import PlaceholderView from '../views/PlaceholderView.vue'

export const routes: RouteRecordRaw[] = [
  { path: '/', name: 'dashboard', component: PlaceholderView, meta: { titleKey: 'nav.dashboard' } },
  { path: '/players', name: 'players', component: PlaceholderView, meta: { titleKey: 'nav.players' } },
  { path: '/templates', name: 'templates', component: PlaceholderView, meta: { titleKey: 'nav.templates' } },
  { path: '/sections', name: 'sections', component: PlaceholderView, meta: { titleKey: 'nav.sections' } },
  { path: '/reports', name: 'reports', component: PlaceholderView, meta: { titleKey: 'nav.reports' } },
  { path: '/appeals', name: 'appeals', component: PlaceholderView, meta: { titleKey: 'nav.appeals' } },
]

export function createAppRouter(history: RouterHistory = createWebHistory()) {
  return createRouter({ history, routes })
}

export default createAppRouter()
