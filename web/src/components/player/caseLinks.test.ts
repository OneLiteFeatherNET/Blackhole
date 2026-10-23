import { describe, expect, it } from 'vitest'
import type { Punishment } from '../../api/types'
import { mountWithApp } from '../../test/mount'
import ActivePunishments from './ActivePunishments.vue'
import PunishmentHistory from './PunishmentHistory.vue'

const SOURCE = '11111111-2222-3333-4444-555555555555'

function entry(overrides: Partial<Punishment> = {}): Punishment {
  return { identifier: 'p/1', shortId: 'ABC123', source: SOURCE, verified: false, section: 'network', permanent: false, lifted: false, metaData: {}, ...overrides }
}

describe('links to the case page', () => {
  it('links every active punishment to its case, encoding the identifier', async () => {
    const { wrapper, router } = await mountWithApp(ActivePunishments, { props: { active: { network: entry() } } })

    const link = wrapper.get('[data-testid="open-case-network"]')
    expect(link.attributes('href')).toBe(router.resolve({ name: 'case', params: { reference: 'p/1' } }).href)
    expect(link.attributes('href')).toContain(encodeURIComponent('p/1'))

    wrapper.unmount()
  })

  it('links every row of the history to its case', async () => {
    const { wrapper } = await mountWithApp(PunishmentHistory, {
      props: { history: [entry({ identifier: 'p-1' }), entry({ identifier: 'p-2', lifted: true })] },
    })

    expect(wrapper.get('[data-testid="open-case-p-1"]').attributes('href')).toBe('/cases/p-1')
    expect(wrapper.get('[data-testid="open-case-p-2"]').attributes('href')).toBe('/cases/p-2')

    wrapper.unmount()
  })
})
