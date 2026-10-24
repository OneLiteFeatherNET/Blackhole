import type { CaseTimelineItem } from '../../api/types'
import { field, num, text } from './caseEvents'

/**
 * Where a Discord ticket is, as far as the case events tell: the panel has no ticket route (the bot owns
 * those), so the state is derived from the lifecycle markers the bot's calls leave on the case.
 * `CLOSING` also covers a ticket whose appeal was decided but whose transcript is not verified (yet).
 */
export type TicketPhase = 'OPEN' | 'CLOSING' | 'CLOSED' | 'ARCHIVED' | 'DELETED'

export interface TicketView {
  ticketId: string
  ticketNo: number
  appealId: string
  openedAt: number
  phase: TicketPhase
  /** `ticket.transcript_complete` exists. */
  transcriptComplete: boolean
  /** The newest stored protocol version of the ticket's appeal; null while none is stored. */
  protocolVersion: number | null
  /** Since when the transcript may be incomplete: the decision, or the last message before the channel was lost. Null when it is complete. */
  incompleteSince: number | null
  /** A person deleted the channel before the transcript was verified. */
  channelLostEarly: boolean
  /** The transcript is complete but no protocol is stored, so the channel is neither archived nor deleted. */
  protocolMissing: boolean
}

const OWN_TYPES = new Set(['ticket.transcript_complete', 'ticket.archived', 'ticket.channel_deleted'])

/** The tickets of a case, oldest first. Events of a ticket whose `ticket.opened` was not loaded are ignored. */
export function buildTickets(events: CaseTimelineItem[]): TicketView[] {
  const ordered = [...events].sort((a, b) => a.seq - b.seq)
  const views: TicketView[] = []
  for (const opened of ordered.filter((event) => event.type === 'ticket.opened')) {
    const ticketId = text(field(opened, 'ticketId'))
    const appealId = text(field(opened, 'appealId'))
    const ticketNo = num(field(opened, 'ticketNo'))
    if (!ticketId || !appealId || ticketNo === null || views.some((view) => view.ticketId === ticketId)) continue

    const own = ordered.filter((event) => OWN_TYPES.has(event.type) && text(field(event, 'ticketId')) === ticketId)
    const complete = own.find((event) => event.type === 'ticket.transcript_complete')
    const archived = own.find((event) => event.type === 'ticket.archived')
    const deleted = own.find((event) => event.type === 'ticket.channel_deleted')
    const decided = ordered.filter((event) => event.type === 'appeal.decided' && text(field(event, 'appealId')) === appealId).pop()

    let protocolVersion = num(field(archived ?? opened, 'protocolVersion'))
    for (const stored of ordered) {
      if (stored.type !== 'protocol.stored' || text(field(stored, 'appealId')) !== appealId) continue
      const version = num(field(stored, 'version'))
      if (version !== null) protocolVersion = Math.max(protocolVersion ?? 0, version)
    }

    const transcriptComplete = complete !== undefined || (deleted !== undefined && field(deleted, 'transcriptComplete') === true)
    const phase: TicketPhase = deleted ? 'DELETED' : archived ? 'ARCHIVED' : complete ? 'CLOSED' : decided ? 'CLOSING' : 'OPEN'
    const channelLostEarly = deleted !== undefined && !transcriptComplete
    const incompleteSince = channelLostEarly
      ? (num(field(deleted, 'lastRecordedMessageAt')) ?? deleted.occurredAt)
      : phase === 'CLOSING' && decided
        ? decided.occurredAt
        : null

    views.push({
      ticketId,
      ticketNo,
      appealId,
      openedAt: opened.occurredAt,
      phase,
      transcriptComplete,
      protocolVersion,
      incompleteSince,
      channelLostEarly,
      protocolMissing: transcriptComplete && protocolVersion === null && phase !== 'ARCHIVED' && phase !== 'DELETED',
    })
  }
  return views
}

/** The Discord participants of the thread, in order of first appearance, numbered for display (never an id). */
export function participantsOf(messages: Array<{ participantId: string | null; channel: string | null }>): string[] {
  const seen: string[] = []
  for (const message of messages) {
    if (message.channel === 'DISCORD' && message.participantId && !seen.includes(message.participantId)) seen.push(message.participantId)
  }
  return seen
}
