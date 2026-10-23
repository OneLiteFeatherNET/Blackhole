import type { Ticker } from '../nav/ticker'

/** A Ticker the test drives: `tick()` runs every registered callback once, like a period passing. */
export function fakeTicker() {
  const active = new Map<number, { ms: number; callback: () => void }>()
  let next = 0
  const ticker: Ticker = {
    every(ms, callback) {
      const id = next++
      active.set(id, { ms, callback })
      return () => active.delete(id)
    },
  }
  return {
    ticker,
    tick: () => active.forEach(({ callback }) => callback()),
    /** The periods of the running timers. */
    get periods() {
      return [...active.values()].map((entry) => entry.ms)
    },
  }
}
