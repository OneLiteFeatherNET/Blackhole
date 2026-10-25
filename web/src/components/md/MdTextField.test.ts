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

  it('uses the default height and inner padding when not dense', () => {
    const wrapper = mount(MdTextField, { props: { label: 'Spielername', modelValue: '' } })

    expect(wrapper.get('input').classes()).toEqual(expect.arrayContaining(['h-14', 'px-4']))
  })

  it('uses the dense height and inner padding of a filter toolbar field', () => {
    const wrapper = mount(MdTextField, { props: { label: 'Suche', modelValue: '', dense: true } })

    expect(wrapper.get('input').classes()).toEqual(expect.arrayContaining(['h-10', 'px-3']))
    expect(wrapper.get('input').classes()).not.toContain('h-14')
  })

  it('pads the supporting text the same in both densities', () => {
    const normal = mount(MdTextField, { props: { label: 'A', modelValue: '', supportingText: 'Hinweis' } })
    const dense = mount(MdTextField, { props: { label: 'B', modelValue: '', supportingText: 'Hinweis', dense: true } })

    expect(normal.get('p').classes()).toContain('px-4')
    expect(dense.get('p').classes()).toContain('px-4')
  })
})
