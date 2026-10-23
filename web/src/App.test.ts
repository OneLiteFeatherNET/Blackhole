import { flushPromises, mount } from '@vue/test-utils'
import { chooseOption } from './test/select'
import { beforeEach, describe, expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from './App.vue'
import { i18n } from './i18n'
import { createAppRouter } from './router'
import { useSessionStore } from './stores/session'
import { THEME_STORAGE_KEY } from './stores/ui'
import { API_KEY } from './api/inject'
import { AUTH_KEY } from './auth/inject'
import { fakeAuthService } from './test/auth'
import { mockApi, ok, piniaWithRoles } from './test/mount'

async function mountApp(path = '/', options: { roles?: string[]; auth?: ReturnType<typeof fakeAuthService> } = {}) {
  const auth = options.auth ?? fakeAuthService()
  const router = createAppRouter(createMemoryHistory())
  await router.push(path)
  await router.isReady()
  const pinia = piniaWithRoles(options.roles)
  const wrapper = mount(App, {
    global: {
      plugins: [pinia, i18n, router],
      provide: {
        [AUTH_KEY as symbol]: auth.service,
        [API_KEY as symbol]: mockApi({
          dashboard: async () => ok({ activePunishmentsBySection: [], latestEscalations: [] }),
          profile: async () => ok({ active: {}, history: [] }),
          eloProfile: async () => ok({ owner: '', sections: [] }),
          eloHistory: async () => ok([]),
        }),
      },
    },
    attachTo: document.body,
  })
  return Object.assign(wrapper, { auth, session: useSessionStore(pinia) })
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

  it('keeps the players entry current on a player detail page', async () => {
    const wrapper = await mountApp(`/players/${'ab'.repeat(64)}`)

    expect(wrapper.get('a[aria-current="page"]').text()).toBe('Spieler')

    wrapper.unmount()
  })

  it('shows who is signed in instead of a free-text acting-as field', async () => {
    const wrapper = await mountApp('/')

    expect(wrapper.get('[data-testid="signed-in-as"]').text()).toBe('Angemeldet als Dev Admin')
    expect(wrapper.find('input').exists(), 'text input').toBe(false)

    wrapper.unmount()
  })

  it('signs out from the top bar', async () => {
    const wrapper = await mountApp('/')

    await wrapper.get('[data-testid="sign-out"]').trigger('click')

    expect(wrapper.auth.calls.signOut, 'sign-outs').toBe(1)

    wrapper.unmount()
  })

  it('offers no navigation to a user without a role', async () => {
    const wrapper = await mountApp('/no-access', { roles: [] })

    expect(wrapper.find('nav').exists(), 'navigation').toBe(false)
    expect(wrapper.get('h1').text()).toBe('Kein Zugriff')

    wrapper.unmount()
  })

  it('announces the redirect to the sign-in service in a live region', async () => {
    const auth = fakeAuthService()
    const wrapper = await mountApp('/', { auth })

    auth.service.redirecting.value = true
    await flushPromises()

    const status = wrapper.get('[data-testid="auth-status"]')
    expect(status.attributes('aria-live'), 'live region').toBe('polite')
    expect(status.text()).toBe('Weiterleitung zum Anmeldedienst ...')
    expect(wrapper.find('nav').exists(), 'navigation while redirecting').toBe(false)

    wrapper.unmount()
  })

  it('tells the user about a refused action', async () => {
    const wrapper = await mountApp('/')

    wrapper.session.forbidden = true
    await flushPromises()

    expect(wrapper.text()).toContain('Dafür fehlt dir die Berechtigung.')

    wrapper.unmount()
  })

  it('switches the language and the theme from the top bar', async () => {
    const wrapper = await mountApp('/')
    const [themeSelect, localeSelect] = wrapper.findAll('[role="combobox"]')

    await chooseOption(themeSelect!, 'dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')

    await chooseOption(localeSelect!, 'en')
    await flushPromises()
    expect(wrapper.get('nav').attributes('aria-label')).toBe('Main navigation')
    expect(wrapper.get('h1').text()).toBe('Dashboard')

    wrapper.unmount()
  })
})
