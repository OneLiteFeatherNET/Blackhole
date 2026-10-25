import { mount, RouterLinkStub } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import TextLink from './TextLink.vue'

describe('TextLink', () => {
  it('renders a router link to its destination with the shared text-link classes', () => {
    const wrapper = mount(TextLink, {
      props: { to: '/players/Steve' },
      slots: { default: 'Steve' },
      global: { stubs: { RouterLink: RouterLinkStub } },
    })

    const link = wrapper.findComponent(RouterLinkStub)
    expect(link.exists()).toBe(true)
    expect(link.props('to')).toBe('/players/Steve')
    expect(link.classes()).toEqual(expect.arrayContaining(['text-primary', 'underline', 'underline-offset-2']))
    expect(wrapper.text()).toBe('Steve')
  })

  it('renders a plain anchor when given an href instead of a route', () => {
    const wrapper = mount(TextLink, { props: { href: 'https://example.org/regeln' }, slots: { default: 'Regeln' } })

    expect(wrapper.element.tagName).toBe('A')
    expect(wrapper.attributes('href')).toBe('https://example.org/regeln')
  })

  it('keeps a visible focus ring for keyboard users', () => {
    const wrapper = mount(TextLink, { props: { href: '/x' }, slots: { default: 'x' } })

    expect(wrapper.classes()).toEqual(expect.arrayContaining(['focus-visible:outline-2', 'focus-visible:outline-primary']))
  })
})
