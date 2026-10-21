import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MdIconButton from './MdIconButton.vue'

describe('MdIconButton', () => {
  it('names the button and its tooltip after the label and hides the icon from assistive technology', () => {
    const wrapper = mount(MdIconButton, { props: { label: 'Remove step 1', path: 'M0 0h24v24H0z' } })

    expect(wrapper.attributes('aria-label')).toBe('Remove step 1')
    expect(wrapper.attributes('title')).toBe('Remove step 1')
    expect(wrapper.get('svg').attributes('aria-hidden')).toBe('true')
    expect(wrapper.attributes('type')).toBe('button')
  })

  it('passes the disabled attribute on to the button', async () => {
    const wrapper = mount(MdIconButton, { props: { label: 'Up', path: 'M0 0' }, attrs: { disabled: true } })

    expect(wrapper.attributes('disabled')).toBeDefined()
  })
})
