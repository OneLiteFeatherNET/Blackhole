import { flushPromises, mount } from '@vue/test-utils'
import { chooseOption } from './test/select'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from './App.vue'
import { i18n } from './i18n'
import { createAppRouter } from './router'
import { useSessionStore } from './stores/session'
import { LOCALE_STORAGE_KEY, NAV_COLLAPSED_STORAGE_KEY, THEME_STORAGE_KEY } from './stores/ui'
import { fakeMatchMedia } from './test/media'
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
          proxyCapabilities: async () => ok({ proxies: [] }),
          dashboard: async () => ok({ activePunishmentsBySection: [], latestEscalations: [] }),
          joinGuard: async () => ok({ manualLevel: 'OFF', automaticLevel: 'OFF', effectiveLevel: 'OFF', updatedAt: 0 }),
          areaBans: async () => ok([]),
          inbox: async () => ok({ generatedAt: 0, needsAnswer: { count: 0, items: [] }, openAppeals: { count: 0, items: [] }, newReports: { count: 0, items: [] }, unassignedCases: { count: 0, items: [] } }),
          profile: async () => ok({ active: {}, history: [] }),
          eloProfile: async () => ok({ owner: '', sections: [] }),
          playerSessions: async () => ok({ sessions: [], page: 0, size: 50, hasMore: false }),
          eloHistory: async () => ok([]),
          evasionSightings: async () => ok({ detectionEnabled: true, windowDays: 7, linkedOwners: [], truncated: false }),
          intelHits: async () => ok([]),
          clientRuleHitsOfOwner: async () => ok([]),
          exemption: async () => ok({ exempt: false, sources: [], sections: [], rank: null }),
          staffLinkStatus: async () => ok({ linked: false }),
          discordLinkStatus: async () => ok({ linked: false }),
          cases: async () => ok({ items: [], page: 0, size: 20, totalSize: 0, totalPages: 0 }),
          templates: async () => ok([]),
          sections: async () => ok([]),
        }),
      },
    },
    attachTo: document.body,
  })
  return Object.assign(wrapper, { auth, session: useSessionStore(pinia) })
}

/** Opens the account menu of the top bar, which holds the account, theme, language and sign-out controls. */
async function openAccountMenu(wrapper: Awaited<ReturnType<typeof mountApp>>) {
  await wrapper.get('[data-testid="account-menu"]').trigger('click')
}

describe('App shell', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  beforeEach(() => {
    localStorage.clear()
    delete document.documentElement.dataset.theme
    localStorage.setItem(LOCALE_STORAGE_KEY, 'de')
  })

  it('shows German navigation and the title of the current view for a German viewer', async () => {
    const wrapper = await mountApp('/players')

    expect(wrapper.get('nav').attributes('aria-label')).toBe('Hauptnavigation')
    expect(wrapper.get('h1').text()).toBe('Spielersuche')
    expect(wrapper.get('a[aria-current="page"]').text()).toBe('Spielersuche')

    wrapper.unmount()
  })

  it('keeps the players entry current on a player detail page', async () => {
    const wrapper = await mountApp(`/players/${'ab'.repeat(64)}`)

    expect(wrapper.get('a[aria-current="page"]').text()).toBe('Spielersuche')

    wrapper.unmount()
  })

  it('shows who is signed in instead of a free-text acting-as field', async () => {
    const wrapper = await mountApp('/')
    await openAccountMenu(wrapper)

    expect(wrapper.get('[data-testid="signed-in-as"]').text()).toBe('Angemeldet als Dev Admin')
    expect(wrapper.find('input:not([data-testid="global-search"])').exists(), 'free-text input').toBe(false)

    wrapper.unmount()
  })

  it('signs out from the account menu', async () => {
    const wrapper = await mountApp('/')
    await openAccountMenu(wrapper)

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

  it('offers the administration workspace in the switcher to an admin', async () => {
    const wrapper = await mountApp('/')

    await wrapper.get('[data-testid="workspace-switcher"]').trigger('click')

    expect(wrapper.findAll('[role="menuitem"] [data-testid="workspace-label"]').map((label) => label.text()), 'switcher').toEqual(['Moderation', 'Verwaltung'])

    wrapper.unmount()
  })

  it('places the switcher in the top right of the app bar, after the brand and before the account menu', async () => {
    const wrapper = await mountApp('/')
    const header = wrapper.get('header').element
    const brand = wrapper.get('header a[href="/"]').element
    const switcher = wrapper.get('[data-testid="workspace-switcher"]').element
    const account = wrapper.get('[data-testid="account-menu"]').element

    expect(brand.compareDocumentPosition(switcher) & Node.DOCUMENT_POSITION_FOLLOWING, 'after the brand').toBeTruthy()
    expect(switcher.compareDocumentPosition(account) & Node.DOCUMENT_POSITION_FOLLOWING, 'before the account menu').toBeTruthy()
    expect(header.contains(switcher), 'in the app bar').toBe(true)
    expect(wrapper.findAll('header a').filter((link) => link.attributes('data-testid')?.startsWith('workspace-')), 'no links in the bar').toHaveLength(0)

    wrapper.unmount()
  })

  it.each([['MODERATOR'], ['VIEWER']])('offers no switcher to a %s', async (role) => {
    const wrapper = await mountApp('/', { roles: [role] })

    expect(wrapper.find('[data-testid="workspace-switcher"]').exists(), 'switcher').toBe(false)

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

  it('switches the language and the theme from the account menu', async () => {
    const wrapper = await mountApp('/')
    await openAccountMenu(wrapper)
    const [themeSelect, localeSelect] = wrapper.findAll('[role="combobox"]:not([data-testid="global-search"])')

    await chooseOption(themeSelect!, 'dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')

    await chooseOption(localeSelect!, 'en')
    await flushPromises()
    expect(wrapper.get('nav').attributes('aria-label')).toBe('Main navigation')
    expect(wrapper.get('h1').text()).toBe('Inbox')

    wrapper.unmount()
  })

  it('opens the account dialog from the signed-in area', async () => {
    const wrapper = await mountApp('/', { roles: ['VIEWER'] })

    expect(wrapper.find('[role="dialog"]').exists(), 'closed at first').toBe(false)
    await openAccountMenu(wrapper)
    await wrapper.get('[data-testid="link-minecraft"]').trigger('click')

    expect(wrapper.find('[role="dialog"]').exists(), 'opened by the button').toBe(true)

    wrapper.unmount()
  })

  it('shows the moderation rail on the inbox for an admin', async () => {
    const wrapper = await mountApp('/')

    expect(wrapper.findAll('nav [role="group"]').map((group) => group.find('p').text()), 'headings').toEqual(['Arbeit', 'Spieler', 'Schutz'])
    expect(wrapper.findAll('nav a').map((a) => a.text()), 'order').toEqual(['Posteingang', 'Fälle', 'Einsprüche', 'Meldungen', 'Spielersuche', 'Aktive Strafen', 'Ausnahmeliste', 'VPN-Richtlinie', 'Beitrittsschutz', 'Gebietssperren'])

    wrapper.unmount()
  })

  it('shows the administration rail on an administration page for an admin', async () => {
    const wrapper = await mountApp('/admin/templates')

    expect(wrapper.findAll('nav [role="group"]').map((group) => group.find('p').text()), 'headings').toEqual(['Konfiguration', 'Prüfung', 'System'])
    expect(wrapper.findAll('nav a').map((a) => a.text()), 'order').toEqual([
      'Vorlagen',
      'Bereiche',
      'Client-Regeln',
      'Einspruchsbedingungen',
      'Schutzprüfungen',
      'Teamkonten',
      'Rollback',
      'Importe',
      'CloudNet',
      'Massen-Kick',
      'Audit-Protokoll',
    ])
    await wrapper.get('[data-testid="workspace-switcher"]').trigger('click')
    expect(wrapper.get('[data-testid="workspace-administration"]').attributes('aria-current')).toBe('true')

    wrapper.unmount()
  })

  it('shows no administration rail to a moderator on the inbox', async () => {
    const wrapper = await mountApp('/', { roles: ['MODERATOR'] })

    expect(wrapper.findAll('nav [role="group"]').map((group) => group.find('p').text())).toEqual(['Arbeit', 'Spieler', 'Schutz'])

    wrapper.unmount()
  })

  it('marks only the current destination with aria-current', async () => {
    const wrapper = await mountApp('/cases')

    expect(wrapper.findAll('nav a[aria-current="page"]').map((a) => a.text())).toEqual(['Fälle'])

    wrapper.unmount()
  })

  it('shows the not-found page for an unknown path after sign-in instead of waiting forever', async () => {
    const wrapper = await mountApp('/gibt-es-nicht')

    expect(wrapper.get('h1').text()).toBe('Seite nicht gefunden')
    expect(wrapper.get('[data-testid="auth-status"]').text(), 'status text').toBe('')
    expect(wrapper.get('[data-testid="back-to-dashboard"]').attributes('href')).toBe('/')
    expect(wrapper.find('nav').exists(), 'navigation stays available').toBe(true)

    wrapper.unmount()
  })

  it('folds the drawer into a rail and remembers the choice', async () => {
    const wrapper = await mountApp('/')
    const toggle = wrapper.get('[data-testid="nav-toggle"]')
    expect(toggle.attributes('aria-label')).toBe('Navigation einklappen')
    expect(wrapper.findAll('nav [role="group"]'), 'headings while expanded').toHaveLength(3)

    await toggle.trigger('click')

    expect(localStorage.getItem(NAV_COLLAPSED_STORAGE_KEY)).toBe('true')
    expect(toggle.attributes('aria-label')).toBe('Navigation ausklappen')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('nav').classes(), 'rail width').toContain('w-20')

    wrapper.unmount()
  })

  it('starts as a rail when the viewer collapsed the navigation before', async () => {
    localStorage.setItem(NAV_COLLAPSED_STORAGE_KEY, 'true')

    const wrapper = await mountApp('/')

    expect(wrapper.get('nav').classes()).toContain('w-20')

    wrapper.unmount()
  })

  it('works when localStorage throws', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })
    const wrapper = await mountApp('/')

    await wrapper.get('[data-testid="nav-toggle"]').trigger('click')

    expect(wrapper.get('nav').classes(), 'collapsed for this page').toContain('w-20')

    wrapper.unmount()
    vi.restoreAllMocks()
  })

  it('shows a rail without toggle on medium windows', async () => {
    vi.stubGlobal('matchMedia', fakeMatchMedia({ value: 900 }).matchMedia)

    const wrapper = await mountApp('/')

    expect(wrapper.get('nav').classes()).toContain('w-20')
    expect(wrapper.find('[data-testid="nav-toggle"]').exists(), 'toggle').toBe(false)

    wrapper.unmount()
  })

  it('opens a modal drawer from the menu button on compact windows and closes it on navigation', async () => {
    vi.stubGlobal('matchMedia', fakeMatchMedia({ value: 500 }).matchMedia)
    const wrapper = await mountApp('/')
    expect(wrapper.find('nav').exists(), 'closed at first').toBe(false)

    await wrapper.get('[data-testid="nav-toggle"]').trigger('click')
    expect(wrapper.get('[data-testid="nav-toggle"]').attributes('aria-expanded')).toBe('true')
    await wrapper.findAll('nav a').find((a) => a.text() === 'Spielersuche')!.trigger('click')
    await flushPromises()

    expect(wrapper.find('nav').exists(), 'closed after navigating').toBe(false)
    expect(wrapper.get('h1').text()).toBe('Spielersuche')

    wrapper.unmount()
  })

  it('closes the modal drawer with Escape and with the scrim', async () => {
    vi.stubGlobal('matchMedia', fakeMatchMedia({ value: 500 }).matchMedia)
    const wrapper = await mountApp('/')

    await wrapper.get('[data-testid="nav-toggle"]').trigger('click')
    await wrapper.get('nav').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('nav').exists(), 'after Escape').toBe(false)

    await wrapper.get('[data-testid="nav-toggle"]').trigger('click')
    await wrapper.get('[data-testid="nav-scrim"]').trigger('click')
    expect(wrapper.find('nav').exists(), 'after the scrim').toBe(false)

    wrapper.unmount()
  })

  it('puts the search into the top bar for a signed-in user with a role', async () => {
    const wrapper = await mountApp('/')

    expect(wrapper.find('header [data-testid="global-search"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('offers no search to a user without a role', async () => {
    const wrapper = await mountApp('/no-access', { roles: [] })

    expect(wrapper.find('[data-testid="global-search"]').exists()).toBe(false)

    wrapper.unmount()
  })
})
