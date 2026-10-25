import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { i18n } from '../i18n'
import PageShell from './PageShell.vue'

function mounting(props: { title: string; description?: string }, slots: Record<string, string> = {}) {
  i18n.global.locale.value = 'en'
  return mount(PageShell, { props, slots, attachTo: document.body })
}

describe('PageShell', () => {
  it('renders the title as the only level-one heading', () => {
    const wrapper = mounting({ title: 'Cases' }, { default: '<p>content</p>' })

    const headings = wrapper.findAll('h1')
    expect(headings).toHaveLength(1)
    expect(headings[0]!.text()).toBe('Cases')

    wrapper.unmount()
  })

  it('places the slots in the order breadcrumbs, title, description, actions, toolbar, content, aside', () => {
    const wrapper = mounting(
      { title: 'Cases', description: 'Open work' },
      {
        breadcrumbs: '<nav data-testid="s-breadcrumbs"></nav>',
        description: '<p data-testid="s-description-slot"></p>',
        actions: '<button data-testid="s-actions"></button>',
        toolbar: '<div data-testid="s-toolbar"></div>',
        default: '<p data-testid="s-content"></p>',
        aside: '<aside data-testid="s-aside"></aside>',
      },
    )

    const html = wrapper.html()
    const order = [
      html.indexOf('data-testid="s-breadcrumbs"'),
      html.indexOf('<h1'),
      html.indexOf('Open work'),
      html.indexOf('data-testid="s-actions"'),
      html.indexOf('data-testid="s-toolbar"'),
      html.indexOf('data-testid="s-content"'),
      html.indexOf('data-testid="s-aside"'),
    ]
    order.forEach((position) => expect(position, 'every slot is rendered').toBeGreaterThan(-1))
    expect(order, 'slots in template order').toEqual([...order].sort((a, b) => a - b))

    wrapper.unmount()
  })

  it('renders no description, toolbar or aside when none is given', () => {
    const wrapper = mounting({ title: 'Cases' }, { default: '<p>content</p>' })

    expect(wrapper.find('[data-testid="page-description"]').exists()).toBe(false)
    expect(wrapper.find('aside').exists()).toBe(false)
    expect(wrapper.find('[data-testid="page-toolbar"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows the description when one is given', () => {
    const wrapper = mounting({ title: 'Cases', description: 'Open work' })

    expect(wrapper.get('[data-testid="page-description"]').text()).toBe('Open work')

    wrapper.unmount()
  })
})
