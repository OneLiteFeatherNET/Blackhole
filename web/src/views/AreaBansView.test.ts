import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { AreaBan } from '../api/types'
import { mockApi, mountWithApp, ok } from '../test/mount'
import AreaBansView from './AreaBansView.vue'

const NOW = Date.UTC(2030, 0, 3, 12, 0)

const ACTIVE = { id: 'b-active', kind: 'ASN', value: 'AS4134', reason: 'Proxy farm', source: 'manual', createdAt: NOW, expiresAt: NOW + 1000, active: true } as unknown as AreaBan
const LIFTED = { id: 'b-lifted', kind: 'COUNTRY', value: 'XX', reason: 'Fraud', source: 'manual', createdAt: NOW, liftedAt: NOW + 500, active: false } as unknown as AreaBan

function setup(overrides: Record<string, unknown> = {}) {
  const api = {
    areaBans: vi.fn(async () => ok([ACTIVE, LIFTED])),
    ...overrides,
  }
  return { api, mount: (roles: string[]) => mountWithApp(AreaBansView, { api: mockApi(api), roles, path: '/area-bans' }) }
}

describe('AreaBansView role gating', () => {
  it.each([
    ['viewer', ['VIEWER'], false],
    ['moderator', ['MODERATOR', 'VIEWER'], true],
    ['admin', ['ADMIN', 'MODERATOR', 'VIEWER'], true],
  ])('offers creating and lifting to a %s (%j)', async (_label, roles, expected) => {
    const { mount } = setup()
    const { wrapper } = await mount(roles)

    expect(wrapper.find('[data-testid="new-area-ban"]').exists()).toBe(expected)
    expect(wrapper.find('[data-testid="lift-b-active"]').exists()).toBe(expected)
    wrapper.unmount()
  })

  it('offers no lift for a ban that is already lifted', async () => {
    const { mount } = setup()
    const { wrapper } = await mount(['MODERATOR', 'VIEWER'])

    expect(wrapper.find('[data-testid="lift-b-lifted"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('AreaBansView list', () => {
  it('asks only for the bans in force by default', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount(['VIEWER'])

    expect(api.areaBans).toHaveBeenLastCalledWith(false)
    wrapper.unmount()
  })

  it('lists the lifted and expired bans when the toggle is set', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount(['VIEWER'])

    await wrapper.get('[data-testid="show-inactive"] input').setValue(true)
    await flushPromises()

    expect(api.areaBans).toHaveBeenLastCalledWith(true)
    wrapper.unmount()
  })

  it('shows the status of each ban', async () => {
    const { mount } = setup()
    const { wrapper } = await mount(['VIEWER'])

    expect(wrapper.text()).toContain('AS4134')
    expect(wrapper.text()).toContain('Proxy farm')
    expect(wrapper.text()).toContain('Lifted')
    wrapper.unmount()
  })
})
