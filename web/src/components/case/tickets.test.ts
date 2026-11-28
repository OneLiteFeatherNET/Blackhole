import { describe, expect, it } from 'vitest'
import { timelineItem } from '../../test/cases'
import { buildTickets, knownLockOf, participantsOf } from './tickets'

const TICKET = '3f2b8c1e-0000-4000-8000-0000000000a1'
const APPEAL = '3f2b8c1e-0000-4000-8000-0000000000a2'

const opened = () => timelineItem({ type: 'ticket.opened', payload: { ticketId: TICKET, appealId: APPEAL, ticketNo: 7 } })
const decided = () => timelineItem({ type: 'appeal.decided', payload: { appealId: APPEAL, decision: 'DENIED' } })
const complete = () => timelineItem({ type: 'ticket.transcript_complete', payload: { ticketId: TICKET, appealId: APPEAL, count: 4, edits: 0, deletes: 0 } })
const stored = (version: number) => timelineItem({ type: 'protocol.stored', payload: { appealId: APPEAL, version, evidenceId: 'e', entries: 4, digest: 'd' } })
const archived = (protocolVersion: number) => timelineItem({ type: 'ticket.archived', payload: { ticketId: TICKET, appealId: APPEAL, protocolVersion } })

describe('buildTickets', () => {
  it('has no ticket without a ticket.opened event', () => {
    expect(buildTickets([complete(), decided()])).toEqual([])
  })

  it('shows a fresh ticket as open with no transcript and no protocol', () => {
    const [ticket] = buildTickets([opened()])

    expect(ticket).toMatchObject({ ticketNo: 7, phase: 'OPEN', transcriptComplete: false, protocolVersion: null, incompleteSince: null, protocolMissing: false })
  })

  it('says the transcript may be incomplete since the decision when no completeness event follows', () => {
    const decision = decided()
    const [ticket] = buildTickets([opened(), decision])

    expect(ticket).toMatchObject({ phase: 'CLOSING', transcriptComplete: false, incompleteSince: decision.occurredAt })
  })

  it('flags a complete transcript without a stored protocol', () => {
    const [ticket] = buildTickets([opened(), decided(), complete()])

    expect(ticket).toMatchObject({ phase: 'CLOSED', transcriptComplete: true, incompleteSince: null, protocolMissing: true })
  })

  it('takes the newest stored protocol version', () => {
    const [ticket] = buildTickets([opened(), decided(), complete(), stored(1), stored(3), stored(2)])

    expect(ticket).toMatchObject({ protocolVersion: 3, protocolMissing: false })
  })

  it('shows an archived ticket with the protocol version it was archived with', () => {
    const [ticket] = buildTickets([opened(), decided(), complete(), archived(2)])

    expect(ticket).toMatchObject({ phase: 'ARCHIVED', protocolVersion: 2, protocolMissing: false })
  })

  it('reports a channel deleted before the transcript was verified, since the last recorded message', () => {
    const lost = timelineItem({ type: 'ticket.channel_deleted', payload: { ticketId: TICKET, appealId: APPEAL, transcriptComplete: false, lastRecordedMessageAt: 1234 } })
    const [ticket] = buildTickets([opened(), lost])

    expect(ticket).toMatchObject({ phase: 'DELETED', channelLostEarly: true, incompleteSince: 1234 })
  })

  it('does not call a regular deletion after the protocol incomplete', () => {
    const gone = timelineItem({ type: 'ticket.channel_deleted', payload: { ticketId: TICKET, appealId: APPEAL, transcriptComplete: true } })
    const [ticket] = buildTickets([opened(), decided(), complete(), stored(1), gone])

    expect(ticket).toMatchObject({ phase: 'DELETED', channelLostEarly: false, incompleteSince: null, protocolMissing: false })
  })
})

describe('participantsOf', () => {
  it('lists each Discord participant once, in order, and ignores portal and panel messages', () => {
    const messages = [
      { participantId: 'a', channel: 'DISCORD' },
      { participantId: null, channel: 'PORTAL' },
      { participantId: 'b', channel: 'DISCORD' },
      { participantId: 'a', channel: 'DISCORD' },
      { participantId: 'c', channel: 'PANEL' },
    ]

    expect(participantsOf(messages)).toEqual(['a', 'b'])
  })
})

describe('knownLockOf', () => {
  const frozen = (frozenNow: boolean, freezeSource: string | null, ticketId = TICKET) =>
    timelineItem({ type: 'ticket.frozen', payload: { ticketId, appealId: APPEAL, frozen: frozenNow, freezeSource } })

  it('takes the newest ticket.frozen event over the state the case showed at load', () => {
    const events = [frozen(true, 'STAFF'), frozen(false, null)]

    expect(knownLockOf(events, { frozen: true, freezeSource: 'STAFF' }, TICKET)).toEqual({ frozen: false, source: null })
  })

  it('reads a lock made in Discord from the case state when no event has arrived yet', () => {
    expect(knownLockOf([opened()], { frozen: true, freezeSource: 'STAFF' }, TICKET)).toEqual({ frozen: true, source: 'STAFF' })
  })

  it('knows no lock when neither the case nor the timeline has one', () => {
    expect(knownLockOf([opened()], null, TICKET)).toBeNull()
  })

  it('ignores a lock event of another ticket', () => {
    const other = '3f2b8c1e-0000-4000-8000-0000000000b9'

    expect(knownLockOf([frozen(true, 'STAFF', other)], { frozen: false, freezeSource: null }, TICKET)).toEqual({ frozen: false, source: null })
  })
})
