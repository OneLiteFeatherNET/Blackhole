import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { useCaseSelection, type CaseSelection } from '../../composables/useCaseSelection'
import type { CaseGroupItem, CaseListItem } from '../../api/types'
import { mockApi, mountWithApp, ok } from '../../test/mount'
import type { DataColumn } from '../DataTable.vue'
import CasePlayerGroups from './CasePlayerGroups.vue'

const OWNER = 'ab'.repeat(64)
const OTHER = 'cd'.repeat(64)

const COLUMNS: DataColumn<CaseListItem>[] = [
  { key: 'shortId', label: 'Case', priority: 1 },
  { key: 'playerName', label: 'Player', priority: 2 },
  { key: 'section', label: 'Section', priority: 3 },
  { key: 'status', label: 'Status', priority: 1 },
  { key: 'lastActivityAt', label: 'Last activity', priority: 3 },
]

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

function setup(groups: CaseGroupItem[] = [group()], totalPages = 1, pageItems: CaseListItem[] = [item()]) {
  const caseGroups = vi.fn(async () => ok({ items: groups, page: 0, size: 20, totalSize: groups.length, totalPages }))
  const cases = vi.fn(async () => ok({ items: pageItems, page: 0, size: 20, totalSize: pageItems.length, totalPages: 1 }))
  const selection: CaseSelection = useCaseSelection()
  return {
    caseGroups,
    cases,
    selection,
    mount: (props: Record<string, unknown> = {}) =>
      mountWithApp(CasePlayerGroups, {
        api: mockApi({ caseGroups, cases }),
        path: '/cases',
        props: { columns: COLUMNS, selection, ...props },
      }),
  }
}

function toggles(wrapper: VueWrapper) {
  return wrapper.findAll('[data-testid="case-group-toggle"]')
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

  it('expands a player in place and asks for that player\'s cases with the owner as a request parameter', async () => {
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

  it('renders the header as a button, so Enter and Space toggle it natively', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await flushPromises()

    expect(wrapper.get('[data-testid="case-group-toggle"]').element.tagName).toBe('BUTTON')
    expect(wrapper.get('[data-testid="case-group-toggle"]').attributes('type')).toBe('button')

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

  it('keeps several players open at once, each with its own cases', async () => {
    const { cases, mount } = setup([group(), group({ owner: OTHER, playerName: 'Alex', ownerShort: 'cdcdcdcdcdcd' })])
    const { wrapper } = await mount()
    await flushPromises()

    await toggles(wrapper)[0]!.trigger('click')
    await toggles(wrapper)[1]!.trigger('click')
    await flushPromises()

    expect(toggles(wrapper).map((button) => button.attributes('aria-expanded'))).toEqual(['true', 'true'])
    expect(cases).toHaveBeenCalledWith(expect.objectContaining({ owner: OWNER }))
    expect(cases).toHaveBeenCalledWith(expect.objectContaining({ owner: OTHER }))
    expect(wrapper.findAll('a')).toHaveLength(2)

    wrapper.unmount()
  })

  it('lists each player once, in the order the server gave', async () => {
    const { mount } = setup([group(), group({ owner: OTHER, playerName: 'Alex', ownerShort: 'cdcdcdcdcdcd' })])
    const { wrapper } = await mount()
    await flushPromises()

    const names = toggles(wrapper).map((button) => button.text())
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

  it('points aria-controls at the case rows only while the group is expanded', async () => {
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

  it('shows the cases of an expanded player as rows with the case columns, without the player column', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await flushPromises()

    await wrapper.get('[data-testid="case-group-toggle"]').trigger('click')
    await flushPromises()

    // The checkbox column, then the flat table's columns without the player column (the group names the player).
    const caseRow = wrapper.findAll('[data-testid="case-group-rows"] tr')[0]!
    expect(caseRow.findAll('td')).toHaveLength(COLUMNS.length)
    expect(caseRow.text()).toContain('#AB12CD')
    expect(caseRow.text()).toContain('Open')
    expect(caseRow.text()).not.toContain('Steve')

    wrapper.unmount()
  })

  it('labels the expanded case columns in a header row, after the checkbox column', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await flushPromises()

    const headers = wrapper.findAll('thead th').map((cell) => cell.text())
    expect(headers.slice(1)).toEqual(['Case', 'Section', 'Status', 'Last activity'])

    wrapper.unmount()
  })

  it('puts the checkbox of a player header and the checkboxes of its cases in one column, aligned the same way', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await flushPromises()
    await wrapper.get('[data-testid="case-group-toggle"]').trigger('click')
    await flushPromises()

    const playerCell = wrapper.get('[data-testid="case-group-select"]').element.closest('td')!
    const caseCell = wrapper.get('[data-testid="case-select"]').element.closest('td')!
    expect(playerCell.className).toBe(caseCell.className)
    expect(caseCell.className).toContain('align-middle')

    wrapper.unmount()
  })

  it('puts the caret directly before the player name, inside the header button', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await flushPromises()

    const button = wrapper.get('[data-testid="case-group-toggle"]').element
    const name = [...button.querySelectorAll('span')].find((span) => span.textContent?.trim() === 'Steve')!
    expect(name.previousElementSibling?.tagName.toLowerCase()).toBe('svg')
    expect(button.className).not.toContain('w-full')

    wrapper.unmount()
  })

  it('shows an error with a retry when the groups cannot be loaded', async () => {
    const caseGroups = vi.fn(async () => ({ ok: false as const, error: { status: 500, message: 'boom', kind: 'UNAVAILABLE' } }))
    const { wrapper } = await mountWithApp(CasePlayerGroups, {
      api: mockApi({ caseGroups }),
      path: '/cases',
      props: { columns: COLUMNS, selection: useCaseSelection() },
    })
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

  it('clears the selection when the group page changes', async () => {
    const { selection, mount } = setup([group()], 5)
    const { wrapper } = await mount()
    await flushPromises()
    selection.toggle({ punishmentIdentifier: 'p-0001', version: 1 }, true)

    await wrapper.get('[data-testid="next"]').trigger('click')
    await flushPromises()

    expect(selection.count.value).toBe(0)

    wrapper.unmount()
  })

  it('clears the selection when a filter changes', async () => {
    const { selection, mount } = setup()
    const { wrapper } = await mount()
    await flushPromises()
    selection.toggle({ punishmentIdentifier: 'p-0001', version: 1 }, true)

    await wrapper.setProps({ status: 'CLOSED' })
    await flushPromises()

    expect(selection.count.value).toBe(0)

    wrapper.unmount()
  })

  describe('case checkboxes', () => {
    it('names each case checkbox after its case and selects that case with its version', async () => {
      const { selection, mount } = setup()
      const { wrapper } = await mount()
      await flushPromises()
      await wrapper.get('[data-testid="case-group-toggle"]').trigger('click')
      await flushPromises()

      const checkbox = wrapper.get('[data-testid="case-select"] input')
      const label = wrapper.get(`label[for="${checkbox.attributes('id')}"]`)
      expect(label.text()).toBe('Select case #AB12CD')

      await checkbox.setValue(true)

      expect(selection.isSelected('p-0001')).toBe(true)
      expect(selection.items.value).toEqual([{ punishmentIdentifier: 'p-0001', version: 1, shortId: '#AB12CD' }])

      wrapper.unmount()
    })

    it('disables the case checkboxes that would pass the cap of 100', async () => {
      const { selection, mount } = setup()
      const { wrapper } = await mount()
      await flushPromises()
      selection.toggleMany(Array.from({ length: 100 }, (_, index) => ({ punishmentIdentifier: `x-${index}`, version: 1 })), true)
      await wrapper.get('[data-testid="case-group-toggle"]').trigger('click')
      await flushPromises()

      const caseBox = wrapper.get('[data-testid="case-select"] input')
      expect(caseBox.attributes('disabled')).toBeDefined()

      wrapper.unmount()
    })
  })

  describe('player checkbox', () => {
    it('selects every loaded case of the player and names the player', async () => {
      const { selection, mount } = setup([group()], 1, [item(), item({ punishmentIdentifier: 'p-0002', shortId: '#ZZ99XX' })])
      const { wrapper } = await mount()
      await flushPromises()
      await wrapper.get('[data-testid="case-group-toggle"]').trigger('click')
      await flushPromises()

      const playerBox = wrapper.get('[data-testid="case-group-select"] input')
      expect(wrapper.get(`label[for="${playerBox.attributes('id')}"]`).text()).toBe('Select all cases of Steve')

      await playerBox.setValue(true)

      expect(selection.count.value).toBe(2)

      wrapper.unmount()
    })

    it('shows the mixed state when only some loaded cases of the player are selected', async () => {
      const { selection, mount } = setup([group()], 1, [item(), item({ punishmentIdentifier: 'p-0002', shortId: '#ZZ99XX' })])
      const { wrapper } = await mount()
      await flushPromises()
      await wrapper.get('[data-testid="case-group-toggle"]').trigger('click')
      await flushPromises()
      selection.toggle({ punishmentIdentifier: 'p-0002', version: 1 }, true)
      await flushPromises()

      const playerBox = wrapper.get('[data-testid="case-group-select"] input').element as HTMLInputElement
      expect(playerBox.indeterminate).toBe(true)
      expect(playerBox.checked).toBe(false)

      wrapper.unmount()
    })

    it('says how many cases are loaded when not all of the player are', async () => {
      const { mount } = setup([group({ totalCount: 4 })])
      const { wrapper } = await mount()
      await flushPromises()
      await wrapper.get('[data-testid="case-group-toggle"]').trigger('click')
      await flushPromises()

      expect(wrapper.get('[data-testid="case-group-loaded"]').text()).toBe('Loaded 1 of 4')

      wrapper.unmount()
    })

    it('does not talk about loaded cases once every case of the player is loaded', async () => {
      const { mount } = setup([group({ totalCount: 1, openCount: 1 })])
      const { wrapper } = await mount()
      await flushPromises()
      await wrapper.get('[data-testid="case-group-toggle"]').trigger('click')
      await flushPromises()

      expect(wrapper.find('[data-testid="case-group-loaded"]').exists()).toBe(false)

      wrapper.unmount()
    })

    it('shows no count before any case of the player is loaded, so it never reads as "0 of 4"', async () => {
      const { mount } = setup([group({ totalCount: 4 })])
      const { wrapper } = await mount()
      await flushPromises()

      expect(wrapper.find('[data-testid="case-group-loaded"]').exists()).toBe(false)
      expect(wrapper.text()).not.toContain('0 of 4')

      wrapper.unmount()
    })

    it('loads the first page of a player that was never opened and selects it, leaving the player collapsed', async () => {
      const { cases, selection, mount } = setup()
      const { wrapper } = await mount()
      await flushPromises()

      await wrapper.get('[data-testid="case-group-select"] input').setValue(true)
      await flushPromises()

      expect(cases).toHaveBeenCalledWith(expect.objectContaining({ owner: OWNER, page: 0 }))
      expect(selection.count.value).toBe(1)
      expect(wrapper.get('[data-testid="case-group-toggle"]').attributes('aria-expanded')).toBe('false')

      wrapper.unmount()
    })

    it('deselects the loaded cases of the player', async () => {
      const { selection, mount } = setup()
      const { wrapper } = await mount()
      await flushPromises()
      await wrapper.get('[data-testid="case-group-toggle"]').trigger('click')
      await flushPromises()
      selection.toggle({ punishmentIdentifier: 'p-0001', version: 1 }, true)
      await flushPromises()

      await wrapper.get('[data-testid="case-group-select"] input').setValue(false)

      expect(selection.count.value).toBe(0)

      wrapper.unmount()
    })
  })
})
