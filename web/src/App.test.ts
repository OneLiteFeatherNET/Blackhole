import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from './App.vue'
import { i18n } from './i18n'
import { createAppRouter } from './router'
import { THEME_STORAGE_KEY } from './stores/ui'

async function mountApp(path = '/') {
  const router = createAppRouter(createMemoryHistory())
  await router.push(path)
  await router.isReady()
  return mount(App, { global: { plugins: [createPinia(), i18n, router] }, attachTo: document.body })
}

describe('App shell', () => {
  beforeEach(() => {
    localStorage.clear()
    delete document.documentElement.dataset.theme
    i18n.global.locale.value = 'de'
  })

  it('shows German navigation and the title of the current view by default', async () => {
    const wrapper = await mountApp('/players')

    expect(wrapper.get('nav').attributes('aria-label')).toBe('Hauptnavigation')
    expect(wrapper.get('h1').text()).toBe('Spieler')
    expect(wrapper.get('a[aria-current="page"]').text()).toBe('Spieler')

    wrapper.unmount()
  })

  it('switches the language and the theme from the top bar', async () => {
    const wrapper = await mountApp('/')
    const [themeSelect, localeSelect] = wrapper.findAll('select')

    await themeSelect!.setValue('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')

    await localeSelect!.setValue('en')
    await flushPromises()
    expect(wrapper.get('nav').attributes('aria-label')).toBe('Main navigation')
    expect(wrapper.get('h1').text()).toBe('Dashboard')

    wrapper.unmount()
  })
})
