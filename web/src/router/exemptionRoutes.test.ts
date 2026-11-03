import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { fakeAuthService } from '../test/auth'
import { mockApi, ok } from '../test/mount'
import { installAuthGuard } from './authGuard'
import { createAppRouter } from './index'

// The exemption review pages are administration pages: the list, the detail and the decision child route.
const OID = '00000000-0000-4000-8000-0000000000a1'
const ID = '5e8f2c10-0000-4000-8000-0000000000c1'
const PATHS: Array<[string, string]> = [
  ['list', '/admin/exemption-reviews?status=open'],
  ['detail', `/admin/exemption-reviews/${ID}`],
  ['decision', `/admin/exemption-reviews/${ID}/decide?action=apply`],
]

function setup(options: { token?: string | null; roles?: string[] } = {}) {
  setActivePinia(createPinia())
  const auth = fakeAuthService({ token: options.token })
  const session = useSessionStore()
  const api = mockApi({ me: async () => ok({ oid: OID, displayName: 'Dev', roles: options.roles ?? ['ADMIN', 'MODERATOR', 'VIEWER'] }) })
  const router = createAppRouter(createMemoryHistory())
  installAuthGuard(router, { auth: auth.service, session, api })
  return { router, auth }
}

describe('exemption review routes', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it.each(PATHS)('opens the %s page for an administrator', async (_page, path) => {
    const { router } = setup()

    await router.push(path)

    expect(router.currentRoute.value.fullPath).toBe(path)
  })

  it.each(PATHS)('refuses the %s page to a viewer', async (_page, path) => {
    const { router } = setup({ roles: ['VIEWER'] })

    await router.push(path)

    expect(router.currentRoute.value.name).toBe('no-access')
  })

  it.each(PATHS)('refuses the %s page to a moderator without administrator rights', async (_page, path) => {
    const { router } = setup({ roles: ['MODERATOR', 'VIEWER'] })

    await router.push(path)

    expect(router.currentRoute.value.name).toBe('no-access')
  })

  it.each(PATHS)('sends a signed-out visitor to sign-in before the %s page', async (_page, path) => {
    const { router, auth } = setup({ token: null })

    await router.push(path)

    expect(auth.calls.signIn).toEqual([path])
    expect(router.currentRoute.value.name, 'no page was shown').toBeUndefined()
  })
})
