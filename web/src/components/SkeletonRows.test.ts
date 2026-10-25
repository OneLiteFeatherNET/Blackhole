import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import SkeletonRows from './SkeletonRows.vue'

describe('SkeletonRows', () => {
  it('renders the requested number of rows', () => {
    const wrapper = mount(SkeletonRows, { props: { rows: 4, columns: 3 } })

    expect(wrapper.findAll('[data-skeleton="row"]')).toHaveLength(4)
  })

  it('renders one placeholder cell per column in each row', () => {
    const wrapper = mount(SkeletonRows, { props: { rows: 2, columns: 3 } })

    for (const row of wrapper.findAll('[data-skeleton="row"]')) {
      expect(row.findAll('[data-skeleton="cell"]')).toHaveLength(3)
    }
  })

  it('is hidden from assistive technology and holds no text', () => {
    const wrapper = mount(SkeletonRows, { props: { rows: 2, columns: 2 } })

    expect(wrapper.attributes('aria-hidden')).toBe('true')
    expect(wrapper.text()).toBe('')
  })
})
