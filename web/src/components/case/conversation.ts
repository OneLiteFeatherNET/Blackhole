import type { CaseActorInfo, CaseTimelineItem } from '../../api/types'
import { field, text } from './caseEvents'

/** The backend rejects longer messages. */
export const MAX_CASE_MESSAGE_LENGTH = 2000

/** Who wrote a message: the portal player, staff, or (from Discord) a bot or an author nobody could identify. */
export type AuthorKind = 'PLAYER' | 'STAFF' | 'BOT' | 'EXTERNAL'

/** One message of the thread, folded from its `message.*` events. */
export interface ThreadMessage {
  id: string
  author: AuthorKind
  /** Who wrote the first version; for a player this is the player actor (no name, no oid). */
  actor: CaseActorInfo
  channel: string | null
  /** The opaque participant of a Discord author (never a Discord id); null for portal and panel messages. */
  participantId: string | null
  /** How many attachments and embeds the Discord message had; their content is not stored. */
  attachments: number
  /** The chat files the message sent (evidence staged in the chat); empty for Discord messages. */
  files: ChatFile[]
  visibility: string
  /** The latest text; null once redacted. A deleted message keeps its last text for staff. */
  text: string | null
  /** The sanitised block tree of the latest text, drawn as Markdown; null when there is none. */
  blocks: unknown[] | null
  edits: number
  deleted: boolean
  redacted: boolean
  firstSeq: number
  occurredAt: number
}

/** A chat file of a message; the bytes are fetched through the evidence route when someone asks for them. */
export interface ChatFile {
  attachmentId: string
  name: string
  contentType: string
  size: number
  provenance: 'STAFF' | 'PLAYER' | string
}

/** A share or a withdrawal of evidence, shown in the thread as a system line; it carries no content. */
export interface ShareMarker {
  key: string
  seq: number
  kind: 'SHARED' | 'WITHDRAWN'
  mode: string | null
  lineCount: number | null
}

export type Banner = 'FORFEITED' | 'FROZEN' | null

function filesOf(event: CaseTimelineItem): ChatFile[] {
  const list = field(event, 'attachments')
  if (!Array.isArray(list)) return []
  return list.flatMap((raw): ChatFile[] => {
    if (typeof raw !== 'object' || raw === null) return []
    const item = raw as Record<string, unknown>
    if (typeof item.attachmentId !== 'string') return []
    return [
      {
        attachmentId: item.attachmentId,
        name: typeof item.name === 'string' ? item.name : 'file',
        contentType: typeof item.contentType === 'string' ? item.contentType : 'application/octet-stream',
        size: typeof item.size === 'number' ? item.size : 0,
        provenance: typeof item.provenance === 'string' ? item.provenance : 'STAFF',
      },
    ]
  })
}

/** The share events of a case, oldest first; the content of a share is never in the event. */
export function buildMarkers(events: CaseTimelineItem[]): ShareMarker[] {
  return events
    .filter((event) => event.type === 'evidence.shared' || event.type === 'evidence.share_revoked')
    .sort((a, b) => a.seq - b.seq)
    .map((event) => {
      const shareId = text(field(event, 'shareId')) ?? String(event.seq)
      const shared = event.type === 'evidence.shared'
      const lines = field(event, 'lineCount')
      return {
        key: `${event.type}:${shareId}`,
        seq: event.seq,
        kind: shared ? 'SHARED' : 'WITHDRAWN',
        mode: shared ? text(field(event, 'mode')) : null,
        lineCount: typeof lines === 'number' ? lines : null,
      }
    })
}

const isMessageType = (type: string): boolean => type.startsWith('message.')

function messageIdOf(event: CaseTimelineItem): string | null {
  return event.subjectId ?? text(field(event, 'messageId'))
}

function authorOf(event: CaseTimelineItem): Record<string, unknown> {
  const author = field(event, 'author')
  return typeof author === 'object' && author !== null ? (author as Record<string, unknown>) : {}
}

function authorKindOf(event: CaseTimelineItem): AuthorKind {
  const kind = authorOf(event).kind
  if (kind === 'BOT' || kind === 'EXTERNAL') return kind
  return kind === 'PLAYER' || event.actor.trust === 'PLAYER' ? 'PLAYER' : 'STAFF'
}

const count = (value: unknown): number => (Array.isArray(value) ? value.length : 0)

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
        participantId: text(authorOf(event).participantId),
        attachments: count(field(event, 'attachments')) + count(field(event, 'embeds')),
        files: filesOf(event),
        blocks: event.blocks ?? null,
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
      message.blocks = event.blocks ?? null
    } else if (event.type === 'message.deleted') {
      message.deleted = true
    } else if (event.type === 'message.redacted') {
      message.redacted = true
    }
    if (event.redactedAt != null) message.redacted = true
  }
  const thread = [...byId.values()]
  for (const message of thread) {
    if (message.redacted) {
      message.text = null
      message.blocks = null
    }
  }
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
