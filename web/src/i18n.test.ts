import { afterEach, describe, expect, it } from 'vitest'
import de from './locales/de.json'
import en from './locales/en.json'
import { DEFAULT_LOCALE, i18n } from './i18n'

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

describe('terminology of the configuration pages', () => {
  it('names a ban Sperre in German, as the glossary does', () => {
    expect(de.enforcement.BAN).toBe('Sperre')
  })

  it('names the templates Templates and Vorlagen', () => {
    expect(en.templates.title).toBe('Templates')
    expect(de.templates.title).toBe('Vorlagen')
  })
})

/** Every German text value, without the keys: a key such as `banner` is not wording. */
function leafValues(value: unknown): string[] {
  if (typeof value === 'string') return [value]
  if (typeof value !== 'object' || value === null) return []
  return Object.values(value).flatMap((child) => leafValues(child))
}

describe('German ban wording follows the glossary', () => {
  // "banned-players.json" is a file name the import reads, so it is not wording.
  it('names a ban Sperre and bans Sperren, never Bann or Banns', () => {
    const offending = leafValues(de).filter((text) => /bann(?!ed-)/i.test(text))

    expect(offending, 'German texts that still say Bann').toEqual([])
  })
})

describe('default language', () => {
  afterEach(() => {
    i18n.global.locale.value = 'en'
  })

  it('is English', () => {
    expect(DEFAULT_LOCALE).toBe('en')
  })

  it('falls back to English for a key that only the English texts define', () => {
    const englishBefore = JSON.parse(JSON.stringify(i18n.global.getLocaleMessage('en')))
    try {
      i18n.global.mergeLocaleMessage('en', { fallbackProbe: { text: 'English only' } })
      i18n.global.locale.value = 'de'

      expect(i18n.global.t('fallbackProbe.text')).toBe('English only')
    } finally {
      i18n.global.setLocaleMessage('en', englishBefore)
    }
  })
})
