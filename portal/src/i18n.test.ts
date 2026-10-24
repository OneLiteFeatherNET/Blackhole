import { describe, expect, it } from 'vitest'
import { DEFAULT_LOCALE } from './i18n'
import de from './locales/de.json'
import en from './locales/en.json'

function leafKeys(value: unknown, prefix = ''): string[] {
  if (typeof value !== 'object' || value === null) return [prefix]
  return Object.entries(value).flatMap(([key, child]) => leafKeys(child, prefix ? `${prefix}.${key}` : key))
}

function lookup(messages: unknown, key: string): unknown {
  return key.split('.').reduce<unknown>((node, part) => (node as Record<string, unknown> | undefined)?.[part], messages)
}

describe('locale files', () => {
  it('define the same keys in German and English', () => {
    expect(leafKeys(en).sort()).toEqual(leafKeys(de).sort())
  })

  it('have no empty strings', () => {
    for (const messages of [en, de]) {
      for (const key of leafKeys(messages)) expect(lookup(messages, key), key).toBeTruthy()
    }
  })

  it('use the same placeholders in both languages', () => {
    for (const key of leafKeys(en)) {
      const names = (text: unknown) => [...String(text).matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort()
      expect(names(lookup(de, key)), key).toEqual(names(lookup(en, key)))
    }
  })

  it('make English the default language', () => {
    expect(DEFAULT_LOCALE).toBe('en')
  })
})

// Every translation key a component asks for literally, e.g. t('login.title'), must exist in both languages.
const sources = import.meta.glob('./**/*.vue', { query: '?raw', import: 'default', eager: true }) as Record<string, string>

describe('translation keys used by components', () => {
  const used = Object.entries(sources).flatMap(([file, source]) =>
    [...source.matchAll(/\bt\(\s*'([A-Za-z0-9_.]+)'/g)].map((match) => ({ file, key: match[1]! })),
  )

  it('finds the keys at all', () => {
    expect(used.length, 'the scan should see the components').toBeGreaterThan(40)
  })

  it.each([
    ['en', en],
    ['de', de],
  ] as const)('are all defined in the %s texts', (_language, messages) => {
    expect(used.filter(({ key }) => typeof lookup(messages, key) !== 'string')).toEqual([])
  })
})

// Keys that are built from a value at runtime are covered here instead.
describe('translation keys built from values', () => {
  const dynamic: Array<[string, string[]]> = [
    ['case.state', ['ACTIVE', 'EXPIRED', 'LIFTED', 'REVOKED']],
    ['conversation.author', ['PLAYER', 'STAFF', 'SYSTEM']],
    ['conversation.marker', ['APPEAL_FILED', 'APPEAL_DECIDED', 'FROZEN', 'TICKET_OPENED', 'TICKET_CLOSED', 'OTHER']],
    ['conversation.decision', ['DENIED', 'GRANTED_LIFT', 'GRANTED_REDUCTION', 'OTHER']],
    ['conversation.errors', ['FROZEN', 'NO_APPEAL', 'CONFLICT', 'PII_REJECTED', 'VALIDATION', 'TOO_LARGE', 'UNAVAILABLE']],
    ['appealForm.errors', ['NOT_ELIGIBLE', 'NOT_ELIGIBLE_NOW', 'FORFEITED', 'FROZEN', 'CONFLICT', 'PII_REJECTED', 'VALIDATION', 'TOO_LARGE', 'UNAVAILABLE']],
  ]

  it.each(dynamic)('%s is defined for every value', (prefix, values) => {
    for (const messages of [en, de]) {
      for (const value of values) expect(typeof lookup(messages, `${prefix}.${value}`), `${prefix}.${value}`).toBe('string')
    }
  })
})
