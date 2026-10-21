import { defineStore } from 'pinia'
import { ref } from 'vue'
import { DEFAULT_LOCALE, i18n, isLocale, type Locale } from '../i18n'

export type ThemeChoice = 'system' | 'light' | 'dark'

export const THEME_STORAGE_KEY = 'blackhole.theme'
export const LOCALE_STORAGE_KEY = 'blackhole.locale'
export const ACTING_AS_STORAGE_KEY = 'blackhole.actingAs'

function isThemeChoice(value: unknown): value is ThemeChoice {
  return value === 'system' || value === 'light' || value === 'dark'
}

// Storage can be unavailable (private windows, blocked site data); the choice then lasts for the page only.
function readStorage(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function writeStorage(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    // Not persisted; the in-memory choice still applies.
  }
}

function removeStorage(key: string): void {
  try {
    localStorage.removeItem(key)
  } catch {
    // Nothing to remove.
  }
}

/** "system" removes the override so the OS preference (see md3-tokens.generated.css) decides. */
export function applyTheme(choice: ThemeChoice): void {
  const root = document.documentElement
  if (choice === 'system') {
    delete root.dataset.theme
  } else {
    root.dataset.theme = choice
  }
}

export const useUiStore = defineStore('ui', () => {
  const storedTheme = readStorage(THEME_STORAGE_KEY)
  const storedLocale = readStorage(LOCALE_STORAGE_KEY)

  const theme = ref<ThemeChoice>(isThemeChoice(storedTheme) ? storedTheme : 'system')
  const locale = ref<Locale>(isLocale(storedLocale) ? storedLocale : DEFAULT_LOCALE)
  // The operator's self-declared name; unverified, only written into notes (see the identity gap).
  const actingAs = ref(readStorage(ACTING_AS_STORAGE_KEY) ?? '')

  applyTheme(theme.value)
  i18n.global.locale.value = locale.value
  document.documentElement.lang = locale.value

  function setTheme(choice: ThemeChoice): void {
    theme.value = choice
    writeStorage(THEME_STORAGE_KEY, choice)
    applyTheme(choice)
  }

  function setLocale(next: Locale): void {
    locale.value = next
    writeStorage(LOCALE_STORAGE_KEY, next)
    i18n.global.locale.value = next
    document.documentElement.lang = next
  }

  function setActingAs(label: string): void {
    actingAs.value = label.trim()
    if (actingAs.value) writeStorage(ACTING_AS_STORAGE_KEY, actingAs.value)
    else removeStorage(ACTING_AS_STORAGE_KEY)
  }

  return { theme, locale, actingAs, setTheme, setLocale, setActingAs }
})
