import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import NavigationBar, { type BarItem } from './NavigationBar.vue'

const Empty = { render: () => null }
const icon = 'M0 0h24v24H0z'

const items: BarItem[] = [
  { to: '/', label: 'Posteingang', icon, badge: { text: '99+', label: '120 Vorgänge' } },
  { to: '/cases', label: 'Fälle', icon },
  { to: '/appeals', label: 'Einsprüche', icon },
  { to: '/reports', label: 'Meldungen', icon },
]

async function setup(props: Record<string, unknown> = {}, path = '/') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: Empty },
      { path: '/cases', component: Empty },
      { path: '/appeals', component: Empty },
      { path: '/reports', component: Empty },
    ],
  })
  await router.push(path)
  await router.isReady()
  return mount(NavigationBar, {
    props: { label: 'Hauptnavigation', items, more: { label: 'Mehr', expanded: false }, ...props },
    global: { plugins: [router] },
    attachTo: document.body,
  })
}

describe('NavigationBar', () => {
  it('is a labelled navigation landmark pinned to the bottom edge of a compact window', async () => {
    const wrapper = await setup()

    const bar = wrapper.get('nav')
    expect(bar.attributes('aria-label')).toBe('Hauptnavigation')
    expect(bar.classes()).toEqual(expect.arrayContaining(['fixed', 'inset-x-0', 'bottom-0']))

    wrapper.unmount()
  })

  it('shows the four primary destinations and More, five items in all', async () => {
    const wrapper = await setup()

    // The label is the visible text after the badge, which a bar item with a count also shows.
    const labels = wrapper.findAll('nav a').map((link) => link.get('.max-w-full').text())
    expect(labels).toEqual(['Posteingang', 'Fälle', 'Einsprüche', 'Meldungen'])
    expect(wrapper.findAll('nav > * ').length, 'links and More').toBe(5)
    expect(wrapper.get('[data-testid="nav-more"]').text()).toBe('Mehr')

    wrapper.unmount()
  })

  it('marks the current destination with aria-current', async () => {
    const wrapper = await setup({}, '/appeals')

    expect(wrapper.findAll('a[aria-current="page"]').map((link) => link.text())).toEqual(['Einsprüche'])

    wrapper.unmount()
  })

  it('shows a badge on the icon with the capped count, and the real number to a screen reader', async () => {
    const wrapper = await setup()

    const badge = wrapper.get('[data-testid="nav-badge"]')
    expect(badge.text()).toBe('99+')
    expect(badge.attributes('aria-hidden')).toBe('true')
    expect(wrapper.get('a[href="/"] .sr-only').text()).toBe('120 Vorgänge')

    wrapper.unmount()
  })

  it('gives every item a 48px target with the shared focus ring', async () => {
    const wrapper = await setup()

    for (const element of [...wrapper.findAll('a'), wrapper.get('[data-testid="nav-more"]')]) {
      expect(element.classes(), element.text()).toEqual(expect.arrayContaining(['min-h-12', 'focus-ring']))
    }

    wrapper.unmount()
  })

  it('asks for the other destinations with More and reports whether they are open', async () => {
    const wrapper = await setup({ more: { label: 'Mehr', expanded: true } })

    const more = wrapper.get('[data-testid="nav-more"]')
    expect(more.attributes('aria-haspopup')).toBe('dialog')
    expect(more.attributes('aria-expanded')).toBe('true')
    await more.trigger('click')

    expect(wrapper.emitted('more')).toHaveLength(1)

    wrapper.unmount()
  })
})
