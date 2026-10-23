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
