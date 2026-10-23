/** What the global search makes of the text a staff member typed. */
export type SearchIntent =
  | { kind: 'player'; name: string }
  /** `#` and eight hex characters, the id players quote; may match several punishments. */
  | { kind: 'shortId'; shortId: string }
  /** A full punishment identifier (UUID); opens the case directly. */
  | { kind: 'reference'; reference: string }
  | { kind: 'invalid'; reason: 'empty' | 'unknown' }

// Same rule the backend applies before it asks a resolver.
const MINECRAFT_NAME = /^[a-zA-Z0-9_]{3,16}$/
const SHORT_ID = /^#([0-9a-fA-F]{8})$/
const UUID = /^#?([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$/

export function parseSearch(text: string): SearchIntent {
  const wanted = text.trim()
  if (wanted === '') return { kind: 'invalid', reason: 'empty' }

  const short = SHORT_ID.exec(wanted)
  if (short) return { kind: 'shortId', shortId: `#${short[1]!.toUpperCase()}` }

  const uuid = UUID.exec(wanted)
  if (uuid) return { kind: 'reference', reference: uuid[1]!.toLowerCase() }

  if (MINECRAFT_NAME.test(wanted)) return { kind: 'player', name: wanted }
  return { kind: 'invalid', reason: 'unknown' }
}
