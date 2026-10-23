import { vi } from 'vitest'

type Listener = () => void

/**
 * Stubs window.matchMedia so `(prefers-reduced-motion: reduce)` answers `reduced`; any other query
 * does not match. `set()` flips the answer and tells the listeners, like the system setting changing.
 * Call `vi.unstubAllGlobals()` afterwards.
 */
export function stubReducedMotion(reduced: boolean) {
  let current = reduced
  const listeners = new Set<Listener>()
  vi.stubGlobal('matchMedia', (query: string) => ({
    get matches() {
      return query.includes('prefers-reduced-motion') && current
    },
    addEventListener: (_: string, listener: Listener) => listeners.add(listener),
    removeEventListener: (_: string, listener: Listener) => listeners.delete(listener),
  }))
  return {
    listeners,
    set(value: boolean) {
      current = value
      listeners.forEach((listener) => listener())
    },
  }
}
