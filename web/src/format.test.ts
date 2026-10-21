import { describe, expect, it } from 'vitest'
import { formatDateTime, shortHash } from './format'

describe('format', () => {
  it('formats a timestamp in the requested language and zone', () => {
    const at = Date.UTC(2030, 0, 2, 12, 30)

    expect(formatDateTime(at, 'en', 'UTC')).toContain('Jan 2, 2030')
    expect(formatDateTime(at, 'de', 'UTC')).toContain('02.01.2030')
  })

  it('shortens an owner hash to eight characters and an ellipsis', () => {
    expect(shortHash('0123456789abcdef')).toBe('01234567…')
  })
})
