import {
  createRouter,
  createWebHistory,
  type RouteLocationGeneric,
  type RouteLocationRaw,
  type RouteRecordRaw,
  type RouterHistory,
} from 'vue-router'
import AdminView from '../views/AdminView.vue'
import AuthCallbackView from '../views/AuthCallbackView.vue'
import AuthErrorView from '../views/AuthErrorView.vue'
import CasesView from '../views/CasesView.vue'
import ClientRulesView from '../views/ClientRulesView.vue'
import ExemptionReviewsView from '../views/ExemptionReviewsView.vue'
import CaseView from '../views/CaseView.vue'
import InboxView from '../views/InboxView.vue'
import IntelAllowListView from '../views/IntelAllowListView.vue'
import IntelPolicyView from '../views/IntelPolicyView.vue'
import IpBansView from '../views/IpBansView.vue'
import NotFoundView from '../views/NotFoundView.vue'
import NoAccessView from '../views/NoAccessView.vue'
import PlayerDetailView from '../views/PlayerDetailView.vue'
import PlayersView from '../views/PlayersView.vue'
import PunishedPlayersView from '../views/PunishedPlayersView.vue'
import AppealConditionsView from '../views/AppealConditionsView.vue'
import AppealsView from '../views/AppealsView.vue'
import AreaBansView from '../views/AreaBansView.vue'
import JoinGuardView from '../views/JoinGuardView.vue'
import ReportDetailView from '../views/ReportDetailView.vue'
import ReportsView from '../views/ReportsView.vue'
import SectionsView from '../views/SectionsView.vue'
import TemplatesView from '../views/TemplatesView.vue'
import LeaveView from '../views/LeaveView.vue'

/*
 * Two workspaces (`meta.workspace`): Moderation keeps its URLs; Administration lives under /admin (ADMIN only, `meta.admin`).
 * The configuration URLs of the former single rail redirect to /admin, keeping the query string.
 */

/** A redirect that keeps the query string of the old URL. */
function keepQuery(path: string): (to: RouteLocationGeneric) => RouteLocationRaw {
  return (to) => ({ path, query: to.query })
}

export const routes: RouteRecordRaw[] = [
  // The inbox is the front page of the Moderation workspace. `pageShell` means the page renders its own single heading (PageShell).
  { path: '/', name: 'inbox', component: InboxView, meta: { titleKey: 'inbox.title', pageShell: true, workspace: 'moderation' } },
  // `nav` names the navigation destination a page belongs to, so the detail pages keep their group marked.
  { path: '/punished', name: 'punished', component: PunishedPlayersView, meta: { titleKey: 'nav.activePunishments', pageShell: true, workspace: 'moderation' } },
  { path: '/ip-bans', name: 'ip-bans', component: IpBansView, meta: { titleKey: 'nav.ipBans', pageShell: true, workspace: 'moderation' } },
  { path: '/players', name: 'players', component: PlayersView, meta: { titleKey: 'nav.playerLookup', pageShell: true, workspace: 'moderation' } },
  { path: '/players/:owner', name: 'player', component: PlayerDetailView, props: true, meta: { titleKey: 'nav.playerLookup', nav: '/players', pageShell: true, workspace: 'moderation' } },
  // The VPN and hosting pages: the policy is read by every staff role, the exemptions are added by moderators.
  { path: '/intel/policy', name: 'intel-policy', component: IntelPolicyView, meta: { titleKey: 'nav.vpnPolicy', pageShell: true, workspace: 'moderation' } },
  { path: '/intel/allow-list', name: 'intel-allow-list', component: IntelAllowListView, meta: { titleKey: 'nav.allowList', pageShell: true, workspace: 'moderation' } },
  { path: '/reports', name: 'reports', component: ReportsView, meta: { titleKey: 'nav.reports', pageShell: true, workspace: 'moderation' } },
  { path: '/reports/:id', name: 'report', component: ReportDetailView, props: true, meta: { titleKey: 'reports.detail.title', nav: '/reports', pageShell: true, workspace: 'moderation' } },
  { path: '/appeals', name: 'appeals', component: AppealsView, meta: { titleKey: 'nav.appeals', pageShell: true, workspace: 'moderation' } },
  { path: '/cases', name: 'cases', component: CasesView, meta: { titleKey: 'nav.cases', pageShell: true, workspace: 'moderation' } },
  { path: '/cases/:reference', name: 'case', component: CaseView, props: true, meta: { titleKey: 'case.title', nav: '/cases', pageShell: true, workspace: 'moderation' } },
  { path: '/guard', name: 'guard', component: JoinGuardView, meta: { titleKey: 'guard.title', pageShell: true, workspace: 'moderation' } },
  { path: '/area-bans', name: 'area-bans', component: AreaBansView, meta: { titleKey: 'areaBans.title', pageShell: true, workspace: 'moderation' } },

  // The Administration workspace. `/admin` is its home; the configuration pages are their own routes.
  { path: '/admin', redirect: '/admin/templates' },
  { path: '/admin/templates', name: 'admin-templates', component: TemplatesView, meta: { titleKey: 'nav.templates', admin: true, pageShell: true, workspace: 'administration' } },
  { path: '/admin/sections', name: 'admin-sections', component: SectionsView, meta: { titleKey: 'nav.sections', admin: true, pageShell: true, workspace: 'administration' } },
  { path: '/admin/client-rules', name: 'admin-client-rules', component: ClientRulesView, meta: { titleKey: 'nav.clientRules', admin: true, pageShell: true, workspace: 'administration' } },
  { path: '/admin/appeal-terms', name: 'admin-appeal-terms', component: AppealConditionsView, meta: { titleKey: 'nav.appealTerms', admin: true, pageShell: true, workspace: 'administration' } },
  { path: '/admin/exemption-reviews', name: 'admin-exemption-reviews', component: ExemptionReviewsView, meta: { titleKey: 'nav.exemptionReviews', admin: true, pageShell: true, workspace: 'administration' } },
  // The system pages: one component with one tab route each (`meta.adminTab`), each a rail item of its own.
  { path: '/admin/staff', name: 'admin-staff', component: AdminView, meta: { titleKey: 'nav.staffAccounts', admin: true, pageShell: true, adminTab: 'staff', workspace: 'administration' } },
  { path: '/admin/rollback', name: 'admin-rollback', component: AdminView, meta: { titleKey: 'nav.rollback', admin: true, pageShell: true, adminTab: 'rollback', workspace: 'administration' } },
  { path: '/admin/imports', name: 'admin-imports', component: AdminView, meta: { titleKey: 'nav.imports', admin: true, pageShell: true, adminTab: 'imports', workspace: 'administration' } },
  { path: '/admin/cloudnet', name: 'admin-cloudnet', component: AdminView, meta: { titleKey: 'nav.cloudnet', admin: true, pageShell: true, adminTab: 'cloudnet', workspace: 'administration' } },
  { path: '/admin/kick', name: 'admin-kick', component: AdminView, meta: { titleKey: 'nav.kick', admin: true, pageShell: true, adminTab: 'kick', workspace: 'administration' } },
  { path: '/admin/audit', name: 'admin-audit', component: AdminView, meta: { titleKey: 'nav.audit', admin: true, pageShell: true, adminTab: 'audit', workspace: 'administration' } },

  // Former URLs of the single rail.
  { path: '/templates', redirect: keepQuery('/admin/templates') },
  { path: '/sections', redirect: keepQuery('/admin/sections') },
  { path: '/client-rules', redirect: keepQuery('/admin/client-rules') },
  { path: '/appeal-conditions', redirect: keepQuery('/admin/appeal-terms') },

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
