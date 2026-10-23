type Listener = () => void

/** A matchMedia whose answers follow `width`; `change()` tells the listeners, like a resize. */
export function fakeMatchMedia(width: { value: number }) {
  const listeners = new Set<Listener>()
  const matchMedia = (query: string) => {
    const found = /min-width:\s*(\d+)px/.exec(query)
    // Other queries (e.g. prefers-reduced-motion) are not a width and never match here.
    const min = found ? Number(found[1]) : Infinity
    return {
      get matches() {
        return width.value >= min
      },
      addEventListener: (_: string, listener: Listener) => listeners.add(listener),
      removeEventListener: (_: string, listener: Listener) => listeners.delete(listener),
    }
  }
  return { matchMedia, change: () => listeners.forEach((listener) => listener()), listeners }
}
