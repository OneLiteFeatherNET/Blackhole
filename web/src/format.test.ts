import { describe, expect, it } from 'vitest'
import { formatDateTime, formatDuration, shortHash } from './format'

describe('format', () => {
  it('formats a timestamp in the requested language and zone', () => {
    const at = Date.UTC(2030, 0, 2, 12, 30)

    expect(formatDateTime(at, 'en', 'UTC')).toContain('Jan 2, 2030')
    expect(formatDateTime(at, 'de', 'UTC')).toContain('02.01.2030')
  })

  it('spells out durations in the UI language', () => {
    expect(formatDuration('P7D', 'en')).toBe('1 week')
    expect(formatDuration('P10D', 'en')).toBe('10 days')
    expect(formatDuration('P1D', 'en')).toBe('1 day')
    expect(formatDuration('PT1H30M', 'en')).toBe('1 hour, 30 minutes')
    expect(formatDuration('P7D', 'de')).toBe('1 Woche')
  })

  it('returns text that is not a supported duration unchanged', () => {
    expect(formatDuration('soon', 'en')).toBe('soon')
    expect(formatDuration('P', 'en')).toBe('P')
    expect(formatDuration('P1M', 'en'), 'months are not a Duration').toBe('P1M')
  })

  it('shortens an owner hash to eight characters and an ellipsis', () => {
    expect(shortHash('0123456789abcdef')).toBe('01234567…')
  })
})
