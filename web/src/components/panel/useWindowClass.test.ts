import { effectScope, type EffectScope } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import { fakeMatchMedia } from '../../test/media'
import { useWindowClass } from './useWindowClass'

describe('useWindowClass', () => {
  let scope: EffectScope | undefined

  afterEach(() => {
    scope?.stop()
    scope = undefined
  })

  function classAt(width: number) {
    const media = fakeMatchMedia({ value: width })
    scope = effectScope()
    const windowClass = scope.run(() => useWindowClass({ matchMedia: media.matchMedia }))!
    return { windowClass, media }
  }

  it.each([
    [320, 'compact'],
    [480, 'compact'],
    [599, 'compact'],
    [600, 'medium'],
    [720, 'medium'],
    [839, 'medium'],
    [840, 'expanded'],
    [1280, 'expanded'],
  ])('classifies a %i px window as %s (Material window size classes)', (width, expected) => {
    expect(classAt(width).windowClass.value).toBe(expected)
  })

  it('follows a resize of the window without a reload', () => {
    const width = { value: 480 }
    const media = fakeMatchMedia(width)
    scope = effectScope()
    const windowClass = scope.run(() => useWindowClass({ matchMedia: media.matchMedia }))!
    expect(windowClass.value, 'starts compact').toBe('compact')

    width.value = 1024
    media.change()

    expect(windowClass.value, 'becomes expanded after the resize').toBe('expanded')
  })

  it('reads compact when matchMedia does not exist (no window to measure)', () => {
    scope = effectScope()
    const windowClass = scope.run(() => useWindowClass({ matchMedia: undefined }))!
    expect(windowClass.value).toBe('compact')
  })
})
