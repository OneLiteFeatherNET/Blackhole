import { reactive, type InjectionKey } from 'vue'
import type { ApiResult, PortalApi } from './api/portal'
import type { PlayerScope, PunishmentFacts } from './api/contract'
import { normaliseBanId } from './banId'
import { releasePreview, type DraftFile } from './chat/files'

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
    /** The last request for the punishment failed for another reason than an ended session. */
    punishmentFailed: false,
    /** Set after a failed login; the same value for every cause of failure. */
    loginError: null as 'INVALID_BAN_ID' | 'UNAVAILABLE' | 'CHALLENGE_FAILED' | 'RATE_LIMITED' | null,
    busy: false,
    /** Unsent text. Survives the expiry page so a long statement is not lost; gone on logout or when the tab closes. */
    drafts: { statement: '', message: '', files: [] as DraftFile[] },
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
    state.punishmentFailed = false
    const result = await api.punishment()
    if (!alive(result)) return
    if (result.ok) state.punishment = result.data
    else state.punishmentFailed = true
  }

  async function bootstrap() {
    const result = await api.session()
    if (result.ok) {
      enter(result.data)
      await refreshPunishment()
    } else {
      const code = result.error.code
      state.loginError = code === 'UNAVAILABLE' || code === 'RATE_LIMITED' ? code : null
      state.phase = 'login'
    }
  }

  async function login(typed: string, altcha?: string) {
    const banId = normaliseBanId(typed)
    if (banId === null) {
      // Same message as a wrong ID, and nothing is sent for input that cannot be one.
      state.loginError = 'INVALID_BAN_ID'
      return
    }
    state.busy = true
    try {
      // The solution is sent only when the widget produced one; with the check off the field is absent.
      const result = altcha === undefined ? await api.login(banId) : await api.login(banId, altcha)
      if (result.ok) {
        enter(result.data)
        await refreshPunishment()
      } else if (result.error.code === 'CHALLENGE_FAILED') {
        state.loginError = 'CHALLENGE_FAILED'
      } else if (result.error.code === 'RATE_LIMITED') {
        state.loginError = 'RATE_LIMITED'
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
    for (const file of state.drafts.files) releasePreview(file)
    state.drafts.files = []
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
