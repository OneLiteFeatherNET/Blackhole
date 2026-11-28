import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { mockApi, mountWithApp, ok } from '../../test/mount'
import { groupKey } from './groupKey'
import ImportsPage from './ImportsPage.vue'

const GROUPS = {
  total: 3,
  groups: [
    { reason: 'Griefing', count: 2 },
    { reason: 'Banned by an operator', count: 1 },
  ],
}

function setup(path: string, roles = ['ADMIN'], overrides: Record<string, unknown> = {}) {
  const api = {
    templates: vi.fn(async () => ok([])),
    sections: vi.fn(async () => ok([])),
    importedPunishmentGroups: vi.fn(async () => ok(GROUPS)),
    importVanillaBans: vi.fn(async () => ok({ dryRun: true, playersTotal: 0 })),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(ImportsPage, { api: mockApi(api), roles, path }) }
}

describe('ImportsPage tabs in the address', () => {
  it('shows the vanilla import by default, with the way to a new import and no request for the groups', async () => {
    const { api, mount } = setup('/admin/imports')
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="vanilla-new"]').attributes('href')).toBe('/admin/imports/vanilla/new')
    expect(wrapper.find('[data-testid="imported-punishments"]').exists()).toBe(false)
    expect(api.importedPunishmentGroups, 'the groups are read on their tab only').not.toHaveBeenCalled()
    expect(api.importVanillaBans).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('shows the imported punishments on ?tab=punishments and reads the groups', async () => {
    const { api, mount } = setup('/admin/imports?tab=punishments')
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.find('[data-testid="imported-punishments"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="vanilla-new"]').exists()).toBe(false)
    expect(api.importedPunishmentGroups).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  it('falls back to the vanilla tab for an unknown tab and takes the value out of the address', async () => {
    const { mount } = setup('/admin/imports?tab=nope&group=x')
    const { wrapper, router } = await mount()
    await flushPromises()

    expect(wrapper.find('[data-testid="vanilla-new"]').exists()).toBe(true)
    expect(router.currentRoute.value.query).toEqual({ group: 'x' })

    wrapper.unmount()
  })

  it('keeps the tab of a link on the page itself', async () => {
    const { mount } = setup('/admin/imports')
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="imports-tab-punishments"]').attributes('href')).toBe('/admin/imports?tab=punishments')
    expect(wrapper.get('[data-testid="imports-tab-vanilla"]').attributes('aria-current')).toBe('page')

    wrapper.unmount()
  })
})

describe('ImportsPage vanilla import dialog at /admin/imports/vanilla/new', () => {
  it('opens the import form over the page and imports nothing until the import is confirmed', async () => {
    const { api, mount } = setup('/admin/imports/vanilla/new')
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.find('[data-testid="vanilla-import"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="vanilla-import"] h2').exists(), 'no second heading inside the dialog').toBe(false)
    expect(api.importVanillaBans).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('ImportsPage group in the address', () => {
  it('marks the group the address names, by the hash of its reason, and never shows the reason in the address', async () => {
    const { mount } = setup(`/admin/imports?tab=punishments&group=${groupKey('Griefing')}`)
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.get(`[data-testid="group-${groupKey('Griefing')}"]`).attributes('aria-current')).toBe('true')
    expect(wrapper.get(`[data-testid="group-${groupKey('Banned by an operator')}"]`).attributes('aria-current')).toBeUndefined()

    wrapper.unmount()
  })

  it('links each group to the address that names it, without its reason', async () => {
    const { mount } = setup('/admin/imports?tab=punishments')
    const { wrapper } = await mount()
    await flushPromises()

    const href = wrapper.get(`[data-testid="group-${groupKey('Griefing')}"]`).attributes('href')
    expect(href).toBe(`/admin/imports?tab=punishments&group=${groupKey('Griefing')}`)
    expect(href).not.toContain('Griefing')

    wrapper.unmount()
  })
})

describe('ImportsPage for other roles', () => {
  it.each([['MODERATOR'], ['VIEWER']])('shows a %s nothing and requests nothing', async (role) => {
    const { api, mount } = setup('/admin/imports?tab=punishments', [role])
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="admin-imports"]').exists()).toBe(false)
    expect(api.importedPunishmentGroups).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})
