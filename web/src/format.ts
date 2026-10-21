/**
 * Date and time of an epoch-millis timestamp in the UI language, in the viewer's time zone unless
 * one is given (tests pass UTC so they do not depend on the machine).
 */
export function formatDateTime(epochMillis: number, locale: string, timeZone?: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short', timeZone }).format(epochMillis)
}

/** First characters of an owner hash: enough to tell rows apart, not the whole identifier. */
export function shortHash(hash: string): string {
  return `${hash.slice(0, 8)}…`
}
