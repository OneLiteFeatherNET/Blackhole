import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { fakeAuthService } from '../test/auth'
import { mockApi, ok } from '../test/mount'
import { installAuthGuard } from './authGuard'
import { createAppRouter } from './index'

const OID = '00000000-0000-4000-8000-0000000000a1'
const BAN_ID = '6f1c2b3a-4d5e-4f60-8a7b-9c0d1e2f3a4b'
const OWNER = 'b'.repeat(128)

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

/** Every route of the moderation lists, with its name, a concrete path and the lowest role that may open it. */
const ROUTES: Array<{ path: string; name: string; needs: Exclude<Role, 'ANONYMOUS'> }> = [
  { path: '/ip-bans', name: 'ip-bans', needs: 'VIEWER' },
  { path: '/ip-bans/new', name: 'ip-ban-new', needs: 'MODERATOR' },
  { path: `/ip-bans/${BAN_ID}`, name: 'ip-ban', needs: 'VIEWER' },
  { path: `/ip-bans/${BAN_ID}/lift`, name: 'ip-ban-lift', needs: 'MODERATOR' },
  { path: '/area-bans', name: 'area-bans', needs: 'VIEWER' },
  { path: '/area-bans/new', name: 'area-ban-new', needs: 'MODERATOR' },
  { path: `/area-bans/${BAN_ID}`, name: 'area-ban', needs: 'VIEWER' },
  { path: `/area-bans/${BAN_ID}/lift`, name: 'area-ban-lift', needs: 'MODERATOR' },
  { path: '/guard', name: 'guard', needs: 'VIEWER' },
  { path: '/guard/level?level=LOCKDOWN', name: 'guard-level', needs: 'MODERATOR' },
  { path: '/guard/settings', name: 'guard-settings', needs: 'VIEWER' },
  { path: '/guard/settings/edit', name: 'guard-settings-edit', needs: 'ADMIN' },
  { path: '/intel/policy', name: 'intel-policy', needs: 'VIEWER' },
  { path: '/intel/policy/VPN', name: 'intel-policy-class', needs: 'VIEWER' },
  { path: '/intel/policy/VPN/edit', name: 'intel-policy-class-edit', needs: 'ADMIN' },
  { path: '/intel/allow-list', name: 'intel-allow-list', needs: 'VIEWER' },
  { path: '/intel/allow-list/new', name: 'intel-allow-list-new', needs: 'MODERATOR' },
  { path: `/intel/allow-list/${OWNER}`, name: 'intel-allow-entry', needs: 'VIEWER' },
  { path: `/intel/allow-list/${OWNER}/remove`, name: 'intel-allow-entry-remove', needs: 'MODERATOR' },
]

describe('the moderation list routes', () => {
  it.each(ROUTES)('names $path as $name', ({ path, name }) => {
    const { router } = setup('ADMIN')

    expect(router.resolve(path).name).toBe(name)
  })

  it.each(ROUTES)('opens $path for the lowest role that may open it ($needs)', async ({ path, needs }) => {
    const { router } = setup(needs)

    await router.push(path)

    expect(router.currentRoute.value.name).toBe(router.resolve(path).name)
  })

  it.each(ROUTES.filter((route) => route.needs !== 'VIEWER'))('sends a viewer away from $path', async ({ path, needs }) => {
    const { router } = setup('VIEWER')

    await router.push(path)

    expect(router.currentRoute.value.name).toBe('no-access')
    expect(router.currentRoute.value.query.reason).toBe(needs === 'ADMIN' ? 'admin' : 'moderator')
  })

  it.each(ROUTES.filter((route) => route.needs === 'ADMIN'))('sends a moderator without administrator rights away from $path', async ({ path }) => {
    const { router } = setup('MODERATOR')

    await router.push(path)

    expect(router.currentRoute.value.name).toBe('no-access')
    expect(router.currentRoute.value.query.reason).toBe('admin')
  })

  it.each(ROUTES)('asks an anonymous caller to sign in before $path and requests no staff data', async ({ path }) => {
    const { router, auth, me } = setup('ANONYMOUS')

    await router.push(path)

    expect(auth.calls.signIn).toEqual([path])
    expect(me).not.toHaveBeenCalled()
  })
})

describe('the addresses of the moderation lists', () => {
  const ADDRESSES = ['203.0.113.7', '198.51.100.0/24', '2001:db8::/32']

  it.each([
    ['ip-ban', { id: BAN_ID }],
    ['ip-ban-lift', { id: BAN_ID }],
    ['area-ban', { id: BAN_ID }],
    ['area-ban-lift', { id: BAN_ID }],
    ['intel-allow-entry', { owner: OWNER }],
    ['intel-allow-entry-remove', { owner: OWNER }],
  ])('builds the address of %s from its id only, never an address or range', (name, params) => {
    const { router } = setup('ADMIN')

    const path = router.resolve({ name, params }).fullPath

    for (const address of ADDRESSES) expect(path).not.toContain(address)
  })
})
