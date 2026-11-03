import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { chooseOption } from '../test/select'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { RouterView } from 'vue-router'
import type { EloEvent, EloProfile, Punishment, PunishmentProfile } from '../api/types'
import { useLookupStore } from '../stores/lookup'
import { evidenceItem, evidencePage } from '../test/evidence'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import PlayerDetailView from './PlayerDetailView.vue'

const OWNER = 'ab'.repeat(64)

/** The app shell: the route that renders the player and, behind it, the child route of an action. */
const Shell = defineComponent({ setup: () => () => h(RouterView) })

function punishment(overrides: Partial<Punishment> = {}): Punishment {
  return {
    identifier: 'p-1',
    shortId: 'ABC123',
    source: '11111111-2222-3333-4444-555555555555',
    verified: true,
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
    evasionSightings: vi.fn(async () => ok({ detectionEnabled: true, windowDays: 7, linkedOwners: [], truncated: false })),
    intelHits: vi.fn(async () => ok([])),
    clientRuleHitsOfOwner: vi.fn(async () => ok([])),
    exemption: vi.fn(async () => ok({ exempt: false, sources: [], sections: [], rank: null })),
    playerSessions: vi.fn(async () => ok({ sessions: [], page: 0, size: 50, hasMore: false })),
    listPlayerNames: vi.fn(async () => ok({ owner: OWNER, playerUuid: null, names: [], page: 0, size: 50, hasMore: false })),
    ...overrides,
  })
}

/** The page at `/players/:owner` with an optional query, e.g. `?tab=names`. */
async function open(owner = OWNER, overrides: Record<string, unknown> = {}, query = '') {
  return mountWithApp(PlayerDetailView, { path: `/players/${owner}${query}`, props: { owner }, api: api(overrides) })
}

/** The page behind its child route: the address decides which action dialog is open. */
async function openRoute(path: string, overrides: Record<string, unknown> = {}) {
  return mountWithApp(Shell, { path, api: api(overrides) })
}

/** The text the snackbar announces; the copy-link live region of the header is separate. */
function snackbarText(wrapper: VueWrapper): string {
  return wrapper.findAll('[role="status"]').map((region) => region.text()).join('|')
}

describe('PlayerDetailView', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  it('shows the name history of the player on the names tab', async () => {
    const listPlayerNames = vi.fn(async () =>
      ok({ owner: OWNER, playerUuid: null, names: [{ name: 'Alex', firstSeen: 1, lastSeen: 2 }], page: 0, size: 50, hasMore: false }),
    )
    const { wrapper } = await open(OWNER, { listPlayerNames }, '?tab=names')

    expect(listPlayerNames).toHaveBeenCalledWith(OWNER, 0, 50)
    expect(wrapper.get('[aria-labelledby="names-heading"]').text()).toContain('Alex')

    wrapper.unmount()
  })

  it('shows the protected badge of a player who is exempt', async () => {
    const exempt = { exempt: true, sources: ['LINKED_STAFF'], sections: [], rank: 'ADMIN' as const }
    const { wrapper } = await open(OWNER, { exemption: vi.fn(async () => ok(exempt)) })

    expect(wrapper.get('[data-testid="exemption-badge"]').text()).toBe('Linked staff')
    wrapper.unmount()
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

  it('lists the punishment history with lifted entries marked, on the punishments tab', async () => {
    const { wrapper } = await open(OWNER, {}, '?tab=punishments')

    const rows = wrapper.get('table[aria-label="Punishment history"]').findAll('tbody tr')
    expect(rows).toHaveLength(1)
    expect(rows[0]!.text()).toContain('Spam')
    expect(rows[0]!.text()).toContain('1 / 2')
    expect(rows[0]!.text()).toContain('Lifted')

    wrapper.unmount()
  })

  it('shows the elo history of the first section and reloads it when another is chosen', async () => {
    const eloHistory = vi.fn(async (_owner: string, section: string) => ok(section === 'chat' ? events : []))
    const { wrapper } = await open(OWNER, { eloHistory }, '?tab=elo')

    expect(eloHistory).toHaveBeenCalledWith(OWNER, 'chat')
    const rows = wrapper.get('table[aria-label="ELO history"]').findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('-100')
    expect(rows[0]!.text()).toContain('Punishment applied')

    await chooseOption(wrapper.get('[role="combobox"]'), 'network')
    await flushPromises()

    expect(eloHistory).toHaveBeenLastCalledWith(OWNER, 'network')
    expect(wrapper.text()).toContain('No ELO changes recorded for this section.')

    wrapper.unmount()
  })

  it('shows a player without an active punishment as clean on the overview', async () => {
    const { wrapper } = await open(OWNER, { profile: vi.fn(async () => ok({ active: {}, history: [] })) })

    expect(wrapper.text()).toContain('No active punishments.')
    wrapper.unmount()
  })

  it('shows an empty punishment history as such on the punishments tab', async () => {
    const { wrapper } = await open(OWNER, { profile: vi.fn(async () => ok({ active: {}, history: [] })) }, '?tab=punishments')

    expect(wrapper.text()).toContain('No punishments so far.')
    expect(wrapper.find('table[aria-label="Punishment history"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows the player name when this session looked it up, and the short hash otherwise', async () => {
    const withoutName = await open()
    expect(withoutName.wrapper.get('h1').text()).toBe(`${OWNER.slice(0, 8)}…`)
    expect(withoutName.wrapper.find('[data-testid="page-description"]').exists(), 'no second line without a name').toBe(false)
    withoutName.wrapper.unmount()

    const { wrapper } = await mountWithApp(PlayerDetailView, { path: `/players/${OWNER}`, props: { owner: OWNER }, api: api() })
    useLookupStore().remember(OWNER, 'Steve')
    await flushPromises()
    expect(wrapper.get('h1').text()).toBe('Steve')

    wrapper.unmount()
  })

  it('keeps the short hash as secondary text under the name', async () => {
    const { wrapper } = await mountWithApp(PlayerDetailView, { path: `/players/${OWNER}`, props: { owner: OWNER }, api: api() })
    useLookupStore().remember(OWNER, 'Steve')
    await flushPromises()

    expect(wrapper.get('[data-testid="page-description"]').text()).toBe(`${OWNER.slice(0, 8)}…`)
    expect(wrapper.get('h1').text()).toBe('Steve')

    wrapper.unmount()
  })

  it('has one level-one heading and breadcrumbs back to the lookup, not a text link in the body', async () => {
    const { wrapper } = await open()

    expect(wrapper.findAll('h1')).toHaveLength(1)
    const crumbs = wrapper.get('nav[aria-label="Breadcrumbs"]')
    // The default test user is an administrator, so the workspace leads the trail; the lookup is the link before the player.
    expect(crumbs.findAll('a')[0]!.text()).toBe('Moderation')
    expect(crumbs.findAll('a')[1]!.attributes('href')).toBe('/players')
    expect(crumbs.findAll('a')[1]!.text()).toBe('Player lookup')
    expect(crumbs.get('[aria-current="page"]').text()).toBe(`${OWNER.slice(0, 8)}…`)
    expect(wrapper.findAll(`a[href="/players"]`), 'the way back is the breadcrumb only').toHaveLength(1)

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
    const session = { sessionId: 's-1', owner: OWNER, playerUuid: '11111111-2222-3333-4444-555555555555', playerName: 'Steve', ip: '203.0.113.7', asOrg: 'Example Transit', outcome: 'ALLOWED', startedAt: 1, endedAt: null }
    const { wrapper } = await open(OWNER, { playerSessions: vi.fn(async () => ok({ sessions: [session], page: 0, size: 50, hasMore: false })) })
    useLookupStore().remember(OWNER, 'Steve')
    await flushPromises()

    const stored = JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage })
    expect(stored).not.toContain(OWNER)
    expect(stored).not.toContain('Steve')
    expect(stored, 'the session address and provider stay in memory').not.toContain('203.0.113.7')
    expect(stored).not.toContain('Example Transit')
    expect(stored).not.toContain('11111111-2222-3333-4444-555555555555')

    wrapper.unmount()
  })
})

describe('PlayerDetailView: tabs', () => {
  it('shows the overview by default and none of the content of the other tabs', async () => {
    const { wrapper } = await open()

    expect(wrapper.find('[aria-labelledby="scores-heading"]').exists()).toBe(true)
    expect(wrapper.find('[aria-labelledby="names-heading"]').exists()).toBe(false)
    expect(wrapper.find('[aria-labelledby="elo-heading"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('loads the data of the tab that the address names and not the data of the others', async () => {
    const evasionSightings = vi.fn(async () => ok({ detectionEnabled: true, windowDays: 7, linkedOwners: [], truncated: false }))
    const { wrapper } = await open(OWNER, { evasionSightings }, '?tab=elo')
    await flushPromises()

    expect(wrapper.find('table[aria-label="ELO history"]').exists()).toBe(true)
    expect(evasionSightings, 'the evasion tab is not open').not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('changes the tab in the address without reading the player again', async () => {
    const profileCall = vi.fn(async () => ok(profile))
    const { wrapper, router } = await open(OWNER, { profile: profileCall })

    await wrapper.get('[data-testid="tab-names"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.tab).toBe('names')
    expect(wrapper.find('[aria-labelledby="names-heading"]').exists()).toBe(true)
    expect(profileCall).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('replaces an unknown tab in the address with the overview', async () => {
    const { wrapper, router } = await open(OWNER, {}, '?tab=unknown')

    expect(router.currentRoute.value.query.tab).toBeUndefined()
    expect(wrapper.find('[aria-labelledby="scores-heading"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('lists the warnings of the history on the warnings tab and never a Ban ID', async () => {
    const warning = punishment({ identifier: 'warning-identifier-7f3c', section: 'chat', kind: 'WARN', shortId: 'ABC999' } as never)
    const { wrapper } = await open(OWNER, { profile: vi.fn(async () => ok({ active: {}, history: [warning] })) }, '?tab=warnings')

    expect(wrapper.get('[data-testid="warnings-list"]').text()).toContain('ABC999')
    expect(wrapper.html()).not.toContain('warning-identifier-7f3c')
    wrapper.unmount()
  })

  it('lists the reports against the player on the reports tab, each one a link to its own page', async () => {
    const report = { identifier: '3f2b8c1e-0000-4000-8000-000000000001', category: 'CHEATING', status: 'OPEN', createdAt: Date.UTC(2030, 0, 1) }
    const reportsAgainst = vi.fn(async () => ok([report]))
    const { wrapper } = await open(OWNER, { reportsAgainst }, '?tab=reports')
    await flushPromises()

    expect(reportsAgainst).toHaveBeenCalledWith(OWNER)
    expect(wrapper.get('[data-testid="player-reports"] a').attributes('href')).toBe(`/reports/${report.identifier}`)
    wrapper.unmount()
  })

  it('lists the cases of the player on the cases tab, each one a link to its case', async () => {
    const cases = vi.fn(async () => ok({ items: [{ punishmentIdentifier: 'p-1', shortId: '#A1B2C3D4', status: 'OPEN', lastActivityAt: Date.UTC(2030, 0, 1) }], page: 0, size: 50, totalPages: 1, totalItems: 1 }))
    const { wrapper } = await open(OWNER, { cases }, '?tab=cases')
    await flushPromises()

    expect(cases).toHaveBeenCalledWith({ owner: OWNER })
    expect(wrapper.get('[data-testid="player-cases"] a').attributes('href')).toBe('/cases/p-1')
    wrapper.unmount()
  })

  it('lists the punishments of the player on the evidence tab, each linking to its evidence', async () => {
    const { wrapper } = await open(OWNER, { profile: vi.fn(async () => ok({ ...profile, active: { network: punishment({ evidenceCount: 2 } as never) } })) }, '?tab=evidence')

    expect(wrapper.get('[data-testid="evidence-list"] a').attributes('href')).toBe(`/punished/ABC123?owner=${OWNER}&tab=evidence`)
    expect(wrapper.get('[data-testid="evidence-list"]').text()).toContain('2 evidence items')
    wrapper.unmount()
  })

  it('shows the revoke of an active punishment as a link to its own address, not as a dialog', async () => {
    const { wrapper } = await open(OWNER, { revoke: vi.fn() })

    expect(wrapper.get('[data-testid="open-revoke-network"]').attributes('href')).toBe(`/punished/ABC123/revoke?owner=${OWNER}`)
    wrapper.unmount()
  })
})

describe('PlayerDetailView: staff actions', () => {
  const template = { identifier: 'id-1', key: 'hacking', reason: 'Hacking', section: 'network', steps: [] }
  const preview = {
    section: 'network', enforcement: 'BAN', step: 2, stepCount: 3, duration: 'P7D', eloCost: 150,
    scoreBefore: 900, scoreAfter: 750, permanentThreshold: 300, escalatesToPermanent: false,
  }
  const applyApi = (overrides: Record<string, unknown> = {}) => ({
    templates: vi.fn(async () => ok([template])),
    previewApply: vi.fn(async () => ok(preview)),
    ...overrides,
  })

  it('opens the apply route from the header and sends nothing before a template is confirmed', async () => {
    const applyTemplate = vi.fn()
    const { wrapper, router } = await openRoute(`/players/${OWNER}`, applyApi({ applyTemplate }))

    await wrapper.get('[data-testid="open-apply"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('player-punish')
    expect(applyTemplate, 'nothing happens on navigation').not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('applies a template from its route after confirmation, reloads the player and returns to the page', async () => {
    const profileCall = vi.fn(async () => ok(profile))
    const applyTemplate = vi.fn(async () => ok({ step: 2, stepCount: 3, scoreAfter: 750, section: 'network' }))
    const { wrapper, router } = await openRoute(`/players/${OWNER}/punish`, applyApi({ profile: profileCall, applyTemplate }))
    await flushPromises()

    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'hacking')
    await flushPromises()
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(applyTemplate).toHaveBeenCalledWith(OWNER, 'hacking', null)
    expect(profileCall, 'profile loaded again after the action').toHaveBeenCalledTimes(2)
    expect(router.currentRoute.value.name, 'back on the player').toBe('player')
    expect(snackbarText(wrapper)).toContain('Applied step 2 of 3; the score is now 750.')

    wrapper.unmount()
  })

  it('applies a template with the player uuid that the newest session shows', async () => {
    const uuid = '11111111-2222-3333-4444-555555555555'
    const session = { sessionId: 's-1', owner: OWNER, playerUuid: uuid, playerName: 'Steve', ip: null, asOrg: null, outcome: 'ALLOWED', startedAt: 1, endedAt: null }
    const applyTemplate = vi.fn(async () => ok({ step: 2, stepCount: 3, scoreAfter: 750, section: 'network' }))
    const { wrapper } = await openRoute(
      `/players/${OWNER}/punish`,
      applyApi({
        playerSessions: vi.fn(async () => ok({ sessions: [session], page: 0, size: 1, hasMore: false })),
        applyTemplate,
      }),
    )
    await flushPromises()

    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'hacking')
    await flushPromises()
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(applyTemplate).toHaveBeenCalledWith(OWNER, 'hacking', uuid)

    wrapper.unmount()
  })

  it('closes the apply route on cancel without any request', async () => {
    const applyTemplate = vi.fn()
    const { wrapper, router } = await openRoute(`/players/${OWNER}/punish`, applyApi({ applyTemplate }))
    await flushPromises()

    await wrapper.get('[data-testid="cancel"]').trigger('click')
    await flushPromises()

    expect(applyTemplate).not.toHaveBeenCalled()
    expect(router.currentRoute.value.name).toBe('player')
    wrapper.unmount()
  })

  it('opens the warn route and sends no warning until a template is applied', async () => {
    const applyTemplate = vi.fn()
    const { wrapper, router } = await openRoute(`/players/${OWNER}/warn`, { templates: vi.fn(async () => ok([template])), applyTemplate })
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('player-warn')
    expect(applyTemplate).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('opens the kick route without sending a kick', async () => {
    const kickPlayer = vi.fn()
    const { wrapper, router } = await openRoute(`/players/${OWNER}/kick`, {
      sections: vi.fn(async () => ok([])),
      cloudnetCatalogue: vi.fn(async () => fail('none', 0, 'NETWORK')),
      kickPlayer,
    })
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('player-kick')
    expect(kickPlayer).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('adjusts a section score from its route, with a note, and returns to the page', async () => {
    const adjustElo = vi.fn(async () => ok({ section: 'chat', scoreBefore: 900, scoreAfter: 850 }))
    const { wrapper, router } = await openRoute(`/players/${OWNER}`, { adjustElo })

    await wrapper.get('[data-testid="open-adjust-chat"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe(`/players/${OWNER}/elo/adjust?section=chat`)
    expect(adjustElo, 'nothing happens before the confirmation').not.toHaveBeenCalled()

    const [delta, note] = wrapper.findAll('[role="dialog"] input')
    await delta!.setValue('-50')
    await note!.setValue('spam wave')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(adjustElo).toHaveBeenCalledWith(OWNER, { section: 'chat', delta: -50, note: 'spam wave' })
    expect(router.currentRoute.value.name).toBe('player')
    expect(snackbarText(wrapper)).toContain('Score adjusted to 850.')

    wrapper.unmount()
  })

  it('shows not-found and no adjust form for a section the player does not have', async () => {
    const adjustElo = vi.fn()
    const { wrapper } = await openRoute(`/players/${OWNER}/elo/adjust?section=unknown`, { adjustElo })
    await flushPromises()

    expect(wrapper.text()).toContain('This item does not exist or was removed.')
    expect(wrapper.find('[role="dialog"] input').exists()).toBe(false)
    expect(adjustElo).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('PlayerDetailView: evidence', () => {
  const evidenceApi = () => {
    const evidence = vi.fn(async () => ok(evidencePage([evidenceItem({ kind: 'ANTICHEAT', payload: { check: 'Speed' } }), evidenceItem({ kind: 'FILE', file: { name: 'shot.png', contentType: 'image/png', size: 1, sha256: 'x' } })])))
    return { evidence, overrides: { evidence, evidenceCounts: vi.fn(async () => ok({ count: 2, playerSupplied: 0 })), evidenceContent: async () => ok(new Blob(['x'], { type: 'image/png' })) } }
  }

  it('loads no evidence until a punishment is opened', async () => {
    const { evidence, overrides } = evidenceApi()
    const { wrapper } = await open(OWNER, overrides)

    expect(evidence).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="evidence-panel"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows the evidence of an active punishment with the kind icons', async () => {
    const { evidence, overrides } = evidenceApi()
    const { wrapper } = await open(OWNER, overrides)

    await wrapper.get('[data-testid="open-evidence-network"]').trigger('click')
    await flushPromises()

    expect(evidence).toHaveBeenCalledWith('p-1', { includeInactive: false, page: 0 })
    expect(wrapper.findAll('[data-testid="kind-icon"]').map((icon) => icon.attributes('data-kind'))).toEqual(['ANTICHEAT', 'FILE'])

    wrapper.unmount()
  })

  it('shows the evidence of a punishment from the history', async () => {
    const { evidence, overrides } = evidenceApi()
    const { wrapper } = await open(OWNER, overrides, '?tab=punishments')

    await wrapper.get('[data-testid="open-evidence-p-0"]').trigger('click')
    await flushPromises()

    expect(evidence).toHaveBeenCalledWith('p-0', { includeInactive: false, page: 0 })
    expect(wrapper.findAll('[data-testid="evidence-item"]')).toHaveLength(2)

    wrapper.unmount()
  })

  it('closes the evidence dialog again', async () => {
    const { overrides } = evidenceApi()
    const { wrapper } = await open(OWNER, overrides)

    await wrapper.get('[data-testid="open-evidence-network"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="close-evidence"]').trigger('click')

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('PlayerDetailView: header', () => {
  it('shows the player state in words: punished while a punishment is in force', async () => {
    const { wrapper } = await open()

    expect(wrapper.get('[data-testid="player-state"]').text()).toBe('Punished')
    wrapper.unmount()
  })

  it('shows a player without an active punishment as not punished', async () => {
    const { wrapper } = await open(OWNER, { profile: vi.fn(async () => ok({ ...profile, active: {} })) })

    expect(wrapper.get('[data-testid="player-state"]').text()).toBe('Not punished')
    wrapper.unmount()
  })

  it('gives a viewer no apply action and says why', async () => {
    const { wrapper } = await mountWithApp(PlayerDetailView, { path: `/players/${OWNER}`, props: { owner: OWNER }, api: api(), roles: ['VIEWER'] })

    expect(wrapper.find('[data-testid="open-apply"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="entity-notice"]').text()).toBe('A viewer cannot change a player.')
    wrapper.unmount()
  })

  it('names the player in the header and keeps the owner hash under the name', async () => {
    const { wrapper } = await open(OWNER, { listPlayerNames: vi.fn(async () => ok({ owner: OWNER, playerUuid: null, names: [{ name: 'Steve', firstSeen: 0, lastSeen: 0 }], page: 0, size: 50, hasMore: false })) })

    expect(wrapper.get('[data-testid="entity-header"] h1').text()).toBe('Steve')
    wrapper.unmount()
  })
})

describe('PlayerDetailView: Kick is destructive', () => {
  it('gives Kick the error tone, so it does not weigh the same as Warn', async () => {
    const { wrapper } = await open()

    await wrapper.get('[data-testid="entity-header"] button[aria-haspopup="menu"]').trigger('click')
    const item = (label: string) => wrapper.findAll('[role="menuitem"]').find((candidate) => candidate.text() === label)!
    expect(item('Kick').classes()).toContain('text-error')
    expect(item('Warn').classes()).not.toContain('text-error')

    wrapper.unmount()
  })
})
