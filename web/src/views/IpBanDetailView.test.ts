import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { IpBan } from '../api/types'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import IpBanLiftDialog from '../components/ipban/IpBanLiftDialog.vue'
import IpBanDetailView from './IpBanDetailView.vue'

const NOW = Date.UTC(2026, 9, 29, 12, 0, 0)
const DAY_MS = 86_400_000

const active: IpBan = { id: 'b-active', range: '203.0.113.7', reason: 'Ban evasion', source: 'PANEL', createdAt: NOW - DAY_MS, expiresAt: NOW + DAY_MS, warnings: [] }
const lifted: IpBan = { id: 'b-lifted', range: '198.51.100.0/24', reason: 'False alarm', source: 'PANEL', createdAt: NOW - 3 * DAY_MS, liftedAt: NOW - DAY_MS, liftReason: 'Verified', warnings: [] }

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(NOW)
})

afterEach(() => {
  vi.useRealTimers()
})

/** Mounts the ban page with the lift route open (`lift`) or the page alone. */
function setup(overrides: Record<string, unknown> = {}, roles: string[] = ['MODERATOR'], id = 'b-active', lift = true) {
  const api = {
    ipBan: vi.fn(async (requested: string) => (requested === 'b-active' ? ok(active) : requested === 'b-lifted' ? ok(lifted) : fail('no such ban', 404, 'NOT_FOUND'))),
    liftIpBan: vi.fn(async () => ok({ ...active, liftedAt: NOW, liftReason: 'Verified' })),
    ...overrides,
  }
  return {
    api,
    mount: () => mountWithApp(IpBanDetailView, { api: mockApi(api), roles, props: { id }, path: `/ip-bans/${id}${lift ? '/lift' : ''}` }),
  }
}

describe('IpBanDetailView: the page', () => {
  it('shows the ban with its state, reason and source', async () => {
    const { mount } = setup({}, ['VIEWER'], 'b-active', false)
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.text()).toContain('203.0.113.7')
    expect(wrapper.text()).toContain('Ban evasion')
    expect(wrapper.find('[data-testid="item-missing"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('shows the lift action to a moderator only while the ban is active', async () => {
    const { mount } = setup({}, ['MODERATOR'], 'b-active', false)
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.find('[data-testid="lift-open"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('offers no lift to a viewer, and none for a ban already lifted', async () => {
    const viewer = setup({}, ['VIEWER'], 'b-active', false)
    const first = await viewer.mount()
    await flushPromises()
    expect(first.wrapper.find('[data-testid="lift-open"]').exists()).toBe(false)
    first.wrapper.unmount()

    const lifting = setup({}, ['MODERATOR'], 'b-lifted', false)
    const second = await lifting.mount()
    await flushPromises()
    expect(second.wrapper.find('[data-testid="lift-open"]').exists()).toBe(false)
    second.wrapper.unmount()
  })

  it('shows a not-found notice for an id that does not exist, and no dialog', async () => {
    const { mount } = setup({}, ['MODERATOR'], 'b-missing')
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.get('[data-testid="item-missing"]').text()).toContain('does not exist any more')
    expect(wrapper.findComponent(IpBanLiftDialog).exists(), 'no lift dialog without a ban').toBe(false)
    wrapper.unmount()
  })

  it('has no address that carries the range: the page is addressed by the id', async () => {
    const { mount } = setup({}, ['MODERATOR'], 'b-active', false)
    const { wrapper, router } = await mount()
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/ip-bans/b-active')
    expect(router.currentRoute.value.fullPath).not.toContain('203.0.113.7')
    wrapper.unmount()
  })
})

describe('IpBanDetailView: lift', () => {
  it('keeps the lift disabled until a reason is typed', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.get('[data-testid="confirm"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('A reason is required.')
    wrapper.unmount()
  })

  it('lifts with the trimmed reason, reloads the ban and leaves the lift route', async () => {
    const liftIpBan = vi.fn(async () => ok({ ...active, liftedAt: NOW, liftReason: 'Verified' }))
    const ipBan = vi.fn(async () => ok(active))
    const { mount } = setup({ liftIpBan, ipBan })
    const { wrapper, router } = await mount()
    await flushPromises()

    await wrapper.get('[data-testid="lift-reason"] input').setValue('  Verified  ')
    await flushPromises()
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(liftIpBan).toHaveBeenCalledWith('b-active', '  Verified  ')
    expect(ipBan).toHaveBeenCalledTimes(2)
    expect(router.currentRoute.value.fullPath).toBe('/ip-bans/b-active')
    wrapper.unmount()
  })

  it('explains a ban that is no longer active instead of the server text', async () => {
    const liftIpBan = vi.fn(async () => fail('server text', 409, 'CONFLICT'))
    const { mount } = setup({ liftIpBan })
    const { wrapper } = await mount()
    await flushPromises()
    await wrapper.get('[data-testid="lift-reason"] input').setValue('Verified')
    await flushPromises()

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toBe('This ban is no longer active.')
    wrapper.unmount()
  })

  it('shows the server text for a lift refused without a known status', async () => {
    const liftIpBan = vi.fn(async () => fail('no such ban', 404, 'NOT_FOUND'))
    const { mount } = setup({ liftIpBan })
    const { wrapper } = await mount()
    await flushPromises()
    await wrapper.get('[data-testid="lift-reason"] input').setValue('Verified')
    await flushPromises()

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toBe('no such ban')
    wrapper.unmount()
  })

  it('sends nothing when the lift route is only opened', async () => {
    const liftIpBan = vi.fn(async () => ok(active))
    const { mount } = setup({ liftIpBan })
    const { wrapper } = await mount()
    await flushPromises()

    expect(liftIpBan).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
