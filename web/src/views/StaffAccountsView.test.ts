import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { mockApi, mountWithApp, ok } from '../test/mount'
import StaffAccountsView from './StaffAccountsView.vue'

const ADMIN = ['ADMIN']
const MODERATOR = ['MODERATOR']
const VIEWER = ['VIEWER']

function staffApi(overrides: Record<string, unknown> = {}) {
  return {
    staffActors: vi.fn(async () => ok([])),
    staffLinks: vi.fn(async () => ok([])),
    discordLinks: vi.fn(async () => ok([])),
    ...overrides,
  }
}

describe('StaffAccountsView', () => {
  it('lists the staff accounts, the linked Minecraft accounts and the Discord links to an admin', async () => {
    const api = staffApi()
    const { wrapper } = await mountWithApp(StaffAccountsView, { api: mockApi(api), roles: ADMIN, path: '/admin/staff' })
    await flushPromises()

    expect(wrapper.find('[data-testid="staff-actors"]').exists(), 'staff accounts').toBe(true)
    expect(wrapper.find('[data-testid="staff-links"]').exists(), 'Minecraft links').toBe(true)
    expect(wrapper.find('[data-testid="discord-links"]').exists(), 'Discord links').toBe(true)
    expect(api.staffActors).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })
})

// The role gating of the panels moved here with them from the sections page; the view is admin-only.
describe('StaffAccountsView: role gating for the Minecraft links', () => {
  it.each([[MODERATOR], [VIEWER]])('is hidden from a %s and never requests the list', async (role) => {
    const api = staffApi()
    const { wrapper } = await mountWithApp(StaffAccountsView, { api: mockApi(api), roles: role, path: '/admin/staff' })

    expect(wrapper.find('[data-testid="staff-links"]').exists(), 'no panel').toBe(false)
    expect(api.staffLinks, 'no request').not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('is shown to an admin', async () => {
    const api = staffApi()
    const { wrapper } = await mountWithApp(StaffAccountsView, { api: mockApi(api), roles: ADMIN, path: '/admin/staff' })

    expect(wrapper.find('[data-testid="staff-links"]').exists(), 'panel').toBe(true)
    expect(api.staffLinks).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })
})

describe('StaffAccountsView: role gating for the Discord links', () => {
  it.each([[MODERATOR], [VIEWER]])('is hidden from a %s and never requests the list', async (role) => {
    const api = staffApi()
    const { wrapper } = await mountWithApp(StaffAccountsView, { api: mockApi(api), roles: role, path: '/admin/staff' })

    expect(wrapper.find('[data-testid="discord-links"]').exists(), 'no panel').toBe(false)
    expect(api.discordLinks, 'no request').not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('is shown to an admin', async () => {
    const api = staffApi()
    const { wrapper } = await mountWithApp(StaffAccountsView, { api: mockApi(api), roles: ADMIN, path: '/admin/staff' })

    expect(wrapper.find('[data-testid="discord-links"]').exists(), 'panel').toBe(true)
    expect(api.discordLinks).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })
})
