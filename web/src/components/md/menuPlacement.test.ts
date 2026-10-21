import { describe, expect, it } from 'vitest'
import { MAX_MENU_HEIGHT, placeMenu } from './menuPlacement'

const viewport = { width: 1000, height: 800 }
const anchor = (top: number) => ({ left: 100, top, bottom: top + 56, width: 320 })

describe('placeMenu', () => {
  it('opens below the field with the anchor width when there is room', () => {
    const placement = placeMenu(anchor(100), viewport, 3)

    expect(placement).toMatchObject({ up: false, top: 160, left: 100, width: 320 })
    expect(placement.bottom).toBeUndefined()
  })

  it('caps the height so long lists scroll instead of filling the screen', () => {
    expect(placeMenu(anchor(100), viewport, 40).maxHeight).toBe(MAX_MENU_HEIGHT)
  })

  it('flips upwards when it would be cut off below and there is more room above', () => {
    const placement = placeMenu(anchor(650), viewport, 10)

    expect(placement.up).toBe(true)
    expect(placement.bottom).toBe(800 - 650 + 4)
    expect(placement.top).toBeUndefined()
    expect(placement.maxHeight).toBe(MAX_MENU_HEIGHT)
  })

  it('stays below when there is even less room above', () => {
    const placement = placeMenu(anchor(20), { width: 1000, height: 200 }, 10)

    expect(placement.up).toBe(false)
    expect(placement.maxHeight).toBe(200 - 76 - 8 - 4)
  })

  it('keeps an 8px margin to the viewport edge when constrained', () => {
    const down = placeMenu(anchor(100), { width: 1000, height: 300 }, 10)
    expect(down.top! + down.maxHeight).toBeLessThanOrEqual(300 - 8)

    const up = placeMenu(anchor(250), { width: 1000, height: 330 }, 10)
    expect(up.up).toBe(true)
    expect(250 - 4 - up.maxHeight).toBeGreaterThanOrEqual(8)
  })

  it('clamps the menu horizontally into the viewport', () => {
    const placement = placeMenu({ left: 900, top: 100, bottom: 156, width: 320 }, viewport, 3)

    expect(placement.left + placement.width).toBeLessThanOrEqual(1000 - 8)
    expect(placeMenu({ left: -20, top: 100, bottom: 156, width: 100 }, viewport, 3).left).toBe(8)
  })
})
