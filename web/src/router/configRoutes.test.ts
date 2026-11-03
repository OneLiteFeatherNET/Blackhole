import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { fakeAuthService } from '../test/auth'
import { mockApi, ok } from '../test/mount'
import { installAuthGuard } from './authGuard'
import { createAppRouter } from './index'

const OID = '00000000-0000-4000-8000-0000000000a1'

/** Every canonical address of the configuration pages (`restructure-staff-panel`, decision 7, rows 27 to 34). */
const CONFIG_ROUTES: Array<[string, string]> = [
  ['/admin/templates', 'admin-templates'],
  ['/admin/templates/new', 'admin-template-new'],
  ['/admin/templates/id-hacking', 'admin-template'],
  ['/admin/templates/id-hacking/edit', 'admin-template-edit'],
  ['/admin/sections', 'admin-sections'],
  ['/admin/sections/new', 'admin-section-new'],
  ['/admin/sections/chat', 'admin-section'],
  ['/admin/sections/chat/edit', 'admin-section-edit'],
  ['/admin/sections/chat/disable', 'admin-section-disable'],
  ['/admin/client-rules', 'admin-client-rules'],
  ['/admin/client-rules/new', 'admin-client-rule-new'],
  ['/admin/client-rules/meteor', 'admin-client-rule'],
  ['/admin/client-rules/meteor/edit', 'admin-client-rule-edit'],
  ['/admin/appeal-terms', 'admin-appeal-terms'],
  ['/admin/appeal-terms/new', 'admin-appeal-terms-new'],
  ['/admin/appeal-terms/versions/3', 'admin-appeal-terms-version'],
]

function setup(roles: string[]) {
  setActivePinia(createPinia())
  const auth = fakeAuthService()
  const session = useSessionStore()
  const me = vi.fn(async () => ok({ oid: OID, displayName: 'Dev', roles }))
  const router = createAppRouter(createMemoryHistory())
  installAuthGuard(router, { auth: auth.service, session, api: mockApi({ me }) })
  return { router }
}

describe('configuration addresses', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it.each(CONFIG_ROUTES)('resolves %s to the route %s', (path, name) => {
    const router = createAppRouter(createMemoryHistory())

    expect(router.resolve(path).name).toBe(name)
  })

  it.each(CONFIG_ROUTES)('marks %s as administrator only', (path) => {
    const router = createAppRouter(createMemoryHistory())

    expect(router.resolve(path).meta.admin).toBe(true)
  })

  it.each(CONFIG_ROUTES)('sends a moderator who opens %s to the no-access page with the admin reason', async (path) => {
    const { router } = setup(['MODERATOR', 'VIEWER'])

    await router.push(path)

    expect(router.currentRoute.value.name).toBe('no-access')
    expect(router.currentRoute.value.query.reason).toBe('admin')
  })

  it.each(CONFIG_ROUTES)('sends a viewer who opens %s to the no-access page', async (path) => {
    const { router } = setup(['VIEWER'])

    await router.push(path)

    expect(router.currentRoute.value.name).toBe('no-access')
  })

  it.each(CONFIG_ROUTES)('opens %s for an administrator', async (path, name) => {
    const { router } = setup(['ADMIN', 'MODERATOR', 'VIEWER'])

    await router.push(path)

    expect(router.currentRoute.value.name).toBe(name)
  })

  it('keeps the tab of a client rule in the query', async () => {
    const { router } = setup(['ADMIN', 'MODERATOR', 'VIEWER'])

    await router.push('/admin/client-rules/meteor?tab=hits')

    expect(router.currentRoute.value.name).toBe('admin-client-rule')
    expect(router.currentRoute.value.query.tab).toBe('hits')
  })
})
