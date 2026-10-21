import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import MdCheckbox from './MdCheckbox.vue'

describe('MdCheckbox', () => {
  it('associates its label with the checkbox and emits the new state', async () => {
    const onUpdate = vi.fn()
    const wrapper = mount(MdCheckbox, {
      props: { label: 'Enabled', modelValue: false, 'onUpdate:modelValue': onUpdate },
    })
    const input = wrapper.get('input')

    expect(wrapper.get('label').attributes('for')).toBe(input.attributes('id'))

    await input.setValue(true)

    expect(onUpdate).toHaveBeenLastCalledWith(true)
  })

  it('links the supporting text for assistive technology', () => {
    const wrapper = mount(MdCheckbox, { props: { label: 'Enabled', modelValue: true, supportingText: 'Disabled sections punish nobody' } })

    expect(wrapper.get('input').attributes('aria-describedby')).toBe(wrapper.get('p').attributes('id'))
    expect((wrapper.get('input').element as HTMLInputElement).checked).toBe(true)
  })
})
