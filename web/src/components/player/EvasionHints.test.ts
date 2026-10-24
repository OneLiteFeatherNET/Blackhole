import { describe, expect, it, vi } from 'vitest'
import type { EvasionSightings } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import EvasionHints from './EvasionHints.vue'

const OWNER = 'ab'.repeat(64)
const ALT = 'cd'.repeat(64)

function sightings(overrides: Partial<EvasionSightings> = {}): EvasionSightings {
  return { detectionEnabled: true, windowDays: 7, linkedOwners: [], truncated: false, clientFingerprintEnabled: false, clientWindowDays: 14, ...overrides }
}

function mountHints(evasionSightings: ReturnType<typeof vi.fn>) {
  return mountWithApp(EvasionHints, { api: mockApi({ evasionSightings }), props: { owner: OWNER } })
}

const linked = { owner: ALT, reason: 'SHARED_IP', via: 'IP' as const, sharedAddresses: 2, firstSeen: Date.UTC(2030, 0, 1), lastSeen: Date.UTC(2030, 0, 2) }

describe('EvasionHints', () => {
  it('says so when no other account shares an address', async () => {
    const { wrapper } = await mountHints(vi.fn(async () => ok(sightings())))

    expect(wrapper.get('[data-testid="evasion-empty"]').text()).toContain('last 7 days')
    expect(wrapper.find('table').exists(), 'no table without rows').toBe(false)

    wrapper.unmount()
  })

  it('lists a linked account with a link to its player page and the reason', async () => {
    const { wrapper } = await mountHints(vi.fn(async () => ok(sightings({ linkedOwners: [linked] }))))

    const link = wrapper.get('table a')
    expect(link.attributes('href')).toBe(`/players/${ALT}`)
    expect(link.text()).toBe('cdcdcdcd…')
    expect(wrapper.get('table').text()).toContain('Same network address')
    expect(wrapper.get('table').text()).toContain('2')

    wrapper.unmount()
  })

  it('shows only a shortened hash as text', async () => {
    const { wrapper } = await mountHints(vi.fn(async () => ok(sightings({ linkedOwners: [linked] }))))

    expect(wrapper.text()).not.toContain(ALT)

    wrapper.unmount()
  })

  it('tells that detection is off instead of claiming there are no alts', async () => {
    const { wrapper } = await mountHints(vi.fn(async () => ok(sightings({ detectionEnabled: false }))))

    expect(wrapper.get('[data-testid="evasion-disabled"]').text()).toContain('switched off')
    expect(wrapper.find('[data-testid="evasion-empty"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('notes when the list is cut off', async () => {
    const { wrapper } = await mountHints(vi.fn(async () => ok(sightings({ linkedOwners: [linked], truncated: true }))))

    expect(wrapper.get('[data-testid="evasion-truncated"]').text()).toContain('1 most recent')

    wrapper.unmount()
  })

  it('shows a failure as an alert', async () => {
    const { wrapper } = await mountHints(vi.fn(async () => fail('boom', 500, 'UNKNOWN')))

    expect(wrapper.get('[data-testid="evasion-error"]').attributes('role')).toBe('alert')

    wrapper.unmount()
  })

  it('renders its states inside a polite live region', async () => {
    const { wrapper } = await mountHints(vi.fn(async () => ok(sightings())))

    expect(wrapper.get('[aria-live="polite"]').text()).toContain('last 7 days')

    wrapper.unmount()
  })

  it('labels a link by the client setup alone as a weak hint', async () => {
    const clientOnly = { ...linked, reason: 'SHARED_CLIENT', via: 'CLIENT' as const, sharedAddresses: 0 }
    const { wrapper } = await mountHints(vi.fn(async () => ok(sightings({ linkedOwners: [clientOnly], clientFingerprintEnabled: true }))))

    expect(wrapper.get('table').text()).toContain('Same client setup')
    expect(wrapper.get('[data-testid="weak-hint"]').text()).toContain('weak hint')
    expect(wrapper.get('[data-testid="evasion-weak-note"]').text()).toContain('no proof')

    wrapper.unmount()
  })

  it('shows a link by address and client setup as the strong one, without the weak label', async () => {
    const both = { ...linked, via: 'BOTH' as const }
    const { wrapper } = await mountHints(vi.fn(async () => ok(sightings({ linkedOwners: [both], clientFingerprintEnabled: true }))))

    expect(wrapper.get('table').text()).toContain('Address and client setup')
    expect(wrapper.find('[data-testid="weak-hint"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="evasion-weak-note"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('does not call detection off while the client signal still works', async () => {
    const { wrapper } = await mountHints(vi.fn(async () => ok(sightings({ detectionEnabled: false, clientFingerprintEnabled: true }))))

    expect(wrapper.find('[data-testid="evasion-disabled"]').exists()).toBe(false)

    wrapper.unmount()
  })
})
