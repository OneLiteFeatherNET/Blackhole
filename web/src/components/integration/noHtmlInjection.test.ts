import { describe, expect, it } from 'vitest'

// Integration text is data: it must reach the page as text. No file of this folder may turn it into markup.
const sources = Object.fromEntries(
  Object.entries(import.meta.glob('./*.{vue,ts}', { query: '?raw', import: 'default', eager: true }) as Record<string, string>).filter(
    ([file]) => !file.endsWith('.test.ts'),
  ),
)

describe('integration components render text only', () => {
  it('finds the component sources at all', () => {
    expect(Object.keys(sources).length).toBeGreaterThan(3)
  })

  it.each(Object.entries(sources))('%s uses no v-html, innerHTML or markdown renderer', (file, source) => {
    expect(source, `${file} must not use v-html`).not.toMatch(/v-html/)
    expect(source, `${file} must not write innerHTML`).not.toMatch(/innerHTML|outerHTML|insertAdjacentHTML/)
    expect(source, `${file} must not use the markdown renderer`).not.toMatch(/ChatMarkdown|marked|markdown-it/)
  })
})
