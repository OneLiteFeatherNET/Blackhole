import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, type Pinia } from 'pinia'
import type { Component } from 'vue'
import { createMemoryHistory, type Router } from 'vue-router'
import type { BlackholeApi } from '../api/blackhole'
import { API_KEY } from '../api/inject'
import type { AuthService } from '../auth/authService'
import { AUTH_KEY } from '../auth/inject'
import type { ApiResult } from '../api/types'
import { i18n } from '../i18n'
import { createAppRouter } from '../router'
import { useSessionStore } from '../stores/session'
import { fakeAuthService } from './auth'

/**
 * A BlackholeApi where only the given methods exist. Calling any other method fails the test with
 * its name, so a screen can never silently talk to something the test did not expect.
 */
export function mockApi(methods: Partial<Record<keyof BlackholeApi, unknown>> = {}): BlackholeApi {
  return new Proxy(methods as unknown as BlackholeApi, {
    get(target, name: string) {
      if (name in target) return (target as unknown as Record<string, unknown>)[name]
      return () => {
        throw new Error(`unexpected API call: ${name}`)
      }
    },
  })
}

export function ok<T>(data: T): ApiResult<T> {
  return { ok: true, data }
}

export function fail<T = never>(message: string, status = 400, kind: 'VALIDATION' | 'NOT_FOUND' | 'CONFLICT' | 'NETWORK' | 'UNKNOWN' = 'VALIDATION'): ApiResult<T> {
  return { ok: false, error: { status, message, kind } }
}

export const ALL_ROLES = ['ADMIN', 'MODERATOR', 'VIEWER']

/** A Pinia whose session store already holds a signed-in user with the given roles (all roles by default). */
export function piniaWithRoles(roles: string[] = ALL_ROLES, displayName: string | null = 'Dev Admin'): Pinia {
  const pinia = createPinia()
  useSessionStore(pinia).me = { oid: '00000000-0000-4000-8000-0000000000a1', displayName, roles }
  return pinia
}

export interface Mounted {
  wrapper: VueWrapper
  router: Router
}

/**
 * Mounts a view or component with Pinia, English texts, an in-memory router at `path` and the
 * given API. Always unmount the wrapper at the end of the test.
 */
export async function mountWithApp(component: Component, options: { api?: BlackholeApi; path?: string; props?: Record<string, unknown>; roles?: string[]; auth?: AuthService } = {}): Promise<Mounted> {
  i18n.global.locale.value = 'en'
  const router = createAppRouter(createMemoryHistory())
  await router.push(options.path ?? '/')
  await router.isReady()
  const wrapper = mount(component, {
    props: options.props,
    global: {
      plugins: [piniaWithRoles(options.roles), i18n, router],
      provide: { [API_KEY as symbol]: options.api ?? mockApi(), [AUTH_KEY as symbol]: options.auth ?? fakeAuthService().service },
    },
    attachTo: document.body,
  })
  await flushPromises()
  return { wrapper, router }
}
