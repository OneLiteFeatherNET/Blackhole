import { createI18n } from 'vue-i18n'
import de from './locales/de.json'
import en from './locales/en.json'

export const SUPPORTED_LOCALES = ['en', 'de'] as const
export type Locale = (typeof SUPPORTED_LOCALES)[number]
/** English unless the viewer picked a language; the browser language is deliberately not used. */
export const DEFAULT_LOCALE: Locale = 'en'

export function createPortalI18n() {
  return createI18n({ legacy: false, locale: DEFAULT_LOCALE, fallbackLocale: DEFAULT_LOCALE, messages: { de, en } })
}
