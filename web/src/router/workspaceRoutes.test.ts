import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { LAST_WORKSPACE_STORAGE_KEY } from '../nav/workspaces'
import { useSessionStore } from '../stores/session'
import { fakeAuthService } from '../test/auth'
import { mockApi, ok } from '../test/mount'
import { installAuthGuard } from './authGuard'
import { createAppRouter } from './index'

const OID = '00000000-0000-4000-8000-0000000000a1'

function setup(roles: string[]) {
  setActivePinia(createPinia())
  const auth = fakeAuthService()
  const session = useSessionStore()
  const me = vi.fn(async () => ok({ oid: OID, displayName: 'Dev', roles }))
  const api = mockApi({ me })
  const router = createAppRouter(createMemoryHistory())
  installAuthGuard(router, { auth: auth.service, session, api })
  return { router }
}

describe('old configuration URLs', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('moves /templates to /admin/templates and keeps the query string', async () => {
    const { router } = setup(['ADMIN', 'MODERATOR', 'VIEWER'])

    await router.push('/templates?page=2')

    expect(router.currentRoute.value.fullPath).toBe('/admin/templates?page=2')
  })

  it('moves /admin to the templates home', async () => {
    const { router } = setup(['ADMIN', 'MODERATOR', 'VIEWER'])

    await router.push('/admin')

    expect(router.currentRoute.value.fullPath).toBe('/admin/templates')
  })

  it.each([
    ['/sections', '/admin/sections'],
    ['/client-rules', '/admin/client-rules'],
    ['/appeal-conditions', '/admin/appeal-terms'],
  ])('moves %s to %s', async (from, to) => {
    const { router } = setup(['ADMIN', 'MODERATOR', 'VIEWER'])

    await router.push(from)

    expect(router.currentRoute.value.fullPath).toBe(to)
  })

  it('sends a moderator who opens a configuration URL to the no-access page', async () => {
    const { router } = setup(['MODERATOR', 'VIEWER'])

    await router.push('/sections')

    expect(router.currentRoute.value.name).toBe('no-access')
  })
})

describe('administration routes', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('refuses /admin/templates to a moderator', async () => {
    const { router } = setup(['MODERATOR', 'VIEWER'])

    await router.push('/admin/templates')

    expect(router.currentRoute.value.name).toBe('no-access')
  })

  it.each([['/admin/staff'], ['/admin/imports'], ['/admin/cloudnet']])('opens %s for an administrator', async (path) => {
    const { router } = setup(['ADMIN', 'MODERATOR', 'VIEWER'])

    await router.push(path)

    expect(router.currentRoute.value.fullPath).toBe(path)
  })
})

describe('stored workspace on the bare root', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('opens the administration home for an administrator who last used it', async () => {
    localStorage.setItem(LAST_WORKSPACE_STORAGE_KEY, 'administration')
    const { router } = setup(['ADMIN', 'MODERATOR', 'VIEWER'])

    await router.push('/')

    expect(router.currentRoute.value.fullPath).toBe('/admin/templates')
  })

  it('keeps the inbox for a moderator, whatever is stored', async () => {
    localStorage.setItem(LAST_WORKSPACE_STORAGE_KEY, 'administration')
    const { router } = setup(['MODERATOR', 'VIEWER'])

    await router.push('/')

    expect(router.currentRoute.value.fullPath).toBe('/')
  })

  it('does not redirect an explicit URL', async () => {
    localStorage.setItem(LAST_WORKSPACE_STORAGE_KEY, 'administration')
    const { router } = setup(['ADMIN', 'MODERATOR', 'VIEWER'])

    await router.push('/cases')

    expect(router.currentRoute.value.fullPath).toBe('/cases')
  })

  it('shows the inbox when storage throws', async () => {
    const original = Storage.prototype.getItem
    Storage.prototype.getItem = () => {
      throw new Error('blocked')
    }
    try {
      const { router } = setup(['ADMIN', 'MODERATOR', 'VIEWER'])

      await router.push('/')

      expect(router.currentRoute.value.fullPath).toBe('/')
    } finally {
      Storage.prototype.getItem = original
    }
  })
})
