import { describe, expect, it } from 'vitest'
import { parseSearch } from './parseSearch'

describe('parseSearch', () => {
  it.each([
    ['Steve', { kind: 'player', name: 'Steve' }],
    ['  Notch_1  ', { kind: 'player', name: 'Notch_1' }],
    ['#a1b2c3d4', { kind: 'shortId', shortId: '#A1B2C3D4' }],
    ['#A1B2C3D4', { kind: 'shortId', shortId: '#A1B2C3D4' }],
    ['3F2B8C1E-0000-4000-8000-000000000001', { kind: 'reference', reference: '3f2b8c1e-0000-4000-8000-000000000001' }],
    ['#3f2b8c1e-0000-4000-8000-000000000001', { kind: 'reference', reference: '3f2b8c1e-0000-4000-8000-000000000001' }],
  ])('reads %j', (text, expected) => {
    expect(parseSearch(text)).toEqual(expected)
  })

  it.each([['   ', 'empty'], ['', 'empty'], ['ab', 'unknown'], ['#1234', 'unknown'], ['#a1b2c3d4e', 'unknown'], ['two words', 'unknown'], ['a'.repeat(17), 'unknown']])(
    'rejects %j as %s',
    (text, reason) => {
      expect(parseSearch(text)).toEqual({ kind: 'invalid', reason })
    },
  )

  it('treats eight hex characters without # as a player name, since that is a valid name', () => {
    expect(parseSearch('a1b2c3d4')).toEqual({ kind: 'player', name: 'a1b2c3d4' })
  })
})
