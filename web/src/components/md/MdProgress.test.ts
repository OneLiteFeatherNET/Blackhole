import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MdProgress from './MdProgress.vue'

describe('MdProgress', () => {
  it('reports a determinate value and fills the track to it', async () => {
    const wrapper = mount(MdProgress, { props: { label: 'Fortschritt', value: 40 } })

    expect(wrapper.attributes('role')).toBe('progressbar')
    expect(wrapper.attributes('aria-valuenow')).toBe('40')
    expect(wrapper.get('[style]').attributes('style')).toContain('width: 40%')

    await wrapper.setProps({ value: 80 })

    expect(wrapper.attributes('aria-valuenow')).toBe('80')
  })

  it('is indeterminate without a value and exposes no aria-valuenow', () => {
    const wrapper = mount(MdProgress, { props: { label: 'Lädt' } })

    expect(wrapper.attributes('aria-valuenow')).toBeUndefined()
    expect(wrapper.find('.md-indeterminate').exists()).toBe(true)
  })
})
