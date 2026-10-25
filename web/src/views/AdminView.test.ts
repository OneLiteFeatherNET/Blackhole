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

  it('shows the import panels on the imports page only', async () => {
    const { wrapper } = await mountWithApp(AdminView, { api: mockApi(adminApi()), roles: ['ADMIN'], path: '/admin/imports' })

    expect(wrapper.get('h1').text()).toBe('Imports')
    expect(wrapper.find('[data-testid="vanilla-import"]').exists(), 'import panel').toBe(true)
    expect(wrapper.find('[data-testid="imported-punishments"]').exists(), 'imported punishments panel').toBe(true)
    expect(wrapper.find('[data-testid="staff-actors"]').exists(), 'no staff panel').toBe(false)

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
