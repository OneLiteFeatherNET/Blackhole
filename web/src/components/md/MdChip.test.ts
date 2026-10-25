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

  it('shows a check mark on a selected filter chip only', async () => {
    const wrapper = mount(MdChip, { props: { label: 'Aktiv', variant: 'filter', selected: false } })

    expect(wrapper.find('svg').exists()).toBe(false)

    await wrapper.setProps({ selected: true })

    expect(wrapper.find('svg').exists()).toBe(true)
  })

  it('gives an input chip a remove button named after the filter and emits remove', async () => {
    const wrapper = mount(MdChip, { props: { label: 'Aktiv', variant: 'input' } })

    await wrapper.get('button[aria-label="Remove filter Aktiv"]').trigger('click')

    expect(wrapper.emitted('remove')).toHaveLength(1)
  })

  it('renders a status chip as plain text, not as a button', () => {
    const wrapper = mount(MdChip, { props: { label: 'Verifiziert', variant: 'status' } })

    expect(wrapper.element.tagName).toBe('SPAN')
    expect(wrapper.attributes('aria-pressed')).toBeUndefined()
  })

  it('gives every variant the full-round shape of design section 2', () => {
    for (const variant of ['filter', 'input', 'status'] as const) {
      const wrapper = mount(MdChip, { props: { label: 'Aktiv', variant } })

      expect(wrapper.classes(), variant).toContain('rounded-full')
      expect(wrapper.classes(), variant).not.toContain('rounded-md-sm')
    }
  })
})
