import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MdCard from './MdCard.vue'

describe('MdCard', () => {
  it('renders its content', () => {
    const wrapper = mount(MdCard, { slots: { default: '<p>Inhalt</p>' } })

    expect(wrapper.get('p').text()).toBe('Inhalt')
  })

  it('applies the elevated variant as a shadow on the container color', () => {
    const wrapper = mount(MdCard, { props: { variant: 'elevated' }, slots: { default: 'x' } })

    expect(wrapper.classes()).toEqual(expect.arrayContaining(['bg-surface-container-low', 'shadow-md-1']))
  })
})
