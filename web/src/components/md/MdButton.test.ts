import { mount, RouterLinkStub } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import MdButton from './MdButton.vue'

describe('MdButton', () => {
  it('renders its slot with the styling of the chosen variant', () => {
    const wrapper = mount(MdButton, { props: { variant: 'tonal' }, slots: { default: 'Speichern' } })

    expect(wrapper.text()).toBe('Speichern')
    expect(wrapper.classes()).toContain('bg-secondary-container')
  })

  it('calls the click listener when pressed', async () => {
    const onClick = vi.fn()
    const wrapper = mount(MdButton, { attrs: { onClick }, slots: { default: 'Weiter' } })

    await wrapper.trigger('click')

    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('does not respond while disabled', async () => {
    const onClick = vi.fn()
    const wrapper = mount(MdButton, { props: { disabled: true }, attrs: { onClick }, slots: { default: 'Weiter' } })

    await wrapper.trigger('click')

    expect(wrapper.attributes('disabled')).toBeDefined()
    expect(onClick).not.toHaveBeenCalled()
  })

  it('renders the outlined variant with an outline and primary text', () => {
    const wrapper = mount(MdButton, { props: { variant: 'outlined' }, slots: { default: 'Alle Vorlagen' } })

    expect(wrapper.classes()).toEqual(expect.arrayContaining(['border-outline', 'text-primary']))
  })

  it('renders the error variant with the error roles for destructive confirms', () => {
    const wrapper = mount(MdButton, { props: { variant: 'error' }, slots: { default: 'Löschen' } })

    expect(wrapper.classes()).toEqual(expect.arrayContaining(['bg-error', 'text-on-error']))
  })

  it('renders as a router link with the same classes when told to navigate', () => {
    const wrapper = mount(MdButton, {
      props: { variant: 'tonal', as: 'link', to: '/cases' },
      slots: { default: 'Öffnen' },
      global: { stubs: { RouterLink: RouterLinkStub } },
    })

    const link = wrapper.findComponent(RouterLinkStub)
    expect(link.exists()).toBe(true)
    expect(link.props('to')).toBe('/cases')
    expect(link.classes()).toContain('bg-secondary-container')
  })

  it('takes its disabled look from the state-layer token, not a fixed opacity', () => {
    const wrapper = mount(MdButton, { props: { disabled: true }, slots: { default: 'Weiter' } })

    expect(wrapper.classes()).toContain('disabled:opacity-(--md-sys-state-disabled-opacity)')
    expect(wrapper.classes()).not.toContain('disabled:opacity-[0.38]')
  })
})
