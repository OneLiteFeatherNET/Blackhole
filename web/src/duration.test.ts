import { describe, expect, it } from 'vitest'
import { describeDuration, formatDuration, MAX_SECONDS, parseDuration, toIso } from './duration'

describe('parseDuration', () => {
  it.each([
    ['30s', 'PT30S', 30],
    ['30m', 'PT30M', 1800],
    ['5min', 'PT5M', 300],
    ['12h', 'PT12H', 43_200],
    ['3d', 'P3D', 259_200],
    ['3w', 'P21D', 1_814_400],
    ['1w 2d', 'P9D', 777_600],
    ['1w2d', 'P9D', 777_600],
    ['1h30m', 'PT1H30M', 5400],
    ['90m', 'PT1H30M', 5400],
    ['36h', 'P1DT12H', 129_600],
    ['1d 2h 3m 4s', 'P1DT2H3M4S', 93_784],
    ['2d, 3h', 'P2DT3H', 183_600],
  ])('reads %s as %s', (input, iso, seconds) => {
    expect(parseDuration(input)).toEqual({ ok: true, iso, seconds })
  })

  it.each([
    ['30M', 'PT30M'],
    ['3D', 'P3D'],
    ['2W', 'P14D'],
    ['  7d  ', 'P7D'],
    ['7 d', 'P7D'],
    ['3t', 'P3D'],
    ['3T', 'P3D'],
    ['3 Tage', 'P3D'],
    ['2 wochen', 'P14D'],
    ['4 Std', 'PT4H'],
    ['4 hours', 'PT4H'],
    ['10 minutes', 'PT10M'],
    ['20 Sekunden', 'PT20S'],
  ])('accepts the spelling %j', (input, iso) => {
    const result = parseDuration(input)

    expect(result.ok && result.iso).toBe(iso)
  })

  it.each([
    ['P7D', 'P7D'],
    ['p7d', 'P7D'],
    ['PT12H', 'PT12H'],
    ['pt90m', 'PT1H30M'],
    ['P1DT12H', 'P1DT12H'],
    ['PT36H', 'P1DT12H'],
    ['P3W', 'P21D'],
    ['PT0H5S', 'PT5S'],
  ])('accepts the ISO-8601 form %s and normalises it to %s', (input, iso) => {
    const result = parseDuration(input)

    expect(result.ok && result.iso).toBe(iso)
  })

  it.each([
    ['', 'empty'],
    ['   ', 'empty'],
    ['abc', 'invalid'],
    ['d3', 'invalid'],
    ['3d x', 'invalid'],
    ['3.5h', 'invalid'],
    ['-3d', 'invalid'],
    ['3', 'missingUnit'],
    ['3x', 'invalid'],
    ['3d 2d', 'duplicateUnit'],
    ['1h 30m 5h', 'duplicateUnit'],
    ['0m', 'zero'],
    ['0d 0h', 'zero'],
    ['P', 'invalid'],
    ['PT', 'invalid'],
    ['P1DT', 'invalid'],
    ['PT1.5S', 'invalid'],
    ['P0D', 'zero'],
    ['-P1D', 'invalid'],
    ['1000000000d', 'tooLarge'],
    ['4000d', 'tooLarge'],
  ])('refuses %j with the reason %s', (input, reason) => {
    expect(parseDuration(input)).toEqual({ ok: false, reason })
  })

  it.each(['1mo', '2 months', '1y', '2 years', '1 Monat', '3 Jahre', '1j', 'P1M', 'P1Y', 'P1Y2M'])(
    'explains that %j is not supported because the length varies',
    (input) => {
      expect(parseDuration(input)).toEqual({ ok: false, reason: 'unsupportedUnit' })
    },
  )

  it('accepts exactly ten years and refuses one second more', () => {
    expect(parseDuration('3650d').ok).toBe(true)
    expect(parseDuration(`PT${MAX_SECONDS + 1}S`)).toEqual({ ok: false, reason: 'tooLarge' })
  })
})

describe('formatDuration', () => {
  it.each([
    ['P21D', '3w'],
    ['P7D', '1w'],
    ['P10D', '10d'],
    ['P1D', '1d'],
    ['PT90M', '1h 30m'],
    ['PT1H30M', '1h 30m'],
    ['PT12H', '12h'],
    ['PT36H', '1d 12h'],
    ['PT5M', '5m'],
    ['PT45S', '45s'],
    ['P1DT2H3M4S', '1d 2h 3m 4s'],
    ['P14DT1H', '2w 1h'],
  ])('shows %s as %s', (iso, short) => {
    expect(formatDuration(iso)).toBe(short)
  })

  it.each(['', 'soon', 'P', 'P1M', 'PT0S'])('returns %j unchanged because it is no duration', (text) => {
    expect(formatDuration(text)).toBe(text)
  })
})

describe('round trip', () => {
  it.each(['30s', '5m', '1h 30m', '12h', '1d 12h', '10d', '3w', '2w 1h', '1d 2h 3m 4s'])(
    'keeps %s when it is parsed and shown again',
    (short) => {
      const parsed = parseDuration(short)

      expect(parsed.ok && formatDuration(parsed.iso)).toBe(short)
    },
  )

  it.each(['P1D', 'P21D', 'PT1H30M', 'P1DT12H', 'PT45S'])('keeps %s when it is shown and parsed again', (iso) => {
    const parsed = parseDuration(formatDuration(iso))

    expect(parsed.ok && parsed.iso).toBe(iso)
  })

  it('turns a week input into the days the backend understands, never a W', () => {
    const parsed = parseDuration('3w')

    expect(parsed.ok && parsed.iso).not.toContain('W')
  })
})

describe('toIso', () => {
  it('writes days before the time part and leaves out empty parts', () => {
    expect(toIso(86_400)).toBe('P1D')
    expect(toIso(3_600)).toBe('PT1H')
    expect(toIso(90_061)).toBe('P1DT1H1M1S')
  })
})

describe('describeDuration', () => {
  it.each([
    [1_814_400, 'en', '3 weeks'],
    [1_814_400, 'de', '3 Wochen'],
    [5400, 'en', '1 hour, 30 minutes'],
    [5400, 'de', '1 Stunde, 30 Minuten'],
    [864_000, 'en', '10 days'],
    [300, 'de', '5 Minuten'],
  ])('spells out %d seconds in %s as %s', (seconds, locale, text) => {
    expect(describeDuration(seconds, locale)).toBe(text)
  })
})
