import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { LAST_WORKSPACE_STORAGE_KEY } from '../nav/workspaces'
import { useSessionStore } from '../stores/session'
import { fakeAuthService, type FakeAuthOptions } from '../test/auth'
import { mockApi, ok } from '../test/mount'
import { installAuthGuard } from './authGuard'
import { createAppRouter } from './index'

const OID = '00000000-0000-4000-8000-0000000000a1'
const OWNER = 'ab'.repeat(32)

function setup(options: { auth?: FakeAuthOptions; roles?: string[] } = {}) {
  setActivePinia(createPinia())
  const auth = fakeAuthService(options.auth)
  const session = useSessionStore()
  const me = vi.fn(async () => ok({ oid: OID, displayName: 'Dev', roles: options.roles ?? ['ADMIN', 'MODERATOR', 'VIEWER'] }))
  const router = createAppRouter(createMemoryHistory())
  installAuthGuard(router, { auth: auth.service, session, api: mockApi({ me }) })
  return { router, auth }
}

describe('old addresses redirect and keep their query string', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it.each([
    ['/appeals?show=all', '/appeals?status=all'],
    ['/appeals?show=all&q=x', '/appeals?status=all&q=x'],
    ['/reports?show=all', '/reports?status=all'],
    ['/templates?q=ban', '/admin/templates?q=ban'],
    ['/sections?page=2', '/admin/sections?page=2'],
    ['/client-rules', '/admin/client-rules'],
    ['/appeal-conditions?x=1', '/admin/appeal-terms?x=1'],
    ['/cases/A1B2C3D4/timeline', '/cases/A1B2C3D4?tab=notes-audit'],
    ['/cases/A1B2C3D4/timeline?status=OPEN', '/cases/A1B2C3D4?status=OPEN&tab=notes-audit'],
    ['/cases/A1B2C3D4/timeline#event-7f3c2a10', '/cases/A1B2C3D4?tab=notes-audit#event-7f3c2a10'],
  ])('moves %s to %s', async (from, to) => {
    const { router } = setup()

    await router.push(from)

    expect(router.currentRoute.value.fullPath).toBe(to)
  })

  it('drops an old show value that has no new spelling and keeps the other keys', async () => {
    const { router } = setup()

    await router.push('/appeals?show=pending&x=1')

    expect(router.currentRoute.value.fullPath).toBe('/appeals?x=1')
  })

  it('shows the not-found view for an address that never existed, not a blank page', async () => {
    const { router } = setup()

    await router.push('/cases/A1B2C3D4/notes')

    expect(router.currentRoute.value.name).toBe('not-found')
  })
})

describe('invalid list values are replaced, not errors', () => {
  it('removes an unknown status from the address and keeps the rest', async () => {
    const { router } = setup()

    await router.push('/appeals?status=bogus&x=1')

    expect(router.currentRoute.value.fullPath).toBe('/appeals?x=1')
    expect(router.currentRoute.value.name).toBe('appeals')
  })

  it('removes a name given as the report player and keeps the valid owner', async () => {
    const { router } = setup()

    await router.push('/reports?player=Steve')
    expect(router.currentRoute.value.fullPath).toBe('/reports')

    await router.push(`/reports?player=${OWNER}&status=resolved`)
    expect(router.currentRoute.value.fullPath).toBe(`/reports?player=${OWNER}&status=resolved`)
  })

  it('keeps the list of the open case, so Back to a case returns to the same filter', async () => {
    const { router } = setup()

    await router.push('/cases/A1B2C3D4?status=IN_REVIEW&view=player')

    expect(router.currentRoute.value.fullPath).toBe('/cases/A1B2C3D4?status=IN_REVIEW&view=player')
  })
})

describe('the redirects are behind the sign-in and the access checks', () => {
  it('sends a visitor who is not signed in to the sign-in with the new address, not the old one', async () => {
    const { router, auth } = setup({ auth: { token: null } })

    await router.push('/cases/A1B2C3D4/timeline')

    expect(auth.calls.signIn).toEqual(['/cases/A1B2C3D4?tab=notes-audit'])
    expect(router.currentRoute.value.name).not.toBe('case')
  })

  it('sends a signed-in visitor without any role to the no-access page', async () => {
    const { router } = setup({ roles: [] })

    await router.push('/appeals?show=all')

    expect(router.currentRoute.value.name).toBe('no-access')
  })
})

describe('list state is never written to storage', () => {
  it('moves through list addresses without a write to local or session storage', async () => {
    const writes: string[] = []
    const failing = {
      getItem: () => null,
      removeItem: () => undefined,
      clear: () => undefined,
      key: () => null,
      length: 0,
      setItem: (key: string) => {
        writes.push(key)
      },
    }
    vi.stubGlobal('localStorage', failing)
    vi.stubGlobal('sessionStorage', failing)
    try {
      const { router } = setup()

      await router.push('/cases?status=OPEN&view=player&page=2')
      await router.push('/appeals?status=all')
      await router.push('/reports?status=all&player=' + OWNER)
      await router.push('/punished?kind=BAN&sort=expires-asc')

      expect(writes.filter((key) => key !== LAST_WORKSPACE_STORAGE_KEY), 'list state written to storage').toEqual([])
    } finally {
      vi.unstubAllGlobals()
    }
  })
})
