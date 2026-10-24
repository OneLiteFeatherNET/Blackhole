import { describe, expect, it } from 'vitest'

// Stored text is shown as text everywhere: no component may inject markup (design decision 5).
const sources = import.meta.glob('./**/*.{vue,ts}', { query: '?raw', import: 'default', eager: true }) as Record<string, string>

describe('plain text rendering', () => {
  const files = Object.entries(sources).filter(([name]) => !name.endsWith('.test.ts'))

  it('scans the components', () => {
    expect(files.length).toBeGreaterThan(8)
  })

  it('never uses v-html, innerHTML or outerHTML', () => {
    const offenders = files.filter(([, source]) => /v-html|innerHTML|outerHTML|insertAdjacentHTML/.test(source)).map(([name]) => name)

    expect(offenders).toEqual([])
  })

  it('never reads or writes browser storage or document.cookie', () => {
    const offenders = files.filter(([, source]) => /localStorage|sessionStorage|indexedDB|document\.cookie/.test(source)).map(([name]) => name)

    expect(offenders).toEqual([])
  })
})
