/** The zone the settings start with when the browser cannot list the zones it supports. */
export const DEFAULT_ZONE = 'Europe/Berlin'

/** The bounds of the absence threshold, in whole minutes. The server refuses anything outside them as well. */
export const THRESHOLD_MIN_MINUTES = 5
export const THRESHOLD_MAX_MINUTES = 240

const HH_MM = /^([01]\d|2[0-3]):[0-5]\d$/

/** The editable unattended settings as the form holds them; the threshold is empty while the field is. */
export interface GuardSettingsForm {
  unattendedLevel: 'STRICT' | 'LOCKDOWN'
  absenceThresholdMinutes: number | null
  nightWindowEnabled: boolean
  nightWindowStart: string
  nightWindowEnd: string
  timeZone: string
}

/** A field with a problem, and the translation key that says what is wrong with it. */
export type SettingsProblems = Partial<Record<'absenceThresholdMinutes' | 'nightWindow' | 'timeZone', string>>

/**
 * The problems of a form, by the same bounds the server applies: the threshold is a whole number from 5 to 240, the window
 * times are HH:mm and differ, and the zone is named. An empty result means the form may be sent.
 */
export function settingsProblems(form: GuardSettingsForm): SettingsProblems {
  const problems: SettingsProblems = {}
  const minutes = form.absenceThresholdMinutes
  if (minutes === null || !Number.isInteger(minutes) || minutes < THRESHOLD_MIN_MINUTES || minutes > THRESHOLD_MAX_MINUTES) {
    problems.absenceThresholdMinutes = 'guard.settings.problem.threshold'
  }
  if (!HH_MM.test(form.nightWindowStart) || !HH_MM.test(form.nightWindowEnd) || form.nightWindowStart === form.nightWindowEnd) {
    problems.nightWindow = 'guard.settings.problem.window'
  }
  if (form.timeZone.trim() === '') {
    problems.timeZone = 'guard.settings.problem.zone'
  }
  return problems
}

/**
 * The start and end of a window as clock times in the given zone. The zone is passed in, so the caller decides whose clock
 * is shown; with no zone the browser's own zone is used.
 */
export function windowInZone(startMillis: number, endMillis: number, locale: string, timeZone?: string): { start: string; end: string } {
  const format = new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone })
  return { start: format.format(startMillis), end: format.format(endMillis) }
}

/** The zones a selector offers: every zone the browser supports, or the default zone when it cannot list them. */
export function zoneChoices(source: { supportedValuesOf?: (key: 'timeZone') => string[] } = Intl): string[] {
  const zones = typeof source.supportedValuesOf === 'function' ? source.supportedValuesOf('timeZone') : []
  return zones.length > 0 ? zones : [DEFAULT_ZONE]
}
