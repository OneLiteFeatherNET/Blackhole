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
import CaseStatusDialog from '../components/case/CaseStatusDialog.vue'
import InboxView from '../views/InboxView.vue'
import NotFoundView from '../views/NotFoundView.vue'
import NoAccessView from '../views/NoAccessView.vue'
import PlayerDetailView from '../views/PlayerDetailView.vue'
import PlayersView from '../views/PlayersView.vue'
import PunishedPlayersView from '../views/PunishedPlayersView.vue'
import AppealConditionsView from '../views/AppealConditionsView.vue'
import AppealTermsVersionView from '../views/AppealTermsVersionView.vue'
import AppealTermsEditRoute from '../components/appealterms/AppealTermsEditRoute.vue'
import ClientRuleDetailView from '../views/ClientRuleDetailView.vue'
import ClientRuleEditRoute from '../components/clientrules/ClientRuleEditRoute.vue'
import SectionDetailView from '../views/SectionDetailView.vue'
import SectionEditRoute from '../components/sections/SectionEditRoute.vue'
import SectionDisableRoute from '../components/sections/SectionDisableRoute.vue'
import TemplateDetailView from '../views/TemplateDetailView.vue'
import TemplateEditRoute from '../components/templates/TemplateEditRoute.vue'
import AppealsView from '../views/AppealsView.vue'
import AppealView from '../views/AppealView.vue'
import AppealDecideRoute from '../components/appeals/AppealDecideRoute.vue'
import { moderationRoutes } from './moderationRouteTable'
import ReportsView from '../views/ReportsView.vue'
import SectionsView from '../views/SectionsView.vue'
import TemplatesView from '../views/TemplatesView.vue'
import LeaveView from '../views/LeaveView.vue'
import { exemptionRouteTable } from './exemptionRouteTable'

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
  { path: '/players', name: 'players', component: PlayersView, meta: { titleKey: 'nav.playerLookup', pageShell: true, workspace: 'moderation' } },
  { path: '/players/:owner', name: 'player', component: PlayerDetailView, props: true, meta: { titleKey: 'nav.playerLookup', nav: '/players', pageShell: true, workspace: 'moderation' } },
  // The VPN and hosting pages, the IP and area bans, the join guard and their detail pages: `moderationRouteTable.ts`.
  ...moderationRoutes,
  { path: '/reports', name: 'reports', component: ReportsView, meta: { titleKey: 'nav.reports', pageShell: true, workspace: 'moderation' } },
  // The detail of a list-detail page is the same component as its list (ReportsView): the open report is the id in the path.
  { path: '/reports/:id', name: 'report', component: ReportsView, props: true, meta: { titleKey: 'reports.detail.title', nav: '/reports', pageShell: true, workspace: 'moderation' } },
  { path: '/appeals', name: 'appeals', component: AppealsView, meta: { titleKey: 'nav.appeals', pageShell: true, workspace: 'moderation' } },
  // The decision is a child route: the page stays rendered behind its dialog, and the address opens nothing by itself.
  {
    path: '/appeals/:id',
    name: 'appeal',
    component: AppealView,
    props: true,
    meta: { titleKey: 'appeal.title', nav: '/appeals', pageShell: true, workspace: 'moderation' },
    children: [{ path: 'decide', name: 'appeal-decide', component: AppealDecideRoute }],
  },
  { path: '/cases', name: 'cases', component: CasesView, meta: { titleKey: 'nav.cases', pageShell: true, workspace: 'moderation' } },
  // The open case is the reference in the path; the list (CasesView) stays mounted and renders the case beside it.
  {
    path: '/cases/:reference',
    name: 'case',
    component: CasesView,
    props: true,
    meta: { titleKey: 'case.title', nav: '/cases', pageShell: true, workspace: 'moderation' },
    // Close and reopen are confirmation dialogs over the case (decision 8, rule 4): opening them sends nothing.
    children: [
      { path: 'close', name: 'case-close', component: CaseStatusDialog, props: (route) => ({ reference: String(route.params.reference), action: 'close' }) },
      { path: 'reopen', name: 'case-reopen', component: CaseStatusDialog, props: (route) => ({ reference: String(route.params.reference), action: 'reopen' }) },
    ],
  },

  // The Administration workspace. `/admin` is its home; the configuration pages are their own routes.
  // A list holds its create dialog as a child route, an item holds its detail page and its edit and action dialogs.
  // The page renders its child dialog from the active route (the dialog components are not a RouterView).
  { path: '/admin', redirect: '/admin/templates' },
  {
    path: '/admin/templates',
    name: 'admin-templates',
    component: TemplatesView,
    meta: { titleKey: 'nav.templates', admin: true, pageShell: true, workspace: 'administration' },
    children: [{ path: 'new', name: 'admin-template-new', component: TemplateEditRoute }],
  },
  {
    path: '/admin/templates/:id',
    name: 'admin-template',
    component: TemplateDetailView,
    props: true,
    meta: { titleKey: 'nav.templates', nav: '/admin/templates', admin: true, pageShell: true, workspace: 'administration' },
    children: [{ path: 'edit', name: 'admin-template-edit', component: TemplateEditRoute }],
  },
  {
    path: '/admin/sections',
    name: 'admin-sections',
    component: SectionsView,
    meta: { titleKey: 'nav.sections', admin: true, pageShell: true, workspace: 'administration' },
    children: [{ path: 'new', name: 'admin-section-new', component: SectionEditRoute }],
  },
  {
    path: '/admin/sections/:key',
    name: 'admin-section',
    component: SectionDetailView,
    props: (route) => ({ sectionKey: String(route.params.key) }),
    meta: { titleKey: 'nav.sections', nav: '/admin/sections', admin: true, pageShell: true, workspace: 'administration' },
    children: [
      { path: 'edit', name: 'admin-section-edit', component: SectionEditRoute },
      { path: 'disable', name: 'admin-section-disable', component: SectionDisableRoute },
    ],
  },
  {
    path: '/admin/client-rules',
    name: 'admin-client-rules',
    component: ClientRulesView,
    meta: { titleKey: 'nav.clientRules', admin: true, pageShell: true, workspace: 'administration' },
    children: [{ path: 'new', name: 'admin-client-rule-new', component: ClientRuleEditRoute }],
  },
  {
    path: '/admin/client-rules/:key',
    name: 'admin-client-rule',
    component: ClientRuleDetailView,
    props: (route) => ({ ruleKey: String(route.params.key) }),
    meta: { titleKey: 'nav.clientRules', nav: '/admin/client-rules', admin: true, pageShell: true, workspace: 'administration' },
    children: [{ path: 'edit', name: 'admin-client-rule-edit', component: ClientRuleEditRoute }],
  },
  {
    path: '/admin/appeal-terms',
    name: 'admin-appeal-terms',
    component: AppealConditionsView,
    meta: { titleKey: 'nav.appealTerms', admin: true, pageShell: true, workspace: 'administration' },
    children: [{ path: 'new', name: 'admin-appeal-terms-new', component: AppealTermsEditRoute }],
  },
  {
    path: '/admin/appeal-terms/versions/:version(\\d+)',
    name: 'admin-appeal-terms-version',
    component: AppealTermsVersionView,
    props: (route) => ({ version: Number(route.params.version) }),
    meta: { titleKey: 'nav.appealTerms', nav: '/admin/appeal-terms', admin: true, pageShell: true, workspace: 'administration' },
  },
  ...exemptionRouteTable,
  // The system pages: one component with one tab route each (`meta.adminTab`), each a rail item of its own.
  { path: '/admin/staff', name: 'admin-staff', component: AdminView, meta: { titleKey: 'nav.staffAccounts', admin: true, pageShell: true, adminTab: 'staff', workspace: 'administration' } },
  { path: '/admin/rollback', name: 'admin-rollback', component: AdminView, meta: { titleKey: 'nav.rollback', admin: true, pageShell: true, adminTab: 'rollback', workspace: 'administration' } },
  { path: '/admin/imports', name: 'admin-imports', component: AdminView, meta: { titleKey: 'nav.imports', admin: true, pageShell: true, adminTab: 'imports', workspace: 'administration' } },
  { path: '/admin/cloudnet', name: 'admin-cloudnet', component: AdminView, meta: { titleKey: 'nav.cloudnet', admin: true, pageShell: true, adminTab: 'cloudnet', workspace: 'administration' } },
  { path: '/admin/kick', name: 'admin-kick', component: AdminView, meta: { titleKey: 'nav.kick', admin: true, pageShell: true, adminTab: 'kick', workspace: 'administration' } },
  { path: '/admin/audit', name: 'admin-audit', component: AdminView, meta: { titleKey: 'nav.audit', admin: true, pageShell: true, adminTab: 'audit', workspace: 'administration' } },
  // Entries and dialogs of the system pages (`restructure-staff-panel`, decisions 3 and 7). Each is a route of its page:
  // the page stays rendered, a dialog renders over it, and a state change is a child route that sends nothing on mount.
  // `nav` keeps the rail item of the page current on these addresses (as the detail pages of the workspace do).
  { path: '/admin/rollback/new', name: 'admin-rollback-new', component: AdminView, meta: { titleKey: 'nav.rollback', nav: '/admin/rollback', admin: true, pageShell: true, adminTab: 'rollback', workspace: 'administration' } },
  { path: '/admin/rollback/:id', name: 'admin-rollback-job', component: AdminView, meta: { titleKey: 'nav.rollback', nav: '/admin/rollback', admin: true, pageShell: true, adminTab: 'rollback', workspace: 'administration' } },
  { path: '/admin/rollback/:id/execute', name: 'admin-rollback-execute', component: AdminView, meta: { titleKey: 'nav.rollback', nav: '/admin/rollback', admin: true, pageShell: true, adminTab: 'rollback', workspace: 'administration' } },
  { path: '/admin/rollback/:id/cancel', name: 'admin-rollback-cancel', component: AdminView, meta: { titleKey: 'nav.rollback', nav: '/admin/rollback', admin: true, pageShell: true, adminTab: 'rollback', workspace: 'administration' } },
  { path: '/admin/kick/new', name: 'admin-kick-new', component: AdminView, meta: { titleKey: 'nav.kick', nav: '/admin/kick', admin: true, pageShell: true, adminTab: 'kick', workspace: 'administration' } },
  { path: '/admin/kick/:id', name: 'admin-kick-detail', component: AdminView, meta: { titleKey: 'nav.kick', nav: '/admin/kick', admin: true, pageShell: true, adminTab: 'kick', workspace: 'administration' } },
  { path: '/admin/imports/vanilla/new', name: 'admin-imports-vanilla-new', component: AdminView, meta: { titleKey: 'nav.imports', nav: '/admin/imports', admin: true, pageShell: true, adminTab: 'imports', workspace: 'administration' } },
  { path: '/admin/audit/:auditId', name: 'admin-audit-entry', component: AdminView, meta: { titleKey: 'nav.audit', nav: '/admin/audit', admin: true, pageShell: true, adminTab: 'audit', workspace: 'administration' } },
  // `kind` is the catalogue's own name for the list (tasks, groups, services, proxies); `key` is the entry's name in it.
  { path: '/admin/cloudnet/:kind/:key', name: 'admin-cloudnet-entry', component: AdminView, meta: { titleKey: 'nav.cloudnet', nav: '/admin/cloudnet', admin: true, pageShell: true, adminTab: 'cloudnet', workspace: 'administration' } },

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
