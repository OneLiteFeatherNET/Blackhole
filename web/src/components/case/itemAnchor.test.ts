import { describe, expect, it } from 'vitest'
import { anchorDomId, anchorHash, anchorIdOf } from './itemAnchor'

describe('anchorIdOf', () => {
  it('reads the item id of an event fragment', () => {
    expect(anchorIdOf('#event-7f3c2a10')).toBe('7f3c2a10')
  })

  it('reads a uuid item id', () => {
    expect(anchorIdOf('#event-3f2b8c1e-0000-4000-8000-000000000021')).toBe('3f2b8c1e-0000-4000-8000-000000000021')
  })

  it.each(['', '#', '#top', '#event-', '#event-a b', '#event-<script>', '#event-x/../y', 'event-7f3c2a10', `#event-${'a'.repeat(65)}`])(
    'gives no id for %j',
    (hash) => {
      expect(anchorIdOf(hash)).toBeNull()
    },
  )
})

describe('anchor addresses', () => {
  it('names the DOM id and the fragment of an item the same way', () => {
    expect(anchorDomId('42')).toBe('event-42')
    expect(anchorHash('42')).toBe('#event-42')
    expect(anchorIdOf(anchorHash('42'))).toBe('42')
  })
})
