import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import SkeletonBlock from './SkeletonBlock.vue'

describe('SkeletonBlock', () => {
  it('is hidden from assistive technology and holds no text', () => {
    const wrapper = mount(SkeletonBlock, { props: { height: '96px' } })

    expect(wrapper.attributes('aria-hidden')).toBe('true')
    expect(wrapper.text()).toBe('')
  })

  it('takes the height it is given', () => {
    const wrapper = mount(SkeletonBlock, { props: { height: '96px' } })

    expect(wrapper.element.style.height).toBe('96px')
  })
})
