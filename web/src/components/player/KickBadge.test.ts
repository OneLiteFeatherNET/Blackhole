import { describe, expect, it } from 'vitest'
import type { Punishment } from '../../api/types'
import { mountWithApp } from '../../test/mount'
import PunishmentHistory from './PunishmentHistory.vue'
import { countActive } from './punishmentInfo'

const NOW = Date.UTC(2026, 9, 25, 12, 0, 0)
const SOURCE = '11111111-2222-3333-4444-555555555555'

function entry(overrides: Partial<Punishment> = {}): Punishment {
  return { identifier: 'p-1', shortId: '#ABC12345', source: SOURCE, verified: false, section: 'network', permanent: false, lifted: false, metaData: {}, ...overrides }
}

const kick = (identifier: string) => entry({ identifier, kind: 'KICK', permanent: false, metaData: {} })
const ban = (identifier: string) => entry({ identifier, kind: 'PUNISHMENT', permanent: true })

describe('PunishmentHistory kicks', () => {
  it('marks a kick row with the Kick badge', async () => {
    const { wrapper } = await mountWithApp(PunishmentHistory, { props: { history: [kick('k-1')], now: NOW } })

    expect(wrapper.get('[data-testid="kick-badge"]').text()).toBe('Kick')
    wrapper.unmount()
  })

  it('shows no Kick badge on an ordinary punishment', async () => {
    const { wrapper } = await mountWithApp(PunishmentHistory, { props: { history: [ban('b-1')], now: NOW } })

    expect(wrapper.find('[data-testid="kick-badge"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('does not count a kick as active', async () => {
    const { wrapper } = await mountWithApp(PunishmentHistory, { props: { history: [ban('b-1'), kick('k-1'), kick('k-2')], now: NOW } })

    expect(wrapper.get('[data-testid="active-count"]').text()).toBe('1 active')
    wrapper.unmount()
  })
})

describe('countActive', () => {
  it('counts a permanent punishment and a temporary one that has not ended', () => {
    const temporary = entry({ identifier: 't', metaData: { expiration_date: NOW + 1000 } as never })

    expect(countActive([ban('b'), temporary], NOW)).toBe(2)
  })

  it('ignores kicks, lifted and ended punishments', () => {
    const ended = entry({ identifier: 'e', metaData: { expiration_date: NOW - 1 } as never })
    const lifted = entry({ identifier: 'l', permanent: true, lifted: true })

    expect(countActive([kick('k'), ended, lifted], NOW)).toBe(0)
  })
})
