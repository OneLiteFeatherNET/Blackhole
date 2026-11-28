import { describe, expect, it, vi } from 'vitest'
import { mockApi, mountWithApp, ok } from '../test/mount'
import AdminView from './AdminView.vue'

// Every call the area can make; a test that wants a call to fail or return data overrides one of them.
function adminApi(overrides: Record<string, unknown> = {}) {
  return {
    staffActors: vi.fn(async () => ok([])),
    staffLinks: vi.fn(async () => ok([])),
    discordLinks: vi.fn(async () => ok([])),
    templates: vi.fn(async () => ok([])),
    sections: vi.fn(async () => ok([])),
    importedPunishmentGroups: vi.fn(async () => ok({ total: 0, groups: [] })),
    cloudnetCatalogue: vi.fn(async () => ok({ available: false, tasks: [], groups: [], services: [], proxies: [], reportedAt: null })),
    auditLog: vi.fn(async () => ok({ items: [], nextCursor: null })),
    ...overrides,
  }
}

// The system pages of the Administration workspace: one route each, each a rail item (no tab strip any more).
describe('AdminView system pages', () => {
  it('shows the staff accounts page at /admin/staff with its own heading and no tabs', async () => {
    const { wrapper } = await mountWithApp(AdminView, { api: mockApi(adminApi()), roles: ['ADMIN'], path: '/admin/staff' })

    expect(wrapper.get('h1').text()).toBe('Staff accounts')
    expect(wrapper.find('[role="tablist"]').exists(), 'no tab strip').toBe(false)
    expect(wrapper.find('[data-testid="staff-actors"]').exists(), 'staff panel').toBe(true)
    expect(wrapper.find('[data-testid="vanilla-import"]').exists(), 'no import panel').toBe(false)

    wrapper.unmount()
  })

  it('shows the imports page at /admin/imports with the vanilla import as its first tab', async () => {
    const { wrapper } = await mountWithApp(AdminView, { api: mockApi(adminApi()), roles: ['ADMIN'], path: '/admin/imports' })

    expect(wrapper.get('h1').text()).toBe('Imports')
    expect(wrapper.find('[data-testid="admin-imports"]').exists(), 'imports page').toBe(true)
    expect(wrapper.find('[data-testid="imported-punishments"]').exists(), 'no groups on the first tab').toBe(false)
    expect(wrapper.find('[data-testid="staff-actors"]').exists(), 'no staff panel').toBe(false)

    wrapper.unmount()
  })

  it('shows the imported punishments on their own tab', async () => {
    const { wrapper } = await mountWithApp(AdminView, { api: mockApi(adminApi()), roles: ['ADMIN'], path: '/admin/imports?tab=punishments' })

    expect(wrapper.find('[data-testid="imported-punishments"]').exists(), 'imported punishments panel').toBe(true)

    wrapper.unmount()
  })

  it('shows one CloudNet entry at its own address and not the catalogue', async () => {
    const { wrapper } = await mountWithApp(AdminView, { api: mockApi(adminApi()), roles: ['ADMIN'], path: '/admin/cloudnet/tasks/BedWars' })

    expect(wrapper.get('h1').text()).toBe('CloudNet')
    expect(wrapper.find('[data-testid="cloudnet-entry"]').exists(), 'entry').toBe(true)
    expect(wrapper.find('[data-testid="cloudnet-catalogue"]').exists(), 'no catalogue').toBe(false)

    wrapper.unmount()
  })

  it('shows the mass-kick dialog over the kick page at /admin/kick/new', async () => {
    const { wrapper } = await mountWithApp(AdminView, { api: mockApi(adminApi()), roles: ['ADMIN'], path: '/admin/kick/new' })

    expect(wrapper.get('h1').text()).toBe('Mass kick')
    expect(wrapper.find('[data-testid="mass-kick"]').exists(), 'mass kick form').toBe(true)

    wrapper.unmount()
  })

  it('shows the rollback preview dialog over the rollback page at /admin/rollback/new', async () => {
    const { wrapper } = await mountWithApp(AdminView, { api: mockApi(adminApi()), roles: ['ADMIN'], path: '/admin/rollback/new' })

    expect(wrapper.get('h1').text()).toBe('Staff rollback')
    expect(wrapper.find('[data-testid="rollback-new-form"]').exists(), 'preview form').toBe(true)

    wrapper.unmount()
  })

  it('shows the CloudNet catalogue on the CloudNet page only', async () => {
    const { wrapper } = await mountWithApp(AdminView, { api: mockApi(adminApi()), roles: ['ADMIN'], path: '/admin/cloudnet' })

    expect(wrapper.get('h1').text()).toBe('CloudNet')
    expect(wrapper.find('[data-testid="cloudnet-catalogue"]').exists(), 'catalogue panel').toBe(true)
    expect(wrapper.find('[data-testid="vanilla-import"]').exists(), 'no import panel').toBe(false)

    wrapper.unmount()
  })
})

describe('AdminView audit log', () => {
  it('shows the read-only audit log to an administrator at /admin/audit', async () => {
    const api = adminApi({ auditLog: vi.fn(async () => ok({ items: [], nextCursor: null })) })
    const { wrapper } = await mountWithApp(AdminView, { api: mockApi(api), roles: ['ADMIN'], path: '/admin/audit' })

    expect(wrapper.get('h1').text()).toBe('Audit log')
    expect(wrapper.find('[data-testid="audit-log"]').exists(), 'audit panel').toBe(true)
    expect(api.auditLog).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  it.each([['MODERATOR'], ['VIEWER']])('hides the audit log from a %s and requests nothing of it', async (role) => {
    const api = adminApi({ auditLog: vi.fn(async () => ok({ items: [], nextCursor: null })) })
    const { wrapper } = await mountWithApp(AdminView, { api: mockApi(api), roles: [role], path: '/admin/audit' })

    expect(wrapper.find('[data-testid="audit-log"]').exists(), 'no audit panel').toBe(false)
    expect(api.auditLog, 'no request').not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('AdminView for other roles', () => {
  it.each([['MODERATOR'], ['VIEWER']])('shows a %s no panel and requests nothing of the area', async (role) => {
    const api = adminApi()
    const { wrapper } = await mountWithApp(AdminView, { api: mockApi(api), roles: [role], path: '/admin/staff' })

    expect(wrapper.find('[data-testid="staff-actors"]').exists(), 'no panel').toBe(false)
    expect(api.staffActors, 'no request').not.toHaveBeenCalled()
    expect(api.cloudnetCatalogue, 'no request').not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('AdminView staff rollback page', () => {
  it('shows the rollback page at /admin/rollback with its own heading and not the staff list', async () => {
    const { wrapper } = await mountWithApp(AdminView, { api: mockApi(adminApi()), roles: ['ADMIN'], path: '/admin/rollback' })

    expect(wrapper.get('h1').text()).toBe('Staff rollback')
    expect(wrapper.find('[data-testid="staff-rollback"]').exists(), 'rollback page').toBe(true)
    expect(wrapper.find('[data-testid="staff-actors"]').exists(), 'no staff panel').toBe(false)

    wrapper.unmount()
  })
})
