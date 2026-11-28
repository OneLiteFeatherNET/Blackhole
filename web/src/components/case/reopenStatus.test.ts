import { describe, expect, it, vi } from 'vitest'
import type { BlackholeApi } from '../../api/blackhole'
import type { CaseTimelineItem } from '../../api/types'
import { timelineItem, timelinePage } from '../../test/cases'
import { fail, mockApi, ok } from '../../test/mount'
import { reopenTarget, statusBeforeClose } from './reopenStatus'

function statusChange(from: string | null, to: string, seq: number): CaseTimelineItem {
  return timelineItem({ type: 'case.status_changed', seq, payload: { from, to } })
}

describe('statusBeforeClose', () => {
  it('returns the status the case had before its newest close', () => {
    // Newest first: the case was reopened from IN_REVIEW after an earlier close, then closed from WAITING.
    const events = [statusChange('WAITING', 'CLOSED', 9), statusChange('CLOSED', 'IN_REVIEW', 8), statusChange('IN_REVIEW', 'CLOSED', 7)]

    expect(statusBeforeClose(events)).toBe('WAITING')
  })

  it('ignores other case events between the close and the reopen', () => {
    const events = [timelineItem({ type: 'case.priority_changed', payload: { from: 'NORMAL', to: 'HIGH' } }), statusChange('OPEN', 'CLOSED', 3)]

    expect(statusBeforeClose(events)).toBe('OPEN')
  })

  it('returns null when no close is in the events', () => {
    expect(statusBeforeClose([statusChange('OPEN', 'IN_REVIEW', 2)])).toBeNull()
  })

  it('returns null when the recorded previous status is not one a case can return to', () => {
    expect(statusBeforeClose([statusChange(null, 'CLOSED', 2)])).toBeNull()
  })
})

describe('reopenTarget', () => {
  it('reads the case status changes newest first and returns the status before the close', async () => {
    const timeline = vi.fn(async () => ok(timelinePage([statusChange('IN_REVIEW', 'CLOSED', 4)])))
    const api = mockApi({ caseTimeline: timeline }) as BlackholeApi

    const target = await reopenTarget(api, 'p-0001')

    expect(timeline).toHaveBeenCalledWith('p-0001', expect.objectContaining({ types: ['case'], order: 'desc' }))
    expect(target).toEqual({ status: 'IN_REVIEW', found: true })
  })

  it('follows the cursor until the close is found', async () => {
    const timeline = vi
      .fn()
      .mockResolvedValueOnce(ok(timelinePage([timelineItem({ type: 'case.tags_changed' })], 'next')))
      .mockResolvedValueOnce(ok(timelinePage([statusChange('WAITING', 'CLOSED', 1)])))
    const api = mockApi({ caseTimeline: timeline }) as BlackholeApi

    expect(await reopenTarget(api, 'p-0001')).toEqual({ status: 'WAITING', found: true })
    expect(timeline).toHaveBeenLastCalledWith('p-0001', expect.objectContaining({ cursor: 'next' }))
  })

  it('falls back to Open and says so when no earlier status is found', async () => {
    const api = mockApi({ caseTimeline: async () => ok(timelinePage([])) }) as BlackholeApi

    expect(await reopenTarget(api, 'p-0001')).toEqual({ status: 'OPEN', found: false })
  })

  it('falls back to Open when the timeline cannot be read', async () => {
    const api = mockApi({ caseTimeline: async () => fail('busy', 500, 'UNKNOWN') }) as BlackholeApi

    expect(await reopenTarget(api, 'p-0001')).toEqual({ status: 'OPEN', found: false })
  })
})
