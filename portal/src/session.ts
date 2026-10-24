import { reactive, type InjectionKey } from 'vue'
import type { ApiResult, PortalApi } from './api/portal'
import type { PlayerScope, PunishmentFacts } from './api/contract'
import { normaliseBanId } from './banId'

export type Phase = 'loading' | 'login' | 'in' | 'expired'

/** Holds the CSRF value in memory; the API client reads it for every state-changing call. */
export interface CsrfHolder {
  value: string | null
}

export function createCsrfHolder(): CsrfHolder {
  return { value: null }
}

/**
 * The state of the visit: who is signed in and what has not been sent yet. All of it lives in
 * memory. Nothing is written to cookies (the BFF owns those) or to any browser storage,
 * and the Ban ID is never stored: it only exists in the login field until it is sent.
 */
export function createPortalSession(api: PortalApi, csrf: CsrfHolder = createCsrfHolder()) {
  const state = reactive({
    phase: 'loading' as Phase,
    scope: 'FULL' as PlayerScope,
    expiresAt: null as string | null,
    punishment: null as PunishmentFacts | null,
    /** Set after a failed login; the same value for every cause of failure. */
    loginError: null as 'INVALID_BAN_ID' | 'UNAVAILABLE' | null,
    busy: false,
    /** Unsent text. Survives the expiry page so a long statement is not lost; gone on logout or when the tab closes. */
    drafts: { statement: '', message: '' },
  })

  function enter(info: { csrf: string; expiresAt: string; scope: PlayerScope }) {
    csrf.value = info.csrf
    state.scope = info.scope
    state.expiresAt = info.expiresAt
    state.loginError = null
    state.phase = 'in'
  }

  function expire() {
    csrf.value = null
    state.punishment = null
    state.expiresAt = null
    state.phase = 'expired'
  }

  /** Returns false (and shows the expiry page) when the backend ended the session. */
  function alive<T>(result: ApiResult<T>): boolean {
    if (!result.ok && result.error.code === 'SESSION_EXPIRED') {
      expire()
      return false
    }
    return true
  }

  async function refreshPunishment() {
    const result = await api.punishment()
    if (!alive(result)) return
    if (result.ok) state.punishment = result.data
  }

  async function bootstrap() {
    const result = await api.session()
    if (result.ok) {
      enter(result.data)
      await refreshPunishment()
    } else {
      state.loginError = result.error.code === 'UNAVAILABLE' ? 'UNAVAILABLE' : null
      state.phase = 'login'
    }
  }

  async function login(typed: string) {
    const banId = normaliseBanId(typed)
    if (banId === null) {
      // Same message as a wrong ID, and nothing is sent for input that cannot be one.
      state.loginError = 'INVALID_BAN_ID'
      return
    }
    state.busy = true
    try {
      const result = await api.login(banId)
      if (result.ok) {
        enter(result.data)
        await refreshPunishment()
      } else {
        state.loginError = result.error.code === 'UNAVAILABLE' ? 'UNAVAILABLE' : 'INVALID_BAN_ID'
      }
    } finally {
      state.busy = false
    }
  }

  async function logout() {
    await api.logout()
    csrf.value = null
    state.punishment = null
    state.expiresAt = null
    state.drafts.statement = ''
    state.drafts.message = ''
    state.loginError = null
    state.phase = 'login'
  }

  /** From the expiry page back to the login; drafts stay. */
  function reauthenticate() {
    state.loginError = null
    state.phase = 'login'
  }

  return { state, api, bootstrap, login, logout, expire, alive, refreshPunishment, reauthenticate }
}

export type PortalSession = ReturnType<typeof createPortalSession>
export const SESSION_KEY: InjectionKey<PortalSession> = Symbol('portal-session')
