import { describe, expect, it } from 'vitest'
import { groupKey } from './groupKey'

describe('groupKey', () => {
  it('gives the same key for the same reason every time', () => {
    expect(groupKey('Griefing')).toBe(groupKey('Griefing'))
  })

  it('gives a different key for a different reason', () => {
    expect(groupKey('Griefing')).not.toBe(groupKey('Hacking'))
  })

  it('never contains the reason text, and is eight hex digits', () => {
    const key = groupKey('Banned by Steve_99')

    expect(key).toMatch(/^[0-9a-f]{8}$/)
    expect(key).not.toContain('steve')
  })
})
