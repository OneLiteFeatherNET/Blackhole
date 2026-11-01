import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CaseListItem } from '../api/types'
import { mockApi, mountWithApp, ok } from '../test/mount'
import CasesView from './CasesView.vue'

const OWNER = 'ab'.repeat(64)
const STORAGE_KEY = 'blackhole.cases.view'

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
  return { cases, caseGroups, mount: () => mountWithApp(CasesView, { api: mockApi({ cases, caseGroups }), path: '/cases' }) }
}

afterEach(() => {
  window.localStorage.removeItem(STORAGE_KEY)
})

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
    expect(headers.slice(0, 3)).toEqual(['Case', 'Player', 'Section'])

    wrapper.unmount()
  })

  it('shows the grouped view when the viewer chooses it and asks for the groups', async () => {
    const { caseGroups, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="view-player"]').trigger('click')
    await flushPromises()

    expect(caseGroups).toHaveBeenCalled()
    expect(wrapper.get('[data-testid="view-player"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.find('tbody tr').exists()).toBe(false)
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe('player')

    wrapper.unmount()
  })

  it('opens in the grouped view when the viewer chose it before', async () => {
    window.localStorage.setItem(STORAGE_KEY, 'player')
    const { caseGroups, mount } = setup()
    const { wrapper } = await mount()
    await flushPromises()

    expect(caseGroups).toHaveBeenCalled()
    expect(wrapper.findAll('[data-testid="case-group-toggle"]')).toHaveLength(1)

    wrapper.unmount()
  })
})
