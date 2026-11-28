import type { RouteRecordRaw } from 'vue-router'
import AreaBanDetailView from '../views/AreaBanDetailView.vue'
import AreaBansView from '../views/AreaBansView.vue'
import GuardSettingsView from '../views/GuardSettingsView.vue'
import IntelAllowListEntryView from '../views/IntelAllowListEntryView.vue'
import IntelAllowListView from '../views/IntelAllowListView.vue'
import IntelPolicyDetailView from '../views/IntelPolicyDetailView.vue'
import IntelPolicyView from '../views/IntelPolicyView.vue'
import IpBanDetailView from '../views/IpBanDetailView.vue'
import IpBansView from '../views/IpBansView.vue'
import JoinGuardView from '../views/JoinGuardView.vue'
import { DIALOG_ROUTE } from './childRoute'

/*
 * The moderation lists (`restructure-staff-panel`, decision 7, rows 16 to 25). A list, a detail page and the
 * dialogs of one item are routes: `new`, `lift`, `edit`, `level` and `remove` are child routes that the page
 * renders as a dialog. `meta.moderator` and `meta.admin` are checked by the auth guard before the page requests
 * any data. No address, range, name or secret is a path parameter (`routeTable.test.ts`).
 */
const panel = { pageShell: true, workspace: 'moderation' } as const

export const moderationRoutes: RouteRecordRaw[] = [
  {
    path: '/ip-bans',
    name: 'ip-bans',
    component: IpBansView,
    meta: { titleKey: 'nav.ipBans', ...panel },
    children: [{ path: 'new', name: 'ip-ban-new', component: DIALOG_ROUTE, meta: { moderator: true } }],
  },
  {
    path: '/ip-bans/:id',
    name: 'ip-ban',
    component: IpBanDetailView,
    props: true,
    meta: { titleKey: 'ipBans.title', nav: '/ip-bans', ...panel },
    children: [{ path: 'lift', name: 'ip-ban-lift', component: DIALOG_ROUTE, meta: { moderator: true } }],
  },
  {
    path: '/area-bans',
    name: 'area-bans',
    component: AreaBansView,
    meta: { titleKey: 'areaBans.title', ...panel },
    children: [{ path: 'new', name: 'area-ban-new', component: DIALOG_ROUTE, meta: { moderator: true } }],
  },
  {
    path: '/area-bans/:id',
    name: 'area-ban',
    component: AreaBanDetailView,
    props: true,
    meta: { titleKey: 'areaBans.title', nav: '/area-bans', ...panel },
    children: [{ path: 'lift', name: 'area-ban-lift', component: DIALOG_ROUTE, meta: { moderator: true } }],
  },
  {
    path: '/guard',
    name: 'guard',
    component: JoinGuardView,
    meta: { titleKey: 'guard.title', ...panel },
    children: [{ path: 'level', name: 'guard-level', component: DIALOG_ROUTE, meta: { moderator: true } }],
  },
  {
    path: '/guard/settings',
    name: 'guard-settings',
    component: GuardSettingsView,
    meta: { titleKey: 'guard.settings.title', nav: '/guard', ...panel },
    children: [{ path: 'edit', name: 'guard-settings-edit', component: DIALOG_ROUTE, meta: { admin: true } }],
  },
  { path: '/intel/policy', name: 'intel-policy', component: IntelPolicyView, meta: { titleKey: 'nav.vpnPolicy', ...panel } },
  {
    path: '/intel/policy/:classification',
    name: 'intel-policy-class',
    component: IntelPolicyDetailView,
    props: true,
    meta: { titleKey: 'nav.vpnPolicy', nav: '/intel/policy', ...panel },
    children: [{ path: 'edit', name: 'intel-policy-class-edit', component: DIALOG_ROUTE, meta: { admin: true } }],
  },
  {
    path: '/intel/allow-list',
    name: 'intel-allow-list',
    component: IntelAllowListView,
    meta: { titleKey: 'nav.allowList', ...panel },
    children: [{ path: 'new', name: 'intel-allow-list-new', component: DIALOG_ROUTE, meta: { moderator: true } }],
  },
  {
    path: '/intel/allow-list/:owner',
    name: 'intel-allow-entry',
    component: IntelAllowListEntryView,
    props: true,
    meta: { titleKey: 'nav.allowList', nav: '/intel/allow-list', ...panel },
    children: [{ path: 'remove', name: 'intel-allow-entry-remove', component: DIALOG_ROUTE, meta: { moderator: true } }],
  },
]
