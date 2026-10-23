import { inject, type InjectionKey } from 'vue'
import { useSessionStore } from '../stores/session'
import type { AuthService } from './authService'

export const AUTH_KEY: InjectionKey<AuthService> = Symbol('blackhole-auth')

/** The sign-in of the running app; tests provide a fake under {@link AUTH_KEY} instead. */
export function useAuth(): AuthService {
  const auth = inject(AUTH_KEY)
  if (!auth) throw new Error('No AuthService provided; install it with app.provide(AUTH_KEY, auth)')
  return auth
}

/** Ends the session: forgets who was signed in and sends the browser to the authority's sign-out. */
export function useSignOut(): () => Promise<void> {
  const auth = useAuth()
  const session = useSessionStore()
  return async () => {
    session.reset()
    try {
      await auth.signOut()
    } catch {
      // The authority could not be reached; starting over signs in again (or fails visibly there).
      window.location.assign('/')
    }
  }
}
