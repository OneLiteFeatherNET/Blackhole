import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import App from './App.vue'
import { API_KEY } from './api/inject'
import { AUTH_KEY } from './auth/inject'
import { i18n } from './i18n'
import { TICKER_KEY } from './nav/ticker'
import { createAppRouter } from './router'
import { LOCALE_STORAGE_KEY } from './stores/ui'
import { fakeAuthService } from './test/auth'
import { mockApi, ok, piniaWithRoles } from './test/mount'
import { fakeTicker } from './test/ticker'

interface Numbers {
  cases: number
  appeals: number
  reports: number
}

async function mountApp(numbers: Numbers, path = '/players') {
  const current = { ...numbers }
  const dashboard = vi.fn(async () => ok({ activePunishmentsBySection: [], latestEscalations: [], pendingAppeals: current.appeals, openReports: current.reports }))
  const cases = vi.fn(async () => ok({ items: [], page: 0, size: 1, totalSize: current.cases, totalPages: current.cases }))
  const clock = fakeTicker()
  const router = createAppRouter(createMemoryHistory())
  await router.push(path)
  await router.isReady()
  const wrapper = mount(App, {
    global: {
      plugins: [piniaWithRoles(), i18n, router],
      provide: {
        [AUTH_KEY as symbol]: fakeAuthService().service,
        [API_KEY as symbol]: mockApi({
          dashboard,
          cases,
          profile: async () => ok({ active: {}, history: [] }),
          staffLinkStatus: async () => ok({ linked: false }),
        }),
        [TICKER_KEY as symbol]: clock.ticker,
      },
    },
    attachTo: document.body,
  })
  await flushPromises()
  return { wrapper, router, clock, dashboard, cases, set: (next: Partial<Numbers>) => Object.assign(current, next) }
}

const link = (wrapper: Awaited<ReturnType<typeof mountApp>>['wrapper'], label: string) => wrapper.findAll('nav a').find((a) => a.text().includes(label))!

describe('navigation badges', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem(LOCALE_STORAGE_KEY, 'de')
  })

  it('shows the open cases, appeals awaiting review and unresolved reports', async () => {
    const { wrapper } = await mountApp({ cases: 7, appeals: 3, reports: 5 })

    expect(link(wrapper, 'Fälle').get('[data-testid="nav-badge"]').text()).toBe('7')
    expect(link(wrapper, 'Einsprüche').get('[data-testid="nav-badge"]').text()).toBe('3')
    expect(link(wrapper, 'Meldungen').get('[data-testid="nav-badge"]').text()).toBe('5')
    expect(link(wrapper, 'Spieler').find('[data-testid="nav-badge"]').exists(), 'players has no count').toBe(false)

    wrapper.unmount()
  })

  it('states the number in the accessible label, with singular and plural', async () => {
    const { wrapper } = await mountApp({ cases: 1, appeals: 3, reports: 5 })

    expect(link(wrapper, 'Fälle').get('.sr-only').text()).toBe('1 offener Fall')
    expect(link(wrapper, 'Einsprüche').get('.sr-only').text()).toBe('3 Einsprüche warten auf Prüfung')
    expect(link(wrapper, 'Meldungen').get('.sr-only').text()).toBe('5 ungelöste Meldungen')
    expect(link(wrapper, 'Fälle').get('[data-testid="nav-badge"]').attributes('aria-hidden'), 'visible text hidden from the reader').toBe('true')

    wrapper.unmount()
  })

  it('hides a badge at 0', async () => {
    const { wrapper } = await mountApp({ cases: 0, appeals: 0, reports: 2 })

    expect(wrapper.findAll('[data-testid="nav-badge"]')).toHaveLength(1)
    expect(link(wrapper, 'Fälle').text()).toBe('Fälle')

    wrapper.unmount()
  })

  it('caps the visible text at 99+ but keeps the real number in the label', async () => {
    const { wrapper } = await mountApp({ cases: 99, appeals: 100, reports: 1234 })

    expect(link(wrapper, 'Fälle').get('[data-testid="nav-badge"]').text(), '99').toBe('99')
    expect(link(wrapper, 'Einsprüche').get('[data-testid="nav-badge"]').text(), '100').toBe('99+')
    expect(link(wrapper, 'Meldungen').get('[data-testid="nav-badge"]').text(), '1234').toBe('99+')
    expect(link(wrapper, 'Meldungen').get('.sr-only').text()).toBe('1234 ungelöste Meldungen')

    wrapper.unmount()
  })

  it('shows the badge on the icon in the collapsed rail too', async () => {
    localStorage.setItem('blackhole.navCollapsed', 'true')

    const { wrapper } = await mountApp({ cases: 4, appeals: 0, reports: 0 })

    expect(link(wrapper, 'Fälle').get('[data-testid="nav-badge"]').text()).toBe('4')

    wrapper.unmount()
  })

  it('refreshes once a minute through the injected timer', async () => {
    const { wrapper, clock, dashboard, cases, set } = await mountApp({ cases: 1, appeals: 1, reports: 1 })
    expect(clock.periods).toEqual([60_000])
    expect(dashboard).toHaveBeenCalledTimes(1)

    set({ cases: 12, reports: 0 })
    clock.tick()
    await flushPromises()

    expect(dashboard).toHaveBeenCalledTimes(2)
    expect(cases).toHaveBeenCalledTimes(2)
    expect(link(wrapper, 'Fälle').get('[data-testid="nav-badge"]').text()).toBe('12')
    expect(link(wrapper, 'Meldungen').find('[data-testid="nav-badge"]').exists(), 'reports now zero').toBe(false)

    wrapper.unmount()
    expect(clock.periods, 'timer stopped').toEqual([])
  })

  it('refreshes when the user navigates', async () => {
    const { wrapper, router, cases, set } = await mountApp({ cases: 1, appeals: 1, reports: 1 })

    set({ appeals: 9 })
    await router.push('/')
    await flushPromises()

    expect(cases).toHaveBeenCalledTimes(2)
    expect(link(wrapper, 'Einsprüche').get('[data-testid="nav-badge"]').text()).toBe('9')

    wrapper.unmount()
  })
})
