// The view the viewer chose on the Cases page, remembered in this browser only. It holds one value, `cases` or
// `player`: no name, no owner and no case. Blocked or unavailable storage falls back to the flat list silently.

export type CasesViewMode = 'cases' | 'player'

const KEY = 'blackhole.cases.view'

function browserStorage(): Storage | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    return null
  }
}

export function readCasesView(storage: Storage | null = browserStorage()): CasesViewMode {
  try {
    return storage?.getItem(KEY) === 'player' ? 'player' : 'cases'
  } catch {
    return 'cases'
  }
}

export function writeCasesView(mode: CasesViewMode, storage: Storage | null = browserStorage()): void {
  try {
    storage?.setItem(KEY, mode)
  } catch {
    // The choice is kept for this visit only; nothing else depends on it.
  }
}
