import { describe, expect, it } from 'vitest'
import de from './locales/de.json'
import en from './locales/en.json'

function leafKeys(value: unknown, prefix = ''): string[] {
  if (typeof value !== 'object' || value === null) return [prefix]
  return Object.entries(value).flatMap(([key, child]) => leafKeys(child, prefix ? `${prefix}.${key}` : key))
}

describe('locale files', () => {
  it('define the same keys in German and English', () => {
    expect(leafKeys(en).sort()).toEqual(leafKeys(de).sort())
  })

  it('have no empty strings', () => {
    for (const key of leafKeys(de)) {
      const value = key.split('.').reduce<unknown>((node, part) => (node as Record<string, unknown>)[part], de)
      expect(value, key).toBeTruthy()
    }
  })
})
