import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { AreaBan, GuardState } from '../../api/types'
import { mockApi, mountWithApp, ok } from '../../test/mount'
import GuardTile from './GuardTile.vue'

const NOW = Date.UTC(2030, 0, 3, 12, 0)

function guard(overrides: Partial<GuardState> = {}): GuardState {
  return { manualLevel: 'OFF', automaticLevel: 'OFF', effectiveLevel: 'OFF', updatedAt: NOW, ...overrides }
}

function ban(id: string, active: boolean): AreaBan {
  return { id, kind: 'ASN', value: 'AS4134', reason: 'Proxy farm', source: 'manual', createdAt: NOW, active } as unknown as AreaBan
}

function mounting(joinGuard: GuardState, areaBans: AreaBan[]) {
  return mountWithApp(GuardTile, {
    api: mockApi({
      joinGuard: vi.fn(async () => ok(joinGuard)),
      areaBans: vi.fn(async () => ok(areaBans)),
    }),
    roles: ['VIEWER'],
    path: '/',
  })
}

describe('GuardTile', () => {
  it('shows the effective level and the number of active area bans', async () => {
    const { wrapper } = await mounting(guard(), [ban('a', true), ban('b', true)])
    await flushPromises()

    const tile = wrapper.get('[data-testid="guard-tile"]')
    expect(tile.text()).toContain('Off')
    expect(tile.text()).toContain('2 active area bans')
    wrapper.unmount()
  })

  it('counts only the bans in force', async () => {
    const { wrapper } = await mounting(guard(), [ban('a', true), ban('b', true), ban('c', false)])
    await flushPromises()

    expect(wrapper.get('[data-testid="guard-tile"]').text()).toContain('2 active area bans')
    wrapper.unmount()
  })

  it('links to the join guard page', async () => {
    const { wrapper } = await mounting(guard(), [])
    await flushPromises()

    expect(wrapper.get('[data-testid="guard-tile"]').attributes('href')).toBe('/guard')
    wrapper.unmount()
  })
})
