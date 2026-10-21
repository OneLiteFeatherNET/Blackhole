import { readFileSync } from 'node:fs'
import { join } from 'node:path'

import { describe, expect, it } from 'vitest'

const tokens = readFileSync(join(import.meta.dirname, 'styles/md3-tokens.generated.css'), 'utf8')

// A native <select> opens an OS-drawn list that ignores the theme (white list, light text in dark
// mode). Every dropdown must be an MdSelect instead.
const sources = import.meta.glob('./**/*.vue', { query: '?raw', import: 'default', eager: true }) as Record<string, string>

describe('dropdowns and theme', () => {
  it('finds the components at all', () => {
    expect(Object.keys(sources).length).toBeGreaterThan(20)
  })

  it('uses no native select element in any component', () => {
    const offenders = Object.entries(sources)
      .filter(([, source]) => /<select[\s>]/.test(source))
      .map(([file]) => file)

    expect(offenders).toEqual([])
  })

  it('tells the browser the color scheme of every theme so remaining native controls follow it', () => {
    expect(tokens).toMatch(/color-scheme:\s*light/)
    expect(tokens.match(/color-scheme:\s*dark/g)?.length, 'dark via the OS setting and via the toggle').toBeGreaterThanOrEqual(2)
  })
})
