import { describe, expect, it } from 'vitest'
import type { BlackholeApi } from '../api/blackhole'
import type { ApiResult, CaseListItem, CaseListPage } from '../api/types'
import { caseListItem, fakeCaseList } from '../test/inbox'
import { assignedCases, MAX_PAGES, unassignedUrgentCases } from './caseQueues'

const ME = '00000000-0000-4000-8000-0000000000a1'
const OTHER = '00000000-0000-4000-8000-0000000000b2'
const DAY = 86_400_000

function apiWith(read: (query?: Parameters<BlackholeApi['cases']>[0]) => Promise<ApiResult<CaseListPage>>): Pick<BlackholeApi, 'cases'> {
  return { cases: read as BlackholeApi['cases'] }
}

describe('assignedCases', () => {
  it('counts the open cases of the caller and leaves closed ones out', async () => {
    const list = fakeCaseList([
      caseListItem({ shortId: 'A1', assigneeOid: ME, status: 'OPEN' }),
      caseListItem({ shortId: 'A2', assigneeOid: ME, status: 'IN_REVIEW' }),
      caseListItem({ shortId: 'A3', assigneeOid: ME, status: 'WAITING' }),
      caseListItem({ shortId: 'A4', assigneeOid: ME, status: 'CLOSED' }),
      caseListItem({ shortId: 'A5', assigneeOid: OTHER, status: 'OPEN' }),
    ])

    const result = await assignedCases(apiWith(list.read), ME)

    expect(result.ok && result.data.count).toBe(3)
    expect(result.ok && result.data.items.map((item) => item.shortId).sort()).toEqual(['A1', 'A2', 'A3'])
  })

  it('asks for each open status with the caller as assignee, never for the closed ones', async () => {
    const list = fakeCaseList([])

    await assignedCases(apiWith(list.read), ME)

    expect(list.queries.map((query) => query.status).sort()).toEqual(['IN_REVIEW', 'OPEN', 'WAITING'])
    expect(list.queries.every((query) => query.assignee === ME)).toBe(true)
  })

  it('orders the rows oldest activity first across the statuses', async () => {
    const list = fakeCaseList([
      caseListItem({ shortId: 'NEW', assigneeOid: ME, status: 'OPEN', lastActivityAt: Date.UTC(2030, 0, 5) }),
      caseListItem({ shortId: 'OLD', assigneeOid: ME, status: 'WAITING', lastActivityAt: Date.UTC(2030, 0, 1) }),
      caseListItem({ shortId: 'MID', assigneeOid: ME, status: 'IN_REVIEW', lastActivityAt: Date.UTC(2030, 0, 3) }),
    ])

    const result = await assignedCases(apiWith(list.read), ME)

    expect(result.ok && result.data.items.map((item) => item.shortId)).toEqual(['OLD', 'MID', 'NEW'])
  })

  it('reads every page, so the count and the rows are not cut at the first page', async () => {
    const items = Array.from({ length: 5 }, (_, index) =>
      caseListItem({ shortId: `C${index}`, assigneeOid: ME, status: 'OPEN', lastActivityAt: index * DAY }),
    )
    const list = fakeCaseList(items, 2)

    const result = await assignedCases(apiWith(list.read), ME)

    expect(result.ok && result.data.count).toBe(5)
    expect(result.ok && result.data.items).toHaveLength(5)
  })

  it('fails as a whole when one of the reads fails, so the section can show its own error', async () => {
    const failing = async (query?: { status?: string }) =>
      query?.status === 'WAITING'
        ? ({ ok: false, error: { status: 503, message: 'backend down', kind: 'UNKNOWN' } } as const)
        : ({ ok: true, data: { items: [], page: 0, size: 100, totalSize: 0, totalPages: 0 } } as const)

    const result = await assignedCases(apiWith(failing as never), ME)

    expect(result.ok).toBe(false)
  })

  it('marks the count as truncated when the page bound is reached', async () => {
    const items = Array.from({ length: MAX_PAGES + 5 }, (_, index) =>
      caseListItem({ shortId: `T${index}`, assigneeOid: ME, status: 'OPEN', lastActivityAt: index }),
    )
    const list = fakeCaseList(items, 1)

    const result = await assignedCases(apiWith(list.read), ME)

    expect(result.ok && result.data.truncated).toBe(true)
    expect(result.ok && result.data.count).toBe(MAX_PAGES)
  })
})

describe('unassignedUrgentCases', () => {
  it('lists open URGENT and HIGH cases without an assignee and leaves the others out', async () => {
    const list = fakeCaseList([
      caseListItem({ shortId: 'U1', priority: 'URGENT' }),
      caseListItem({ shortId: 'H1', priority: 'HIGH' }),
      caseListItem({ shortId: 'N1', priority: 'NORMAL' }),
      caseListItem({ shortId: 'L1', priority: 'LOW' }),
      caseListItem({ shortId: 'UA', priority: 'URGENT', assigneeOid: OTHER }),
      caseListItem({ shortId: 'UC', priority: 'URGENT', status: 'CLOSED' }),
    ])

    const result = await unassignedUrgentCases(apiWith(list.read))

    expect(result.ok && result.data.items.map((item) => item.shortId).sort()).toEqual(['H1', 'U1'])
    expect(result.ok && result.data.count).toBe(2)
  })

  it('puts URGENT before HIGH and then the oldest activity first', async () => {
    const list = fakeCaseList([
      caseListItem({ shortId: 'HIGH-OLD', priority: 'HIGH', lastActivityAt: Date.UTC(2030, 0, 1) }),
      caseListItem({ shortId: 'URGENT-NEW', priority: 'URGENT', lastActivityAt: Date.UTC(2030, 0, 9) }),
      caseListItem({ shortId: 'URGENT-OLD', priority: 'URGENT', lastActivityAt: Date.UTC(2030, 0, 2) }),
    ])

    const result = await unassignedUrgentCases(apiWith(list.read))

    expect(result.ok && result.data.items.map((item) => item.shortId)).toEqual(['URGENT-OLD', 'URGENT-NEW', 'HIGH-OLD'])
  })

  it('reads only the URGENT and HIGH priorities in the open statuses', async () => {
    const list = fakeCaseList([])

    await unassignedUrgentCases(apiWith(list.read))

    expect(list.queries.map((query) => `${query.priority}/${query.status}`).sort()).toEqual([
      'HIGH/IN_REVIEW',
      'HIGH/OPEN',
      'HIGH/WAITING',
      'URGENT/IN_REVIEW',
      'URGENT/OPEN',
      'URGENT/WAITING',
    ])
  })

  it('counts only the rows without an assignee, across all pages', async () => {
    const items: CaseListItem[] = Array.from({ length: 4 }, (_, index) =>
      caseListItem({ shortId: `P${index}`, priority: 'HIGH', assigneeOid: index % 2 === 0 ? OTHER : null }),
    )
    const list = fakeCaseList(items, 1)

    const result = await unassignedUrgentCases(apiWith(list.read))

    expect(result.ok && result.data.count).toBe(2)
  })
})
