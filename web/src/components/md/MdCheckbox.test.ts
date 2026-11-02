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

describe('MdCheckbox: selection states', () => {
  it('shows the indeterminate state on the native checkbox, which has no attribute for it', () => {
    const wrapper = mount(MdCheckbox, { props: { label: 'Select all', modelValue: false, indeterminate: true } })

    expect((wrapper.get('input').element as HTMLInputElement).indeterminate).toBe(true)
  })

  it('follows the parent when the mixed state is resolved after the user changed the box', async () => {
    const wrapper = mount(MdCheckbox, { props: { label: 'Select all', modelValue: false, indeterminate: true } })
    const input = wrapper.get('input')

    await input.setValue(true)
    await wrapper.setProps({ modelValue: true, indeterminate: false })

    expect((input.element as HTMLInputElement).indeterminate).toBe(false)
  })

  it('names the box by its label even when the label is hidden', () => {
    const wrapper = mount(MdCheckbox, { props: { label: 'Select case #AB12CD', modelValue: false, hideLabel: true } })

    expect(wrapper.get('label').classes()).toContain('sr-only')
    expect(wrapper.get('input').attributes('id')).toBe(wrapper.get('label').attributes('for'))
    expect(wrapper.get('label').text()).toBe('Select case #AB12CD')
  })

  it('cannot be changed while disabled', () => {
    const wrapper = mount(MdCheckbox, { props: { label: 'Enabled', modelValue: false, disabled: true } })

    expect(wrapper.get('input').attributes('disabled')).toBeDefined()
  })
})
