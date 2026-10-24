import { describe, expect, it } from 'vitest'
import { timelineItem } from '../../test/cases'
import { bannerFor, buildThread } from './conversation'

const M1 = '3f2b8c1e-0000-4000-8000-000000000011'
const PLAYER_ACTOR = { trust: 'PLAYER' as const }

const added = (seq: number, messageId: string, text: string | null, author: 'PLAYER' | 'STAFF' = 'STAFF') =>
  timelineItem({
    seq,
    type: 'message.added',
    subjectId: messageId,
    visibility: 'PLAYER',
    actor: author === 'PLAYER' ? PLAYER_ACTOR : { oid: '00000000-0000-4000-8000-0000000000b2', name: 'Ada', trust: 'VERIFIED' },
    payload: { messageId, author: { kind: author }, channel: author === 'PLAYER' ? 'PORTAL' : 'PANEL', ...(text === null ? {} : { text }) },
  })

describe('buildThread', () => {
  it('folds edits into the latest text and counts them', () => {
    const thread = buildThread([
      added(1, M1, 'first'),
      timelineItem({ seq: 2, type: 'message.edited', subjectId: M1, payload: { messageId: M1, text: 'second' } }),
      timelineItem({ seq: 3, type: 'message.edited', subjectId: M1, payload: { messageId: M1, text: 'third' } }),
    ])

    expect(thread).toHaveLength(1)
    expect(thread[0]).toMatchObject({ text: 'third', edits: 2, deleted: false, redacted: false })
  })

  it('keeps the last text of a deleted message for staff and marks it deleted', () => {
    const thread = buildThread([added(1, M1, 'oops'), timelineItem({ seq: 2, type: 'message.deleted', subjectId: M1, payload: { messageId: M1 } })])

    expect(thread[0]).toMatchObject({ text: 'oops', deleted: true, redacted: false })
  })

  it('empties every version of a redacted message', () => {
    const thread = buildThread([
      added(1, M1, 'phone 555'),
      timelineItem({ seq: 2, type: 'message.edited', subjectId: M1, payload: { messageId: M1, text: 'phone 556' } }),
      timelineItem({ seq: 3, type: 'message.redacted', subjectId: M1, payload: { messageId: M1, reason: 'PERSONAL_DATA' } }),
    ])

    expect(thread[0]).toMatchObject({ text: null, redacted: true })
  })

  it('treats a version the server redacted as redacted even without the redaction event', () => {
    const thread = buildThread([{ ...added(1, M1, null), redactedAt: 5 }])

    expect(thread[0]).toMatchObject({ text: null, redacted: true })
  })

  it('tells the player from staff and keeps the order of the first events', () => {
    const thread = buildThread([added(4, 'b', 'answer'), added(2, 'a', 'question', 'PLAYER')])

    expect(thread.map((message) => [message.id, message.author])).toEqual([
      ['a', 'PLAYER'],
      ['b', 'STAFF'],
    ])
  })

  it('ignores events of other types and edits of a message it never saw', () => {
    const thread = buildThread([
      timelineItem({ seq: 1, type: 'note.added', payload: { text: 'secret note' } }),
      timelineItem({ seq: 2, type: 'message.edited', subjectId: 'ghost', payload: { messageId: 'ghost', text: 'x' } }),
    ])

    expect(thread).toEqual([])
  })
})

describe('bannerFor', () => {
  const leak = (seq: number, afterFiling: boolean) =>
    timelineItem({ seq, type: 'ban_id.leak_marked', payload: { reason: 'POSTED_PUBLIC', source: 'STAFF', afterFiling } })

  it('shows nothing without a leak', () => {
    expect(bannerFor(null, [leak(1, false)])).toBeNull()
    expect(bannerFor(undefined, [])).toBeNull()
  })

  it('forfeits the right when the leak came before an appeal was filed', () => {
    expect(bannerFor(1700, [leak(1, false)])).toBe('FORFEITED')
  })

  it('freezes the ticket when the leak came after the appeal was filed', () => {
    expect(bannerFor(1700, [leak(1, true)])).toBe('FROZEN')
  })

  it('follows the newest leak after an earlier one was cleared by a reissue', () => {
    expect(bannerFor(1700, [leak(1, true), leak(9, false)])).toBe('FORFEITED')
  })
})
