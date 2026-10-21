import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import MdNavigationRail from './MdNavigationRail.vue'

const Empty = { render: () => null }

function setup() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: Empty },
      { path: '/players', component: Empty },
    ],
  })
  const items = [
    { to: '/', label: 'Übersicht', icon: 'M0 0h24v24H0z' },
    { to: '/players', label: 'Spieler', icon: 'M0 0h24v24H0z' },
  ]
  return { router, items }
}

describe('MdNavigationRail', () => {
  it('lists the destinations and marks the current one as the page', async () => {
    const { router, items } = setup()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(MdNavigationRail, {
      props: { label: 'Hauptnavigation', items },
      global: { plugins: [router] },
    })

    expect(wrapper.get('nav').attributes('aria-label')).toBe('Hauptnavigation')
    expect(wrapper.findAll('a').map((link) => link.text())).toEqual(['Übersicht', 'Spieler'])
    expect(wrapper.get('a[aria-current="page"]').text()).toBe('Übersicht')
  })

  it('navigates when a destination is activated', async () => {
    const { router, items } = setup()
    await router.push('/')
    await router.isReady()
    const wrapper = mount(MdNavigationRail, {
      props: { label: 'Hauptnavigation', items },
      global: { plugins: [router] },
    })

    await wrapper.findAll('a')[1]!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/players')
    expect(wrapper.get('a[aria-current="page"]').text()).toBe('Spieler')
  })
})
