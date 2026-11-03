import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { RouterView } from 'vue-router'
import type { EloProfile, Punishment, PunishmentProfile } from '../api/types'
import { evidenceItem, evidencePage } from '../test/evidence'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import PunishedDetailView from './PunishedDetailView.vue'

const OWNER = 'ab'.repeat(64)
const SHORT_ID = 'A1B2C3D4'

/** The app shell: the route that renders the punished entry and, behind it, its revoke confirmation. */
const Shell = defineComponent({ setup: () => () => h(RouterView) })

function punishment(overrides: Partial<Punishment> = {}): Punishment {
  return {
    identifier: 'p-1',
    shortId: SHORT_ID,
    source: '11111111-2222-3333-4444-555555555555',
    verified: true,
    section: 'network',
    permanent: false,
    lifted: false,
    createdAt: Date.UTC(2030, 0, 1),
    metaData: {} as never,
    template: { reason: 'Hacking', key: 'hacking' } as never,
    stepIndex: 1,
    stepCount: 3,
    eloCost: 150,
    evidenceCount: 2,
    ...overrides,
  } as Punishment
}

const elo: EloProfile = {
  owner: OWNER,
  sections: [{ section: 'network', displayName: 'Network', enforcement: 'BAN', score: 250, baseScore: 1000, softThreshold: 700, permanentThreshold: 300, pardonScore: 700 }],
}

function api(overrides: Record<string, unknown> = {}) {
  const profile: PunishmentProfile = { active: { network: punishment() }, history: [punishment({ identifier: 'p-0', section: 'chat', lifted: true })] }
  return mockApi({
    punishmentsByShortId: vi.fn(async () => ok([punishment()])),
    profile: vi.fn(async () => ok(profile)),
    eloProfile: vi.fn(async () => ok(elo)),
    evidence: vi.fn(async () => ok(evidencePage([evidenceItem({ kind: 'ANTICHEAT', payload: { check: 'Speed' } })]))),
    evidenceCounts: vi.fn(async () => ok({ count: 1, playerSupplied: 0 })),
    revoke: vi.fn(async () => ok({ section: 'network' })),
    ...overrides,
  })
}

describe('PunishedDetailView', () => {
  it('shows the punishment found by its short id, with its section and reason', async () => {
    const punishmentsByShortId = vi.fn(async () => ok([punishment()]))
    const { wrapper } = await mountWithApp(PunishedDetailView, {
      path: `/punished/${SHORT_ID}`,
      props: { shortId: SHORT_ID },
      api: api({ punishmentsByShortId }),
    })

    expect(punishmentsByShortId).toHaveBeenCalledWith(SHORT_ID)
    expect(wrapper.get('[data-testid="punished-detail"]').text()).toContain('network')
    expect(wrapper.get('[data-testid="punished-detail"]').text()).toContain('Hacking')
    wrapper.unmount()
  })

  it('shows not-found and no details for a short id that matches nothing', async () => {
    const { wrapper } = await mountWithApp(PunishedDetailView, {
      path: `/punished/${SHORT_ID}`,
      props: { shortId: SHORT_ID },
      api: api({ punishmentsByShortId: vi.fn(async () => ok([])) }),
    })

    expect(wrapper.text()).toContain('This item does not exist or was removed.')
    expect(wrapper.find('[data-testid="punished-detail"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('does not ask the backend for a value that is not a short id', async () => {
    const punishmentsByShortId = vi.fn()
    const { wrapper } = await mountWithApp(PunishedDetailView, {
      path: '/punished/not-a-short-id',
      props: { shortId: 'not-a-short-id' },
      api: api({ punishmentsByShortId }),
    })

    expect(punishmentsByShortId).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('This item does not exist or was removed.')
    wrapper.unmount()
  })

  it('offers the revoke action to a moderator, as a link to its confirmation route', async () => {
    const { wrapper } = await mountWithApp(PunishedDetailView, {
      path: `/punished/${SHORT_ID}?owner=${OWNER}`,
      props: { shortId: SHORT_ID },
      api: api(),
    })

    expect(wrapper.get('[data-testid="open-revoke"]').attributes('href')).toBe(`/punished/${SHORT_ID}/revoke?owner=${OWNER}`)
    wrapper.unmount()
  })

  it('offers copy link, which holds the short id and no Ban ID', async () => {
    const { wrapper } = await mountWithApp(PunishedDetailView, {
      path: `/punished/${SHORT_ID}?owner=${OWNER}`,
      props: { shortId: SHORT_ID },
      api: api(),
    })

    expect(wrapper.find('[data-testid="copy-link"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('gives a viewer no revoke action and says so', async () => {
    const { wrapper } = await mountWithApp(PunishedDetailView, {
      path: `/punished/${SHORT_ID}?owner=${OWNER}`,
      props: { shortId: SHORT_ID },
      api: api(),
      roles: ['VIEWER'],
    })

    expect(wrapper.find('[data-testid="open-revoke"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('offers no revoke for a punishment that is already lifted', async () => {
    const { wrapper } = await mountWithApp(PunishedDetailView, {
      path: `/punished/${SHORT_ID}?owner=${OWNER}`,
      props: { shortId: SHORT_ID },
      api: api({ punishmentsByShortId: vi.fn(async () => ok([punishment({ lifted: true })])) }),
    })

    expect(wrapper.find('[data-testid="open-revoke"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('shows the history of the player only when the owner is in the address', async () => {
    const profile = vi.fn(async () => ok({ active: {}, history: [] }))
    const { wrapper } = await mountWithApp(PunishedDetailView, {
      path: `/punished/${SHORT_ID}?tab=history`,
      props: { shortId: SHORT_ID },
      api: api({ profile }),
    })

    expect(profile).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Open the player to see its history.')
    wrapper.unmount()
  })

  it('loads the history of the owner on the history tab', async () => {
    const profile = vi.fn(async () => ok({ active: {}, history: [punishment({ identifier: 'p-0', section: 'chat', lifted: true })] }))
    const { wrapper } = await mountWithApp(PunishedDetailView, {
      path: `/punished/${SHORT_ID}?tab=history&owner=${OWNER}`,
      props: { shortId: SHORT_ID },
      api: api({ profile }),
    })

    expect(profile).toHaveBeenCalledWith(OWNER)
    expect(wrapper.get('table[aria-label="Punishment history"]').text()).toContain('chat')
    wrapper.unmount()
  })

  it('loads the evidence of the punishment on the evidence tab', async () => {
    const evidence = vi.fn(async () => ok(evidencePage([evidenceItem({ kind: 'ANTICHEAT', payload: { check: 'Speed' } })])))
    const { wrapper } = await mountWithApp(PunishedDetailView, {
      path: `/punished/${SHORT_ID}?tab=evidence`,
      props: { shortId: SHORT_ID },
      api: api({ evidence }),
    })

    expect(evidence).toHaveBeenCalledWith('p-1', expect.anything())
    wrapper.unmount()
  })

  it('shows no details and no revoke when several punishments share the short id', async () => {
    const { wrapper } = await mountWithApp(PunishedDetailView, {
      path: `/punished/${SHORT_ID}?owner=${OWNER}`,
      props: { shortId: SHORT_ID },
      api: api({ punishmentsByShortId: vi.fn(async () => ok([punishment({ identifier: 'p-1' }), punishment({ identifier: 'p-2' })])) }),
    })

    expect(wrapper.text()).toContain('More than one punishment has this short ID.')
    expect(wrapper.find('[data-testid="punished-detail"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="open-revoke"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('shows the error of the backend when the lookup fails', async () => {
    const { wrapper } = await mountWithApp(PunishedDetailView, {
      path: `/punished/${SHORT_ID}`,
      props: { shortId: SHORT_ID },
      api: api({ punishmentsByShortId: vi.fn(async () => fail('backend down', 0, 'NETWORK')) }),
    })

    expect(wrapper.get('[role="alert"]').text()).toContain('backend down')
    wrapper.unmount()
  })

  describe('the revoke confirmation route', () => {
    it('sends nothing when it is opened and revokes the section after the confirmation', async () => {
      const revoke = vi.fn(async () => ok({ section: 'network' }))
      const { wrapper, router } = await mountWithApp(Shell, {
        path: `/punished/${SHORT_ID}/revoke?owner=${OWNER}`,
        api: api({ revoke }),
      })
      await flushPromises()

      expect(revoke, 'nothing happens before the confirmation').not.toHaveBeenCalled()
      await wrapper.get('[data-testid="confirm"]').trigger('click')
      await flushPromises()

      expect(revoke).toHaveBeenCalledWith(OWNER, 'network')
      expect(router.currentRoute.value.name).toBe('punished-detail')
      wrapper.unmount()
    })

    it('closes without a request when the confirmation is cancelled', async () => {
      const revoke = vi.fn()
      const { wrapper, router } = await mountWithApp(Shell, {
        path: `/punished/${SHORT_ID}/revoke?owner=${OWNER}`,
        api: api({ revoke }),
      })
      await flushPromises()

      await wrapper.get('[data-testid="cancel"]').trigger('click')
      await flushPromises()

      expect(revoke).not.toHaveBeenCalled()
      expect(router.currentRoute.value.name).toBe('punished-detail')
      wrapper.unmount()
    })

    it('shows the not-found text and no confirmation when the owner is missing', async () => {
      const revoke = vi.fn()
      const { wrapper } = await mountWithApp(Shell, { path: `/punished/${SHORT_ID}/revoke`, api: api({ revoke }) })
      await flushPromises()

      expect(wrapper.find('[data-testid="confirm"]').exists()).toBe(false)
      expect(revoke).not.toHaveBeenCalled()
      wrapper.unmount()
    })
  })
})
