import type { CaseActorInfo, CaseTimelineItem } from '../../api/types'
import { field, text } from './caseEvents'

/** The backend rejects longer messages. */
export const MAX_CASE_MESSAGE_LENGTH = 2000

export type AuthorKind = 'PLAYER' | 'STAFF'

/** One message of the thread, folded from its `message.*` events. */
export interface ThreadMessage {
  id: string
  author: AuthorKind
  /** Who wrote the first version; for a player this is the player actor (no name, no oid). */
  actor: CaseActorInfo
  channel: string | null
  visibility: string
  /** The latest text; null once redacted. A deleted message keeps its last text for staff. */
  text: string | null
  edits: number
  deleted: boolean
  redacted: boolean
  firstSeq: number
  occurredAt: number
}

export type Banner = 'FORFEITED' | 'FROZEN' | null

const isMessageType = (type: string): boolean => type.startsWith('message.')

function messageIdOf(event: CaseTimelineItem): string | null {
  return event.subjectId ?? text(field(event, 'messageId'))
}

function authorKindOf(event: CaseTimelineItem): AuthorKind {
  const author = field(event, 'author')
  const kind = typeof author === 'object' && author !== null ? (author as Record<string, unknown>).kind : undefined
  return kind === 'PLAYER' || event.actor.trust === 'PLAYER' ? 'PLAYER' : 'STAFF'
}

/**
 * Folds the events of a case into messages: the first `message.added` opens one, edits replace the
 * text, a delete marks it, a redaction (an event or a redacted version) empties it. Events of a
 * message whose first event was not loaded are ignored, like the backend does.
 */
export function buildThread(events: CaseTimelineItem[]): ThreadMessage[] {
  const byId = new Map<string, ThreadMessage>()
  const ordered = events.filter((event) => isMessageType(event.type)).sort((a, b) => a.seq - b.seq)
  for (const event of ordered) {
    const id = messageIdOf(event)
    if (!id) continue
    if (event.type === 'message.added') {
      if (byId.has(id)) continue
      byId.set(id, {
        id,
        author: authorKindOf(event),
        actor: event.actor,
        channel: text(field(event, 'channel')),
        visibility: event.visibility,
        text: text(field(event, 'text')),
        edits: 0,
        deleted: false,
        redacted: event.redactedAt != null,
        firstSeq: event.seq,
        occurredAt: event.occurredAt,
      })
      continue
    }
    const message = byId.get(id)
    if (!message) continue
    if (event.type === 'message.edited') {
      message.edits += 1
      message.text = text(field(event, 'text'))
    } else if (event.type === 'message.deleted') {
      message.deleted = true
    } else if (event.type === 'message.redacted') {
      message.redacted = true
    }
    if (event.redactedAt != null) message.redacted = true
  }
  const thread = [...byId.values()]
  for (const message of thread) if (message.redacted) message.text = null
  return thread.sort((a, b) => a.firstSeq - b.firstSeq)
}

/**
 * Which banner the case shows: nothing without a leak, a forfeited right when the Ban ID was marked
 * leaked before an appeal was filed, a frozen ticket when it was marked afterwards. The newest leak
 * event decides; a reissue clears `banIdLeakedAt`, so there is no banner then.
 */
export function bannerFor(leakedAt: number | null | undefined, events: CaseTimelineItem[]): Banner {
  if (leakedAt == null) return null
  const leaks = events.filter((event) => event.type === 'ban_id.leak_marked').sort((a, b) => b.seq - a.seq)
  return leaks[0] && field(leaks[0], 'afterFiling') === true ? 'FROZEN' : 'FORFEITED'
}
