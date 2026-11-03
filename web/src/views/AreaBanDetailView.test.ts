import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { AreaBan } from '../api/types'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import AreaBanDetailView from './AreaBanDetailView.vue'

const NOW = Date.UTC(2030, 0, 3, 12, 0)
const ID = '6f1c2b3a-4d5e-4f60-8a7b-9c0d1e2f3a4b'

const ACTIVE = { id: ID, kind: 'ASN', value: 'AS4134', reason: 'Proxy farm', source: 'manual', createdAt: NOW, expiresAt: NOW + 1000, active: true } as unknown as AreaBan
const LIFTED = { id: ID, kind: 'ASN', value: 'AS4134', reason: 'Proxy farm', source: 'manual', createdAt: NOW, liftedAt: NOW + 500, active: false } as unknown as AreaBan

/** Mounts the area ban page with the lift route open (`lift`) or the page alone. */
function setup(ban: AreaBan | null, overrides: Record<string, unknown> = {}, roles: string[] = ['MODERATOR', 'VIEWER'], lift = false) {
  const api = {
    areaBan: vi.fn(async () => (ban ? ok(ban) : fail('no such ban', 404, 'NOT_FOUND'))),
    liftAreaBan: vi.fn(async () => ok({ ...ACTIVE, liftedAt: NOW, active: false })),
    ...overrides,
  }
  return {
    api,
    mount: () => mountWithApp(AreaBanDetailView, { api: mockApi(api), roles, props: { id: ID }, path: `/area-bans/${ID}${lift ? '/lift' : ''}` }),
  }
}

describe('AreaBanDetailView', () => {
  it('shows the area and the reason of an active ban, with the lift action for a moderator', async () => {
    const { mount } = setup(ACTIVE)
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.text()).toContain('AS4134')
    expect(wrapper.text()).toContain('Proxy farm')
    expect(wrapper.find('[data-testid="lift-open"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('shows a lifted ban without a lift action', async () => {
    const { mount } = setup(LIFTED)
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.find('[data-testid="lift-open"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('offers no lift to a viewer', async () => {
    const { mount } = setup(ACTIVE, {}, ['VIEWER'])
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.find('[data-testid="lift-open"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('shows the not-found notice for an unknown id', async () => {
    const { mount } = setup(null)
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.get('[data-testid="item-missing"]').text()).toContain('does not exist any more')
    wrapper.unmount()
  })

  it('sends nothing when the lift route is only opened, and lifts with the reason on confirmation', async () => {
    const liftAreaBan = vi.fn(async () => ok({ ...ACTIVE, liftedAt: NOW, active: false }))
    const { mount } = setup(ACTIVE, { liftAreaBan }, ['MODERATOR', 'VIEWER'], true)
    const { wrapper } = await mount()
    await flushPromises()

    expect(liftAreaBan).not.toHaveBeenCalled()

    await wrapper.get('[data-testid="lift-reason"] input').setValue('Verified')
    await flushPromises()
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(liftAreaBan).toHaveBeenCalledWith(ID, 'Verified')
    wrapper.unmount()
  })
})
