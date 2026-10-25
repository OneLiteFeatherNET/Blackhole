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

describe('AdminView tabs', () => {
  it('names the area Administration and offers the tabs Staff accounts, Imports and CloudNet', async () => {
    const { wrapper } = await mountWithApp(AdminView, { api: mockApi(adminApi()), roles: ['ADMIN'], path: '/admin/staff' })

    expect(wrapper.get('h1').text()).toBe('Administration')
    expect(wrapper.findAll('[role="tab"]').map((tab) => tab.text()), 'tabs').toEqual(['Staff accounts', 'Imports', 'CloudNet'])

    wrapper.unmount()
  })

  it('opens the staff accounts tab at /admin and marks it selected', async () => {
    const { wrapper } = await mountWithApp(AdminView, { api: mockApi(adminApi()), roles: ['ADMIN'], path: '/admin' })

    expect(wrapper.get('[role="tab"][aria-selected="true"]').text()).toBe('Staff accounts')
    expect(wrapper.find('[data-testid="staff-actors"]').exists(), 'staff panel').toBe(true)
    expect(wrapper.find('[data-testid="vanilla-import"]').exists(), 'no import panel').toBe(false)

    wrapper.unmount()
  })

  it('shows the import panels on the imports tab only', async () => {
    const { wrapper } = await mountWithApp(AdminView, { api: mockApi(adminApi()), roles: ['ADMIN'], path: '/admin/imports' })

    expect(wrapper.get('[role="tab"][aria-selected="true"]').text()).toBe('Imports')
    expect(wrapper.find('[data-testid="vanilla-import"]').exists(), 'import panel').toBe(true)
    expect(wrapper.find('[data-testid="imported-punishments"]').exists(), 'imported punishments panel').toBe(true)
    expect(wrapper.find('[data-testid="staff-actors"]').exists(), 'no staff panel').toBe(false)

    wrapper.unmount()
  })

  it('shows the CloudNet catalogue on the CloudNet tab only', async () => {
    const { wrapper } = await mountWithApp(AdminView, { api: mockApi(adminApi()), roles: ['ADMIN'], path: '/admin/cloudnet' })

    expect(wrapper.get('[role="tab"][aria-selected="true"]').text()).toBe('CloudNet')
    expect(wrapper.find('[data-testid="cloudnet-catalogue"]').exists(), 'catalogue panel').toBe(true)
    expect(wrapper.find('[data-testid="vanilla-import"]').exists(), 'no import panel').toBe(false)

    wrapper.unmount()
  })

  it('gives the open tab a tab panel labelled by that tab', async () => {
    const { wrapper } = await mountWithApp(AdminView, { api: mockApi(adminApi()), roles: ['ADMIN'], path: '/admin/imports' })

    const selected = wrapper.get('[role="tab"][aria-selected="true"]')
    expect(wrapper.get('[role="tabpanel"]').attributes('aria-labelledby'), 'panel labelled by the selected tab').toBe(selected.attributes('id'))

    wrapper.unmount()
  })
})

describe('AdminView for other roles', () => {
  it.each([['MODERATOR'], ['VIEWER']])('shows a %s no tabs and requests nothing of the area', async (role) => {
    const api = adminApi()
    const { wrapper } = await mountWithApp(AdminView, { api: mockApi(api), roles: [role], path: '/admin/staff' })

    expect(wrapper.find('[role="tablist"]').exists(), 'no tabs').toBe(false)
    expect(wrapper.find('[data-testid="staff-actors"]').exists(), 'no panel').toBe(false)
    expect(api.staffActors, 'no request').not.toHaveBeenCalled()
    expect(api.cloudnetCatalogue, 'no request').not.toHaveBeenCalled()

    wrapper.unmount()
  })
})
