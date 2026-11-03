import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { defineComponent, h, inject, type InjectionKey, type Ref, ref } from 'vue'
import { createMemoryHistory, createRouter, RouterView, useRoute, type Router } from 'vue-router'
import RoutedDialog from '../components/RoutedDialog.vue'
import { i18n } from '../i18n'

const KNOWN_IDS: InjectionKey<Ref<string[]>> = Symbol('known ids')

const ReportPage = defineComponent({
  setup() {
    return () => h('section', { 'data-testid': 'report-page' }, [h('h1', 'Report page'), h(RouterView)])
  },
})

/** A child route that renders the dialog over the report page; the id decides whether the item exists. */
const ResolveDialog = defineComponent({
  setup() {
    const route = useRoute()
    const known = inject(KNOWN_IDS, ref([]))
    return () =>
      h(
        RoutedDialog,
        {
          parent: 'report',
          child: 'report-resolve',
          headline: 'Resolve the report',
          notFound: !known.value.includes(String(route.params.id)),
        },
        {
          default: () => h('p', { 'data-testid': 'resolve-form' }, 'Choose a resolution'),
        },
      )
  },
})

const App = defineComponent({
  setup() {
    return () => h(RouterView)
  },
})

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/reports/:id',
        name: 'report',
        component: ReportPage,
        children: [{ path: 'resolve', name: 'report-resolve', component: ResolveDialog }],
      },
      // The start location of the memory history: no test needs a page here.
      { path: '/:pathMatch(.*)*', name: 'start', component: defineComponent(() => () => h('p', 'start')) },
    ],
  })
}

async function setup(known: string[] = ['A1']): Promise<{ wrapper: VueWrapper; router: Router }> {
  i18n.global.locale.value = 'en'
  const router = makeRouter()
  const wrapper = mount(App, {
    global: {
      plugins: [i18n, router],
      provide: { [KNOWN_IDS as symbol]: ref(known) },
    },
    attachTo: document.body,
  })
  return { wrapper, router }
}

function dialog(wrapper: VueWrapper) {
  return wrapper.find('[role="dialog"]')
}

describe('a child route renders its dialog over the parent page', () => {
  it('shows the parent page and the dialog of the child route', async () => {
    const { wrapper, router } = await setup()

    await router.push('/reports/A1')
    await router.push('/reports/A1/resolve')
    await flushPromises()

    expect(wrapper.find('[data-testid="report-page"]').exists(), 'the parent page stays rendered').toBe(true)
    expect(dialog(wrapper).exists(), 'the dialog of the child route is open').toBe(true)
    expect(dialog(wrapper).text()).toContain('Resolve the report')
    wrapper.unmount()
  })

  it('closes the dialog when Back is pressed and keeps the parent page', async () => {
    const { wrapper, router } = await setup()

    await router.push('/reports/A1')
    await router.push('/reports/A1/resolve')
    await flushPromises()
    router.back()
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/reports/A1')
    expect(dialog(wrapper).exists(), 'Back closes the dialog').toBe(false)
    expect(wrapper.find('[data-testid="report-page"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('opens the dialog for the address typed or reloaded directly', async () => {
    const { wrapper, router } = await setup()

    await router.push('/reports/A1/resolve')
    await flushPromises()

    expect(dialog(wrapper).exists(), 'a reload of the child address reopens the dialog').toBe(true)
    wrapper.unmount()
  })

  it('navigates to the parent route with its query when the dialog is closed', async () => {
    const { wrapper, router } = await setup()

    await router.push('/reports/A1/resolve?from=list')
    await flushPromises()
    await wrapper.get('[data-testid="routed-dialog-close"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/reports/A1?from=list')
    expect(dialog(wrapper).exists()).toBe(false)
    wrapper.unmount()
  })

  it('closes the dialog with Escape through the same route change', async () => {
    const { wrapper, router } = await setup()

    await router.push('/reports/A1/resolve')
    await flushPromises()
    await dialog(wrapper).trigger('keydown', { key: 'Escape' })
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/reports/A1')
    wrapper.unmount()
  })
})

describe('a child route for an unknown item', () => {
  it('shows the not-found text and no form instead of an empty dialog', async () => {
    const { wrapper, router } = await setup(['A1'])

    await router.push('/reports/ZZ9/resolve')
    await flushPromises()

    expect(dialog(wrapper).text()).toContain('This item does not exist or was removed.')
    expect(wrapper.find('[data-testid="resolve-form"]').exists(), 'no form for a missing item').toBe(false)
    wrapper.unmount()
  })

  it('links back to the parent page from the not-found text', async () => {
    const { wrapper, router } = await setup(['A1'])

    await router.push('/reports/ZZ9/resolve')
    await flushPromises()
    await wrapper.get('[data-testid="routed-dialog-back"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/reports/ZZ9')
    wrapper.unmount()
  })
})
