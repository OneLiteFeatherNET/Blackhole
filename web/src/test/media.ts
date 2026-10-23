type Listener = () => void

/** A matchMedia whose answers follow `width`; `change()` tells the listeners, like a resize. */
export function fakeMatchMedia(width: { value: number }) {
  const listeners = new Set<Listener>()
  const matchMedia = (query: string) => {
    const min = Number(/min-width:\s*(\d+)px/.exec(query)![1])
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
