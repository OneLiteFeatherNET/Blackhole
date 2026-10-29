import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { IpBan } from '../api/types'
import { chooseRowMenuItem, fail, mockApi, mountWithApp, ok } from '../test/mount'
import IpBansView from './IpBansView.vue'

const NOW = Date.UTC(2026, 9, 29, 12, 0, 0)
const DAY_MS = 86_400_000

const active: IpBan = { id: 'b-active', range: '203.0.113.7', reason: 'Ban evasion', source: 'PANEL', createdAt: NOW - DAY_MS, expiresAt: NOW + DAY_MS, warnings: [] }
const lifted: IpBan = { id: 'b-lifted', range: '198.51.100.0/24', reason: 'False alarm', source: 'PANEL', createdAt: NOW - 3 * DAY_MS, liftedAt: NOW - DAY_MS, liftReason: 'Verified', warnings: [] }
const expired: IpBan = { id: 'b-expired', range: '2001:db8::/32', reason: 'Botnet', source: 'COMMAND', createdAt: NOW - 9 * DAY_MS, expiresAt: NOW - DAY_MS, warnings: [] }

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(NOW)
})

afterEach(() => {
  vi.useRealTimers()
})

function setup(overrides: Record<string, unknown> = {}, roles?: string[]) {
  const api = {
    ipBans: vi.fn(async () => ok([active, lifted, expired])),
    createIpBan: vi.fn(async () => ok(active)),
    liftIpBan: vi.fn(async () => ok({ ...active, liftedAt: NOW, liftReason: 'Verified' })),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(IpBansView, { api: mockApi(api), path: '/ip-bans', roles }) }
}

type Page = Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']

function rowOf(wrapper: Page, range: string) {
  const row = wrapper.findAll('tbody tr').find((candidate) => candidate.text().includes(range))
  if (!row) throw new Error(`no row for ${range}`)
  return row
}

describe('IpBansView: list', () => {
  it('shows the active bans by default and hides the lifted and expired ones', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const rows = wrapper.findAll('tbody tr').map((row) => row.text())
    expect(rows).toHaveLength(1)
    expect(rows[0]).toContain('203.0.113.7')
    expect(rows[0]).toContain('Ban evasion')

    wrapper.unmount()
  })

  it('shows the lifted and expired bans under the lifted filter', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="filter-lifted"]').trigger('click')
    await flushPromises()

    const text = wrapper.findAll('tbody tr').map((row) => row.text())
    expect(text).toHaveLength(2)
    expect(text.some((row) => row.includes('198.51.100.0/24'))).toBe(true)
    expect(text.some((row) => row.includes('2001:db8::/32'))).toBe(true)

    wrapper.unmount()
  })

  it('says so when there are no bans to show', async () => {
    const { mount } = setup({ ipBans: vi.fn(async () => ok([lifted])) })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="ip-bans-empty"]').text()).toContain('No active IP bans.')

    wrapper.unmount()
  })
})

describe('IpBansView: role gating', () => {
  it('lets a viewer read the list but offers no create or lift action', async () => {
    const { mount } = setup({}, ['VIEWER'])
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="new-ip-ban"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="read-only"]').exists()).toBe(true)
    expect(rowOf(wrapper, '203.0.113.7').findAll('button[aria-haspopup="menu"]')).toHaveLength(0)

    wrapper.unmount()
  })

  it('offers create and lift to a moderator', async () => {
    const { mount } = setup({}, ['MODERATOR'])
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="new-ip-ban"]').exists()).toBe(true)
    expect(rowOf(wrapper, '203.0.113.7').findAll('button[aria-haspopup="menu"]')).toHaveLength(1)

    wrapper.unmount()
  })
})

describe('IpBansView: lift', () => {
  it('keeps the lift disabled until a reason is typed', async () => {
    const { mount } = setup({}, ['MODERATOR'])
    const { wrapper } = await mount()
    await chooseRowMenuItem(wrapper, 0, 'Lift ban')
    await flushPromises()

    expect(wrapper.get('[data-testid="confirm"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('A reason is required.')

    wrapper.unmount()
  })

  it('lifts with the trimmed reason and reloads the list', async () => {
    const liftIpBan = vi.fn(async () => ok({ ...active, liftedAt: NOW, liftReason: 'Verified' }))
    const ipBans = vi.fn(async () => ok([active]))
    const { mount } = setup({ liftIpBan, ipBans }, ['MODERATOR'])
    const { wrapper } = await mount()
    await chooseRowMenuItem(wrapper, 0, 'Lift ban')
    await flushPromises()

    await wrapper.get('[data-testid="lift-reason"] input').setValue('  Verified  ')
    await flushPromises()
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(liftIpBan).toHaveBeenCalledWith('b-active', '  Verified  ')
    expect(ipBans).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('explains a ban that is no longer active instead of the server text', async () => {
    const liftIpBan = vi.fn(async () => ({ ok: false as const, error: { status: 409, message: 'server text', kind: 'CONFLICT' as const } }))
    const { mount } = setup({ liftIpBan }, ['MODERATOR'])
    const { wrapper } = await mount()
    await chooseRowMenuItem(wrapper, 0, 'Lift ban')
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
    const { mount } = setup({ liftIpBan }, ['MODERATOR'])
    const { wrapper } = await mount()
    await chooseRowMenuItem(wrapper, 0, 'Lift ban')
    await flushPromises()
    await wrapper.get('[data-testid="lift-reason"] input').setValue('Verified')
    await flushPromises()

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toBe('no such ban')

    wrapper.unmount()
  })
})
