import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { fakeAuthService } from '../test/auth'
import { mockApi, ok } from '../test/mount'
import { installAuthGuard } from './authGuard'
import { createAppRouter } from './index'
import { allowedReturnTo } from './returnTo'

// The report pages: the list, the report and its two action routes (decision 8, rule 4). The actions need a MODERATOR.
const OID = '00000000-0000-4000-8000-0000000000a1'
const ID = '3f2b8c1e-0000-4000-8000-000000000001'

type Role = 'ADMIN' | 'MODERATOR' | 'VIEWER' | 'ANONYMOUS'

const ROLES: Record<Role, string[]> = {
  ADMIN: ['ADMIN', 'MODERATOR', 'VIEWER'],
  MODERATOR: ['MODERATOR', 'VIEWER'],
  VIEWER: ['VIEWER'],
  ANONYMOUS: [],
}

function setup(role: Role) {
  setActivePinia(createPinia())
  const auth = fakeAuthService({ token: role === 'ANONYMOUS' ? null : 'access-token' })
  const session = useSessionStore()
  const me = vi.fn(async () => ok({ oid: OID, displayName: 'Dev', roles: ROLES[role] }))
  const api = mockApi({ me })
  const router = createAppRouter(createMemoryHistory())
  installAuthGuard(router, { auth: auth.service, session, api })
  return { router, auth, me }
}

const PAGES: Array<{ path: string; name: string }> = [
  { path: '/reports', name: 'reports' },
  { path: `/reports/${ID}`, name: 'report' },
]
const ACTIONS: Array<{ path: string; name: string }> = [
  { path: `/reports/${ID}/resolve`, name: 'report-resolve' },
  { path: `/reports/${ID}/dismiss`, name: 'report-dismiss' },
]

describe('report routes', () => {
  it.each([...PAGES, ...ACTIONS])('names $path as $name', ({ path, name }) => {
    const { router } = setup('ADMIN')

    expect(router.resolve(path).name).toBe(name)
  })

  it.each(PAGES)('opens the $name page for a viewer', async ({ path, name }) => {
    const { router } = setup('VIEWER')

    await router.push(path)

    expect(router.currentRoute.value.name).toBe(name)
  })

  it.each(ACTIONS)('opens $path for a moderator without administrator rights', async ({ path, name }) => {
    const { router } = setup('MODERATOR')

    await router.push(path)

    expect(router.currentRoute.value.name).toBe(name)
  })

  it.each(ACTIONS)('sends a viewer away from $path to the no-access view, not the dialog', async ({ path }) => {
    const { router } = setup('VIEWER')

    await router.push(path)

    expect(router.currentRoute.value.name).toBe('no-access')
    expect(router.currentRoute.value.query.reason).toBe('moderator')
  })

  it.each(ACTIONS)('asks an anonymous caller to sign in before $path and requests no staff data', async ({ path }) => {
    const { router, auth, me } = setup('ANONYMOUS')

    await router.push(path)

    expect(auth.calls.signIn).toEqual([path])
    expect(me).not.toHaveBeenCalled()
  })

  it('keeps the action routes on the return allow-list, so a sign-in comes back to the dialog', () => {
    const router = createAppRouter(createMemoryHistory())

    expect(allowedReturnTo(router, `/reports/${ID}/resolve`)).toBe(`/reports/${ID}/resolve`)
    expect(allowedReturnTo(router, `/reports/${ID}/dismiss?status=open`)).toBe(`/reports/${ID}/dismiss?status=open`)
  })
})
