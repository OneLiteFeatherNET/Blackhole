import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ActivePunishment, ActivePunishmentPage, Section } from '../api/types'
import { useLookupStore } from '../stores/lookup'
import { ME } from '../test/cases'
import { mockApi, mountWithApp, ok } from '../test/mount'
import { chooseOption } from '../test/select'
import PunishedPlayersView from './PunishedPlayersView.vue'

const NOW = Date.UTC(2030, 0, 1, 12, 0)
const ALICE = 'ab'.repeat(64)
const BOB = 'cd'.repeat(64)

function row(overrides: Partial<ActivePunishment> = {}): ActivePunishment {
  return {
    owner: ALICE,
    punishmentId: 'p-0001',
    shortId: '#AB12CD34',
    section: 'network',
    sectionName: 'Network',
    kind: 'BAN',
    templateKey: 'hacking',
    reason: 'Hacking',
    automatic: false,
    createdAt: NOW - 3_600_000,
    expiresAt: NOW + 2 * 86_400_000,
    permanent: false,
    caseStatus: 'IN_REVIEW',
    actorName: 'Ada',
    ...overrides,
  }
}

function page(items: ActivePunishment[], totalPages = 1, totalSize = items.length): ActivePunishmentPage {
  return { items, page: 0, size: 25, totalSize, totalPages }
}

const sections = [{ key: 'network', displayName: 'Network' }, { key: 'chat', displayName: 'Chat' }] as unknown as Section[]

function setup(pages: ActivePunishmentPage[] = [page([row(), row({ owner: BOB, punishmentId: 'p-0002', shortId: '#EF56AB78', permanent: true, expiresAt: null, automatic: true, actorName: null, caseStatus: null, kind: 'MUTE', sectionName: 'Chat', section: 'chat' })])]) {
  const activePunishments = vi.fn(async () => ok(pages.shift() ?? page([])))
  const mount = (roles?: string[]) => mountWithApp(PunishedPlayersView, { api: mockApi({ activePunishments, sections: vi.fn(async () => ok(sections)) }), path: '/punished', roles })
  return { activePunishments, mount }
}

beforeEach(() => {
  // Only the clock is faked: promises and the DOM scheduler keep running, nothing waits.
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(NOW)
})
afterEach(() => vi.useRealTimers())

describe('PunishedPlayersView', () => {
  it('lists the punished players with links to the player and the case', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    const links = rows[0]!.findAll('a')
    expect(links[0]!.attributes('href')).toBe(`/players/${ALICE}`)
    expect(links[0]!.text()).toBe('abababab…')
    expect(links[1]!.attributes('href')).toBe('/cases/p-0001')
    expect(rows[0]!.text()).toContain('Ban')
    expect(rows[0]!.text()).toContain('Hacking')
    expect(rows[0]!.text()).toContain('2 days')
    expect(rows[0]!.text()).toContain('Ada')
    expect(rows[0]!.text()).toContain('In review')
    expect(rows[0]!.text()).toContain('Manual')

    wrapper.unmount()
  })

  it('says permanent, automatic and no case in words, not only in colour', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const text = wrapper.findAll('tbody tr')[1]!.text()
    expect(text).toContain('permanent')
    expect(text).toContain('Automatic (ELO)')
    expect(text).toContain('No case')

    wrapper.unmount()
  })

  it('has a labelled table with column headers', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.get('table').attributes('aria-label')).toBe('Punished players')
    expect(wrapper.findAll('th[scope="col"]').map((th) => th.text())).toEqual([
      'Player', 'Kind and section', 'Reason', 'Since', 'Until', 'Source', 'Issued by', 'Case',
    ])

    wrapper.unmount()
  })

  it('filters by kind and starts again from the first page', async () => {
    const { activePunishments, mount } = setup([page([row()], 2), page([row()], 2), page([row()])])
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="next"]').trigger('click')
    await flushPromises()
    expect(activePunishments).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))

    await wrapper.get('[data-testid="kind-MUTE"]').trigger('click')
    await flushPromises()

    expect(activePunishments).toHaveBeenLastCalledWith(expect.objectContaining({ kind: 'MUTE', page: 0 }))
    expect(wrapper.get('[data-testid="kind-MUTE"]').attributes('aria-pressed')).toBe('true')

    wrapper.unmount()
  })

  it.each([
    ['permanent', { permanent: true, expiresBefore: undefined }],
    ['temporary', { permanent: false, expiresBefore: undefined }],
    ['soon', { permanent: false, expiresBefore: NOW + 86_400_000 }],
  ])('filters by the %s duration chip', async (term, expected) => {
    const { activePunishments, mount } = setup([page([row()]), page([row()])])
    const { wrapper } = await mount()

    await wrapper.get(`[data-testid="term-${term}"]`).trigger('click')
    await flushPromises()

    expect(activePunishments).toHaveBeenLastCalledWith(expect.objectContaining(expected))

    wrapper.unmount()
  })

  it('filters by automatic or manual source and by the signed-in issuer', async () => {
    const { activePunishments, mount } = setup([page([row()]), page([row()]), page([row()])])
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="source-automatic"]').trigger('click')
    await flushPromises()
    expect(activePunishments).toHaveBeenLastCalledWith(expect.objectContaining({ automatic: true }))

    await wrapper.get('[data-testid="filter-mine"]').trigger('click')
    await flushPromises()
    expect(activePunishments).toHaveBeenLastCalledWith(expect.objectContaining({ actor: ME }))

    wrapper.unmount()
  })

  it('filters by section and sorts by expiry in the chosen direction', async () => {
    const { activePunishments, mount } = setup([page([row()]), page([row()]), page([row()]), page([row()])])
    const { wrapper } = await mount()

    await chooseOption(wrapper.get('[data-testid="section-filter"] [role="combobox"]'), 'chat')
    await flushPromises()
    expect(activePunishments).toHaveBeenLastCalledWith(expect.objectContaining({ section: 'chat' }))

    await chooseOption(wrapper.get('[data-testid="sort-select"] [role="combobox"]'), 'EXPIRES')
    await flushPromises()
    expect(activePunishments).toHaveBeenLastCalledWith(expect.objectContaining({ sort: 'EXPIRES', direction: 'DESC' }))

    await wrapper.get('[data-testid="toggle-direction"]').trigger('click')
    await flushPromises()
    expect(activePunishments).toHaveBeenLastCalledWith(expect.objectContaining({ sort: 'EXPIRES', direction: 'ASC' }))

    wrapper.unmount()
  })

  it('pages through the list', async () => {
    const { activePunishments, mount } = setup([page([row()], 2, 30), page([row({ shortId: '#NEXT0001' })], 2, 30)])
    const { wrapper } = await mount()
    expect(wrapper.get('[data-testid="prev"]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-testid="next"]').trigger('click')
    await flushPromises()

    expect(activePunishments).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))
    expect(wrapper.text()).toContain('#NEXT0001')
    expect(wrapper.get('[data-testid="next"]').attributes('disabled')).toBeDefined()

    wrapper.unmount()
  })

  it('searches the listed players by a name resolved in this session', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    useLookupStore().remember(BOB, 'Notch')
    await flushPromises()

    await wrapper.get('[data-testid="search"] input').setValue('notc')

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(1)
    expect(rows[0]!.get('a').text()).toBe('Notch')

    wrapper.unmount()
  })

  it('says so when nothing on the page matches the search', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="search"] input').setValue('nobody-here')

    expect(wrapper.find('table').exists()).toBe(false)
    expect(wrapper.get('[data-testid="punished-no-match"]').text()).toBe('No player on this page matches the search.')

    wrapper.unmount()
  })

  it('shows the empty state when nobody is punished', async () => {
    const { mount } = setup([page([])])
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="punished-empty"]').text()).toBe('No player is punished right now.')
    expect(wrapper.find('table').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows another empty state when the filters match nobody', async () => {
    const { mount } = setup([page([row()]), page([])])
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="kind-SERVER"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="punished-empty"]').text()).toBe('No punished players for these filters.')

    wrapper.unmount()
  })

  it('is read-only for a viewer: no write controls, only links', async () => {
    const { mount } = setup()
    const { wrapper } = await mount(['VIEWER'])

    expect(wrapper.findAll('tbody button')).toHaveLength(0)
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)

    wrapper.unmount()
  })
})
