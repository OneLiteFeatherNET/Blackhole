import type { Router } from 'vue-router'
import { safeReturnTo } from '../auth/authService'

/**
 * The routes a return path (after sign-in) may name. Sign-in, the leave interstitial, the no-access page and
 * the catch-all are left out, so a crafted state can never open them. Checked by a test against the route table.
 */
export const RETURN_ROUTE_NAMES: ReadonlySet<string> = new Set([
  'inbox',
  'punished',
  'ip-bans',
  'players',
  'player',
  'intel-policy',
  'intel-allow-list',
  'reports',
  'report',
  'appeals',
  'cases',
  'case',
  'guard',
  'area-bans',
  'admin-templates',
  'admin-sections',
  'admin-client-rules',
  'admin-appeal-terms',
  'admin-exemption-reviews',
  'admin-staff',
  'admin-rollback',
  'admin-imports',
  'admin-cloudnet',
  'admin-kick',
  'admin-audit',
])

/**
 * The path to follow after a redirect: a same-origin in-app path (`safeReturnTo`) whose route is on the
 * allow-list. Anything else is ignored and the start page is used (CWE-601).
 */
export function allowedReturnTo(router: Pick<Router, 'resolve'>, value: unknown): string {
  const path = safeReturnTo(value)
  const name = router.resolve(path).name
  return typeof name === 'string' && RETURN_ROUTE_NAMES.has(name) ? path : '/'
}
