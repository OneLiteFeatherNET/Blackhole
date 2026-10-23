import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import MdNavigationDrawer, { type DrawerGroup } from './MdNavigationDrawer.vue'

const Empty = { render: () => null }
const icon = 'M0 0h24v24H0z'

const groups: DrawerGroup[] = [
  { key: 'overview', items: [{ to: '/', label: 'Übersicht', icon }] },
  {
    key: 'moderation',
    heading: 'Moderation',
    items: [
      { to: '/cases', label: 'Fälle', icon },
      { to: '/players', label: 'Spieler', icon },
    ],
  },
]

async function setup(props: Record<string, unknown> = {}, path = '/') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: ['/', '/cases', '/players'].map((p) => ({ path: p, component: Empty })).concat([{ path: '/players/:owner', component: Empty }]),
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(MdNavigationDrawer, {
    props: { label: 'Hauptnavigation', groups, mode: 'drawer', ...props },
    global: { plugins: [router] },
    attachTo: document.body,
  })
  return { wrapper, router }
}

describe('MdNavigationDrawer', () => {
  it('is a labelled navigation landmark with headed groups', async () => {
    const { wrapper } = await setup()

    expect(wrapper.get('nav').attributes('aria-label')).toBe('Hauptnavigation')
    const group = wrapper.get('[role="group"]')
    expect(group.attributes('aria-labelledby')).toBe(group.get('p').attributes('id'))
    expect(group.get('p').text()).toBe('Moderation')

    wrapper.unmount()
  })

  it('marks the current destination, also on a sub page, and no other', async () => {
    const { wrapper } = await setup({}, '/players/abc')

    expect(wrapper.findAll('a[aria-current="page"]').map((a) => a.text())).toEqual(['Spieler'])

    wrapper.unmount()
  })

  it('highlights the current destination with the secondary container colours in both layouts', async () => {
    for (const mode of ['drawer', 'rail']) {
      const { wrapper } = await setup({ mode }, '/cases')

      const current = wrapper.get('a[aria-current="page"]')
      const marked = mode === 'rail' ? current.get('span') : current
      expect(marked.classes(), mode).toContain('bg-secondary-container')
      expect(marked.classes(), mode).toContain('text-on-secondary-container')

      wrapper.unmount()
    }
  })

  it('moves the focus with the arrow keys, wrapping around, and with Home and End', async () => {
    const { wrapper } = await setup()
    const links = wrapper.findAll('a').map((a) => a.element as HTMLElement)
    links[0]!.focus()

    await wrapper.get('nav').trigger('keydown', { key: 'ArrowDown' })
    expect(document.activeElement).toBe(links[1])
    await wrapper.get('nav').trigger('keydown', { key: 'End' })
    expect(document.activeElement, 'End').toBe(links[2])
    await wrapper.get('nav').trigger('keydown', { key: 'ArrowDown' })
    expect(document.activeElement, 'wrapped').toBe(links[0])
    await wrapper.get('nav').trigger('keydown', { key: 'ArrowUp' })
    expect(document.activeElement, 'wrapped backwards').toBe(links[2])

    wrapper.unmount()
  })

  it('hides the group headings from sight in the rail but keeps them for screen readers', async () => {
    const { wrapper } = await setup({ mode: 'rail' })

    expect(wrapper.get('[role="group"] p').classes()).toContain('sr-only')

    wrapper.unmount()
  })

  it('renders a modal drawer only while it is open and focuses the current destination', async () => {
    const { wrapper } = await setup({ mode: 'modal', open: false }, '/cases')
    expect(wrapper.find('nav').exists(), 'closed').toBe(false)

    await wrapper.setProps({ open: true })
    await flushPromises()

    expect(wrapper.find('nav').exists(), 'open').toBe(true)
    expect(document.activeElement?.textContent).toContain('Fälle')

    wrapper.unmount()
  })

  it('asks to close on Escape and on the scrim, and keeps Tab inside the modal drawer', async () => {
    const { wrapper } = await setup({ mode: 'modal', open: true })
    await flushPromises()
    const links = wrapper.findAll('a').map((a) => a.element as HTMLElement)

    links[links.length - 1]!.focus()
    await wrapper.get('nav').trigger('keydown', { key: 'Tab' })
    expect(document.activeElement, 'Tab at the end').toBe(links[0])
    await wrapper.get('nav').trigger('keydown', { key: 'Escape' })
    await wrapper.get('[data-testid="nav-scrim"]').trigger('click')

    expect(wrapper.emitted('close')).toHaveLength(2)

    wrapper.unmount()
  })
})
