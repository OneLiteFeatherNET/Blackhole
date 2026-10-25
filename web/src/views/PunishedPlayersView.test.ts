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
  const mount = (roles?: string[], path = '/punished') =>
    mountWithApp(PunishedPlayersView, { api: mockApi({ activePunishments, sections: vi.fn(async () => ok(sections)) }), path, roles })
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

  it('has a labelled table with the eight column headers', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.get('table caption').text()).toBe('Active punishments')
    expect(wrapper.findAll('th[scope="col"]').map((th) => th.text())).toEqual([
      'Player', 'Kind and section', 'Reason', 'Since', 'Until', 'Source', 'Issued by', 'Case',
    ])

    wrapper.unmount()
  })

  it('has one level-one heading and keeps every filter in one toolbar', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.findAll('h1').map((heading) => heading.text())).toEqual(['Active punishments'])
    const toolbar = wrapper.get('[data-testid="filter-toolbar"]')
    for (const testid of ['q', 'section', 'term', 'sort', 'kind-BAN', 'source-automatic', 'filter-mine']) {
      expect(toolbar.find(`[data-testid="${testid}"]`).exists(), testid).toBe(true)
    }
    expect(wrapper.findAll('[role="group"][aria-label="Kind"]'), 'the kind toggles are the toolbar group').toHaveLength(1)

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
  ])('filters by the %s duration', async (term, expected) => {
    const { activePunishments, mount } = setup([page([row()]), page([row()])])
    const { wrapper } = await mount()

    await chooseOption(wrapper.get('[data-testid="term"] [role="combobox"]'), term)
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

  it('filters by section', async () => {
    const { activePunishments, mount } = setup([page([row()]), page([row()])])
    const { wrapper } = await mount()

    await chooseOption(wrapper.get('[data-testid="section"] [role="combobox"]'), 'chat')
    await flushPromises()

    expect(activePunishments).toHaveBeenLastCalledWith(expect.objectContaining({ section: 'chat' }))

    wrapper.unmount()
  })

  it('sorts by expiry in one select that names the order, without a separate direction button', async () => {
    const { activePunishments, mount } = setup([page([row()]), page([row()]), page([row()])])
    const { wrapper } = await mount()

    await chooseOption(wrapper.get('[data-testid="sort"] [role="combobox"]'), 'expires-asc')
    await flushPromises()

    expect(activePunishments).toHaveBeenLastCalledWith(expect.objectContaining({ sort: 'EXPIRES', direction: 'ASC' }))
    expect(wrapper.find('[data-testid="toggle-direction"]').exists(), 'the direction is part of the select').toBe(false)

    wrapper.unmount()
  })

  it('keeps the filters in the route query so that Back returns to them', async () => {
    const { mount } = setup([page([row()]), page([row()])])
    const { wrapper, router } = await mount()

    await wrapper.get('[data-testid="kind-MUTE"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.kind).toBe('MUTE')

    wrapper.unmount()
  })

  it('restores the filters from the route query on load', async () => {
    const { activePunishments, mount } = setup([page([row()])])
    const { wrapper } = await mount(undefined, '/punished?kind=MUTE&source=manual&mine=1')

    expect(wrapper.get('[data-testid="kind-MUTE"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[data-testid="source-manual"]').attributes('aria-pressed')).toBe('true')
    expect(activePunishments).toHaveBeenLastCalledWith(expect.objectContaining({ kind: 'MUTE', automatic: false, actor: ME }))

    wrapper.unmount()
  })

  it('shows each applied filter as an input chip that removes it', async () => {
    const { mount } = setup([page([row()]), page([row()])])
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="kind-MUTE"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="applied-filters"]').text()).toContain('Mute')

    await wrapper.get('[data-testid="applied-filters"] button').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="kind-MUTE"]').attributes('aria-pressed')).toBe('false')
    expect(wrapper.find('[data-testid="applied-filters"]').exists()).toBe(false)

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

  it('does not show a total that counts pages the search cannot reach', async () => {
    const { mount } = setup([page([row()], 2, 30)])
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="total"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('30 active punishments')

    wrapper.unmount()
  })

  it('labels the search as searching only the players on this page', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="q"] label').text()).toContain('Search this page')
    expect(wrapper.get('[data-testid="page-description"]').text()).toContain('only the players on this page')

    wrapper.unmount()
  })

  it('searches the listed players by a name resolved in this session without another request', async () => {
    const { activePunishments, mount } = setup()
    const { wrapper } = await mount()
    useLookupStore().remember(BOB, 'Notch')
    await flushPromises()
    const requests = activePunishments.mock.calls.length

    await wrapper.get('[data-testid="q"] input').setValue('notc')

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(1)
    expect(rows[0]!.get('a').text()).toBe('Notch')
    expect(activePunishments.mock.calls.length, 'the search runs on the loaded page only').toBe(requests)

    wrapper.unmount()
  })

  it('says so when nothing on the page matches the search, and clears the search from there', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="q"] input').setValue('nobody-here')

    expect(wrapper.find('table').exists()).toBe(false)
    const empty = wrapper.get('[data-testid="punished-no-match"]')
    expect(empty.attributes('data-variant')).toBe('no-matches')
    expect(empty.text()).toContain('No player on this page matches the search.')

    await wrapper.get('[data-testid="empty-clear-search"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
    expect((wrapper.get('[data-testid="q"] input').element as HTMLInputElement).value).toBe('')

    wrapper.unmount()
  })

  it('shows the no-items empty state when nobody is punished', async () => {
    const { mount } = setup([page([])])
    const { wrapper } = await mount()

    const empty = wrapper.get('[data-testid="punished-empty"]')
    expect(empty.attributes('data-variant')).toBe('no-items')
    expect(empty.text()).toContain('No player is punished right now.')
    expect(wrapper.find('table').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows the no-matches empty state with clear filters when the filters match nobody', async () => {
    const { mount } = setup([page([row()]), page([])])
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="kind-SERVER"]').trigger('click')
    await flushPromises()

    const empty = wrapper.get('[data-testid="punished-empty"]')
    expect(empty.attributes('data-variant')).toBe('no-matches')
    expect(empty.text()).toContain('No punished players for these filters.')

    await wrapper.get('[data-testid="empty-clear-filters"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="kind-SERVER"]').attributes('aria-pressed')).toBe('false')

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
