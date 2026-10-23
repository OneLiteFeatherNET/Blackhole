import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { BlackholeApi } from '../api/blackhole'
import type { ApiError, CurrentCaller } from '../api/types'
import { canAdminister, canModerate, canRead } from '../auth/permissions'

/**
 * Who is signed in, as the backend reports it (`GET /auth/me`). The UI never decodes tokens; the
 * roles only decide what is offered, the backend stays authoritative. Held in memory only.
 */
export const useSessionStore = defineStore('session', () => {
  const me = ref<CurrentCaller | null>(null)
  /** Set when the last backend answer was 403, so one place can tell the user. */
  const forbidden = ref(false)

  const roles = computed(() => me.value?.roles ?? [])
  const displayName = computed(() => me.value?.displayName ?? null)
  /** False for a signed-in user whose roles open nothing: the UI then offers only the no-access page. */
  const hasAccess = computed(() => canRead(roles.value))
  const moderator = computed(() => canModerate(roles.value))
  const administrator = computed(() => canAdminister(roles.value))

  /** Resolves to the error when the caller could not be loaded. */
  async function load(api: BlackholeApi): Promise<ApiError | null> {
    const result = await api.me()
    if (!result.ok) return result.error
    me.value = { ...result.data, roles: result.data.roles ?? [] }
    return null
  }

  function reset(): void {
    me.value = null
    forbidden.value = false
  }

  return { me, forbidden, roles, displayName, hasAccess, moderator, administrator, load, reset }
})
