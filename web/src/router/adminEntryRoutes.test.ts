import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import type { BlackholeApi } from '../api/blackhole'
import { useSessionStore } from '../stores/session'
import { fakeAuthService, type FakeAuthOptions } from '../test/auth'
import { ok } from '../test/mount'
import { installAuthGuard } from './authGuard'
import { createAppRouter } from './index'

const OID = '00000000-0000-4000-8000-0000000000a1'
const UUID = '11111111-1111-4111-8111-111111111111'

// Every route this change adds to the Administration workspace, with a concrete address for it.
// `page` is the rail item the address belongs to (`meta.nav`).
const ENTRY_ROUTES: Array<{ name: string; path: string; page: string }> = [
  { name: 'admin-rollback-new', path: '/admin/rollback/new', page: '/admin/rollback' },
  { name: 'admin-rollback-job', path: `/admin/rollback/${UUID}`, page: '/admin/rollback' },
  { name: 'admin-rollback-execute', path: `/admin/rollback/${UUID}/execute`, page: '/admin/rollback' },
  { name: 'admin-rollback-cancel', path: `/admin/rollback/${UUID}/cancel`, page: '/admin/rollback' },
  { name: 'admin-kick-new', path: '/admin/kick/new', page: '/admin/kick' },
  { name: 'admin-kick-detail', path: `/admin/kick/${UUID}`, page: '/admin/kick' },
  { name: 'admin-imports-vanilla-new', path: '/admin/imports/vanilla/new', page: '/admin/imports' },
  { name: 'admin-audit-entry', path: `/admin/audit/${UUID}`, page: '/admin/audit' },
  { name: 'admin-cloudnet-entry', path: '/admin/cloudnet/tasks/lobby', page: '/admin/cloudnet' },
]

/** A fake API that records every call: a route that sends a request for staff data shows up in `calls`. */
function setup(options: { auth?: FakeAuthOptions; roles?: string[] } = {}) {
  setActivePinia(createPinia())
  const auth = fakeAuthService(options.auth)
  const session = useSessionStore()
  const calls: string[] = []
  const me = vi.fn(async () => ok({ oid: OID, displayName: 'Dev', roles: options.roles ?? ['ADMIN'] }))
  const api = new Proxy({ me } as unknown as BlackholeApi, {
    get(target, name: string) {
      if (name in target) {
        return (...args: unknown[]) => {
          calls.push(name)
          return (target as unknown as Record<string, (...a: unknown[]) => unknown>)[name]!(...args)
        }
      }
      return () => {
        calls.push(name)
        throw new Error(`unexpected API call: ${name}`)
      }
    },
  })
  const router = createAppRouter(createMemoryHistory())
  installAuthGuard(router, { auth: auth.service, session, api })
  return { router, auth, calls }
}

describe('admin entry routes: administrators', () => {
  it.each(ENTRY_ROUTES)('opens $name for an administrator', async ({ name, path }) => {
    const { router } = setup()

    await router.push(path)

    expect(router.currentRoute.value.name).toBe(name)
  })

  it.each(ENTRY_ROUTES)('keeps the rail item of $page current on $name', ({ path, page }) => {
    const { router } = setup()

    expect(router.resolve(path).meta.nav).toBe(page)
  })
})

describe('admin entry routes: other roles and visitors', () => {
  it.each(ENTRY_ROUTES)('sends a MODERATOR on $name to the no-access page with no staff request', async ({ path }) => {
    const { router, calls } = setup({ roles: ['MODERATOR'] })

    await router.push(path)

    expect(router.currentRoute.value.name).toBe('no-access')
    expect(calls.filter((call) => call !== 'me'), 'no request for admin data').toEqual([])
  })

  it.each(ENTRY_ROUTES)('sends a VIEWER on $name to the no-access page with no staff request', async ({ path }) => {
    const { router, calls } = setup({ roles: ['VIEWER'] })

    await router.push(path)

    expect(router.currentRoute.value.name).toBe('no-access')
    expect(calls.filter((call) => call !== 'me'), 'no request for admin data').toEqual([])
  })

  it.each(ENTRY_ROUTES)('starts sign-in for an anonymous visitor on $name and requests nothing', async ({ path }) => {
    const { router, auth, calls } = setup({ auth: { token: null } })

    await router.push(path)

    expect(auth.calls.signIn).toEqual([path])
    expect(calls, 'no request before sign-in').toEqual([])
  })
})
