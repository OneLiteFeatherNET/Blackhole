import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { fakeMatchMedia } from '../test/media'
import { useBreakpoint } from './useBreakpoint'

function mountProbe() {
  let result!: ReturnType<typeof useBreakpoint>
  const wrapper = mount(
    defineComponent({
      setup() {
        result = useBreakpoint()
        return () => h('div')
      },
    }),
  )
  return { wrapper, size: () => result.value }
}

describe('useBreakpoint', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('assumes the expanded size where matchMedia does not exist', () => {
    const { wrapper, size } = mountProbe()

    expect(size()).toBe('expanded')

    wrapper.unmount()
  })

  it.each([
    [500, 'compact'],
    [800, 'medium'],
    [1400, 'expanded'],
  ])('reports %ipx as %s', (px, expected) => {
    vi.stubGlobal('matchMedia', fakeMatchMedia({ value: px }).matchMedia)

    const { wrapper, size } = mountProbe()

    expect(size()).toBe(expected)

    wrapper.unmount()
  })

  it('follows a resize and stops listening when unmounted', () => {
    const width = { value: 1400 }
    const media = fakeMatchMedia(width)
    vi.stubGlobal('matchMedia', media.matchMedia)
    const { wrapper, size } = mountProbe()

    width.value = 500
    media.change()
    expect(size(), 'after shrinking').toBe('compact')

    wrapper.unmount()
    expect(media.listeners.size, 'listeners left').toBe(0)
  })
})
