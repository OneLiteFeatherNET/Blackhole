import { describe, expect, it } from 'vitest'
import de from './locales/de.json'
import en from './locales/en.json'

// Every translation key a component asks for literally, e.g. t('player.history'), must exist in both
// languages. (Keys built from a variable, like t(`enforcement.${kind}`), are covered by the tests of
// the screens that render each value.)
const sources = import.meta.glob('./**/*.vue', { query: '?raw', import: 'default', eager: true }) as Record<string, string>

function lookup(messages: unknown, key: string): unknown {
  return key.split('.').reduce<unknown>((node, part) => (node as Record<string, unknown> | undefined)?.[part], messages)
}

describe('translation keys used by components', () => {
  const used = Object.entries(sources).flatMap(([file, source]) =>
    [...source.matchAll(/\bt\(\s*'([A-Za-z0-9_.]+)'/g)].map((match) => ({ file, key: match[1]! })),
  )

  it('finds the keys at all', () => {
    expect(used.length, 'the scan should see the components').toBeGreaterThan(100)
  })

  it.each(['en', 'de'] as const)('are all defined in the %s texts', (language) => {
    const messages = language === 'en' ? en : de
    const missing = used.filter(({ key }) => typeof lookup(messages, key) !== 'string')

    expect(missing, `missing in ${language}`).toEqual([])
  })
})
