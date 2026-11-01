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

function setup(groups: CaseGroupItem[] = [group()]) {
  const caseGroups = vi.fn(async () => ok({ items: groups, page: 0, size: 20, totalSize: groups.length, totalPages: 1 }))
  const cases = vi.fn(async () => ok({ items: [item()], page: 0, size: 20, totalSize: 1, totalPages: 1 }))
  return { caseGroups, cases, mount: () => mountWithApp(CasePlayerGroups, { api: mockApi({ caseGroups, cases }), path: '/cases' }) }
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
})
