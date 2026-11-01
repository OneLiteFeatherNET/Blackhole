import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { CaseGroupItem, CaseListItem } from '../../api/types'
import { mockApi, mountWithApp, ok } from '../../test/mount'
import CasePlayerGroups from './CasePlayerGroups.vue'

const OWNER = 'ab'.repeat(64)
const OTHER = 'cd'.repeat(64)

function group(overrides: Partial<CaseGroupItem> = {}): CaseGroupItem {
  return { owner: OWNER, ownerShort: 'abababababab', playerName: 'Steve', openCount: 3, totalCount: 4, lastActivityAt: 2, ...overrides }
}

function item(overrides: Partial<CaseListItem> = {}): CaseListItem {
  return {
    punishmentIdentifier: 'p-0001',
    shortId: '#AB12CD',
    section: 'network',
    punishmentState: 'ACTIVE',
    status: 'OPEN',
    priority: 'NORMAL',
    tags: [],
    punishmentCreatedAt: 1,
    lastActivityAt: 1,
    playerName: 'Steve',
    ownerShort: 'abababababab',
    version: 1,
    ...overrides,
  }
}

function setup(groups: CaseGroupItem[] = [group()], totalPages = 1) {
  const caseGroups = vi.fn(async () => ok({ items: groups, page: 0, size: 20, totalSize: groups.length, totalPages }))
  const cases = vi.fn(async () => ok({ items: [item()], page: 0, size: 20, totalSize: 1, totalPages: 1 }))
  return {
    caseGroups,
    cases,
    mount: (props: Record<string, unknown> = {}) =>
      mountWithApp(CasePlayerGroups, { api: mockApi({ caseGroups, cases }), path: '/cases', props }),
  }
}

describe('CasePlayerGroups', () => {
  it('shows the name of each player with the open and the total count', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await flushPromises()

    const toggle = wrapper.get('[data-testid="case-group-toggle"]')
    expect(toggle.text()).toContain('Steve')
    expect(toggle.text()).toContain('3 open, 4 in total')

    wrapper.unmount()
  })

  it('shows the short owner hash when the player has no known name, never the whole hash', async () => {
    const { mount } = setup([group({ playerName: null })])
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.get('[data-testid="case-group-toggle"]').text()).toContain('abababababab')
    expect(wrapper.html()).not.toContain(OWNER)

    wrapper.unmount()
  })

  it('expands a player and asks for that player\'s cases with the owner as a request parameter', async () => {
    const { cases, mount } = setup()
    const { wrapper } = await mount()
    await flushPromises()

    await wrapper.get('[data-testid="case-group-toggle"]').trigger('click')
    await flushPromises()

    expect(cases).toHaveBeenCalledWith(expect.objectContaining({ owner: OWNER, page: 0 }))
    expect(wrapper.get('[data-testid="case-group-toggle"]').attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('a').attributes('href')).toBe('/cases/p-0001')

    wrapper.unmount()
  })

  it('collapses an expanded player again', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await flushPromises()

    await wrapper.get('[data-testid="case-group-toggle"]').trigger('click')
    await wrapper.get('[data-testid="case-group-toggle"]').trigger('click')

    expect(wrapper.get('[data-testid="case-group-toggle"]').attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('a').exists()).toBe(false)

    wrapper.unmount()
  })

  it('lists each player once, in the order the server gave', async () => {
    const { mount } = setup([group(), group({ owner: OTHER, playerName: 'Alex', ownerShort: 'cdcdcdcdcdcd' })])
    const { wrapper } = await mount()
    await flushPromises()

    const names = wrapper.findAll('[data-testid="case-group-toggle"]').map((button) => button.text())
    expect(names[0]).toContain('Steve')
    expect(names[1]).toContain('Alex')

    wrapper.unmount()
  })

  it('leaves the visible counts to the screen reader: the toggle has no label that replaces them', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await flushPromises()

    const toggle = wrapper.get('[data-testid="case-group-toggle"]')
    expect(toggle.attributes('aria-label')).toBeUndefined()
    expect(toggle.text()).toContain('3 open, 4 in total')

    wrapper.unmount()
  })

  it('points aria-controls at the case list only while the group is expanded', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await flushPromises()
    const toggle = wrapper.get('[data-testid="case-group-toggle"]')
    expect(toggle.attributes('aria-controls')).toBeUndefined()

    await toggle.trigger('click')
    await flushPromises()

    const controls = wrapper.get('[data-testid="case-group-toggle"]').attributes('aria-controls')
    expect(controls).toBeDefined()
    expect(wrapper.find(`#${controls}`).exists()).toBe(true)

    wrapper.unmount()
  })

  it('shows an error with a retry when the groups cannot be loaded', async () => {
    const caseGroups = vi.fn(async () => ({ ok: false as const, error: { status: 500, message: 'boom', kind: 'UNAVAILABLE' } }))
    const { wrapper } = await mountWithApp(CasePlayerGroups, { api: mockApi({ caseGroups }), path: '/cases' })
    await flushPromises()

    expect(wrapper.find('[data-testid="case-group-toggle"]').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('shows the empty state when no player has a matching case', async () => {
    const { mount } = setup([])
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.find('[data-testid="case-groups-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="case-group-toggle"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('offers to clear the filters when the empty result comes from filters', async () => {
    const { mount } = setup([])
    const { wrapper } = await mount({ filtersApplied: true })
    await flushPromises()

    await wrapper.get('[data-testid="groups-clear-filters"]').trigger('click')

    expect(wrapper.emitted('clear-filters')).toHaveLength(1)

    wrapper.unmount()
  })

  it('starts again from the first group page on a filter change and reloads exactly once', async () => {
    const { caseGroups, mount } = setup([group()], 5)
    const { wrapper } = await mount()
    await flushPromises()
    for (let step = 0; step < 3; step++) {
      await wrapper.get('[data-testid="next"]').trigger('click')
      await flushPromises()
    }
    expect(caseGroups).toHaveBeenLastCalledWith(expect.objectContaining({ page: 3 }))
    const before = caseGroups.mock.calls.length

    await wrapper.setProps({ status: 'CLOSED' })
    await flushPromises()

    expect(caseGroups.mock.calls.length - before).toBe(1)
    expect(caseGroups).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, status: 'CLOSED' }))

    wrapper.unmount()
  })

  it('reloads once on a filter change while already on the first page', async () => {
    const { caseGroups, mount } = setup()
    const { wrapper } = await mount()
    await flushPromises()
    const before = caseGroups.mock.calls.length

    await wrapper.setProps({ tag: 'spam' })
    await flushPromises()

    expect(caseGroups.mock.calls.length - before).toBe(1)

    wrapper.unmount()
  })
})
