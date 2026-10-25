import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { InboxSummary } from '../api/types'
import { fail, mockApi, ok } from '../test/mount'
import { badgeText, inboxTotal, useNavCountsStore } from './navCounts'

const page = (totalSize: number) => ({ items: [], page: 0, size: 1, totalSize, totalPages: totalSize })

/** An inbox read with the given counts; the items are not what the badges read. */
function inbox(counts: { appeals?: number; reports?: number; unassigned?: number; needsAnswer?: number } = {}): InboxSummary {
  const section = (count = 0) => ({ count, items: [] })
  return {
    generatedAt: 0,
    needsAnswer: section(counts.needsAnswer),
    openAppeals: section(counts.appeals),
    newReports: section(counts.reports),
    unassignedCases: section(counts.unassigned),
  }
}

describe('badgeText', () => {
  it.each([
    [0, null],
    [null, null],
    [undefined, null],
    [1, '1'],
    [99, '99'],
    [100, '99+'],
    [1234, '99+'],
  ])('shows %s as %s', (count, expected) => {
    expect(badgeText(count)).toBe(expected)
  })
})

describe('inboxTotal', () => {
  it('adds open appeals, new reports and unassigned cases, and not the appeals that need an answer again', () => {
    expect(inboxTotal(inbox({ appeals: 3, reports: 5, unassigned: 2, needsAnswer: 1 }))).toBe(10)
  })
})

describe('nav counts store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('takes appeals, reports and the inbox total from the inbox and open cases from the size of a one-item case page', async () => {
    const cases = vi.fn(async () => ok(page(7)))
    const api = mockApi({ inbox: async () => ok(inbox({ appeals: 3, reports: 5, unassigned: 2 })), cases })
    const counts = useNavCountsStore()

    await counts.refresh(api)

    expect([counts.cases, counts.appeals, counts.reports, counts.inbox]).toEqual([7, 3, 5, 10])
    expect(cases).toHaveBeenCalledWith({ status: 'OPEN', page: 0, size: 1 })
  })

  it('reads an empty inbox as zero everywhere', async () => {
    const api = mockApi({ inbox: async () => ok(inbox()), cases: async () => ok(page(0)) })
    const counts = useNavCountsStore()

    await counts.refresh(api)

    expect([counts.cases, counts.appeals, counts.reports, counts.inbox]).toEqual([0, 0, 0, 0])
  })

  it('keeps the previous numbers when a request fails', async () => {
    const counts = useNavCountsStore()
    await counts.refresh(mockApi({ inbox: async () => ok(inbox({ appeals: 3, reports: 5 })), cases: async () => ok(page(7)) }))

    await counts.refresh(mockApi({ inbox: async () => fail('down', 500), cases: async () => fail('down', 500) }))

    expect([counts.cases, counts.appeals, counts.reports]).toEqual([7, 3, 5])
  })

  it('keeps the previous numbers when a request throws', async () => {
    const counts = useNavCountsStore()
    await counts.refresh(mockApi({ inbox: async () => ok(inbox({ appeals: 3 })), cases: async () => ok(page(7)) }))

    await counts.refresh(mockApi({ inbox: async () => Promise.reject(new Error('network')), cases: async () => ok(page(9)) }))

    expect(counts.appeals).toBe(3)
  })

  it('shares one request between refreshes that overlap', async () => {
    const readInbox = vi.fn(async () => ok(inbox()))
    const api = mockApi({ inbox: readInbox, cases: async () => ok(page(0)) })
    const counts = useNavCountsStore()

    await Promise.all([counts.refresh(api), counts.refresh(api)])
    await counts.refresh(api)

    expect(readInbox).toHaveBeenCalledTimes(2)
  })
})
