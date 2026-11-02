import { describe, expect, it } from 'vitest'
import type { BlackholeApi } from '../api/blackhole'
import type { ApiResult, CaseListPage } from '../api/types'
import { caseListItem, fakeCaseList } from '../test/inbox'
import { ROW_LIMIT, assignedCases, unassignedUrgentCases, URGENT_PAGE_SIZE } from './caseQueues'

const ME = '00000000-0000-4000-8000-0000000000a1'
const OTHER = '00000000-0000-4000-8000-0000000000b2'
const HOUR = 3_600_000

function apiWith(read: (query?: Parameters<BlackholeApi['cases']>[0]) => Promise<ApiResult<CaseListPage>>): Pick<BlackholeApi, 'cases'> {
  return { cases: read as BlackholeApi['cases'] }
}

describe('assignedCases', () => {
  it('counts the open cases of the caller from totalSize and leaves closed ones out', async () => {
    const list = fakeCaseList([
      caseListItem({ shortId: 'A1', assigneeOid: ME, status: 'OPEN' }),
      caseListItem({ shortId: 'A2', assigneeOid: ME, status: 'IN_REVIEW' }),
      caseListItem({ shortId: 'A3', assigneeOid: ME, status: 'WAITING' }),
      caseListItem({ shortId: 'A4', assigneeOid: ME, status: 'CLOSED' }),
      caseListItem({ shortId: 'A5', assigneeOid: OTHER, status: 'OPEN' }),
    ])

    const result = await assignedCases(apiWith(list.read), ME)

    expect(result.ok && result.data.count).toBe(3)
    expect(result.ok && result.data.truncated).toBe(false)
  })

  it('makes one size-1 count read and one size-10 row read per open status, and no other read', async () => {
    const list = fakeCaseList([])

    await assignedCases(apiWith(list.read), ME)

    expect(list.queries).toHaveLength(6)
    const counts = list.queries.filter((query) => query.size === 1)
    const rows = list.queries.filter((query) => query.size === ROW_LIMIT)
    expect(counts.map((query) => query.status).sort()).toEqual(['IN_REVIEW', 'OPEN', 'WAITING'])
    expect(rows.map((query) => query.status).sort()).toEqual(['IN_REVIEW', 'OPEN', 'WAITING'])
    expect(list.queries.every((query) => query.assignee === ME && query.page === 0)).toBe(true)
  })

  it('takes the count from totalSize, so a large set is counted without reading its pages', async () => {
    const many = Array.from({ length: 250 }, (_, index) =>
      caseListItem({ shortId: `M${index}`, assigneeOid: ME, status: 'OPEN', lastActivityAt: index }),
    )
    const list = fakeCaseList(many)

    const result = await assignedCases(apiWith(list.read), ME)

    expect(result.ok && result.data.count).toBe(250)
    expect(list.queries).toHaveLength(6)
  })

  it('returns at most ten rows, oldest activity first among the first page of each status', async () => {
    const many = Array.from({ length: 12 }, (_, index) =>
      caseListItem({ shortId: `R${index}`, assigneeOid: ME, status: 'OPEN', lastActivityAt: (12 - index) * HOUR }),
    )
    const list = fakeCaseList(many)

    const result = await assignedCases(apiWith(list.read), ME)

    // The rows are the first page of size 10 in the server's order (R0 to R9), not the oldest of all twelve:
    // the section reads one page per status by design. Among those ten, R9 has the oldest activity.
    expect(result.ok && result.data.items).toHaveLength(ROW_LIMIT)
    expect(result.ok && result.data.items[0]!.shortId).toBe('R9')
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

  it('fails as a whole when one of the reads fails, so the section can show its own error', async () => {
    const failing = async (query?: { status?: string }) =>
      query?.status === 'WAITING'
        ? ({ ok: false, error: { status: 503, message: 'backend down', kind: 'UNKNOWN' } } as const)
        : ({ ok: true, data: { items: [], page: 0, size: 1, totalSize: 0, totalPages: 0 } } as const)

    const result = await assignedCases(apiWith(failing as never), ME)

    expect(result.ok).toBe(false)
  })
})

describe('unassignedUrgentCases', () => {
  it('lists open URGENT and HIGH cases without an assignee and leaves the others out', async () => {
    const list = fakeCaseList([
      caseListItem({ shortId: 'U1', punishmentIdentifier: 'p-u1', priority: 'URGENT' }),
      caseListItem({ shortId: 'H1', punishmentIdentifier: 'p-h1', priority: 'HIGH' }),
      caseListItem({ shortId: 'N1', priority: 'NORMAL' }),
      caseListItem({ shortId: 'L1', priority: 'LOW' }),
      caseListItem({ shortId: 'UA', priority: 'URGENT', assigneeOid: OTHER }),
      caseListItem({ shortId: 'UC', priority: 'URGENT', status: 'CLOSED' }),
    ])

    const result = await unassignedUrgentCases(apiWith(list.read))

    expect(result.ok && result.data.items.map((item) => item.shortId).sort()).toEqual(['H1', 'U1'])
    expect(result.ok && result.data.count).toBe(2)
  })

  it('makes one read per urgent priority and open status, each the first page of URGENT_PAGE_SIZE', async () => {
    const list = fakeCaseList([])

    await unassignedUrgentCases(apiWith(list.read))

    expect(list.queries).toHaveLength(6)
    expect(list.queries.every((query) => query.size === URGENT_PAGE_SIZE && query.page === 0)).toBe(true)
    expect(list.queries.map((query) => `${query.priority}/${query.status}`).sort()).toEqual([
      'HIGH/IN_REVIEW',
      'HIGH/OPEN',
      'HIGH/WAITING',
      'URGENT/IN_REVIEW',
      'URGENT/OPEN',
      'URGENT/WAITING',
    ])
  })

  it('shows the count as a lower bound when a read was full, and reads no further page', async () => {
    const items = Array.from({ length: URGENT_PAGE_SIZE + 30 }, (_, index) =>
      caseListItem({ shortId: `F${index}`, punishmentIdentifier: `p-f${index}`, priority: 'HIGH', lastActivityAt: index }),
    )
    const list = fakeCaseList(items)

    const result = await unassignedUrgentCases(apiWith(list.read))

    expect(result.ok && result.data.truncated).toBe(true)
    expect(result.ok && result.data.count).toBe(URGENT_PAGE_SIZE)
    expect(list.queries).toHaveLength(6)
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
})
