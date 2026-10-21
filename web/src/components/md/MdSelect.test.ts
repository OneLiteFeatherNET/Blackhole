import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import MdSelect from './MdSelect.vue'

const options = [
  { value: 'system', label: 'System' },
  { value: 'light', label: 'Hell' },
  { value: 'dark', label: 'Dunkel' },
]

describe('MdSelect', () => {
  it('renders the options under a label bound to the select', () => {
    const wrapper = mount(MdSelect, { props: { label: 'Design', modelValue: 'system', options } })

    expect(wrapper.get('label').attributes('for')).toBe(wrapper.get('select').attributes('id'))
    expect(wrapper.findAll('option').map((option) => option.text())).toEqual(['System', 'Hell', 'Dunkel'])
  })

  it('emits the newly chosen value', async () => {
    const onUpdate = vi.fn()
    const wrapper = mount(MdSelect, {
      props: { label: 'Design', modelValue: 'system', options, 'onUpdate:modelValue': onUpdate },
    })

    await wrapper.get('select').setValue('dark')

    expect(onUpdate).toHaveBeenLastCalledWith('dark')
  })
})
