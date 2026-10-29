import { describe, expect, it, vi } from 'vitest'
import type { IntelHit } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import IntelHitsSection from './IntelHitsSection.vue'

const OWNER = 'ab'.repeat(64)
const EARLIER = Date.UTC(2030, 0, 1)
const LATER = Date.UTC(2030, 0, 2)

function hit(overrides: Partial<IntelHit> = {}): IntelHit {
  return {
    id: 'hit-1',
    ip: '203.0.113.7',
    classification: 'VPN',
    sourceKey: 'list:x4bnet-vpn',
    verdict: 'FLAG',
    allowListed: false,
    createdAt: LATER,
    ...overrides,
  }
}

function mountSection(intelHits: ReturnType<typeof vi.fn>, roles: string[] = ['VIEWER']) {
  return mountWithApp(IntelHitsSection, { api: mockApi({ intelHits }), props: { owner: OWNER }, roles })
}

describe('IntelHitsSection', () => {
  it('lists the class, the source key and the verdict of each hit', async () => {
    const { wrapper } = await mountSection(vi.fn(async () => ok([hit()])))

    const row = wrapper.get('tbody tr')
    expect(row.text()).toContain('VPN')
    expect(row.text()).toContain('list:x4bnet-vpn')
    expect(row.text()).toContain('Flag')

    wrapper.unmount()
  })

  it('shows whether the owner was allow-listed at the newest login', async () => {
    const { wrapper } = await mountSection(vi.fn(async () => ok([hit({ allowListed: true })])))

    expect(wrapper.get('[data-testid="intel-allow-list-state"]').text()).toBe('Allow-listed at the last login.')

    wrapper.unmount()
  })

  it('never shows the address of a hit', async () => {
    const { wrapper } = await mountSection(vi.fn(async () => ok([hit()])))

    expect(wrapper.text()).not.toContain('203.0.113.7')

    wrapper.unmount()
  })

  it('shows the newest hit first', async () => {
    const older = hit({ id: 'old', createdAt: EARLIER, sourceKey: 'asn:hosting' })
    const newer = hit({ id: 'new', createdAt: LATER, sourceKey: 'list:x4bnet-vpn' })
    const { wrapper } = await mountSection(vi.fn(async () => ok([older, newer])))

    const rows = wrapper.findAll('tbody tr')
    expect(rows[0]!.text()).toContain('list:x4bnet-vpn')
    expect(rows[1]!.text()).toContain('asn:hosting')

    wrapper.unmount()
  })

  it('says so when no login was flagged', async () => {
    const { wrapper } = await mountSection(vi.fn(async () => ok([])))

    expect(wrapper.get('[data-testid="intel-empty"]').text()).toContain('No VPN or hosting login')
    expect(wrapper.find('table').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows nothing and asks the backend for nothing to a caller without a staff role', async () => {
    const intelHits = vi.fn(async () => ok([hit()]))
    const { wrapper } = await mountSection(intelHits, [])

    expect(intelHits, 'no request without a staff role').not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="intel-hits"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('list:x4bnet-vpn')

    wrapper.unmount()
  })

  it('shows a failure as an alert', async () => {
    const { wrapper } = await mountSection(vi.fn(async () => fail('boom', 500, 'UNKNOWN')))

    expect(wrapper.get('[data-testid="intel-error"]').attributes('role')).toBe('alert')

    wrapper.unmount()
  })
})
