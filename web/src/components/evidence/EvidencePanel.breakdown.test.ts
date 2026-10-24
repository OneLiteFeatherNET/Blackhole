import { describe, expect, it } from 'vitest'
import { evidencePage } from '../../test/evidence'
import { mockApi, mountWithApp, ok } from '../../test/mount'
import EvidencePanel from './EvidencePanel.vue'

const PUNISHMENT = 'ab0e5a9e-1111-4222-8333-444455556666'

describe('EvidencePanel: the team and player-supplied breakdown', () => {
  it('shows the team count and the player-supplied count as separate figures', async () => {
    const api = mockApi({
      evidence: async () => ok(evidencePage([])),
      evidenceCounts: async () => ok({ count: 3, playerSupplied: 2 }),
    })
    const { wrapper } = await mountWithApp(EvidencePanel, { api, props: { punishment: PUNISHMENT } })

    expect(wrapper.get('[data-testid="breakdown"]').text()).toBe('3 team, 2 player-supplied')
    wrapper.unmount()
  })
})
