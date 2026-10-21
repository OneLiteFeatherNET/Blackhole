import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import MdChip from './MdChip.vue'

describe('MdChip', () => {
  it('reflects its selected state in aria-pressed', async () => {
    const wrapper = mount(MdChip, { props: { label: 'Aktiv', selected: false } })

    expect(wrapper.text()).toBe('Aktiv')
    expect(wrapper.attributes('aria-pressed')).toBe('false')

    await wrapper.setProps({ selected: true })

    expect(wrapper.attributes('aria-pressed')).toBe('true')
    expect(wrapper.classes()).toContain('bg-secondary-container')
  })

  it('forwards clicks to its listener', async () => {
    const onClick = vi.fn()
    const wrapper = mount(MdChip, { props: { label: 'Aktiv' }, attrs: { onClick } })

    await wrapper.trigger('click')

    expect(onClick).toHaveBeenCalledTimes(1)
    expect(wrapper.attributes('aria-pressed')).toBeUndefined()
  })
})
