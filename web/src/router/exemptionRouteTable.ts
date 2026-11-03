import type { RouteRecordRaw } from 'vue-router'
import ExemptionReviewDetailView from '../views/ExemptionReviewDetailView.vue'
import ExemptionReviewsView from '../views/ExemptionReviewsView.vue'

/*
 * The exemption review pages (administration, ADMIN only). The decision is the child route `decide`: it renders the
 * detail page with the decision dialog over it, and `?action=apply|dismiss` says which decision. Nothing is sent on
 * navigation; the dialog sends the decision only when it is confirmed.
 */
export const exemptionRouteTable: RouteRecordRaw[] = [
  {
    path: '/admin/exemption-reviews',
    name: 'admin-exemption-reviews',
    component: ExemptionReviewsView,
    meta: { titleKey: 'nav.exemptionReviews', admin: true, pageShell: true, workspace: 'administration' },
  },
  {
    path: '/admin/exemption-reviews/:id',
    name: 'admin-exemption-review',
    component: ExemptionReviewDetailView,
    props: true,
    meta: { titleKey: 'exemptionReviews.detail.title', nav: '/admin/exemption-reviews', admin: true, pageShell: true, workspace: 'administration' },
  },
  {
    path: '/admin/exemption-reviews/:id/decide',
    name: 'admin-exemption-review-decide',
    component: ExemptionReviewDetailView,
    props: true,
    meta: { titleKey: 'exemptionReviews.detail.title', nav: '/admin/exemption-reviews', admin: true, pageShell: true, workspace: 'administration' },
  },
]
