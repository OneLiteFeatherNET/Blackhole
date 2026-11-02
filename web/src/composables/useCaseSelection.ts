import { computed, shallowRef } from 'vue'

/** The most cases one selection holds; the bulk route takes at most this many per request. */
export const CASE_SELECTION_CAP = 100

/** What a selection keeps of a case: the identifier, and the version the bulk edit will check against. */
export interface SelectableCase {
  punishmentIdentifier: string
  version: number
  /** For the result list of a bulk edit; the selection itself does not need it. */
  shortId?: string
}

export interface SelectionSummary {
  checked: boolean
  indeterminate: boolean
  selected: number
}

/**
 * The cases picked for a bulk edit, keyed by punishment identifier, capped at CASE_SELECTION_CAP. The page owns one
 * instance; the actions of the bulk panel read `items` later. Nothing here talks to the API.
 */
export function useCaseSelection() {
  const selected = shallowRef(new Map<string, SelectableCase>())

  const items = computed<SelectableCase[]>(() => [...selected.value.values()])
  const count = computed(() => selected.value.size)
  const atCap = computed(() => count.value >= CASE_SELECTION_CAP)

  function isSelected(punishmentIdentifier: string): boolean {
    return selected.value.has(punishmentIdentifier)
  }

  /** A checkbox may be checked when its case is selected already, or while there is room under the cap. */
  function canSelect(punishmentIdentifier: string): boolean {
    return isSelected(punishmentIdentifier) || !atCap.value
  }

  /** Selects or deselects one case; without `select` it toggles. Returns whether the case is selected afterwards. */
  function toggle(item: SelectableCase, select: boolean = !isSelected(item.punishmentIdentifier)): boolean {
    toggleMany([item], select)
    return isSelected(item.punishmentIdentifier)
  }

  /**
   * Selects or deselects a list. Selecting stops when the cap is full, so a list larger than the room left is
   * partly selected. Returns how many cases changed.
   */
  function toggleMany(cases: SelectableCase[], select: boolean): number {
    const next = new Map(selected.value)
    let changed = 0
    for (const item of cases) {
      const id = item.punishmentIdentifier
      if (select) {
        // The identifier, the version and the short id are kept, whatever else the row carries.
        const kept: SelectableCase = { punishmentIdentifier: id, version: item.version, shortId: item.shortId }
        if (next.has(id)) {
          next.set(id, kept)
          continue
        }
        if (next.size >= CASE_SELECTION_CAP) break
        next.set(id, kept)
        changed++
      } else if (next.delete(id)) {
        changed++
      }
    }
    selected.value = next
    return changed
  }

  function clear(): void {
    selected.value = new Map()
  }

  /** How a header checkbox over `cases` should look: checked when all are selected, mixed when some are. */
  function summary(cases: SelectableCase[]): SelectionSummary {
    const picked = cases.filter((item) => isSelected(item.punishmentIdentifier)).length
    return {
      checked: cases.length > 0 && picked === cases.length,
      indeterminate: picked > 0 && picked < cases.length,
      selected: picked,
    }
  }

  return {
    items,
    count,
    atCap,
    cap: CASE_SELECTION_CAP,
    isSelected,
    canSelect,
    toggle,
    toggleMany,
    clear,
    summary,
  }
}

export type CaseSelection = ReturnType<typeof useCaseSelection>
