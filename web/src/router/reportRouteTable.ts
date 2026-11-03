import type { RouteRecordRaw } from 'vue-router'
import ReportsView from '../views/ReportsView.vue'
import { DIALOG_ROUTE } from './childRoute'

/*
 * The report pages (`restructure-staff-panel`, decisions 3 and 8). The open report is the id in the path and the list
 * state is the query (`status`, `player`). Resolve and dismiss are child routes: the report stays rendered and the
 * dialog renders over it while the route is active. Opening either address sends nothing; the dialog sends the
 * resolution only when it is confirmed, and only a MODERATOR may open it (`meta.moderator`, checked by the guard).
 */
const panel = { pageShell: true, workspace: 'moderation' } as const

export const reportRoutes: RouteRecordRaw[] = [
  { path: '/reports', name: 'reports', component: ReportsView, meta: { titleKey: 'nav.reports', ...panel } },
  {
    path: '/reports/:id',
    name: 'report',
    component: ReportsView,
    props: true,
    meta: { titleKey: 'reports.detail.title', nav: '/reports', ...panel },
    children: [
      { path: 'resolve', name: 'report-resolve', component: DIALOG_ROUTE, meta: { moderator: true } },
      { path: 'dismiss', name: 'report-dismiss', component: DIALOG_ROUTE, meta: { moderator: true } },
    ],
  },
]
