import type { Router } from 'vue-router'
import type { BlackholeApi } from '../api/blackhole'
import type { AuthService } from '../auth/authService'
import type { useSessionStore } from '../stores/session'

export interface AuthGuardDeps {
  auth: AuthService
  session: ReturnType<typeof useSessionStore>
  api: BlackholeApi
}

/**
 * Every route except the callback and the error page needs a signed-in user: without a token the
 * browser is sent to the authority and comes back through `/auth/callback` to the route it asked for.
 * A signed-in user whose roles open nothing is kept on the no-access page, so no data is requested.
 */
export function installAuthGuard(router: Router, { auth, session, api }: AuthGuardDeps): void {
  router.beforeEach(async (to) => {
    if (to.name === 'auth-callback') {
      try {
        return await auth.completeSignIn(new URL(to.fullPath, window.location.origin).href)
      } catch {
        return { name: 'auth-error', query: { reason: 'callback' } }
      }
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
    return to.name === 'no-access' ? '/' : true
  })
}
