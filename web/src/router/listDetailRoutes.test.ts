import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { fakeAuthService } from '../test/auth'
import { mockApi, ok } from '../test/mount'
import { installAuthGuard } from './authGuard'
import { createAppRouter } from './index'

const OID = '00000000-0000-4000-8000-0000000000a1'

function setup(options: { token?: string | null; roles?: string[] } = {}) {
  setActivePinia(createPinia())
  const auth = fakeAuthService({ token: options.token === undefined ? 'token' : options.token })
  const session = useSessionStore()
  const me = vi.fn(async () => ok({ oid: OID, displayName: 'Dev Admin', roles: options.roles ?? ['MODERATOR'] }))
  const router = createAppRouter(createMemoryHistory())
  installAuthGuard(router, { auth: auth.service, session, api: mockApi({ me }) })
  return { router, auth }
}

describe('list and detail routes', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('resolves a case address to the case route, with the reference as its parameter', () => {
    const { router } = setup()

    const resolved = router.resolve('/cases/p-0001?status=OPEN')

    expect(resolved.name).toBe('case')
    expect(resolved.params.reference).toBe('p-0001')
    expect(resolved.query).toEqual({ status: 'OPEN' })
  })

  it('keeps the close and reopen confirmations as child routes of the case', () => {
    const { router } = setup()

    expect(router.resolve('/cases/p-0001/close').name).toBe('case-close')
    expect(router.resolve('/cases/p-0001/reopen').name).toBe('case-reopen')
  })

  it('resolves a report address to the report route, with the id as its parameter', () => {
    const { router } = setup()

    const resolved = router.resolve('/reports/3f2b8c1e-0000-4000-8000-000000000001')

    expect(resolved.name).toBe('report')
    expect(resolved.params.id).toBe('3f2b8c1e-0000-4000-8000-000000000001')
  })

  it('sends a visitor without a session to sign-in before a case is requested', async () => {
    const { router, auth } = setup({ token: null })

    await router.push('/cases/p-0001')

    expect(auth.calls.signIn).toEqual(['/cases/p-0001'])
    expect(router.currentRoute.value.name, 'the navigation was cancelled').toBeUndefined()
  })

  it('shows no case and no report to a user without a role', async () => {
    const { router } = setup({ roles: [] })

    await router.push('/cases/p-0001')
    expect(router.currentRoute.value.name).toBe('no-access')

    await router.push('/reports/3f2b8c1e-0000-4000-8000-000000000001')
    expect(router.currentRoute.value.name).toBe('no-access')
  })
})
