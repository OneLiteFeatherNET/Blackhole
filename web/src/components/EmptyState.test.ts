import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import EmptyState from './EmptyState.vue'

describe('EmptyState', () => {
  it('renders its title and sentence, and the next step in the default slot', () => {
    const wrapper = mount(EmptyState, {
      props: { variant: 'no-items', title: 'Noch keine Fälle', description: 'Ein Fall entsteht aus einem Report.' },
      slots: { default: '<a href="/reports">Zu den Reports</a>' },
    })

    expect(wrapper.get('h3').text()).toBe('Noch keine Fälle')
    expect(wrapper.text()).toContain('Ein Fall entsteht aus einem Report.')
    expect(wrapper.get('a').attributes('href')).toBe('/reports')
  })

  it('hides its decorative icon from assistive technology', () => {
    const wrapper = mount(EmptyState, { props: { variant: 'no-matches', title: 'Keine Treffer' } })

    expect(wrapper.get('svg').attributes('aria-hidden')).toBe('true')
  })

  it('shows a different icon for each variant', () => {
    const paths = (['no-items', 'no-matches', 'no-access'] as const).map((variant) =>
      mount(EmptyState, { props: { variant, title: 'Leer' } }).get('svg path').attributes('d'),
    )

    expect(new Set(paths).size).toBe(3)
  })

  it('marks its variant on the root element for styling and tests', () => {
    const wrapper = mount(EmptyState, { props: { variant: 'no-access', title: 'Kein Zugriff' } })

    expect(wrapper.attributes('data-variant')).toBe('no-access')
  })
})
