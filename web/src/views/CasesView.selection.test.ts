import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import en from '../locales/en.json'
import type { CaseListItem } from '../api/types'
import { mockApi, mountWithApp, ok } from '../test/mount'
import CasesView from './CasesView.vue'

// The same case, shown in the flat list and in a player's group, must carry the same tones and chips.
function pillIn(root: Element, label: string): Element | undefined {
  return [...root.querySelectorAll('span')].find((span) => span.textContent?.trim() === label)
}

function expectCaseToneAndChips(row: Element) {
  expect(pillIn(row, en.case.status.OPEN)?.className).toContain('bg-primary-container')
  expect(pillIn(row, en.case.priority.HIGH)?.className).toContain('bg-warning-container')
  expect(pillIn(row, en.case.state.ACTIVE)?.className).toContain('bg-error-container')
  expect([...row.querySelectorAll('li')].map((chip) => chip.textContent?.trim())).toEqual(['spam', 'chat'])
  expect(row.querySelector('li')?.className).toContain('border-outline')
}

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
    version: 1,
    ...overrides,
  }
}

function setup(items: CaseListItem[] = [item()]) {
  const cases = vi.fn(async () => ok({ items, page: 0, size: 25, totalSize: items.length, totalPages: 1 }))
  const caseGroups = vi.fn(async () =>
    ok({ items: [{ owner: OWNER, ownerShort: 'abababababab', playerName: 'Steve', openCount: 1, totalCount: 1, lastActivityAt: 1 }], page: 0, size: 20, totalSize: 1, totalPages: 1 }),
  )
  return { cases, caseGroups, mount: () => mountWithApp(CasesView, { api: mockApi({ cases, caseGroups }), path: '/cases' }) }
}

afterEach(() => {
  window.localStorage.removeItem(STORAGE_KEY)
})

describe('CasesView selection in the grouped view', () => {
  it('shows a bar with the count of the selected cases, and clears the selection from it', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="view-player"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="case-group-toggle"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="case-group-select"] input').setValue(true)

    expect(wrapper.get('[data-testid="selection-count"]').text()).toBe('1 selected')

    await wrapper.get('[data-testid="selection-clear"]').trigger('click')

    expect(wrapper.find('[data-testid="selection-bar"]').exists()).toBe(false)
    expect((wrapper.get('[data-testid="case-select"] input').element as HTMLInputElement).checked).toBe(false)

    wrapper.unmount()
  })

  it('clears the selection when the view changes', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="view-player"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="case-group-toggle"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="case-group-select"] input').setValue(true)

    await wrapper.get('[data-testid="view-cases"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="selection-bar"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('clears the selection when a filter changes', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="view-player"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="case-group-toggle"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="case-group-select"] input').setValue(true)

    await wrapper.get('[data-testid="status-CLOSED"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="selection-bar"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows the cap note in the bar once 100 cases are selected', async () => {
    const many = Array.from({ length: 100 }, (_, index) => item({ punishmentIdentifier: `p-${index}`, shortId: `#${index}` }))
    const { mount } = setup(many)
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="view-player"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="case-group-toggle"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="case-group-select"] input').setValue(true)

    expect(wrapper.get('[data-testid="selection-limit"]').text()).toContain('100')

    wrapper.unmount()
  })
})

describe('CasesView tones and tag chips', () => {
  const HIGH_TAGGED = item({ priority: 'HIGH', tags: ['spam', 'chat'] })

  it('gives a flat row the case tones and outlined tag chips', async () => {
    const { mount } = setup([HIGH_TAGGED])
    const { wrapper } = await mount()

    expectCaseToneAndChips(wrapper.findAll('tbody tr')[0]!.element)

    wrapper.unmount()
  })

  it('gives the same row in a player group the same tones and chips', async () => {
    const { mount } = setup([HIGH_TAGGED])
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="view-player"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="case-group-toggle"]').trigger('click')
    await flushPromises()

    expectCaseToneAndChips(wrapper.findAll('[data-testid="case-group-rows"] tr')[0]!.element)

    wrapper.unmount()
  })

  it('sizes the view switch to its content instead of the full width of the page', async () => {
    const { mount } = setup([HIGH_TAGGED])
    const { wrapper } = await mount()

    expect(wrapper.get('[role="group"][aria-label="View"]').classes()).toContain('self-start')

    wrapper.unmount()
  })

  it('puts the same surface behind the flat table and the grouped table', async () => {
    const { mount } = setup([HIGH_TAGGED])
    const { wrapper } = await mount()
    expect(wrapper.find('.bg-surface-container-low').exists()).toBe(true)

    await wrapper.get('[data-testid="view-player"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('.bg-surface-container-low').exists()).toBe(true)

    wrapper.unmount()
  })
})

function flatRows(count: number): CaseListItem[] {
  return Array.from({ length: count }, (_, index) => item({ punishmentIdentifier: `f-${index}`, shortId: `#F${index}`, version: 2 }))
}

describe('CasesView selection in the flat list', () => {
  it('gives every row a checkbox named after its case, first in the row', async () => {
    const { mount } = setup(flatRows(2))
    const { wrapper } = await mount()

    const firstCell = wrapper.findAll('tbody tr')[0]!.findAll('td')[0]!
    expect(firstCell.find('[data-testid="case-select"] input').exists()).toBe(true)
    expect(wrapper.findAll('label').map((label) => label.text())).toContain('Select case #F0')

    wrapper.unmount()
  })

  it('selects a row with its version when its checkbox is checked', async () => {
    const { mount } = setup(flatRows(2))
    const { wrapper } = await mount()

    await wrapper.findAll('tbody tr')[1]!.get('[data-testid="case-select"] input').setValue(true)

    expect(wrapper.get('[data-testid="selection-count"]').text()).toBe('1 selected')

    wrapper.unmount()
  })

  it('selects the whole page from the header checkbox, and clears it again', async () => {
    const { mount } = setup(flatRows(3))
    const { wrapper } = await mount()
    const header = wrapper.get('thead input[type="checkbox"]')
    expect(wrapper.findAll('label').map((label) => label.text())).toContain('Select all cases on this page')

    await header.setValue(true)

    expect(wrapper.get('[data-testid="selection-count"]').text()).toBe('3 selected')
    expect(wrapper.findAll('tbody [data-testid="case-select"] input').every((box) => (box.element as HTMLInputElement).checked)).toBe(true)

    await wrapper.get('thead input[type="checkbox"]').setValue(false)

    expect(wrapper.find('[data-testid="selection-bar"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows the header checkbox as mixed when only some rows of the page are selected', async () => {
    const { mount } = setup(flatRows(3))
    const { wrapper } = await mount()

    await wrapper.findAll('tbody tr')[0]!.get('[data-testid="case-select"] input').setValue(true)

    expect((wrapper.get('thead input[type="checkbox"]').element as HTMLInputElement).indeterminate).toBe(true)
    expect((wrapper.get('thead input[type="checkbox"]').element as HTMLInputElement).checked).toBe(false)

    wrapper.unmount()
  })

  it('disables the rows past the cap and says so, once the cap is reached from the header', async () => {
    const { mount } = setup(flatRows(101))
    const { wrapper } = await mount()

    await wrapper.get('thead input[type="checkbox"]').setValue(true)

    expect(wrapper.get('[data-testid="selection-count"]').text()).toBe('100 selected')
    expect(wrapper.get('[data-testid="selection-limit"]').text()).toContain('100')
    const rows = wrapper.findAll('tbody tr')
    expect((rows[99]!.get('[data-testid="case-select"] input').element as HTMLInputElement).checked).toBe(true)
    expect(rows[100]!.get('[data-testid="case-select"] input').attributes('disabled')).toBeDefined()

    wrapper.unmount()
  })

  it('clears the selection when the page changes', async () => {
    const cases = vi.fn(async () => ok({ items: flatRows(2), page: 0, size: 25, totalSize: 4, totalPages: 2 }))
    const { wrapper } = await mountWithApp(CasesView, { api: mockApi({ cases }), path: '/cases' })
    await wrapper.findAll('tbody tr')[0]!.get('[data-testid="case-select"] input').setValue(true)

    await wrapper.get('[data-testid="next"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="selection-bar"]').exists()).toBe(false)

    wrapper.unmount()
  })
})
