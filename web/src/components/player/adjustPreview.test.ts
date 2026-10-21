import { describe, expect, it } from 'vitest'
import { previewAdjustment } from './adjustPreview'

describe('previewAdjustment', () => {
  it('warns about a permanent punishment when the new score is below the permanent threshold', () => {
    expect(previewAdjustment(500, -250, 700, 300, false)).toEqual({ scoreAfter: 250, outcome: 'permanent' })
  })

  it('does not punish a score that lands exactly on the permanent threshold', () => {
    expect(previewAdjustment(500, -200, 700, 300, false).outcome, 'already in the soft zone').toBe('none')
    expect(previewAdjustment(800, -500, 700, 300, false)).toEqual({ scoreAfter: 300, outcome: 'soft' })
  })

  it('announces a temporary punishment only for a downward crossing of the soft threshold', () => {
    expect(previewAdjustment(750, -100, 700, 300, false)).toEqual({ scoreAfter: 650, outcome: 'soft' })
    expect(previewAdjustment(650, -100, 700, 300, false).outcome, 'already below the soft threshold').toBe('none')
  })

  it('decides nothing for a section without a soft threshold above the permanent one', () => {
    expect(previewAdjustment(900, -300, null, 300, false).outcome).toBe('none')
  })

  it('decides nothing for a rising score', () => {
    expect(previewAdjustment(100, 50, 700, 300, false)).toEqual({ scoreAfter: 150, outcome: 'none' })
  })

  it('decides nothing while a permanent punishment is in force', () => {
    expect(previewAdjustment(500, -400, 700, 300, true)).toEqual({ scoreAfter: 100, outcome: 'none' })
  })
})
