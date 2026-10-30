import { describe, expect, it } from 'vitest'
import { DEFAULT_ZONE, settingsProblems, windowInZone, zoneChoices, type GuardSettingsForm } from './guardSettings'

const VALID: GuardSettingsForm = {
  unattendedLevel: 'STRICT',
  absenceThresholdMinutes: 20,
  nightWindowEnabled: true,
  nightWindowStart: '01:00',
  nightWindowEnd: '07:00',
  timeZone: 'Europe/Berlin',
}

describe('settingsProblems', () => {
  it('accepts the bounds of the server: 5 and 240 minutes', () => {
    expect(settingsProblems({ ...VALID, absenceThresholdMinutes: 5 })).toEqual({})
    expect(settingsProblems({ ...VALID, absenceThresholdMinutes: 240 })).toEqual({})
  })

  it('refuses a threshold below 5 or above 240, as the server does', () => {
    expect(settingsProblems({ ...VALID, absenceThresholdMinutes: 4 }).absenceThresholdMinutes).toBe('guard.settings.problem.threshold')
    expect(settingsProblems({ ...VALID, absenceThresholdMinutes: 241 }).absenceThresholdMinutes).toBe('guard.settings.problem.threshold')
  })

  it('refuses a threshold that is not a whole number of minutes or is missing', () => {
    expect(settingsProblems({ ...VALID, absenceThresholdMinutes: 7.5 }).absenceThresholdMinutes).toBe('guard.settings.problem.threshold')
    expect(settingsProblems({ ...VALID, absenceThresholdMinutes: null }).absenceThresholdMinutes).toBe('guard.settings.problem.threshold')
  })

  it('refuses a night window whose start equals its end', () => {
    expect(settingsProblems({ ...VALID, nightWindowStart: '03:00', nightWindowEnd: '03:00' }).nightWindow).toBe('guard.settings.problem.window')
  })

  it('refuses a time that is not HH:mm', () => {
    expect(settingsProblems({ ...VALID, nightWindowStart: '7:00' }).nightWindow).toBe('guard.settings.problem.window')
  })

  it('accepts a window that crosses midnight', () => {
    expect(settingsProblems({ ...VALID, nightWindowStart: '23:00', nightWindowEnd: '02:00' })).toEqual({})
  })

  it('refuses an empty time zone', () => {
    expect(settingsProblems({ ...VALID, timeZone: '' }).timeZone).toBe('guard.settings.problem.zone')
  })
})

describe('windowInZone', () => {
  // 01:00 Berlin on 4 January 2030 is 00:00 UTC. The zone is the one the caller names, never the system zone.
  const start = Date.UTC(2030, 0, 4, 0, 0)
  const end = Date.UTC(2030, 0, 4, 6, 0)

  it('renders the window in the zone it is given', () => {
    expect(windowInZone(start, end, 'en-GB', 'UTC')).toEqual({ start: '00:00', end: '06:00' })
    expect(windowInZone(start, end, 'en-GB', 'Europe/Berlin')).toEqual({ start: '01:00', end: '07:00' })
  })
})

describe('zoneChoices', () => {
  it('lists the zones the browser supports', () => {
    expect(zoneChoices({ supportedValuesOf: () => ['Europe/Berlin', 'Europe/Paris'] })).toEqual(['Europe/Berlin', 'Europe/Paris'])
  })

  it('falls back to the default zone when the browser cannot list zones', () => {
    expect(zoneChoices({})).toEqual([DEFAULT_ZONE])
    expect(DEFAULT_ZONE).toBe('Europe/Berlin')
  })
})
