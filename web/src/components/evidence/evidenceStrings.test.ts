import { describe, expect, it } from 'vitest'

// Every visible word of an evidence component must come from the locale files. This reads the
// templates and fails on literal text between tags and on literal text in accessible-name attributes.
const sources = import.meta.glob('./*.vue', { query: '?raw', import: 'default', eager: true }) as Record<string, string>

function template(source: string): string {
  return /<template>([\s\S]*)<\/template>/.exec(source)?.[1] ?? ''
}

/** Text between tags that is not an interpolation, comment or whitespace and contains letters. */
function literalText(markup: string): string[] {
  const stripped = markup.replace(/<!--[\s\S]*?-->/g, '').replace(/\{\{[\s\S]*?\}\}/g, '')
  return [...stripped.matchAll(/>([^<>]+)</g)].map((match) => match[1]!.trim()).filter((text) => /\p{L}{2,}/u.test(text))
}

/** Static (not v-bind) attributes that a screen reader or tooltip shows. */
function literalAttributes(markup: string): string[] {
  return [...markup.matchAll(/\s(aria-label|aria-description|title|alt|placeholder|label|headline)="([^"]*\p{L}{2,}[^"]*)"/gu)].map(
    (match) => `${match[1]}="${match[2]}"`,
  )
}

describe('evidence components', () => {
  it('finds the components at all', () => {
    expect(Object.keys(sources).length).toBeGreaterThanOrEqual(10)
  })

  it.each(Object.entries(sources))('%s has no hard-coded text', (_file, source) => {
    const markup = template(source)

    expect(literalText(markup), 'text between tags').toEqual([])
    expect(literalAttributes(markup), 'accessible-name attributes').toEqual([])
  })
})
