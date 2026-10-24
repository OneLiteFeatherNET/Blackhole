import { describe, expect, it, vi } from 'vitest'
import { hit } from '../../test/clientRules'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import ClientRuleHits from './ClientRuleHits.vue'

const OWNER = 'ab'.repeat(64)

function mountHits(clientRuleHitsOfOwner: ReturnType<typeof vi.fn>) {
  return mountWithApp(ClientRuleHits, { api: mockApi({ clientRuleHitsOfOwner }), props: { owner: OWNER } })
}

describe('ClientRuleHits (player page)', () => {
  it('lists the recent hits of the player by rule, with what the rule did and where', async () => {
    const { wrapper } = await mountHits(vi.fn(async () => ok([hit({ rule: 'meteor' }), hit({ rule: 'no-op', action: 'DENY', enforced: true, server: 'minigame-2' })])))

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('meteor')
    expect(rows[0]!.text()).toContain('Team notified')
    expect(rows[1]!.text()).toContain('Enforced')
    expect(rows[1]!.text()).toContain('minigame-2')

    wrapper.unmount()
  })

  it('asks for the hits of exactly this player', async () => {
    const hits = vi.fn(async () => ok([]))
    const { wrapper } = await mountHits(hits)

    expect(hits).toHaveBeenCalledWith(OWNER)

    wrapper.unmount()
  })

  it('says so when the player has no hits and shows no table', async () => {
    const { wrapper } = await mountHits(vi.fn(async () => ok([])))

    expect(wrapper.get('[data-testid="hits-empty"]').text()).toContain('No client rule hits')
    expect(wrapper.find('table').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows a failure as an alert', async () => {
    const { wrapper } = await mountHits(vi.fn(async () => fail('boom', 500, 'UNKNOWN')))

    expect(wrapper.get('[data-testid="hits-error"]').attributes('role')).toBe('alert')

    wrapper.unmount()
  })

  it('renders its states inside a polite live region and states that no client value is stored', async () => {
    const { wrapper } = await mountHits(vi.fn(async () => ok([])))

    expect(wrapper.get('[aria-live="polite"]').text()).toContain('No client rule hits')
    expect(wrapper.text()).toContain('not stored')

    wrapper.unmount()
  })
})
