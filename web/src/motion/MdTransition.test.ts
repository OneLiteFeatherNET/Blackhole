import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { stubReducedMotion } from '../test/reducedMotion'
import MdTransition from './MdTransition.vue'

function setup(name: 'scale' | 'menu' = 'scale', slots = '<p v-if="true">content</p>') {
  return mount(MdTransition, { props: { name }, slots: { default: slots } })
}

describe('MdTransition', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('wraps its content in a transition named after the motion pattern', () => {
    stubReducedMotion(false)
    const wrapper = setup('menu')

    expect(wrapper.get('transition-stub').attributes('name')).toBe('md-menu')
  })

  it('falls back to a plain fade when the system asks for reduced motion', () => {
    stubReducedMotion(true)
    const wrapper = setup('scale')

    expect(wrapper.get('transition-stub').attributes('name')).toBe('md-fade')
  })

  it('does not break where matchMedia does not exist', () => {
    vi.stubGlobal('matchMedia', undefined)
    const wrapper = setup('scale')

    expect(wrapper.get('transition-stub').attributes('name')).toBe('md-scale')
  })

  it('follows the setting when it changes while mounted', async () => {
    const media = stubReducedMotion(false)
    const wrapper = setup('scale')

    media.set(true)
    await wrapper.vm.$nextTick()

    expect(wrapper.get('transition-stub').attributes('name')).toBe('md-fade')
  })

  it('stops listening once unmounted', () => {
    const media = stubReducedMotion(false)
    const wrapper = setup('scale')
    expect(media.listeners.size).toBe(1)

    wrapper.unmount()

    expect(media.listeners.size).toBe(0)
  })
})
