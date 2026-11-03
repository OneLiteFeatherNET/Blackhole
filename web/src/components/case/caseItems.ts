import type { BlackholeApi } from '../../api/blackhole'
import type { ApiError } from '../../api/types'
import { buildThread, type ThreadMessage } from './conversation'
import { field, isRedacted, noteIdOf, text } from './caseEvents'

/** The answer of a read by id: the item, no such item, or the read itself failed. */
export type ItemLookup<T> = { kind: 'found'; value: T } | { kind: 'missing' } | { kind: 'failed'; error: ApiError }

/** Timeline pages read while looking for one note; a deep link to an old note stays bounded. */
const MAX_NOTE_PAGES = 20
const NOTE_PAGE_SIZE = 200

/**
 * The latest version of one note, read from the note events of the timeline, newest first. A redacted note has no
 * text any more and counts as missing: nothing can be edited or shown of it.
 */
export async function findNote(api: BlackholeApi, reference: string, noteId: string): Promise<ItemLookup<{ text: string }>> {
  let cursor: string | undefined
  for (let page = 0; page < MAX_NOTE_PAGES; page += 1) {
    const result = await api.caseTimeline(reference, { types: ['note'], order: 'desc', size: NOTE_PAGE_SIZE, cursor })
    if (!result.ok) return { kind: 'failed', error: result.error }
    // Newest first: among the versions on this page, the one with the highest seq is the latest.
    const versions = result.data.items.filter((event) => noteIdOf(event) === noteId)
    const latest = versions.reduce<(typeof versions)[number] | undefined>((best, event) => (best === undefined || event.seq > best.seq ? event : best), undefined)
    if (latest) {
      if (isRedacted(latest)) return { kind: 'missing' }
      return { kind: 'found', value: { text: text(field(latest, 'text')) ?? '' } }
    }
    cursor = result.data.nextCursor ?? undefined
    if (!cursor) break
  }
  return { kind: 'missing' }
}

/** One message of the conversation, by id, with the state it is in now. */
export async function findMessage(api: BlackholeApi, reference: string, messageId: string): Promise<ItemLookup<ThreadMessage>> {
  const result = await api.caseConversation(reference)
  if (!result.ok) return { kind: 'failed', error: result.error }
  const message = buildThread(result.data).find((candidate) => candidate.id === messageId)
  return message ? { kind: 'found', value: message } : { kind: 'missing' }
}

/** Staff messages written in the panel can be corrected or redacted; a Discord message is changed in Discord. */
export const isPanelStaffMessage = (message: ThreadMessage): boolean =>
  message.author === 'STAFF' && message.channel !== 'DISCORD' && !message.deleted && !message.redacted
