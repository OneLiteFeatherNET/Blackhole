import { describe, expect, it } from 'vitest'
import { revealScrollLeft, tabEdges } from './tabs'

describe('tabEdges', () => {
  it('reports no edge when the tabs fit the strip', () => {
    expect(tabEdges(0, 300, 300)).toEqual({ start: false, end: false })
  })

  it('reports a hidden tab at the end while the strip is at its start', () => {
    expect(tabEdges(0, 900, 300)).toEqual({ start: false, end: true })
  })

  it('reports a hidden tab at the start once the strip is scrolled to its end', () => {
    expect(tabEdges(600, 900, 300)).toEqual({ start: true, end: false })
  })

  it('reports both edges in the middle of a long strip', () => {
    expect(tabEdges(300, 900, 300)).toEqual({ start: true, end: true })
  })

  it('ignores a sub-pixel difference from rounding', () => {
    expect(tabEdges(599.5, 900, 300), 'scrolled to the end within a pixel').toEqual({ start: true, end: false })
    expect(tabEdges(0.5, 300.5, 300), 'a half pixel of overflow').toEqual({ start: false, end: false })
  })
})

describe('revealScrollLeft', () => {
  const view = { left: 0, width: 300 }

  it('keeps the position when the tab is already in view', () => {
    expect(revealScrollLeft({ start: 100, end: 220 }, view)).toBe(0)
  })

  it('scrolls back to a tab that starts before the view', () => {
    expect(revealScrollLeft({ start: 100, end: 220 }, { left: 150, width: 300 })).toBe(100)
  })

  it('scrolls forward until a tab that ends after the view is fully visible', () => {
    expect(revealScrollLeft({ start: 300, end: 420 }, view)).toBe(120)
  })
})
