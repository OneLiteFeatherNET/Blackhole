import { describe, expect, it, vi } from 'vitest'
import type { ExemptionStatus } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import ExemptionBadge from './ExemptionBadge.vue'

const OWNER = 'ab'.repeat(64)

function status(overrides: Partial<ExemptionStatus> = {}): ExemptionStatus {
  return { exempt: true, sources: ['LINKED_STAFF'], sections: [], rank: 'MODERATOR', ...overrides }
}

async function mountBadge(answer: ReturnType<typeof ok> | ReturnType<typeof fail>) {
  const api = mockApi({ exemption: vi.fn(async () => answer) })
  const mounted = await mountWithApp(ExemptionBadge, { api, props: { owner: OWNER } })
  return { api, ...mounted }
}

describe('ExemptionBadge', () => {
  it('shows "Linked staff" with the protecting rank in its tooltip for a linked staff member', async () => {
    const { api, wrapper } = await mountBadge(ok(status({ sources: ['LINKED_STAFF'], rank: 'ADMIN' })))

    const badge = wrapper.get('[data-testid="exemption-badge"]')
    expect(badge.text()).toBe('Linked staff')
    expect(badge.attributes('title'), 'the rank protecting the player').toContain('Admin')
    expect(api.exemption, 'asked for this owner').toHaveBeenCalledWith(OWNER)
    wrapper.unmount()
  })

  it('shows "Exempt" and the offline staleness note for a player with the permission', async () => {
    const { wrapper } = await mountBadge(ok(status({ sources: ['PERMISSION'], sections: ['survival'], rank: 'MODERATOR' })))

    const badge = wrapper.get('[data-testid="exemption-badge"]')
    expect(badge.text()).toBe('Exempt')
    expect(badge.attributes('title'), 'a removed permission keeps protecting until the next login').toContain('until the next login')
    wrapper.unmount()
  })

  it('shows nothing for a player who is not exempt', async () => {
    const { wrapper } = await mountBadge(ok(status({ exempt: false, sources: [], sections: [], rank: null })))

    expect(wrapper.find('[data-testid="exemption-badge"]').exists(), 'no badge').toBe(false)
    wrapper.unmount()
  })

  it('shows nothing and does not fail when the exemption cannot be read', async () => {
    const { wrapper } = await mountBadge(fail('unavailable', 503, 'UNKNOWN'))

    expect(wrapper.find('[data-testid="exemption-badge"]').exists(), 'no badge without an answer').toBe(false)
    wrapper.unmount()
  })

  it('never puts the owner hash into the markup', async () => {
    const { wrapper } = await mountBadge(ok(status()))

    expect(wrapper.html(), 'the hash is not displayed or attributed').not.toContain(OWNER)
    wrapper.unmount()
  })
})
