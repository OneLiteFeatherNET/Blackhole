import { describe, expect, it } from 'vitest'
import type { Punishment } from '../../api/types'
import { mountWithApp } from '../../test/mount'
import PunishmentHistory from './PunishmentHistory.vue'
import { countActive } from './punishmentInfo'

const NOW = Date.UTC(2026, 9, 25, 12, 0, 0)
const SOURCE = '11111111-2222-3333-4444-555555555555'

function entry(overrides: Partial<Punishment> = {}): Punishment {
  return { identifier: 'p-1', shortId: '#ABC12345', source: SOURCE, verified: false, section: 'chat', permanent: false, lifted: false, metaData: {}, ...overrides }
}

const warning = (identifier: string, acknowledgedAt: number | null = null) =>
  entry({ identifier, kind: 'WARN', createdAt: NOW - 1000, acknowledgedAt })

describe('PunishmentHistory warnings', () => {
  it('marks a warning row with the Warning badge', async () => {
    const { wrapper } = await mountWithApp(PunishmentHistory, { props: { history: [warning('w-1')], now: NOW } })

    expect(wrapper.get('[data-testid="warning-badge"]').text()).toBe('Warning')
    wrapper.unmount()
  })

  it('shows a pending warning as Pending', async () => {
    const { wrapper } = await mountWithApp(PunishmentHistory, { props: { history: [warning('w-1', null)], now: NOW } })

    expect(wrapper.get('[data-testid="warning-status"]').text()).toBe('Pending')
    wrapper.unmount()
  })

  it('shows the time the player confirmed an acknowledged warning', async () => {
    const acknowledgedAt = Date.UTC(2026, 9, 26, 8, 30, 0)
    const { wrapper } = await mountWithApp(PunishmentHistory, { props: { history: [warning('w-1', acknowledgedAt)], now: NOW } })

    const status = wrapper.get('[data-testid="warning-status"]').text()
    expect(status, 'confirmed state').toContain('Confirmed')
    expect(status, 'the acknowledged date is shown').toContain('2026')
    wrapper.unmount()
  })

  it('shows no Warning badge on an ordinary punishment', async () => {
    const { wrapper } = await mountWithApp(PunishmentHistory, { props: { history: [entry({ identifier: 'b', kind: 'PUNISHMENT', permanent: true })], now: NOW } })

    expect(wrapper.find('[data-testid="warning-badge"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('countActive with warnings', () => {
  it('does not count a pending warning as active', () => {
    expect(countActive([warning('w-1', null)], NOW), 'a warning is never in force').toBe(0)
  })

  it('does not count a warning next to a ban', () => {
    const ban = entry({ identifier: 'b', kind: 'PUNISHMENT', permanent: true })

    expect(countActive([ban, warning('w-1', null)], NOW)).toBe(1)
  })
})
