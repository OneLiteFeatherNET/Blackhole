import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import MdTextField from './MdTextField.vue'

describe('MdTextField', () => {
  it('associates its label with the input and emits the typed value', async () => {
    const onUpdate = vi.fn()
    const wrapper = mount(MdTextField, {
      props: { label: 'Spielername', modelValue: '', 'onUpdate:modelValue': onUpdate },
    })
    const input = wrapper.get('input')

    expect(wrapper.get('label').attributes('for')).toBe(input.attributes('id'))

    await input.setValue('Steve')

    expect(onUpdate).toHaveBeenLastCalledWith('Steve')
  })

  it('can be disabled', () => {
    const wrapper = mount(MdTextField, { props: { label: 'Schlüssel', modelValue: 'chat', disabled: true } })

    expect(wrapper.get('input').attributes('disabled')).toBeDefined()
  })

  it('shows the error next to the field and links it for assistive technology', () => {
    const wrapper = mount(MdTextField, {
      props: { label: 'Dauer', modelValue: '', error: 'Dauer muss positiv sein' },
    })
    const input = wrapper.get('input')
    const message = wrapper.get('p')

    expect(input.attributes('aria-invalid')).toBe('true')
    expect(message.text()).toBe('Dauer muss positiv sein')
    expect(input.attributes('aria-describedby')).toBe(message.attributes('id'))
  })
})
