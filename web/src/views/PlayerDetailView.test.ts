import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { EloEvent, EloProfile, Punishment, PunishmentProfile } from '../api/types'
import { useLookupStore } from '../stores/lookup'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import PlayerDetailView from './PlayerDetailView.vue'

const OWNER = 'ab'.repeat(64)

function punishment(overrides: Partial<Punishment> = {}): Punishment {
  return {
    identifier: 'p-1',
    source: '11111111-2222-3333-4444-555555555555',
    section: 'network',
    permanent: false,
    lifted: false,
    createdAt: Date.UTC(2030, 0, 1),
    metaData: { expiration_date: Date.UTC(2030, 0, 8) } as never,
    template: { reason: 'Hacking', key: 'hacking' } as never,
    stepIndex: 1,
    stepCount: 3,
    eloCost: 150,
    ...overrides,
  }
}

const elo: EloProfile = {
  owner: OWNER,
  sections: [
    { section: 'chat', displayName: 'Chat', enforcement: 'MUTE', score: 900, baseScore: 1000, softThreshold: 700, permanentThreshold: 300, pardonScore: 700 },
    { section: 'network', displayName: 'Network', enforcement: 'BAN', score: 250, baseScore: 1000, softThreshold: 700, permanentThreshold: 300, pardonScore: 700 },
  ],
}

const profile: PunishmentProfile = {
  active: { network: punishment() },
  history: [punishment({ identifier: 'p-0', section: 'chat', lifted: true, stepIndex: 0, stepCount: 2, eloCost: 100, template: { reason: 'Spam', key: 'spam' } as never })],
}

const events: EloEvent[] = [
  { identifier: 'e-2', owner: OWNER, section: 'chat', delta: -100, reasonCode: 'PUNISHMENT_APPLIED', resultingScore: 900, createdAt: Date.UTC(2030, 0, 2), metaData: {} },
  { identifier: 'e-1', owner: OWNER, section: 'chat', delta: 10, reasonCode: 'DECAY_RECOVERY', resultingScore: 1000, createdAt: Date.UTC(2030, 0, 1), metaData: {} },
]

function api(overrides: Record<string, unknown> = {}) {
  return mockApi({
    profile: vi.fn(async () => ok(profile)),
    eloProfile: vi.fn(async () => ok(elo)),
    eloHistory: vi.fn(async () => ok(events)),
    ...overrides,
  })
}

async function open(owner = OWNER, overrides: Record<string, unknown> = {}) {
  return mountWithApp(PlayerDetailView, { path: `/players/${owner}`, props: { owner }, api: api(overrides) })
}

describe('PlayerDetailView', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  it('shows a score bar per section against its thresholds', async () => {
    const { wrapper } = await open()

    const meters = wrapper.findAll('[role="meter"]')
    expect(meters.map((m) => m.attributes('aria-label'))).toEqual(['Chat', 'Network'])
    expect(meters[0]!.attributes('data-zone'), 'chat at 900').toBe('healthy')
    expect(meters[1]!.attributes('data-zone'), 'network at 250 is below the permanent threshold').toBe('permanent')

    wrapper.unmount()
  })

  it('lists the active punishment of a section with its step and reason', async () => {
    const { wrapper } = await open()

    const active = wrapper.get('[data-testid="active-network"]')
    expect(active.text()).toContain('Hacking')
    expect(active.text()).toContain('2 / 3')

    wrapper.unmount()
  })

  it('marks a permanent active punishment without an end date', async () => {
    const permanent = punishment({ permanent: true, template: null, stepIndex: null, stepCount: null, metaData: { reason: 'Banned by score', automatic: true } as never })
    const { wrapper } = await open(OWNER, { profile: vi.fn(async () => ok({ active: { network: permanent }, history: [] })) })

    const active = wrapper.get('[data-testid="active-network"]')
    expect(active.text()).toContain('Permanent')
    expect(active.text()).toContain('Banned by score')

    wrapper.unmount()
  })

  it('lists the punishment history with lifted entries marked', async () => {
    const { wrapper } = await open()

    const rows = wrapper.get('table[aria-label="Punishment history"]').findAll('tbody tr')
    expect(rows).toHaveLength(1)
    expect(rows[0]!.text()).toContain('Spam')
    expect(rows[0]!.text()).toContain('1 / 2')
    expect(rows[0]!.text()).toContain('Lifted')

    wrapper.unmount()
  })

  it('shows the elo history of the first section and reloads it when another is chosen', async () => {
    const eloHistory = vi.fn(async (_owner: string, section: string) => ok(section === 'chat' ? events : []))
    const { wrapper } = await open(OWNER, { eloHistory })

    expect(eloHistory).toHaveBeenCalledWith(OWNER, 'chat')
    const rows = wrapper.get('table[aria-label="ELO history"]').findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('-100')
    expect(rows[0]!.text()).toContain('Punishment applied')

    await wrapper.get('select').setValue('network')
    await flushPromises()

    expect(eloHistory).toHaveBeenLastCalledWith(OWNER, 'network')
    expect(wrapper.text()).toContain('No ELO changes recorded for this section.')

    wrapper.unmount()
  })

  it('shows a player without any punishment as clean', async () => {
    const { wrapper } = await open(OWNER, { profile: vi.fn(async () => ok({ active: {}, history: [] })) })

    expect(wrapper.text()).toContain('No active punishments.')
    expect(wrapper.text()).toContain('No punishments so far.')
    expect(wrapper.find('table[aria-label="Punishment history"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows the player name when this session looked it up, and the short hash otherwise', async () => {
    const withoutName = await open()
    expect(withoutName.wrapper.get('[data-testid="player-title"]').text()).toBe(`${OWNER.slice(0, 8)}…`)
    withoutName.wrapper.unmount()

    const { wrapper } = await mountWithApp(PlayerDetailView, { path: `/players/${OWNER}`, props: { owner: OWNER }, api: api() })
    useLookupStore().remember(OWNER, 'Steve')
    await flushPromises()
    expect(wrapper.get('[data-testid="player-title"]').text()).toBe('Steve')

    wrapper.unmount()
  })

  it('shows not-found and no detail for an owner that is not a hash', async () => {
    const profileCall = vi.fn()
    const { wrapper } = await open('Steve', { profile: profileCall })

    expect(wrapper.text()).toContain('This is not a valid player identifier.')
    expect(profileCall).not.toHaveBeenCalled()
    expect(wrapper.find('[role="meter"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows the backend error and a retry when loading fails', async () => {
    const eloProfile = vi.fn().mockResolvedValueOnce(fail('backend down', 0, 'NETWORK')).mockResolvedValueOnce(ok(elo))
    const { wrapper } = await open(OWNER, { eloProfile })

    expect(wrapper.get('[role="alert"]').text()).toContain('backend down')
    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('[role="meter"]')).toHaveLength(2)

    wrapper.unmount()
  })

  it('keeps no player identifier in browser storage', async () => {
    const { wrapper } = await open()
    useLookupStore().remember(OWNER, 'Steve')
    await flushPromises()

    const stored = JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage })
    expect(stored).not.toContain(OWNER)
    expect(stored).not.toContain('Steve')

    wrapper.unmount()
  })
})
