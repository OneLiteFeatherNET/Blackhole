import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { StaffActor } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import AdminView from '../../views/AdminView.vue'
import StaffActorsPanel from './StaffActorsPanel.vue'

const ada: StaffActor = { oid: '00000000-0000-4000-8000-0000000000b2', kind: 'HUMAN', displayName: 'Ada', firstSeenAt: Date.UTC(2026, 9, 1), lastSeenAt: Date.UTC(2026, 9, 23) }
const proxy: StaffActor = { oid: '00000000-0000-4000-8000-0000000000b3', kind: 'SERVICE', displayName: null, firstSeenAt: Date.UTC(2026, 9, 1), lastSeenAt: Date.UTC(2026, 9, 22) }

function setup(overrides: Record<string, unknown> = {}) {
  const api = {
    staffActors: vi.fn(async () => ok([ada, proxy])),
    anonymizeStaffActor: vi.fn(async () => ok(undefined)),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(StaffActorsPanel, { api: mockApi(api) }) }
}

describe('StaffActorsPanel', () => {
  it('lists name, object id, kind and an unnamed fallback', async () => {
    const { wrapper } = await setup().mount()

    const rows = wrapper.findAll('tbody tr')
    expect(rows, 'rows').toHaveLength(2)
    expect(rows[0]!.text()).toContain('Ada')
    expect(rows[0]!.text()).toContain(ada.oid)
    expect(rows[0]!.text(), 'kind as text').toContain('Person')
    expect(rows[1]!.text()).toContain('(no name)')
    expect(rows[1]!.text(), 'kind as text').toContain('Application')

    wrapper.unmount()
  })

  it('says so when no staff is known', async () => {
    const { wrapper } = await setup({ staffActors: vi.fn(async () => ok([])) }).mount()

    expect(wrapper.get('[data-testid="staff-actors-empty"]').text()).toBe('No staff known yet.')

    wrapper.unmount()
  })

  it('labels each anonymize button with the person it acts on', async () => {
    const { wrapper } = await setup().mount()

    expect(wrapper.get(`[data-testid="anonymize-${ada.oid}"]`).attributes('aria-label')).toBe('Anonymize Ada')

    wrapper.unmount()
  })

  it('names every consequence in the confirmation before anything is sent', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get(`[data-testid="anonymize-${ada.oid}"]`).trigger('click')

    expect(wrapper.get('[role="dialog"]').text()).toContain('Anonymize Ada?')
    const text = wrapper.get('[data-testid="consequences"]').text()
    expect(text, 'name').toContain('The name is removed everywhere')
    expect(text, 'history').toContain('History keeps the object ID')
    expect(text, 'link').toContain('link to the Minecraft account is removed')
    expect(text, 'irreversible').toContain('cannot be undone')
    expect(api.anonymizeStaffActor, 'nothing sent yet').not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('anonymizes nothing on cancel', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get(`[data-testid="anonymize-${ada.oid}"]`).trigger('click')
    await wrapper.get('[data-testid="cancel"]').trigger('click')

    expect(api.anonymizeStaffActor).not.toHaveBeenCalled()
    expect(wrapper.find('[role="dialog"]').exists(), 'dialog closed').toBe(false)

    wrapper.unmount()
  })

  it('anonymizes the chosen principal after confirmation, announces it and reloads', async () => {
    const list = vi.fn().mockResolvedValueOnce(ok([ada, proxy])).mockResolvedValueOnce(ok([{ ...ada, displayName: null }, proxy]))
    const { api, mount } = setup({ staffActors: list })
    const { wrapper } = await mount()

    await wrapper.get(`[data-testid="anonymize-${ada.oid}"]`).trigger('click')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.anonymizeStaffActor).toHaveBeenCalledWith(ada.oid)
    expect(wrapper.findAll('tbody tr')[0]!.text(), 'the name is gone after the reload').not.toContain('Ada')
    expect(wrapper.get('[role="status"]').text(), 'announced in the live region').toContain('Ada was anonymized.')

    wrapper.unmount()
  })

  it('keeps the dialog open with the backend message when anonymizing fails', async () => {
    const { wrapper } = await setup({ anonymizeStaffActor: vi.fn(async () => fail('there is no staff actor', 404, 'NOT_FOUND')) }).mount()

    await wrapper.get(`[data-testid="anonymize-${ada.oid}"]`).trigger('click')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toBe('there is no staff actor')

    wrapper.unmount()
  })

  it('moves focus into the dialog and returns it to the opener on cancel', async () => {
    const { wrapper } = await setup().mount()
    const opener = wrapper.get<HTMLButtonElement>(`[data-testid="anonymize-${ada.oid}"]`)
    opener.element.focus()

    await opener.trigger('click')
    await flushPromises()
    expect(document.activeElement, 'focus in the dialog').toBe(wrapper.get('[role="dialog"]').element)
    await wrapper.get('[data-testid="cancel"]').trigger('click')
    await flushPromises()

    expect(document.activeElement, 'focus back on the opener').toBe(opener.element)

    wrapper.unmount()
  })
})

describe('AdminView', () => {
  it('shows the staff list, the import and the CloudNet catalogue', async () => {
    const api = mockApi({
      staffActors: vi.fn(async () => ok([ada])),
      cloudnetCatalogue: vi.fn(async () => ok({ available: false, tasks: [], groups: [], services: [], proxies: [] })),
      templates: vi.fn(async () => ok([])),
      sections: vi.fn(async () => ok([])),
      importedPunishmentGroups: vi.fn(async () => ok({ total: 0, groups: [] })),
    })
    const { wrapper } = await mountWithApp(AdminView, { api, roles: ['ADMIN'], path: '/admin' })

    expect(wrapper.find('[data-testid="staff-actors"]').exists(), 'staff panel').toBe(true)
    expect(wrapper.find('[data-testid="vanilla-import"]').exists(), 'import panel').toBe(true)
    expect(wrapper.find('[data-testid="imported-punishments"]').exists(), 'imported punishments panel').toBe(true)
    expect(wrapper.find('[data-testid="cloudnet-catalogue"]').exists(), 'catalogue panel').toBe(true)

    wrapper.unmount()
  })
})
