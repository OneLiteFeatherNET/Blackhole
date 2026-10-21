import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import type { Component } from 'vue'
import { createMemoryHistory, type Router } from 'vue-router'
import type { BlackholeApi } from '../api/blackhole'
import { API_KEY } from '../api/inject'
import type { ApiResult } from '../api/types'
import { i18n } from '../i18n'
import { createAppRouter } from '../router'

/**
 * A BlackholeApi where only the given methods exist. Calling any other method fails the test with
 * its name, so a screen can never silently talk to something the test did not expect.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function mockApi(methods: Partial<Record<keyof BlackholeApi, (...args: any[]) => unknown>> = {}): BlackholeApi {
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

export interface Mounted {
  wrapper: VueWrapper
  router: Router
}

/**
 * Mounts a view or component with Pinia, English texts, an in-memory router at `path` and the
 * given API. Always unmount the wrapper at the end of the test.
 */
export async function mountWithApp(component: Component, options: { api?: BlackholeApi; path?: string; props?: Record<string, unknown> } = {}): Promise<Mounted> {
  i18n.global.locale.value = 'en'
  const router = createAppRouter(createMemoryHistory())
  await router.push(options.path ?? '/')
  await router.isReady()
  const wrapper = mount(component, {
    props: options.props,
    global: {
      plugins: [createPinia(), i18n, router],
      provide: { [API_KEY as symbol]: options.api ?? mockApi() },
    },
    attachTo: document.body,
  })
  await flushPromises()
  return { wrapper, router }
}
