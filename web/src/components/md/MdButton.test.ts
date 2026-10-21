import { mount } from '@vue/test-utils'
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
})
