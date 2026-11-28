import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { defineComponent, h, inject, type InjectionKey, type Ref, ref } from 'vue'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import MdButton from '../components/md/MdButton.vue'
import RoutedDialog from '../components/RoutedDialog.vue'
import { i18n } from '../i18n'
import { useRoutedAction } from './routedDialog'

// The fake case page: it has read version 7 of the case, and it records every change that is sent.
interface Fixture {
  version: Ref<number | null>
  sent: number[]
  fail: boolean
  pending: Array<() => void>
}

const FIXTURE: InjectionKey<Fixture> = Symbol('fixture')

const CasePage = defineComponent({
  setup() {
    return () => h('section', { 'data-testid': 'case-page' }, [h('h1', 'Case page'), h(RouterView)])
  },
})

/** The close action of a case: a child route whose confirm sends the version that was read. */
const CloseDialog = defineComponent({
  setup() {
    const fixture = inject(FIXTURE)!
    const action = useRoutedAction({
      parent: 'case',
      child: 'case-close',
      version: fixture.version,
      send: (expectedVersion) => {
        fixture.sent.push(expectedVersion)
        if (fixture.fail) return Promise.reject(new Error('stale version'))
        return new Promise<void>((resolve) => fixture.pending.push(resolve))
      },
    })
    return () =>
      h(
        RoutedDialog,
        { parent: 'case', child: 'case-close', headline: 'Close the case', failed: action.failed.value },
        {
          default: () => h('p', 'Closing sends the change once.'),
          actions: () => h(MdButton, { 'data-testid': 'confirm-action', onClick: action.confirm }, () => 'Close case'),
        },
      )
  },
})

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/cases/:ref',
        name: 'case',
        component: CasePage,
        children: [{ path: 'close', name: 'case-close', component: CloseDialog }],
      },
      { path: '/:pathMatch(.*)*', name: 'start', component: defineComponent(() => () => h('p', 'start')) },
    ],
  })
}

async function setup(version: number | null = 7, fail = false): Promise<{ wrapper: VueWrapper; router: Router; fixture: Fixture }> {
  i18n.global.locale.value = 'en'
  const router = makeRouter()
  const fixture: Fixture = { version: ref(version), sent: [], fail, pending: [] }
  const wrapper = mount(
    defineComponent(() => () => h(RouterView)),
    { global: { plugins: [i18n, router], provide: { [FIXTURE as symbol]: fixture } }, attachTo: document.body },
  )
  await router.push('/cases/A1')
  await flushPromises()
  return { wrapper, router, fixture }
}

function dialog(wrapper: VueWrapper) {
  return wrapper.find('[role="dialog"]')
}

describe('an action route never performs the action on navigation', () => {
  it('sends no request when the action address is opened', async () => {
    const { wrapper, router, fixture } = await setup()

    await router.push('/cases/A1/close')
    await flushPromises()

    expect(fixture.sent, 'opening /cases/:ref/close must not change the case').toEqual([])
    expect(dialog(wrapper).text()).toContain('Close the case')
    wrapper.unmount()
  })

  it('sends no request when Back closes the confirmation', async () => {
    const { wrapper, router, fixture } = await setup()

    await router.push('/cases/A1/close')
    await flushPromises()
    router.back()
    await flushPromises()

    expect(fixture.sent).toEqual([])
    expect(dialog(wrapper).exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('the confirmed change carries the version that was read', () => {
  it('sends the version read with the case and closes the dialog', async () => {
    const { wrapper, router, fixture } = await setup(7)

    await router.push('/cases/A1/close')
    await flushPromises()
    await wrapper.get('[data-testid="confirm-action"]').trigger('click')
    fixture.pending.forEach((resolve) => resolve())
    await flushPromises()

    expect(fixture.sent).toEqual([7])
    expect(router.currentRoute.value.fullPath).toBe('/cases/A1')
    wrapper.unmount()
  })

  it('sends nothing when no version was read', async () => {
    const { wrapper, router, fixture } = await setup(null)

    await router.push('/cases/A1/close')
    await flushPromises()
    await wrapper.get('[data-testid="confirm-action"]').trigger('click')
    await flushPromises()

    expect(fixture.sent, 'a change without a known version is refused').toEqual([])
    expect(dialog(wrapper).exists()).toBe(true)
    wrapper.unmount()
  })

  it('sends a second confirm only after the first one has finished', async () => {
    const { wrapper, router, fixture } = await setup(7)

    await router.push('/cases/A1/close')
    await flushPromises()
    await wrapper.get('[data-testid="confirm-action"]').trigger('click')
    await wrapper.get('[data-testid="confirm-action"]').trigger('click')
    await flushPromises()

    expect(fixture.sent, 'a double click sends one change').toEqual([7])
    wrapper.unmount()
  })

  it('keeps the dialog open and says so when the change is refused', async () => {
    const { wrapper, router, fixture } = await setup(7, true)

    await router.push('/cases/A1/close')
    await flushPromises()
    await wrapper.get('[data-testid="confirm-action"]').trigger('click')
    await flushPromises()

    expect(fixture.sent).toEqual([7])
    expect(router.currentRoute.value.fullPath).toBe('/cases/A1/close')
    expect(wrapper.get('[data-testid="routed-dialog-failed"]').text()).toBe(
      'The change was not saved. Reload the page to see the current version.',
    )
    wrapper.unmount()
  })
})
