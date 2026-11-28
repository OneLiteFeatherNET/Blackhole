import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, type Pinia } from 'pinia'
import { defineComponent, h, type Component, type Slots } from 'vue'
import { createMemoryHistory, RouterView, type Router } from 'vue-router'
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
  // The front page reads the changed-by-others section on every mount, so it gets the empty answer the real client
  // gives for a backend without the section. A test that cares about the section passes its own read.
  const defaults: Partial<Record<keyof BlackholeApi, unknown>> = {
    changedByOthers: async () => ok({ count: 0, items: [] }),
  }
  return new Proxy({ ...defaults, ...methods } as unknown as BlackholeApi, {
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

export function fail<T = never>(message: string, status = 400, kind: 'VALIDATION' | 'NOT_FOUND' | 'CONFLICT' | 'TARGET_EXEMPT' | 'NETWORK' | 'UNKNOWN' = 'VALIDATION'): ApiResult<T> {
  return { ok: false, error: { status, message, kind } }
}

export const ALL_ROLES = ['ADMIN', 'MODERATOR', 'VIEWER']

/** A Pinia whose session store already holds a signed-in user with the given roles (all roles by default). */
export function piniaWithRoles(roles: string[] = ALL_ROLES, displayName: string | null = 'Dev Admin', oid: string | null = SIGNED_IN_OID): Pinia {
  const pinia = createPinia()
  if (oid !== null) useSessionStore(pinia).me = { oid, displayName, roles }
  return pinia
}

/** The object id of the signed-in user of `piniaWithRoles`. */
export const SIGNED_IN_OID = '00000000-0000-4000-8000-0000000000a1'

export interface Mounted {
  wrapper: VueWrapper
  router: Router
}

/**
 * Mounts a view or component with Pinia, English texts, an in-memory router at `path` and the
 * given API. Always unmount the wrapper at the end of the test.
 */
export async function mountWithApp(component: Component, options: { api?: BlackholeApi; path?: string; props?: Record<string, unknown>; slots?: Slots; roles?: string[]; auth?: AuthService; oid?: string | null; provide?: Record<string | symbol, unknown> } = {}): Promise<Mounted> {
  i18n.global.locale.value = 'en'
  const router = createAppRouter(createMemoryHistory())
  await router.push(options.path ?? '/')
  await router.isReady()
  const wrapper = mount(component, {
    props: options.props,
    slots: options.slots,
    global: {
      plugins: [piniaWithRoles(options.roles, 'Dev Admin', options.oid === undefined ? SIGNED_IN_OID : options.oid), i18n, router],
      provide: { [API_KEY as symbol]: options.api ?? mockApi(), [AUTH_KEY as symbol]: options.auth ?? fakeAuthService().service, ...options.provide },
    },
    attachTo: document.body,
  })
  await flushPromises()
  return { wrapper, router }
}

/**
 * Mounts the whole panel at `path`: the app's own router view renders the page for that address, so a test can
 * follow an action that navigates to another page (a detail page, a dialog route) as the app does.
 */
export async function mountRouted(options: Omit<Parameters<typeof mountWithApp>[1], 'props' | 'slots'> = {}): Promise<Mounted> {
  return mountWithApp(defineComponent(() => () => h(RouterView)), options)
}

/** Opens the overflow of a table row and chooses the item with the given label (the row pattern's menu). */
export async function chooseRowMenuItem(wrapper: VueWrapper, row: number, label: string): Promise<void> {
  await wrapper.findAll('tbody tr')[row]!.get('button[aria-haspopup="menu"]').trigger('click')
  const item = wrapper.findAll('[role="menuitem"]').find((candidate) => candidate.text() === label)
  if (!item) throw new Error(`no row menu item "${label}"`)
  await item.trigger('click')
}
