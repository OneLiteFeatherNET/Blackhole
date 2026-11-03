import type { Router } from 'vue-router'
import type { BlackholeApi } from '../api/blackhole'
import type { AuthService } from '../auth/authService'
import { readStoredWorkspace, storeWorkspace, workspaceByKey } from '../nav/workspaces'
import type { WorkspaceKey } from '../nav/navigation'
import type { useSessionStore } from '../stores/session'
import { allowedReturnTo } from './returnTo'

export interface AuthGuardDeps {
  auth: AuthService
  session: ReturnType<typeof useSessionStore>
  api: BlackholeApi
}

/**
 * Every route except the callback and the error page needs a signed-in user: without a token the
 * browser is sent to the authority and comes back through `/auth/callback` to the route it asked for.
 * A signed-in user whose roles open nothing is kept on the no-access page, so no data is requested.
 * Pages marked `meta.admin` need the ADMIN role.
 */
export function installAuthGuard(router: Router, { auth, session, api }: AuthGuardDeps): void {
  router.beforeEach(async (to, from) => {
    if (to.name === 'auth-callback') {
      let returnTo: string
      try {
        returnTo = await auth.completeSignIn(new URL(to.fullPath, window.location.origin).href)
      } catch {
        return { name: 'auth-error', query: { reason: 'callback' } }
      }
      // The return path comes back in the OIDC state: only an allow-listed in-app route is followed.
      return allowedReturnTo(router, returnTo)
    }
    if (to.meta.public) return true

    if (!(await auth.getAccessToken())) {
      try {
        await auth.signIn(to.fullPath)
      } catch {
        return { name: 'auth-error', query: { reason: 'signin' } }
      }
      return false
    }

    if (!session.me) {
      const problem = await session.load(api)
      // A 401 means the http layer already started signing in again.
      if (problem?.status === 401) return false
      if (problem) return { name: 'auth-error', query: { reason: 'me' } }
    }
    if (!session.hasAccess) return to.name === 'no-access' ? true : { name: 'no-access' }
    // Admin-only pages are not offered to other roles; a direct visit lands on the no-access page, which says why.
    if (to.meta.admin && !session.administrator) return { name: 'no-access', query: { reason: 'admin' } }
    // A dialog that changes a list (`meta.moderator`) is refused to a viewer before its page requests any data.
    if (to.meta.moderator && !session.moderator) return { name: 'no-access', query: { reason: 'moderator' } }
    if (to.name === 'no-access') return to.query.reason === 'admin' || to.query.reason === 'moderator' ? true : '/'
    // The stored workspace only decides the first landing on the bare root; an explicit route (and the Inbox link) wins.
    if (from.matched.length === 0 && to.path === '/' && session.administrator && readStoredWorkspace() === 'administration') {
      return workspaceByKey('administration').home
    }
    return true
  })

  // The workspace of every page is remembered for the next visit (convenience only, see `workspaces.ts`).
  router.afterEach((to) => {
    if (to.meta.workspace) storeWorkspace(to.meta.workspace as WorkspaceKey)
  })
}
