import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { CaseListItem } from '../api/types'
import { mockApi, mountWithApp, ok } from '../test/mount'
import CasesView from './CasesView.vue'

const OWNER = 'ab'.repeat(64)

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
    playerName: null,
    ownerShort: 'abababababab',
    version: 1,
    ...overrides,
  }
}

function setup(items: CaseListItem[] = [item()]) {
  const cases = vi.fn(async () => ok({ items, page: 0, size: 25, totalSize: items.length, totalPages: 1 }))
  const caseGroups = vi.fn(async () =>
    ok({ items: [{ owner: OWNER, ownerShort: 'abababababab', playerName: null, openCount: 1, totalCount: 1, lastActivityAt: 1 }], page: 0, size: 20, totalSize: 1, totalPages: 1 }),
  )
  return { cases, caseGroups, mount: (path = '/cases') => mountWithApp(CasesView, { api: mockApi({ cases, caseGroups }), path }) }
}

describe('CasesView player column and grouped view', () => {
  it('shows the player name, or the short owner hash when no name is known, and never the whole hash', async () => {
    const { mount } = setup([item({ playerName: 'Steve' }), item({ punishmentIdentifier: 'p-2', shortId: '#ZZ99XX', playerName: null })])
    const { wrapper } = await mount()

    const rows = wrapper.findAll('tbody tr')
    expect(rows[0]!.get('[data-label="Player"]').text()).toBe('Steve')
    expect(rows[1]!.get('[data-label="Player"]').text()).toContain('abababababab')
    expect(wrapper.html()).not.toContain(OWNER)

    wrapper.unmount()
  })

  it('places the Player column between Case and Section', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const headers = wrapper.findAll('thead th').map((header) => header.text())
    // The first header is the selection checkbox column, whose name is its label.
    expect(headers.slice(0, 1)).toEqual(['Select all cases on this page'])
    expect(headers.slice(1, 4)).toEqual(['Case', 'Player', 'Section'])

    wrapper.unmount()
  })

  it('shows the grouped view when the viewer chooses it and asks for the groups', async () => {
    const { caseGroups, mount } = setup()
    const { wrapper, router } = await mount()

    await wrapper.get('[data-testid="view-player"]').trigger('click')
    await flushPromises()

    expect(caseGroups).toHaveBeenCalled()
    expect(wrapper.get('[data-testid="view-player"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.find('[data-testid="case-group"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="case-group-rows"]').exists()).toBe(false)
    // The view is in the address, so a reload or a shared link opens the same view.
    expect(router.currentRoute.value.query.view).toBe('player')

    wrapper.unmount()
  })

  it('moves between the views without writing the choice to storage', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    const { mount } = setup()
    const { wrapper, router } = await mount()

    await wrapper.get('[data-testid="view-player"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="view-cases"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({})
    expect(setItem, 'list state in storage').not.toHaveBeenCalled()

    setItem.mockRestore()
    wrapper.unmount()
  })

  it('opens in the grouped view when the address names it', async () => {
    const { caseGroups, mount } = setup()
    const { wrapper } = await mount('/cases?view=player')
    await flushPromises()

    expect(caseGroups).toHaveBeenCalled()
    expect(wrapper.findAll('[data-testid="case-group-toggle"]')).toHaveLength(1)

    wrapper.unmount()
  })

  it('does not request the flat case list while the grouped view is shown', async () => {
    const { cases, mount } = setup()
    const { wrapper } = await mount('/cases?view=player')
    await flushPromises()

    expect(cases).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('requests the flat case list once when the viewer switches back to it', async () => {
    const { cases, mount } = setup()
    const { wrapper } = await mount('/cases?view=player')
    await flushPromises()

    await wrapper.get('[data-testid="view-cases"]').trigger('click')
    await flushPromises()

    expect(cases).toHaveBeenCalledTimes(1)
    expect(wrapper.find('tbody tr').exists()).toBe(true)

    wrapper.unmount()
  })
})
