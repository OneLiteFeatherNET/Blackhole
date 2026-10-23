import { ref } from 'vue'
import type { AuthService } from '../auth/authService'
import type { HttpAuth } from '../api/http'

/** An HttpAuth with a fixed token that records what the HTTP layer asked of it. */
export function fakeHttpAuth(options: { token?: string | null; renewed?: string | null } = {}) {
  const token = options.token === undefined ? 'access-token' : options.token
  const renewed = options.renewed === undefined ? 'renewed-token' : options.renewed
  const events = { renewals: 0, signIns: 0, forbidden: 0 }
  const auth: HttpAuth = {
    getAccessToken: async () => token,
    renew: async () => {
      events.renewals++
      return renewed
    },
    signIn: () => {
      events.signIns++
    },
    forbidden: () => {
      events.forbidden++
    },
  }
  return { auth, events }
}

export interface FakeAuthOptions {
  /** The access token, null for a visitor who is not signed in. */
  token?: string | null
  /** The route the callback returns to. */
  returnTo?: string
  completeFails?: boolean
  signInFails?: boolean
}

/** An AuthService that never leaves the page; records the calls the UI made. */
export function fakeAuthService(options: FakeAuthOptions = {}) {
  const calls = { signIn: [] as string[], completeSignIn: [] as Array<string | undefined>, signOut: 0 }
  const service: AuthService = {
    redirecting: ref(false),
    getAccessToken: async () => (options.token === undefined ? 'access-token' : options.token),
    renew: async () => 'renewed-token',
    signIn: async (returnTo) => {
      calls.signIn.push(returnTo)
      if (options.signInFails) throw new Error('authority unreachable')
    },
    completeSignIn: async (url) => {
      calls.completeSignIn.push(url)
      if (options.completeFails) throw new Error('state mismatch')
      return options.returnTo ?? '/'
    },
    signOut: async () => {
      calls.signOut++
    },
  }
  return { service, calls }
}
