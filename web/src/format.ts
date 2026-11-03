import { describeDuration, parseDuration } from './duration'

/**
 * Date and time of an epoch-millis timestamp in the UI language, in the viewer's time zone unless
 * one is given (tests pass UTC so they do not depend on the machine).
 */
export function formatDateTime(epochMillis: number, locale: string, timeZone?: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short', timeZone }).format(epochMillis)
}

/**
 * "P7D" as "1 week" / "1 Woche", "PT1H30M" as "1 hour, 30 minutes". Text that is not a duration the
 * backend accepts is returned unchanged.
 */
export function formatDuration(iso: string, locale: string): string {
  const parsed = parseDuration(iso)
  return parsed.ok ? describeDuration(parsed.seconds, locale) : iso
}

const AGE_UNITS: Array<[Intl.RelativeTimeFormatUnit, number]> = [
  ['day', 86_400_000],
  ['hour', 3_600_000],
  ['minute', 60_000],
]

/** How long ago an epoch-millis timestamp is, relative to `now`: "2 hours ago", "now" under a minute. */
export function formatAge(epochMillis: number, now: number, locale: string): string {
  const diff = epochMillis - now
  const format = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' })
  for (const [unit, size] of AGE_UNITS) {
    if (Math.abs(diff) >= size) return format.format(Math.round(diff / size), unit)
  }
  return format.format(0, 'second')
}

/** First characters of an owner hash: enough to tell rows apart, not the whole identifier. */
export function shortHash(hash: string): string {
  return `${hash.slice(0, 8)}…`
}
