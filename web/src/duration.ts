/**
 * Human friendly durations for the forms. People type `3d`, `1h30m` or `1w 2d`; the backend wants
 * the day and time parts of an ISO-8601 duration (`P3D`, `PT1H30M`: java.time.Duration.parse, which
 * has neither weeks nor months nor years). These pure functions convert between the two.
 */

export type DurationFailure = 'empty' | 'invalid' | 'missingUnit' | 'unsupportedUnit' | 'duplicateUnit' | 'zero' | 'tooLarge'

export type ParsedDuration = { ok: true; iso: string; seconds: number } | { ok: false; reason: DurationFailure }

/** Longest accepted duration: ten years of 365 days. Anything longer is a permanent punishment. */
export const MAX_SECONDS = 10 * 365 * 86_400

const SECONDS_PER = { w: 604_800, d: 86_400, h: 3_600, m: 60, s: 1 } as const
type Unit = keyof typeof SECONDS_PER

/** Spellings accepted for each unit, lower case. `t` is the German "Tage"; `m` is minutes, never months. */
const UNIT_SPELLINGS: Record<Unit, string[]> = {
  w: ['w', 'wk', 'wks', 'week', 'weeks', 'wo', 'woche', 'wochen'],
  d: ['d', 'day', 'days', 't', 'tag', 'tage', 'tagen'],
  h: ['h', 'hr', 'hrs', 'hour', 'hours', 'std', 'stunde', 'stunden'],
  m: ['m', 'min', 'mins', 'minute', 'minutes', 'minuten'],
  s: ['s', 'sec', 'secs', 'second', 'seconds', 'sek', 'sekunde', 'sekunden'],
}
const UNIT_LOOKUP = new Map<string, Unit>(
  (Object.entries(UNIT_SPELLINGS) as Array<[Unit, string[]]>).flatMap(([unit, names]) =>
    names.map((name): [string, Unit] => [name, unit]),
  ),
)
/** Months and years vary in length, so they are recognised only to explain why they are refused. */
const UNSUPPORTED = new Set([
  'mo', 'mon', 'mos', 'month', 'months', 'monat', 'monate', 'monaten',
  'y', 'yr', 'yrs', 'year', 'years', 'j', 'jahr', 'jahre', 'jahren',
])

const ISO = /^P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/i
const TOKEN = /(\d+)\s*([a-zäöü]+)/iy

function finish(seconds: number): ParsedDuration {
  if (seconds <= 0) return { ok: false, reason: 'zero' }
  if (seconds > MAX_SECONDS) return { ok: false, reason: 'tooLarge' }
  return { ok: true, iso: toIso(seconds), seconds }
}

/** The canonical wire format: whole days, then hours, minutes and seconds (`P1DT12H`, `PT1H30M`). */
export function toIso(seconds: number): string {
  const days = Math.floor(seconds / 86_400)
  const hours = Math.floor((seconds % 86_400) / 3_600)
  const minutes = Math.floor((seconds % 3_600) / 60)
  const rest = seconds % 60
  const time = `${hours ? `${hours}H` : ''}${minutes ? `${minutes}M` : ''}${rest ? `${rest}S` : ''}`
  return `P${days ? `${days}D` : ''}${time ? `T${time}` : ''}`
}

function parseIso(text: string): ParsedDuration {
  // P1M / P1Y are months and years in ISO-8601, which java.time.Duration does not know either.
  if (/^P\d+[YM](?!.*T)/i.test(text) || /^P\d+Y/i.test(text)) return { ok: false, reason: 'unsupportedUnit' }
  const match = ISO.exec(text)
  if (!match || /^P$/i.test(text) || /T$/i.test(text)) return { ok: false, reason: 'invalid' }
  const [w, d, h, m, s] = match.slice(1).map((part) => (part === undefined ? 0 : Number(part)))
  if ([w, d, h, m, s].some((amount) => amount! > 999_999_999)) return { ok: false, reason: 'tooLarge' }
  return finish(w! * 604_800 + d! * 86_400 + h! * 3_600 + m! * 60 + s!)
}

/**
 * Reads `30m`, `3d`, `1w 2d`, `1h30m` (units s, m/min, h, d/t, w, any case, also written out in
 * English or German) or a raw ISO-8601 duration such as `P7D` / `PT12H`, and returns the ISO form the
 * backend takes. Months and years are refused on purpose.
 */
export function parseDuration(input: string): ParsedDuration {
  const text = input.trim()
  if (text === '') return { ok: false, reason: 'empty' }
  if (/^p/i.test(text)) return parseIso(text)
  if (/^\d+$/.test(text)) return { ok: false, reason: 'missingUnit' }

  let total = 0
  const seen = new Set<Unit>()
  let position = 0
  while (position < text.length) {
    const gap = /^[\s,]+/.exec(text.slice(position))
    if (gap) {
      position += gap[0].length
      continue
    }
    TOKEN.lastIndex = position
    const token = TOKEN.exec(text)
    if (!token) return { ok: false, reason: 'invalid' }
    const name = token[2]!.toLowerCase()
    const unit = UNIT_LOOKUP.get(name)
    if (!unit) return { ok: false, reason: UNSUPPORTED.has(name) ? 'unsupportedUnit' : 'invalid' }
    if (seen.has(unit)) return { ok: false, reason: 'duplicateUnit' }
    seen.add(unit)
    if (token[1]!.length > 9) return { ok: false, reason: 'tooLarge' }
    total += Number(token[1]) * SECONDS_PER[unit]
    position = TOKEN.lastIndex
  }
  return finish(total)
}

function parts(seconds: number): Array<[Unit, number]> {
  let rest = seconds
  const days = Math.floor(rest / 86_400)
  rest -= days * 86_400
  const result: Array<[Unit, number]> = []
  // Weeks only when the days are whole weeks: "3w", but "10d" rather than "1w 3d".
  if (days > 0) result.push(days % 7 === 0 ? ['w', days / 7] : ['d', days])
  for (const unit of ['h', 'm', 's'] as const) {
    const amount = Math.floor(rest / SECONDS_PER[unit])
    rest -= amount * SECONDS_PER[unit]
    if (amount > 0) result.push([unit, amount])
  }
  return result
}

/** `P21D` as `3w`, `PT90M` as `1h 30m`; text that is not a valid duration is returned unchanged. */
export function formatDuration(iso: string): string {
  const parsed = parseDuration(iso)
  if (!parsed.ok) return iso
  return parts(parsed.seconds).map(([unit, amount]) => `${amount}${unit}`).join(' ')
}

const INTL_UNIT: Record<Unit, string> = { w: 'week', d: 'day', h: 'hour', m: 'minute', s: 'second' }

/** The duration spelled out in the UI language: "3 weeks", "1 hour, 30 minutes". */
export function describeDuration(seconds: number, locale: string): string {
  return parts(seconds)
    .map(([unit, amount]) =>
      new Intl.NumberFormat(locale, { style: 'unit', unit: INTL_UNIT[unit], unitDisplay: 'long' }).format(amount),
    )
    .join(', ')
}
