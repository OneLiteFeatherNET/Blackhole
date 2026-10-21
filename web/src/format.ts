/**
 * Date and time of an epoch-millis timestamp in the UI language, in the viewer's time zone unless
 * one is given (tests pass UTC so they do not depend on the machine).
 */
export function formatDateTime(epochMillis: number, locale: string, timeZone?: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short', timeZone }).format(epochMillis)
}

const DURATION = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/

/**
 * "P7D" as "7 days" / "7 Tage", "PT1H30M" as "1 hour, 30 minutes". The backend accepts the day and
 * time parts of an ISO-8601 duration; anything else is returned unchanged.
 */
export function formatDuration(iso: string, locale: string): string {
  const match = DURATION.exec(iso)
  if (!match || iso === 'P' || iso.endsWith('T')) return iso
  const units: Array<[string, string | undefined]> = [
    ['day', match[1]],
    ['hour', match[2]],
    ['minute', match[3]],
    ['second', match[4]],
  ]
  const parts = units
    .filter(([, amount]) => amount !== undefined)
    .map(([unit, amount]) =>
      new Intl.NumberFormat(locale, { style: 'unit', unit, unitDisplay: 'long' }).format(Number(amount)),
    )
  return parts.length > 0 ? parts.join(', ') : iso
}

/** First characters of an owner hash: enough to tell rows apart, not the whole identifier. */
export function shortHash(hash: string): string {
  return `${hash.slice(0, 8)}…`
}
