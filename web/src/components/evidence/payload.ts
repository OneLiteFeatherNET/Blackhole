import type { Evidence } from '../../api/types'

/** The generated type of a payload is a map of objects; the real shape is checked here, defensively. */
function record(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : {}
}

function text(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null
}

export interface AnticheatRecord {
  check: string | null
  violationLevel: number | null
  server: string | null
  detail: string | null
  values: Array<[string, string]>
}

export function anticheatOf(item: Pick<Evidence, 'payload'>): AnticheatRecord {
  const payload = record(item.payload)
  return {
    check: text(payload.check),
    violationLevel: typeof payload.violationLevel === 'number' ? payload.violationLevel : null,
    server: text(payload.server),
    detail: text(payload.detail),
    values: Object.entries(record(payload.values)).map(([key, value]) => [key, String(value)]),
  }
}

export interface ChatLine {
  /** Epoch millis, or the ISO-8601 text the sender used. */
  timestamp: number | string | null
  /** An owner hash, an alias the sender chose, or null. */
  speaker: string | null
  text: string
}

export function chatLinesOf(item: Pick<Evidence, 'payload'>): ChatLine[] {
  const lines = record(item.payload).lines
  if (!Array.isArray(lines)) return []
  return lines.map((raw) => {
    const line = record(raw)
    const timestamp = line.timestamp
    return {
      timestamp: typeof timestamp === 'number' || typeof timestamp === 'string' ? timestamp : null,
      speaker: text(line.speaker),
      text: typeof line.text === 'string' ? line.text : '',
    }
  })
}

/** The free text of a LINK or REPLAY. */
export function descriptionOf(item: Pick<Evidence, 'payload'>): string | null {
  return text(record(item.payload).description)
}
