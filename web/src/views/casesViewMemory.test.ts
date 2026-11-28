import { describe, expect, it } from 'vitest'
import { readCasesView, writeCasesView } from './casesViewMemory'

/** A storage held in memory by the test itself, so nothing is shared between tests. */
function memoryStorage(initial: Record<string, string> = {}): Storage & { data: Map<string, string> } {
  const data = new Map(Object.entries(initial))
  return {
    data,
    get length() {
      return data.size
    },
    key: (index: number) => [...data.keys()][index] ?? null,
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => void data.set(key, value),
    removeItem: (key: string) => void data.delete(key),
    clear: () => data.clear(),
  }
}

function throwingStorage(): Storage {
  const fail = () => {
    throw new DOMException('blocked', 'SecurityError')
  }
  return { length: 0, key: fail, getItem: fail, setItem: fail, removeItem: fail, clear: fail } as unknown as Storage
}

describe('the chosen cases view', () => {
  it('shows the flat list when nothing was chosen', () => {
    expect(readCasesView(memoryStorage())).toBe('cases')
  })

  it('shows the grouped list when the viewer chose it before', () => {
    expect(readCasesView(memoryStorage({ 'blackhole.cases.view': 'player' }))).toBe('player')
  })

  it('ignores a stored value it does not know', () => {
    expect(readCasesView(memoryStorage({ 'blackhole.cases.view': 'something-else' }))).toBe('cases')
  })

  it('falls back to the flat list when the browser refuses storage', () => {
    expect(readCasesView(throwingStorage())).toBe('cases')
  })

  it('stores only the value under its one key', () => {
    const storage = memoryStorage()

    writeCasesView('player', storage)

    expect([...storage.data.entries()]).toEqual([['blackhole.cases.view', 'player']])
  })

  it('does not fail when the browser refuses storage on write', () => {
    expect(() => writeCasesView('cases', throwingStorage())).not.toThrow()
  })
})
