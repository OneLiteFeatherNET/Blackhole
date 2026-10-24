import { describe, expect, it } from 'vitest'
import { normaliseBanId } from './banId'

describe('normaliseBanId', () => {
  it.each([
    ['K7M29QX4', 'K7M29QX4'],
    ['k7m29qx4', 'K7M29QX4'],
    ['#K7M29QX4', 'K7M29QX4'],
    ['  k7m2 9qx4  ', 'K7M29QX4'],
    ['K7M2-9QX4', 'K7M29QX4'],
    ['k7m2-o9qx', 'K7M209QX'],
    ['IL0O1234', '11001234'],
  ])('reads %s as %s', (typed, expected) => {
    expect(normaliseBanId(typed)).toBe(expected)
  })

  it.each(['', '1234567', '123456789', 'K7M29QXU', '##K7M29QX4', 'K7M2/9QX4', 'ÄÖÜK7M29Q', '<script>'])('rejects %j', (typed) => {
    expect(normaliseBanId(typed)).toBeNull()
  })
})
