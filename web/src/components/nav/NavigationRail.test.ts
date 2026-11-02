import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import NavigationRail, { type RailGroup } from './NavigationRail.vue'

const Empty = { render: () => null }
const icon = 'M0 0h24v24H0z'

const groups: RailGroup[] = [
  { key: 'today', heading: 'Heute', items: [{ to: '/', label: 'Posteingang', icon, badge: { text: '3', label: '3 Vorgänge' } }] },
  {
    key: 'work',
    heading: 'Arbeit',
    items: [
      { to: '/cases', label: 'Fälle', icon },
      { to: '/players', label: 'Spieler', icon },
    ],
  },
]

async function setup(props: Record<string, unknown> = {}, path = '/') {
  const router = createRouter({
    history: createMemoryHistory(),
    // A detail page names its destination in its route meta, as the app's router does.
    routes: [
      { path: '/', component: Empty },
      { path: '/cases', component: Empty },
      { path: '/players', component: Empty },
      { path: '/players/:owner', component: Empty, meta: { nav: '/players' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(NavigationRail, {
    props: { label: 'Hauptnavigation', groups, variant: 'expanded', ...props },
    global: { plugins: [router] },
    attachTo: document.body,
  })
  return { wrapper, router }
}

describe('NavigationRail', () => {
  it('is a labelled navigation landmark with headed groups in the expanded rail', async () => {
    const { wrapper } = await setup()

    expect(wrapper.get('nav').attributes('aria-label')).toBe('Hauptnavigation')
    const group = wrapper.get('[role="group"]')
    expect(group.attributes('aria-labelledby')).toBe(group.get('p').attributes('id'))
    expect(group.get('p').text()).toBe('Heute')
    expect(group.get('p').classes(), 'heading visible').not.toContain('sr-only')

    wrapper.unmount()
  })

  it('has a fixed width for the expanded and the collapsed rail, and no drawer width', async () => {
    const expanded = await setup({ variant: 'expanded' })
    expect(expanded.wrapper.get('nav').classes()).toContain('w-72')
    expanded.wrapper.unmount()

    const collapsed = await setup({ variant: 'collapsed' })
    expect(collapsed.wrapper.get('nav').classes()).toContain('w-20')
    collapsed.wrapper.unmount()
  })

  it('marks the current destination, also on a sub page, and no other', async () => {
    const { wrapper } = await setup({}, '/players/abc')

    expect(wrapper.findAll('a[aria-current="page"]').map((a) => a.text())).toEqual(['Spieler'])

    wrapper.unmount()
  })

  it('highlights the current destination with the secondary container colours in both layouts', async () => {
    for (const variant of ['expanded', 'collapsed']) {
      const { wrapper } = await setup({ variant }, '/cases')

      const current = wrapper.get('a[aria-current="page"]')
      const marked = variant === 'collapsed' ? current.get('span') : current
      expect(marked.classes(), variant).toContain('bg-secondary-container')
      expect(marked.classes(), variant).toContain('text-on-secondary-container')

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

  it('hides the group headings from sight in the collapsed rail but keeps them for screen readers', async () => {
    const { wrapper } = await setup({ variant: 'collapsed' })

    expect(wrapper.get('[role="group"] p').classes()).toContain('sr-only')

    wrapper.unmount()
  })

  it('renders a modal rail only while it is open and focuses the current destination', async () => {
    const { wrapper } = await setup({ variant: 'modal', open: false }, '/cases')
    expect(wrapper.find('nav').exists(), 'closed').toBe(false)

    await wrapper.setProps({ open: true })
    await flushPromises()

    expect(wrapper.find('nav').exists(), 'open').toBe(true)
    expect(wrapper.get('nav').attributes('aria-modal')).toBe('true')
    expect(document.activeElement?.textContent).toContain('Fälle')

    wrapper.unmount()
  })

  it('asks to close on Escape and on the scrim, and keeps Tab inside the modal rail', async () => {
    const { wrapper } = await setup({ variant: 'modal', open: true })
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

  it('gives every navigation link the shared focus ring and a 48px target', async () => {
    const { wrapper } = await setup()

    const links = wrapper.findAll('a')
    expect(links.length).toBeGreaterThan(0)
    for (const link of links) {
      expect(link.classes(), link.text()).toContain('focus-ring')
      expect(link.classes(), link.text()).toContain('min-h-12')
    }

    wrapper.unmount()
  })

  it('shows the count of a badge visibly and in the accessible label with the number', async () => {
    const { wrapper } = await setup()

    const badge = wrapper.get('[data-testid="nav-badge"]')
    expect(badge.text()).toBe('3')
    expect(badge.attributes('aria-hidden')).toBe('true')
    expect(wrapper.get('a[href="/"] .sr-only').text()).toBe('3 Vorgänge')

    wrapper.unmount()
  })

  it('puts the badge on the icon in the collapsed rail and beside the label in the expanded one', async () => {
    const collapsed = await setup({ variant: 'collapsed' })
    expect(collapsed.wrapper.get('[data-testid="nav-badge"]').classes(), 'on the icon').toContain('absolute')
    collapsed.wrapper.unmount()

    const expanded = await setup({ variant: 'expanded' })
    expect(expanded.wrapper.get('[data-testid="nav-badge"]').classes(), 'beside the label').not.toContain('absolute')
    expanded.wrapper.unmount()
  })

  it('offers a More button in the collapsed rail that asks for the other destinations', async () => {
    const { wrapper } = await setup({ variant: 'collapsed', more: { label: 'Mehr', expanded: false } })

    const more = wrapper.get('[data-testid="nav-more"]')
    expect(more.element.tagName).toBe('BUTTON')
    expect(more.attributes('aria-haspopup')).toBe('dialog')
    expect(more.attributes('aria-expanded')).toBe('false')
    expect(more.classes(), 'target').toContain('min-h-12')

    await more.trigger('click')

    expect(wrapper.emitted('more')).toHaveLength(1)

    wrapper.unmount()
  })

  it('offers no More button in the expanded rail, where every group is shown', async () => {
    const { wrapper } = await setup({ variant: 'expanded', more: { label: 'Mehr', expanded: false } })

    expect(wrapper.find('[data-testid="nav-more"]').exists()).toBe(false)

    wrapper.unmount()
  })
})
