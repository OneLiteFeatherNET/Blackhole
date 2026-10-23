import { inject, type InjectionKey } from 'vue'

/** Calls `callback` every `ms` milliseconds until the returned function is called. Injected so tests drive time themselves. */
export interface Ticker {
  every(ms: number, callback: () => void): () => void
}

export const browserTicker: Ticker = {
  every(ms, callback) {
    const id = setInterval(callback, ms)
    return () => clearInterval(id)
  },
}

export const TICKER_KEY: InjectionKey<Ticker> = Symbol('blackhole-ticker')

/** The timer of the running app; tests provide their own under {@link TICKER_KEY}. */
export function useTicker(): Ticker {
  return inject(TICKER_KEY, browserTicker)
}
