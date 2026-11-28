import type { BlackholeApi } from '../api/blackhole'
import type { ApiResult, CaseListItem, CaseListPage, CasePriority, CaseStatus } from '../api/types'

/** The statuses that are not CLOSED: the case list has no "not closed" filter, so each one is asked for. */
export const OPEN_STATUSES: CaseStatus[] = ['OPEN', 'IN_REVIEW', 'WAITING']
/** The priorities that count as urgent in the inbox. */
export const URGENT_PRIORITIES: CasePriority[] = ['URGENT', 'HIGH']
/** The rows a section shows. */
export const ROW_LIMIT = 10
/** The size of the one page read per urgent query. Its first page is the only one read. */
export const URGENT_PAGE_SIZE = 100

/**
 * One section's data. The request count is fixed: an assigned section makes two reads per open status, an
 * urgent section one read per urgent priority and open status, whatever the number of cases.
 */
export interface CaseQueue {
  /** How many cases the section holds. Exact for the assigned section; a lower bound when `truncated`. */
  count: number
  /** True when the count is a lower bound: a read was full, so cases beyond it were not read. */
  truncated: boolean
  /** The rows of the section, in the order it shows them; at most ROW_LIMIT. */
  items: CaseListItem[]
}

type Read = Pick<BlackholeApi, 'cases'>['cases']

/** Runs the reads together; the first failure fails the whole section, so no partial count is shown. */
async function readAll(read: Read, queries: Array<Parameters<Read>[0]>): Promise<ApiResult<CaseListPage[]>> {
  const results = await Promise.all(queries.map((query) => read(query)))
  const failed = results.find((result) => !result.ok)
  if (failed) return failed
  return { ok: true, data: results.map((result) => (result.ok ? result.data : emptyPage())) }
}

function emptyPage(): CaseListPage {
  return { items: [], page: 0, size: 0, totalSize: 0, totalPages: 0 }
}

const PRIORITY_RANK: Record<CasePriority, number> = { URGENT: 0, HIGH: 1, NORMAL: 2, LOW: 3 }

function oldestActivityFirst(a: CaseListItem, b: CaseListItem): number {
  return a.lastActivityAt - b.lastActivityAt
}

/**
 * The open cases the caller holds. Per open status, one size-1 read gives the exact count (totalSize) and one
 * size-10 read gives that status's rows. The rows are the oldest activity among those first pages, at most ten.
 */
export async function assignedCases(api: Pick<BlackholeApi, 'cases'>, oid: string): Promise<ApiResult<CaseQueue>> {
  const queries = OPEN_STATUSES.flatMap((status) => [
    { assignee: oid, status, page: 0, size: 1 },
    { assignee: oid, status, page: 0, size: ROW_LIMIT },
  ])
  const result = await readAll(api.cases, queries)
  if (!result.ok) return result
  const pages = result.data
  const count = OPEN_STATUSES.reduce((sum, _status, index) => sum + pages[index * 2]!.totalSize, 0)
  const items = OPEN_STATUSES.flatMap((_status, index) => pages[index * 2 + 1]!.items)
    .sort(oldestActivityFirst)
    .slice(0, ROW_LIMIT)
  return { ok: true, data: { count, truncated: false, items } }
}

/**
 * The open URGENT and HIGH cases that nobody holds, URGENT first, then oldest activity first. One read per
 * priority and open status, each the first page of URGENT_PAGE_SIZE; the rows without an assignee are kept.
 * When a read was full, cases beyond it are not read: the count is then a lower bound and is shown with a "+".
 *
 * TODO: the case list cannot yet filter for "no assignee". A server-side `assignee=none` filter (not in any
 * change yet, and not added here) would give the exact count and the rows in one read per priority and status,
 * and would remove the client-side filter and the lower bound.
 */
export async function unassignedUrgentCases(api: Pick<BlackholeApi, 'cases'>): Promise<ApiResult<CaseQueue>> {
  const queries = URGENT_PRIORITIES.flatMap((priority) =>
    OPEN_STATUSES.map((status) => ({ priority, status, page: 0, size: URGENT_PAGE_SIZE })),
  )
  const result = await readAll(api.cases, queries)
  if (!result.ok) return result
  const pages = result.data
  const truncated = pages.some((page) => page.totalSize > page.items.length)
  const unassigned = pages
    .flatMap((page) => page.items)
    .filter((item) => !item.assigneeOid)
    .sort((a, b) => PRIORITY_RANK[a.priority] - PRIORITY_RANK[b.priority] || oldestActivityFirst(a, b))
  return { ok: true, data: { count: unassigned.length, truncated, items: unassigned.slice(0, ROW_LIMIT) } }
}
