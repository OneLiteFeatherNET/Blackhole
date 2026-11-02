import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CaseBulkItem, CaseListItem } from '../api/types'
import { chooseOption } from '../test/select'
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
    playerName: 'Steve',
    ownerShort: 'abababababab',
    version: 4,
    ...overrides,
  }
}

function answer(outcome: CaseBulkItem['outcome'], punishmentIdentifier: string): CaseBulkItem {
  return { punishmentIdentifier, outcome, changed: outcome === 'UPDATED', version: 5, currentVersion: outcome === 'CONFLICT' ? 9 : null, auditWritten: true, message: null }
}

function setup(bulk = vi.fn(async (change: { cases: Array<{ punishmentIdentifier: string }> }) => ok(change.cases.map((c) => answer('UPDATED', c.punishmentIdentifier))))) {
  const rows = [item(), item({ punishmentIdentifier: 'p-0002', shortId: '#ZZ99XX' })]
  const cases = vi.fn(async () => ok({ items: rows, page: 0, size: 25, totalSize: 2, totalPages: 1 }))
  const caseGroups = vi.fn(async () =>
    ok({ items: [{ owner: OWNER, ownerShort: 'abababababab', playerName: 'Steve', openCount: 2, totalCount: 2, lastActivityAt: 1 }], page: 0, size: 20, totalSize: 1, totalPages: 1 }),
  )
  return {
    bulk,
    cases,
    caseGroups,
    mount: () => mountWithApp(CasesView, { api: mockApi({ cases, caseGroups, bulkEditCases: bulk }), path: '/cases' }),
  }
}

afterEach(() => {
  window.localStorage.removeItem(STORAGE_KEY)
})

describe('CasesView bulk edit', () => {
  it('opens the bulk panel from the selection bar with the selected cases', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await wrapper.findAll('tbody tr')[0]!.get('[data-testid="case-select"] input').setValue(true)
    await wrapper.findAll('tbody tr')[1]!.get('[data-testid="case-select"] input').setValue(true)

    await wrapper.get('[data-testid="selection-edit"]').trigger('click')

    expect(wrapper.get('[role="dialog"]').text()).toContain('Edit 2 cases')

    wrapper.unmount()
  })

  it('after a batch the list reloads once and the selection is cleared', async () => {
    const { bulk, cases, mount } = setup()
    const { wrapper } = await mount()
    await wrapper.findAll('tbody tr')[0]!.get('[data-testid="case-select"] input').setValue(true)
    await wrapper.get('[data-testid="selection-edit"]').trigger('click')
    await chooseOption(wrapper.get('[data-testid="bulk-status"] [role="combobox"]'), 'CLOSED')
    const before = cases.mock.calls.length

    await wrapper.get('[data-testid="bulk-apply"]').trigger('click')
    await flushPromises()

    expect(bulk).toHaveBeenCalledTimes(1)
    expect(cases.mock.calls.length - before).toBe(1)
    expect(wrapper.find('[data-testid="selection-bar"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-testid="bulk-result"]')).toHaveLength(1)

    wrapper.unmount()
  })

  it('a conflicted case can reload the list from the result', async () => {
    const { cases, mount } = setup(vi.fn(async () => ok([answer('CONFLICT', 'p-0001')])))
    const { wrapper } = await mount()
    await wrapper.findAll('tbody tr')[0]!.get('[data-testid="case-select"] input').setValue(true)
    await wrapper.get('[data-testid="selection-edit"]').trigger('click')
    await chooseOption(wrapper.get('[data-testid="bulk-status"] [role="combobox"]'), 'CLOSED')
    await wrapper.get('[data-testid="bulk-apply"]').trigger('click')
    await flushPromises()
    const before = cases.mock.calls.length

    await wrapper.get('[data-testid="bulk-reload"]').trigger('click')
    await flushPromises()

    expect(cases.mock.calls.length - before).toBe(1)

    wrapper.unmount()
  })

  it('in the grouped view the groups and the open player reload after a batch', async () => {
    const { caseGroups, cases, mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="view-player"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="case-group-toggle"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="case-group-select"] input').setValue(true)
    await wrapper.get('[data-testid="selection-edit"]').trigger('click')
    await chooseOption(wrapper.get('[data-testid="bulk-status"] [role="combobox"]'), 'CLOSED')
    const groupsBefore = caseGroups.mock.calls.length
    const casesBefore = cases.mock.calls.length

    await wrapper.get('[data-testid="bulk-apply"]').trigger('click')
    await flushPromises()

    expect(caseGroups.mock.calls.length - groupsBefore).toBe(1)
    expect(cases.mock.calls.length - casesBefore).toBe(1)
    expect(cases).toHaveBeenLastCalledWith(expect.objectContaining({ owner: OWNER }))

    wrapper.unmount()
  })
})
