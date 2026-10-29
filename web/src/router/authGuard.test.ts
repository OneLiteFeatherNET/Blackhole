import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { fakeAuthService, type FakeAuthOptions } from '../test/auth'
import { fail, mockApi, ok } from '../test/mount'
import { installAuthGuard } from './authGuard'
import { createAppRouter } from './index'

const OID = '00000000-0000-4000-8000-0000000000a1'

function setup(options: { auth?: FakeAuthOptions; roles?: string[]; me?: () => unknown } = {}) {
  setActivePinia(createPinia())
  const auth = fakeAuthService(options.auth)
  const session = useSessionStore()
  const me = vi.fn(options.me ?? (async () => ok({ oid: OID, displayName: 'Dev Admin', roles: options.roles ?? ['ADMIN', 'MODERATOR', 'VIEWER'] })))
  const api = mockApi({ me })
  const router = createAppRouter(createMemoryHistory())
  installAuthGuard(router, { auth: auth.service, session, api })
  return { router, auth, session, me }
}

describe('router guard', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('sends a visitor without a session to the authority and keeps the route they asked for', async () => {
    const { router, auth } = setup({ auth: { token: null } })

    await router.push('/players?q=1')

    expect(auth.calls.signIn).toEqual(['/players?q=1'])
    expect(router.currentRoute.value.name, 'the navigation was cancelled').toBeUndefined()
  })

  it('sends a visitor to an unknown path through the sign-in first, keeping that path', async () => {
    const { router, auth } = setup({ auth: { token: null } })

    await router.push('/gibt-es-nicht')

    expect(auth.calls.signIn).toEqual(['/gibt-es-nicht'])
  })

  it('shows the not-found route to a signed-in user for an unknown path', async () => {
    const { router } = setup()

    await router.push('/gibt-es-nicht')

    expect(router.currentRoute.value.name).toBe('not-found')
  })

  it('keeps a user without a role on the no-access page even for an unknown path', async () => {
    const { router } = setup({ roles: [] })

    await router.push('/gibt-es-nicht')

    expect(router.currentRoute.value.name).toBe('no-access')
  })

  it('does not ask the backend who the visitor is before they have a token', async () => {
    const { router, me } = setup({ auth: { token: null } })

    await router.push('/')

    expect(me).not.toHaveBeenCalled()
  })

  it('completes the sign-in at the callback with the callback URL and shows the route asked for', async () => {
    const { router, auth } = setup({ auth: { returnTo: '/reports' } })

    await router.push('/auth/callback?code=c&state=s')

    expect(auth.calls.completeSignIn).toEqual([`${window.location.origin}/auth/callback?code=c&state=s`])
    expect(router.currentRoute.value.fullPath).toBe('/reports')
  })

  it('shows the error page when the callback is rejected', async () => {
    const { router } = setup({ auth: { completeFails: true } })

    await router.push('/auth/callback?error=access_denied')

    expect(router.currentRoute.value.name).toBe('auth-error')
    expect(router.currentRoute.value.query.reason).toBe('callback')
  })

  it('shows the error page when the authority cannot be reached', async () => {
    const { router } = setup({ auth: { token: null, signInFails: true } })

    await router.push('/')

    expect(router.currentRoute.value.name).toBe('auth-error')
    expect(router.currentRoute.value.query.reason).toBe('signin')
  })

  it('lets the error page open without a session, so a failed sign-in cannot loop', async () => {
    const { router, auth } = setup({ auth: { token: null } })

    await router.push('/auth/error')

    expect(router.currentRoute.value.name).toBe('auth-error')
    expect(auth.calls.signIn, 'sign-ins').toEqual([])
  })

  it('loads the caller once and opens the route for a signed-in user with a role', async () => {
    const { router, session, me } = setup()

    await router.push('/players')
    await router.push('/templates')

    expect(router.currentRoute.value.name).toBe('admin-templates')
    expect(me, 'calls of /auth/me').toHaveBeenCalledTimes(1)
    expect(session.displayName).toBe('Dev Admin')
  })

  it('keeps a signed-in user without any role on the no-access page', async () => {
    const { router } = setup({ roles: [] })

    await router.push('/players')

    expect(router.currentRoute.value.name).toBe('no-access')
  })

  it('does not let a user without a role navigate anywhere else', async () => {
    const { router } = setup({ roles: [] })
    await router.push('/')

    await router.push('/reports')

    expect(router.currentRoute.value.name).toBe('no-access')
  })

  it('treats a metrics-only role as no access', async () => {
    const { router } = setup({ roles: ['METRICS'] })

    await router.push('/')

    expect(router.currentRoute.value.name).toBe('no-access')
  })

  it('sends a user with a role away from the no-access page', async () => {
    const { router } = setup({ roles: ['VIEWER'] })

    await router.push('/no-access')

    expect(router.currentRoute.value.name).toBe('inbox')
  })

  it.each([['VIEWER'], ['MODERATOR']])('sends a %s who opens the admin page to the no-access page with the admin reason', async (role) => {
    const { router } = setup({ roles: [role] })

    await router.push('/admin')

    expect(router.currentRoute.value.name, 'route').toBe('no-access')
    expect(router.currentRoute.value.query.reason, 'reason').toBe('admin')
  })

  it('opens the admin page for an admin', async () => {
    const { router } = setup({ roles: ['ADMIN'] })

    await router.push('/admin')

    expect(router.currentRoute.value.name).toBe('admin-templates')
  })

  it.each([
    ['/admin/staff', 'admin-staff'],
    ['/admin/rollback', 'admin-rollback'],
    ['/admin/imports', 'admin-imports'],
    ['/admin/cloudnet', 'admin-cloudnet'],
    ['/admin/kick', 'admin-kick'],
  ])('keeps every administration tab such as %s away from roles other than the administrator', async (path, name) => {
    for (const roles of [['VIEWER'], ['MODERATOR']]) {
      const { router } = setup({ roles })
      await router.push(path)
      expect(router.currentRoute.value.name, `${roles} route`).toBe('no-access')
      expect(router.currentRoute.value.query.reason, `${roles} reason`).toBe('admin')
    }
    const { router } = setup({ roles: ['ADMIN'] })
    await router.push(path)
    expect(router.currentRoute.value.name, 'administrator route').toBe(name)
  })

  it('keeps a viewer on the admin no-access page instead of bouncing to the dashboard', async () => {
    const { router } = setup({ roles: ['VIEWER'] })

    await router.push('/admin')

    expect(router.currentRoute.value.fullPath).toBe('/no-access?reason=admin')
  })

  it('shows the error page when the caller cannot be loaded', async () => {
    const { router } = setup({ me: async () => fail('boom', 500, 'UNKNOWN') })

    await router.push('/')

    expect(router.currentRoute.value.name).toBe('auth-error')
    expect(router.currentRoute.value.query.reason).toBe('me')
  })

  it('stays put when /auth/me answers 401, because sign-in is already starting again', async () => {
    const { router } = setup({ me: async () => fail('expired', 401, 'UNKNOWN') })

    await router.push('/players')

    expect(router.currentRoute.value.name, 'the navigation was cancelled').toBeUndefined()
  })
})
