import { describe, expect, it } from 'vitest'
import { CASE_SELECTION_CAP, useCaseSelection } from './useCaseSelection'

function cases(count: number, prefix = 'p'): { punishmentIdentifier: string; version: number }[] {
  return Array.from({ length: count }, (_, index) => ({ punishmentIdentifier: `${prefix}-${index}`, version: 1 }))
}

describe('useCaseSelection', () => {
  it('starts empty and holds the cap of 100', () => {
    const selection = useCaseSelection()

    expect(selection.count.value).toBe(0)
    expect(selection.items.value).toEqual([])
    expect(selection.cap).toBe(CASE_SELECTION_CAP)
    expect(CASE_SELECTION_CAP).toBe(100)
  })

  it('toggles one case on and off and keeps its version', () => {
    const selection = useCaseSelection()

    selection.toggle({ punishmentIdentifier: 'p-1', version: 7 }, true)

    expect(selection.isSelected('p-1')).toBe(true)
    expect(selection.items.value).toEqual([{ punishmentIdentifier: 'p-1', version: 7 }])

    selection.toggle({ punishmentIdentifier: 'p-1', version: 7 }, false)

    expect(selection.isSelected('p-1')).toBe(false)
    expect(selection.count.value).toBe(0)
  })

  it('takes the newer version when a selected case is selected again', () => {
    const selection = useCaseSelection()
    selection.toggle({ punishmentIdentifier: 'p-1', version: 1 }, true)

    selection.toggle({ punishmentIdentifier: 'p-1', version: 2 }, true)

    expect(selection.items.value).toEqual([{ punishmentIdentifier: 'p-1', version: 2 }])
  })

  it('selects a list and reports how many cases it changed', () => {
    const selection = useCaseSelection()

    const changed = selection.toggleMany(cases(3), true)

    expect(changed).toBe(3)
    expect(selection.count.value).toBe(3)
  })

  it('stops at the cap: a list larger than the room left selects only what fits', () => {
    const selection = useCaseSelection()
    selection.toggleMany(cases(98, 'a'), true)

    const changed = selection.toggleMany(cases(5, 'b'), true)

    expect(changed).toBe(2)
    expect(selection.count.value).toBe(CASE_SELECTION_CAP)
    expect(selection.isSelected('b-1')).toBe(true)
    expect(selection.isSelected('b-2')).toBe(false)
  })

  it('reports the cap as reached and refuses an unselected case past it', () => {
    const selection = useCaseSelection()
    selection.toggleMany(cases(CASE_SELECTION_CAP), true)

    expect(selection.atCap.value).toBe(true)
    expect(selection.canSelect('not-selected')).toBe(false)
    expect(selection.canSelect('p-0')).toBe(true)
  })

  it('deselects a list without any cap check', () => {
    const selection = useCaseSelection()
    selection.toggleMany(cases(CASE_SELECTION_CAP), true)

    const changed = selection.toggleMany(cases(CASE_SELECTION_CAP), false)

    expect(changed).toBe(CASE_SELECTION_CAP)
    expect(selection.count.value).toBe(0)
    expect(selection.atCap.value).toBe(false)
  })

  it('clears everything at once', () => {
    const selection = useCaseSelection()
    selection.toggleMany(cases(4), true)

    selection.clear()

    expect(selection.count.value).toBe(0)
    expect(selection.items.value).toEqual([])
  })

  it('summarises a list as checked, indeterminate or neither', () => {
    const selection = useCaseSelection()
    const list = cases(3)

    expect(selection.summary(list)).toEqual({ checked: false, indeterminate: false, selected: 0 })

    selection.toggleMany(list.slice(0, 1), true)
    expect(selection.summary(list)).toEqual({ checked: false, indeterminate: true, selected: 1 })

    selection.toggleMany(list, true)
    expect(selection.summary(list)).toEqual({ checked: true, indeterminate: false, selected: 3 })
  })

  it('summarises an empty list as neither checked nor indeterminate', () => {
    const selection = useCaseSelection()

    expect(selection.summary([])).toEqual({ checked: false, indeterminate: false, selected: 0 })
  })
})
