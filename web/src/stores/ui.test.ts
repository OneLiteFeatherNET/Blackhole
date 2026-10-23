import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import { LOCALE_STORAGE_KEY, NAV_COLLAPSED_STORAGE_KEY, THEME_STORAGE_KEY, useUiStore } from './ui'

// The store reads storage and the document when it is created, so each test gets a fresh pinia.
function freshStore() {
  setActivePinia(createPinia())
  return useUiStore()
}

describe('ui store: theme', () => {
  beforeEach(() => {
    localStorage.clear()
    delete document.documentElement.dataset.theme
  })

  it('follows the system preference when nothing is stored', () => {
    const store = freshStore()

    expect(store.theme).toBe('system')
    expect(document.documentElement.dataset.theme).toBeUndefined()
  })

  it('switches to an explicit light or dark scheme and persists the choice', () => {
    const store = freshStore()

    store.setTheme('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')

    store.setTheme('light')
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light')
  })

  it('removes the override again when switching back to system', () => {
    const store = freshStore()
    store.setTheme('dark')

    store.setTheme('system')

    expect(document.documentElement.dataset.theme).toBeUndefined()
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('system')
  })

  it('restores the stored override on the next start', () => {
    freshStore().setTheme('light')

    const restored = freshStore()

    expect(restored.theme).toBe('light')
    expect(document.documentElement.dataset.theme).toBe('light')
  })

  it('ignores an invalid stored value', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'purple')

    const store = freshStore()

    expect(store.theme).toBe('system')
    expect(document.documentElement.dataset.theme).toBeUndefined()
  })
})

describe('ui store: legacy acting-as label', () => {
  it('is removed from the browser storage on start', () => {
    localStorage.setItem('blackhole.actingAs', 'Alex')

    freshStore()

    expect(localStorage.getItem('blackhole.actingAs')).toBeNull()
  })
})

describe('ui store: locale', () => {
  beforeEach(() => {
    localStorage.clear()
    i18n.global.locale.value = 'de'
  })

  it('defaults to German', () => {
    const store = freshStore()

    expect(store.locale).toBe('de')
    expect(i18n.global.locale.value).toBe('de')
  })

  it('switches the interface language and persists it', () => {
    const store = freshStore()

    store.setLocale('en')

    expect(i18n.global.locale.value).toBe('en')
    expect(document.documentElement.lang).toBe('en')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('en')
  })
})

describe('ui store: navigation collapse', () => {
  beforeEach(() => {
    localStorage.clear()
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('starts expanded when nothing is stored', () => {
    expect(freshStore().navCollapsed).toBe(false)
  })

  it('persists the choice and restores it in the next session', () => {
    freshStore().setNavCollapsed(true)

    expect(localStorage.getItem(NAV_COLLAPSED_STORAGE_KEY)).toBe('true')
    expect(freshStore().navCollapsed, 'restored').toBe(true)
  })

  it('ignores a stored value it does not know', () => {
    localStorage.setItem(NAV_COLLAPSED_STORAGE_KEY, 'maybe')

    expect(freshStore().navCollapsed).toBe(false)
  })

  it('still works for the page when storage throws on read and write', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })
    const store = freshStore()
    expect(store.navCollapsed, 'default').toBe(false)

    store.setNavCollapsed(true)

    expect(store.navCollapsed, 'in-memory choice').toBe(true)
  })
})
