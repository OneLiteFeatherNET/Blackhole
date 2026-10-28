// Where motion is wired. Transitions are stubbed (the default of Vue Test Utils), so nothing waits:
// a stub carries the `name` the real transition would apply, which is what these tests read.
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import MdDataTable from '../components/md/MdDataTable.vue'
import MdDialog from '../components/md/MdDialog.vue'
import MdNavigationDrawer from '../components/md/MdNavigationDrawer.vue'
import MdSelect from '../components/md/MdSelect.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import AccountMenu from '../components/account/AccountMenu.vue'
import { i18n } from '../i18n'
import { createAppRouter } from '../router'
import { createMemoryHistory } from 'vue-router'
import { stubReducedMotion } from '../test/reducedMotion'
import { createPinia } from 'pinia'
import App from '../App.vue'
import { API_KEY } from '../api/inject'
import { AUTH_KEY } from '../auth/inject'
import { fakeAuthService } from '../test/auth'
import { mockApi, ok, piniaWithRoles } from '../test/mount'

const mounted: { unmount: () => void }[] = []
function track<T extends { unmount: () => void }>(wrapper: T): T {
  mounted.push(wrapper)
  return wrapper
}

afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.unstubAllGlobals()
})

const transitionName = (wrapper: { get: (selector: string) => { attributes: (name: string) => string | undefined } }) =>
  wrapper.get('transition-stub').attributes('name')

async function mountDrawer() {
  i18n.global.locale.value = 'en'
  const router = createAppRouter(createMemoryHistory())
  await router.push('/')
  await router.isReady()
  return track(
    mount(MdNavigationDrawer, {
      props: {
        label: 'Navigation',
        mode: 'modal',
        open: true,
        groups: [{ key: 'main', items: [{ to: '/', label: 'Overview', icon: 'M0 0', badge: { text: '3', label: '3 open' } }] }],
      },
      global: { plugins: [createPinia(), i18n, router] },
      attachTo: document.body,
    }),
  )
}

/** Each case builds the same component twice: once as usual, once under reduced motion. */
const cases: { name: string; pattern: string; build: () => Promise<{ get: (selector: string) => { attributes: (name: string) => string | undefined } }> }[] = [
  { name: 'dialog', pattern: 'md-scale', build: async () => track(mount(MdDialog, { props: { open: true, headline: 'Revoke' }, attachTo: document.body })) },
  { name: 'snackbar', pattern: 'md-snackbar', build: async () => track(mount(MdSnackbar, { props: { open: true, message: 'Saved' } })) },
  { name: 'modal navigation drawer', pattern: 'md-modal', build: mountDrawer },
  {
    name: 'account menu',
    pattern: 'md-menu',
    build: async () => {
      i18n.global.locale.value = 'en'
      const wrapper = track(mount(AccountMenu, { props: { displayName: 'Dev' }, global: { plugins: [piniaWithRoles(), i18n] }, attachTo: document.body }))
      await wrapper.get('[data-testid="account-menu"]').trigger('click')
      return wrapper
    },
  },
  {
    name: 'select menu',
    pattern: 'md-menu',
    build: async () => {
      const wrapper = track(mount(MdSelect, { props: { label: 'Theme', modelValue: 'a', options: [{ value: 'a', label: 'A' }] }, attachTo: document.body }))
      await wrapper.get('[role="combobox"]').trigger('click')
      return { get: (selector: string) => ({ attributes: (name: string) => document.body.querySelector(selector)?.getAttribute(name) ?? undefined }) }
    },
  },
]

describe('motion wiring', () => {
  describe.each(cases)('$name', ({ pattern, build }) => {
    it(`uses the ${pattern} transition`, async () => {
      stubReducedMotion(false)
      expect(transitionName(await build())).toBe(pattern)
    })

    it('is a plain fade under reduced motion', async () => {
      stubReducedMotion(true)
      expect(transitionName(await build())).toBe('md-fade')
    })
  })

  it('fades the page content of a screen in and out while it loads', () => {
    stubReducedMotion(false)
    i18n.global.locale.value = 'en'
    const wrapper = track(mount(ResourceState, { props: { loading: true, error: null, hasData: true }, slots: { default: '<p>data</p>' }, global: { plugins: [i18n] } }))

    const names = wrapper.findAll('transition-stub').map((stub) => stub.attributes('name'))
    expect(names).toEqual(['md-fade', 'md-fade'])
    expect(wrapper.get('transition-stub[appear="true"]').text()).toBe('data')
  })

  it('staggers table rows by their position', () => {
    const wrapper = track(
      mount(MdDataTable, { props: { label: 'Rows', columns: [{ key: 'name', label: 'Name' }], rows: [{ name: 'a' }, { name: 'b' }] } }),
    )

    const rows = wrapper.findAll('tbody tr')
    expect(rows.map((row) => row.classes('md-enter'))).toEqual([true, true])
    expect(rows.map((row) => row.attributes('style'))).toEqual(['--md-i: 0;', '--md-i: 1;'])
  })

  it('fades one screen through the next in the router view', async () => {
    stubReducedMotion(false)
    i18n.global.locale.value = 'en'
    const router = createAppRouter(createMemoryHistory())
    await router.push('/players')
    await router.isReady()
    const wrapper = track(
      mount(App, {
        global: {
          plugins: [piniaWithRoles(), i18n, router],
          provide: { [AUTH_KEY as symbol]: fakeAuthService().service, [API_KEY as symbol]: mockApi({ staffLinkStatus: async () => ok({ linked: false }), discordLinkStatus: async () => ok({ linked: false }), proxyCapabilities: async () => ok({ proxies: [] }) }) },
        },
        attachTo: document.body,
      }),
    )
    await flushPromises()

    const main = wrapper.get('main')
    const stub = main.get('transition-stub')
    expect(stub.attributes('name')).toBe('md-fade-through')
    expect(stub.attributes('mode')).toBe('out-in')
  })
})
