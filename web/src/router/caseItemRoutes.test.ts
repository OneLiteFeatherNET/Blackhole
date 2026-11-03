import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { fakeAuthService } from '../test/auth'
import { mockApi, ok } from '../test/mount'
import { installAuthGuard } from './authGuard'
import { createAppRouter } from './index'

const OID = '00000000-0000-4000-8000-0000000000a1'
const NOTE = '3f2b8c1e-0000-4000-8000-000000000001'
const MESSAGE = '3f2b8c1e-0000-4000-8000-000000000022'
const EVIDENCE = '3f2b8c1e-0000-4000-8000-0000000000e2'
const CASE = 'p-0001'

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

/** Every route of a case item (notes, messages, protocols) and the evidence item page, with the lowest role that may open it. */
const ROUTES: Array<{ path: string; name: string; needs: Exclude<Role, 'ANONYMOUS'> }> = [
  { path: `/cases/${CASE}/notes/new`, name: 'case-note-new', needs: 'MODERATOR' },
  { path: `/cases/${CASE}/notes/${NOTE}/edit`, name: 'case-note-edit', needs: 'MODERATOR' },
  { path: `/cases/${CASE}/notes/${NOTE}/redact`, name: 'case-note-redact', needs: 'ADMIN' },
  { path: `/cases/${CASE}/messages/${MESSAGE}/edit`, name: 'case-message-edit', needs: 'MODERATOR' },
  { path: `/cases/${CASE}/messages/${MESSAGE}/redact`, name: 'case-message-redact', needs: 'ADMIN' },
  { path: `/cases/${CASE}/protocols/${EVIDENCE}`, name: 'case-protocol', needs: 'VIEWER' },
  { path: `/evidence/${EVIDENCE}`, name: 'evidence-item', needs: 'VIEWER' },
  { path: `/evidence/${EVIDENCE}/delete`, name: 'evidence-delete', needs: 'ADMIN' },
]

describe('the case item routes', () => {
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

  it.each(ROUTES)('sends an anonymous caller to sign-in before $path is opened', async ({ path }) => {
    const { router, auth } = setup('ANONYMOUS')

    await router.push(path)

    expect(auth.calls.signIn).toEqual([path])
    expect(router.currentRoute.value.name).not.toBe(router.resolve(path).name)
  })

  it('keeps the case page as the parent of every note and message route', () => {
    const { router } = setup('ADMIN')

    expect(router.resolve(`/cases/${CASE}/notes/${NOTE}/edit`).matched.map((record) => record.name)).toEqual(['case', 'case-note-edit'])
    expect(router.resolve(`/cases/${CASE}/messages/${MESSAGE}/redact`).matched.map((record) => record.name)).toEqual(['case', 'case-message-redact'])
  })

  it('takes no Ban ID, name or secret in an address: the note, message, protocol and evidence ids only', () => {
    const { router } = setup('ADMIN')

    for (const { path } of ROUTES) {
      expect(router.resolve(path).params, path).not.toHaveProperty('banId')
      expect(router.resolve(path).fullPath, path).not.toMatch(/ban|token|ip=|name=/i)
    }
  })
})
