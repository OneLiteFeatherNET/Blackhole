import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { StaffLink } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import StaffLinksPanel from './StaffLinksPanel.vue'

const ada: StaffLink = { oid: '00000000-0000-4000-8000-0000000000b2', displayName: 'Ada', minecraftUuid: '11111111-1111-4111-8111-111111111111', linkedAt: Date.UTC(2026, 9, 23) }
const nameless: StaffLink = { oid: '00000000-0000-4000-8000-0000000000b3', displayName: null, minecraftUuid: '22222222-2222-4222-8222-222222222222', linkedAt: Date.UTC(2026, 9, 22) }

function setup(overrides: Record<string, unknown> = {}) {
  const api = {
    staffLinks: vi.fn(async () => ok([ada, nameless])),
    unlinkStaffLink: vi.fn(async () => ok(undefined)),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(StaffLinksPanel, { api: mockApi(api) }) }
}

describe('StaffLinksPanel', () => {
  it('lists the links with name, Minecraft account and an unnamed fallback', async () => {
    const { wrapper } = await setup().mount()

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('Ada')
    expect(rows[0]!.text()).toContain(ada.minecraftUuid)
    expect(rows[1]!.text()).toContain('(no name)')

    wrapper.unmount()
  })

  it('says so when nothing is linked', async () => {
    const { wrapper } = await setup({ staffLinks: vi.fn(async () => ok([])) }).mount()

    expect(wrapper.get('[data-testid="staff-links-empty"]').text()).toBe('No accounts are linked.')

    wrapper.unmount()
  })

  it('removes nothing on cancel', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get(`[data-testid="unlink-${ada.oid}"]`).trigger('click')
    await wrapper.get('[data-testid="cancel"]').trigger('click')

    expect(api.unlinkStaffLink).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('removes the chosen link after confirmation and reloads the list', async () => {
    const list = vi.fn().mockResolvedValueOnce(ok([ada, nameless])).mockResolvedValueOnce(ok([nameless]))
    const { api, mount } = setup({ staffLinks: list })
    const { wrapper } = await mount()

    await wrapper.get(`[data-testid="unlink-${ada.oid}"]`).trigger('click')
    expect(wrapper.text()).toContain('Remove the link of Ada?')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.unlinkStaffLink).toHaveBeenCalledWith(ada.oid)
    expect(wrapper.findAll('tbody tr'), 'the removed row is gone').toHaveLength(1)

    wrapper.unmount()
  })

  it('keeps the dialog open with the backend message when removing fails', async () => {
    const { wrapper } = await setup({ unlinkStaffLink: vi.fn(async () => fail('link not found', 404, 'NOT_FOUND')) }).mount()

    await wrapper.get(`[data-testid="unlink-${ada.oid}"]`).trigger('click')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toBe('link not found')

    wrapper.unmount()
  })
})

